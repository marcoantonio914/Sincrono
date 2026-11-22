#!/usr/bin/env python3
"""Envia o projeto Sincrono para o GitHub.

Uso (dentro da pasta do projeto (sincrono-v24), onde está a pasta oculta .git):
    python3 publicar_github.py

O token NÃO fica neste arquivo: o script pergunta (sem mostrar na tela) ou lê a variável de
ambiente SINCRONO_GH_TOKEN. O token não é gravado em nenhum arquivo nem no .git/config.
"""
import getpass
import os
import stat
import subprocess
import sys
import tempfile

USUARIO = "marcoantonio914"
REPO_URL = "https://github.com/marcoantonio914/Sincrono.git"
PASTA = os.path.dirname(os.path.abspath(__file__))


def git(*args, env=None, check=True):
    r = subprocess.run(["git", *args], cwd=PASTA, env=env, text=True, capture_output=True)
    if check and r.returncode != 0:
        raise SystemExit("Erro no git %s:\n%s%s" % (" ".join(args), r.stdout, r.stderr))
    return r


def main():
    url = sys.argv[1] if len(sys.argv) > 1 else REPO_URL
    if subprocess.run(["git", "--version"], capture_output=True).returncode != 0:
        raise SystemExit("O git não está instalado. No Ubuntu: sudo apt install git")
    if not os.path.isdir(os.path.join(PASTA, ".git")):
        raise SystemExit("Não achei a pasta .git aqui. Coloque este script dentro da pasta do projeto (sincrono-v24).")

    # repositório com o commit pronto; garante a branch main e o endereço (sem token)
    git("branch", "-M", "main")
    if git("remote", "get-url", "origin", check=False).returncode == 0:
        git("remote", "set-url", "origin", url)
    else:
        git("remote", "add", "origin", url)

    env = dict(os.environ)
    ask = None
    if not url.startswith("file://") and not os.path.isdir(url):
        token = os.environ.get("SINCRONO_GH_TOKEN") or getpass.getpass("Cole o token do GitHub (não aparece na tela) e dê Enter: ").strip()
        if not token:
            raise SystemExit("Sem token. Nada foi enviado.")
        # o git pede usuário e senha a este programinha temporário, que lê de variáveis de ambiente
        fd, ask = tempfile.mkstemp(suffix=".sh")
        with os.fdopen(fd, "w") as f:
            f.write('#!/bin/sh\ncase "$1" in\n  Username*) echo "$SINCRONO_GH_USER";;\n  *) echo "$SINCRONO_GH_TOKEN_TMP";;\nesac\n')
        os.chmod(ask, stat.S_IRWXU)
        env.update(GIT_ASKPASS=ask, GIT_TERMINAL_PROMPT="0", SINCRONO_GH_USER=USUARIO, SINCRONO_GH_TOKEN_TMP=token)

    try:
        print("Enviando para %s ..." % url)
        r = git("push", "-u", "origin", "main", env=env, check=False)
        print(r.stdout + r.stderr)
        if r.returncode != 0:
            msg = (r.stdout + r.stderr).lower()
            if "authentication" in msg or "403" in msg or "401" in msg or "denied" in msg:
                print("O GitHub recusou o login. Confira se o token tem a permissão 'repo' e não expirou.")
            elif "rejected" in msg or "fetch first" in msg:
                print("O repositório no GitHub já tem conteúdo (por exemplo um README). Me avise que eu explico como juntar.")
            raise SystemExit(1)
        print("Pronto! Veja em: " + url.removesuffix(".git"))
    finally:
        if ask and os.path.exists(ask):
            os.remove(ask)


if __name__ == "__main__":
    main()
