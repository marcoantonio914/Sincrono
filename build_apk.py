#!/usr/bin/env python3
"""
Gera o APK (debug, instalável) e o AAB (release, assinado com a chave de upload) do Sincrono:

    python build_apk.py

O script cuida de tudo o que faltar, sem precisar do Android Studio:
  1. JDK 17+          (usa o que já existir; senão baixa o Temurin 17)
  2. Gradle 8.9       (baixado para a pasta de cache)
  3. Android SDK      (usa o existente; senão baixa as command-line tools,
                       aceita as licenças e instala platform-35 e build-tools)
  4. Cria (uma vez) a chave de upload em ~/.sincrono-keystore (precisa do keytool do JDK)
  5. Compila e copia ./sincrono-debug.apk e ./sincrono-release.aab

Requisitos: Python 3.8+ e acesso à internet na primeira execução.
Cache em ~/.metronomo-build (apague a pasta para recomeçar do zero).

Opções:
    --output ARQ     caminho do APK final   (padrão: ./sincrono-debug.apk; o AAB sai ao lado, com .aab)
    --sdk PASTA      usar este Android SDK  (padrão: detecta ou baixa)
    --java-home PASTA usar este JDK         (padrão: detecta ou baixa)
    --no-aab         não gera o AAB (só o APK)
    --version-code N versionCode do AAB (aumente a cada envio à Play Store; padrão 1)
    --clean          limpa a compilação anterior antes de compilar
"""

import argparse
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent
CACHE_DIR = Path.home() / ".metronomo-build"
KEYSTORE_DIR = Path.home() / ".sincrono-keystore"

GRADLE_VERSION = "8.9"
GRADLE_URL = f"https://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip"
CMDLINE_TOOLS_BUILD = "11076708"
JDK_MAJOR = 17
ANDROID_PLATFORM = "platforms;android-35"
ANDROID_BUILD_TOOLS = "build-tools;35.0.0"

IS_WINDOWS = os.name == "nt"
SYSTEM = platform.system()  # Windows, Linux, Darwin


# --------------------------------------------------------------------------- util

def step(text):
    print(f"\n=== {text}")


def fail(text):
    print(f"\nERRO: {text}", file=sys.stderr)
    sys.exit(1)


def download(url, dest, label):
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    last_error = None
    for attempt in range(1, 4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "metronomo-build/1.0"})
            with urllib.request.urlopen(req, timeout=60) as resp, open(part, "wb") as out:
                total = int(resp.headers.get("Content-Length") or 0)
                done = 0
                started = time.time()
                while True:
                    block = resp.read(1024 * 256)
                    if not block:
                        break
                    out.write(block)
                    done += len(block)
                    if total:
                        pct = done * 100 // total
                        speed = done / max(time.time() - started, 0.1) / 1e6
                        print(f"\r  {label}: {pct:3d}%  ({done / 1e6:.0f}/{total / 1e6:.0f} MB, {speed:.1f} MB/s)", end="", flush=True)
                    else:
                        print(f"\r  {label}: {done / 1e6:.0f} MB", end="", flush=True)
            print()
            part.replace(dest)
            return
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            print(f"\n  tentativa {attempt}/3 falhou: {exc}")
            time.sleep(2 * attempt)
    fail(f"não foi possível baixar {url}\n  {last_error}\n"
         "Verifique a conexão com a internet (e proxy/firewall) e tente de novo.")


def extract_zip(archive, dest):
    """Extrai preservando as permissões de execução (o zipfile do Python não faz isso)."""
    dest = Path(dest)
    with zipfile.ZipFile(archive) as zf:
        for info in zf.infolist():
            target = zf.extract(info, dest)
            mode = (info.external_attr >> 16) & 0o777
            if mode and not IS_WINDOWS:
                os.chmod(target, mode)


def extract_any(archive, dest):
    Path(dest).mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        extract_zip(archive, dest)
    else:
        with tarfile.open(archive) as tf:
            tf.extractall(dest)


def run(cmd, env=None, cwd=None, stdin_text=None, check=True):
    print("  $ " + " ".join(f'"{c}"' if " " in str(c) else str(c) for c in cmd))
    result = subprocess.run(
        [str(c) for c in cmd],
        env=env,
        cwd=cwd,
        input=stdin_text,
        text=True if stdin_text is not None else None,
    )
    if check and result.returncode != 0:
        fail(f"comando falhou (código {result.returncode}).")
    return result.returncode


def exe(name):
    return name + (".exe" if IS_WINDOWS else "")


def bat(name):
    return name + (".bat" if IS_WINDOWS else "")


# --------------------------------------------------------------------------- JDK

def java_major(java_bin):
    try:
        out = subprocess.run([str(java_bin), "-version"], capture_output=True, text=True, timeout=30)
    except Exception:  # noqa: BLE001
        return None
    match = re.search(r'version "(\d+)(?:\.(\d+))?', out.stderr + out.stdout)
    if not match:
        return None
    major = int(match.group(1))
    return int(match.group(2) or 0) if major == 1 else major


def jdk_ok(home):
    java = Path(home) / "bin" / exe("java")
    javac = Path(home) / "bin" / exe("javac")
    if not (java.exists() and javac.exists()):
        return False
    major = java_major(java)
    return major is not None and JDK_MAJOR <= major <= 21


def find_java_home(root):
    root = Path(root)
    candidates = [root]
    for child in root.iterdir() if root.is_dir() else []:
        candidates += [child, child / "Contents" / "Home"]
    for c in candidates:
        if (c / "bin" / exe("java")).exists():
            return c
    return None


def ensure_jdk(explicit):
    step("JDK")
    if explicit:
        if not jdk_ok(explicit):
            fail(f"--java-home inválido ou versão fora de {JDK_MAJOR}-21: {explicit}")
        return Path(explicit)

    for source in (os.environ.get("JAVA_HOME"),):
        if source and jdk_ok(source):
            print(f"  usando JAVA_HOME: {source}")
            return Path(source)

    on_path = shutil.which("java")
    if on_path:
        home = Path(on_path).resolve().parent.parent
        if jdk_ok(home):
            print(f"  usando o JDK do PATH: {home}")
            return home

    # JDK que acompanha o Android Studio (JBR)
    studio_candidates = [
        r"C:\Program Files\Android\Android Studio\jbr",
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
        "/opt/android-studio/jbr",
    ]
    for c in studio_candidates:
        if Path(c).exists() and jdk_ok(c):
            print(f"  usando o JDK do Android Studio: {c}")
            return Path(c)

    jdk_root = CACHE_DIR / "jdk"
    home = find_java_home(jdk_root) if jdk_root.exists() else None
    if home and jdk_ok(home):
        print(f"  usando JDK em cache: {home}")
        return home

    print(f"  nenhum JDK {JDK_MAJOR}+ encontrado; baixando o Temurin {JDK_MAJOR}…")
    os_name = {"Windows": "windows", "Darwin": "mac"}.get(SYSTEM, "linux")
    machine = platform.machine().lower()
    arch = "aarch64" if machine in ("arm64", "aarch64") else "x64"
    url = (f"https://api.adoptium.net/v3/binary/latest/{JDK_MAJOR}/ga/"
           f"{os_name}/{arch}/jdk/hotspot/normal/eclipse")
    archive = CACHE_DIR / "downloads" / "jdk.archive"
    download(url, archive, f"Temurin {JDK_MAJOR}")
    shutil.rmtree(jdk_root, ignore_errors=True)
    extract_any(archive, jdk_root)
    archive.unlink(missing_ok=True)
    home = find_java_home(jdk_root)
    if not home or not jdk_ok(home):
        fail("o JDK baixado não pôde ser usado. Instale o JDK 17 manualmente e rode de novo.")
    return home


# --------------------------------------------------------------------------- Gradle

def ensure_gradle():
    step(f"Gradle {GRADLE_VERSION}")
    gradle_home = CACHE_DIR / "gradle" / f"gradle-{GRADLE_VERSION}"
    gradle_bin = gradle_home / "bin" / bat("gradle")
    if gradle_bin.exists():
        print(f"  usando Gradle em cache: {gradle_home}")
        return gradle_bin
    archive = CACHE_DIR / "downloads" / f"gradle-{GRADLE_VERSION}-bin.zip"
    download(GRADLE_URL, archive, f"Gradle {GRADLE_VERSION}")
    shutil.rmtree(gradle_home.parent, ignore_errors=True)
    print("  extraindo…")
    extract_zip(archive, gradle_home.parent)
    archive.unlink(missing_ok=True)
    if not gradle_bin.exists():
        fail("falha ao extrair o Gradle.")
    return gradle_bin


# --------------------------------------------------------------------------- Android SDK

def sdk_has_platform(sdk):
    return (Path(sdk) / "platforms" / "android-35").exists() and (
        Path(sdk) / "build-tools" / "35.0.0").exists()


def find_sdkmanager(sdk):
    sdk = Path(sdk)
    latest = sdk / "cmdline-tools" / "latest" / "bin" / bat("sdkmanager")
    if latest.exists():
        return latest
    for found in sdk.glob(f"cmdline-tools/*/bin/{bat('sdkmanager')}"):
        return found
    return None


def ensure_sdk(explicit, java_home):
    step("Android SDK")
    env = dict(os.environ, JAVA_HOME=str(java_home))

    candidates = []
    if explicit:
        candidates.append(explicit)
    candidates += [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    if SYSTEM == "Windows":
        candidates.append(os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"))
    elif SYSTEM == "Darwin":
        candidates.append(str(Path.home() / "Library" / "Android" / "sdk"))
    else:
        candidates.append(str(Path.home() / "Android" / "Sdk"))
    candidates.append(str(CACHE_DIR / "android-sdk"))

    sdk = None
    for c in candidates:
        if c and Path(c).exists():
            sdk = Path(c)
            if sdk_has_platform(sdk):
                print(f"  usando SDK: {sdk}")
                return sdk
            if explicit and c == explicit:
                break
    if sdk is None:
        sdk = Path(explicit) if explicit else CACHE_DIR / "android-sdk"
        sdk.mkdir(parents=True, exist_ok=True)

    manager = find_sdkmanager(sdk)
    if manager is None:
        print("  baixando as command-line tools do Android…")
        tag = {"Windows": "win", "Darwin": "mac"}.get(SYSTEM, "linux")
        url = f"https://dl.google.com/android/repository/commandlinetools-{tag}-{CMDLINE_TOOLS_BUILD}_latest.zip"
        archive = CACHE_DIR / "downloads" / "cmdline-tools.zip"
        download(url, archive, "command-line tools")
        tmp = CACHE_DIR / "downloads" / "cmdline-extract"
        shutil.rmtree(tmp, ignore_errors=True)
        extract_zip(archive, tmp)
        target = sdk / "cmdline-tools" / "latest"
        shutil.rmtree(target, ignore_errors=True)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(tmp / "cmdline-tools"), str(target))
        shutil.rmtree(tmp, ignore_errors=True)
        archive.unlink(missing_ok=True)
        manager = find_sdkmanager(sdk)
        if manager is None:
            fail("sdkmanager não encontrado após extrair as command-line tools.")

    print("  aceitando as licenças do Android SDK…")
    run([manager, f"--sdk_root={sdk}", "--licenses"], env=env, stdin_text="y\n" * 60, check=False)
    print("  instalando platform-35 e build-tools 35.0.0 (pode demorar)…")
    run([manager, f"--sdk_root={sdk}", ANDROID_PLATFORM, ANDROID_BUILD_TOOLS], env=env,
        stdin_text="y\n" * 10)
    if not sdk_has_platform(sdk):
        fail("a instalação do SDK não ficou completa.")
    return sdk


# --------------------------------------------------------------------------- build

def write_local_properties(sdk):
    path = PROJECT_DIR / "local.properties"
    path.write_text("sdk.dir=" + str(sdk).replace("\\", "/") + "\n", encoding="utf-8")


def build(args):
    if sys.version_info < (3, 8):
        fail("é necessário Python 3.8 ou superior.")
    if not (PROJECT_DIR / "settings.gradle.kts").exists():
        fail("rode este script dentro da pasta do projeto (onde está o settings.gradle.kts).")

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    java_home = ensure_jdk(args.java_home)
    gradle = ensure_gradle()
    sdk = ensure_sdk(args.sdk, java_home)
    write_local_properties(sdk)

    env = dict(os.environ)
    env["JAVA_HOME"] = str(java_home)
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_SDK_ROOT"] = str(sdk)
    env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")

    keystore_args = []
    if not args.no_aab:
        keystore_args = ensure_keystore(java_home)
        keystore_args.append(f"-PSINCRONO_VERSION_CODE={args.version_code}")
    tasks = ["assembleDebug"] + ([] if args.no_aab else ["bundleRelease"])
    step(f"Compilando ({' + '.join(tasks)}) — a primeira vez baixa dependências e leva alguns minutos")
    cmd = [gradle, "--no-daemon", "--console=plain", "-p", PROJECT_DIR]
    if args.clean:
        cmd.append("clean")
    cmd += tasks + keystore_args
    run(cmd, env=env)

    outputs = PROJECT_DIR / "app" / "build" / "outputs" / "apk" / "debug"
    apks = sorted(outputs.glob("*.apk"))
    if not apks:
        fail(f"a compilação terminou, mas nenhum APK foi encontrado em {outputs}")
    final = Path(args.output) if args.output else PROJECT_DIR / "sincrono-debug.apk"
    shutil.copyfile(apks[0], final)

    final_aab = None
    if not args.no_aab:
        bundles = sorted((PROJECT_DIR / "app" / "build" / "outputs" / "bundle" / "release").glob("*.aab"))
        if not bundles:
            fail("a compilação terminou, mas nenhum AAB foi encontrado.")
        final_aab = final.with_suffix(".aab") if args.output else PROJECT_DIR / "sincrono-release.aab"
        shutil.copyfile(bundles[0], final_aab)

    # Gera o wrapper do Gradle (gradlew) para o projeto abrir normalmente no Android Studio.
    if not (PROJECT_DIR / "gradlew").exists():
        run([gradle, "--no-daemon", "--console=plain", "-p", PROJECT_DIR, "wrapper",
             "--gradle-version", GRADLE_VERSION], env=env, check=False)

    step("Pronto")
    print(f"  APK: {final}  ({final.stat().st_size / 1e6:.1f} MB)")
    if final_aab:
        print(f"  AAB: {final_aab}  ({final_aab.stat().st_size / 1e6:.1f} MB)  — para enviar à Google Play")
        print(f"  Chave de upload: {KEYSTORE_DIR}  (FAÇA BACKUP desta pasta: sem ela não dá para atualizar o app na Play Store)")
    print("  Instalar o APK no celular:")
    print("   - copie o arquivo para o aparelho e abra-o (permita 'instalar apps desconhecidos'), ou")
    print(f"   - com o celular em modo depuração USB:  adb install -r \"{final}\"")


def ensure_keystore(java_home):
    """Cria (uma vez) a chave de upload e devolve os argumentos -P do Gradle."""
    import secrets
    KEYSTORE_DIR.mkdir(parents=True, exist_ok=True)
    jks = KEYSTORE_DIR / "sincrono-upload.jks"
    props = KEYSTORE_DIR / "keystore.properties"
    if not jks.exists() or not props.exists():
        step("Criando a chave de upload (uma vez só)")
        password = secrets.token_urlsafe(18)
        keytool = Path(java_home) / "bin" / ("keytool.exe" if SYSTEM == "Windows" else "keytool")
        jks.unlink(missing_ok=True)
        run([str(keytool), "-genkeypair", "-v", "-keystore", str(jks), "-alias", "upload",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", password, "-keypass", password,
             "-dname", "CN=Sincrono, O=Sincrono, C=BR"])
        props.write_text(f"password={password}\nalias=upload\n", encoding="utf-8")
        try:
            props.chmod(0o600)
        except OSError:
            pass
    password = ""
    for line in props.read_text(encoding="utf-8").splitlines():
        if line.startswith("password="):
            password = line.split("=", 1)[1]
    return [f"-PSINCRONO_KEYSTORE={jks}", f"-PSINCRONO_KS_PASSWORD={password}", "-PSINCRONO_KEY_ALIAS=upload"]


def main():
    parser = argparse.ArgumentParser(description="Gera o APK e o AAB do Sincrono.")
    parser.add_argument("--output", help="caminho do APK final")
    parser.add_argument("--sdk", help="pasta de um Android SDK existente")
    parser.add_argument("--java-home", dest="java_home", help="pasta de um JDK 17-21 existente")
    parser.add_argument("--no-aab", dest="no_aab", action="store_true", help="não gera o AAB")
    parser.add_argument("--version-code", dest="version_code", type=int, default=1, help="versionCode do AAB")
    parser.add_argument("--clean", action="store_true", help="limpa a compilação anterior")
    build(parser.parse_args())


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nInterrompido.")
        sys.exit(130)
