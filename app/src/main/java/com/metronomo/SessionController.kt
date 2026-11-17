package com.metronomo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.metronomo.audio.AudioFilePlayer
import com.metronomo.audio.AudioPlayback
import com.metronomo.audio.ClickEngine
import com.metronomo.audio.Mix
import com.metronomo.audio.OutputRoute
import com.metronomo.calibration.CalibrationManager
import com.metronomo.core.MetronomeState
import com.metronomo.diag.AudioClockTracker
import com.metronomo.diag.SyncMath
import com.metronomo.diag.SyncQuality
import com.metronomo.net.ClockSync
import com.metronomo.net.Discovery
import com.metronomo.net.GroupsInfo
import com.metronomo.net.MixClient
import com.metronomo.net.NetworkBinder
import com.metronomo.net.SessionClient
import com.metronomo.net.SessionServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlin.math.floor

/**
 * Orquestra uma sessão. O anfitrião roda o servidor e também entra nele como um
 * cliente comum (por 127.0.0.1), então anfitrião e convidado usam o mesmo caminho
 * de sincronização e de áudio.
 */
object SessionController {

    const val DEFAULT_PORT = 3000
    private const val MAX_AUDIO_BYTES = 200_000_000L
    private const val PREFS = "metronomo"
    private const val KEY_OFFSET = "offsetMs" // ajuste manual da versão antiga (usado só como valor inicial)
    private const val KEY_DEVICE_ID = "deviceId"
    private const val KEY_OWN_VOLUME = "ownVolume"
    private const val KEY_GROUPS = "groupsState"
    private const val KEY_MIX_LEVELS = "mixLevels" // níveis por aparelho definidos por este aparelho quando é anfitrião

    // Perfis por tipo de saída (alto-falante, fone com fio, USB, Bluetooth):
    // cada saída tem o seu ajuste manual e a sua calibração, com o anfitrião a que ela se refere.
    private fun manualKey(t: OutputRoute.Type) = "manual.${t.name}"
    private fun autoKey(t: OutputRoute.Type) = "auto.${t.name}"
    private fun autoRefKey(t: OutputRoute.Type) = "autoRef.${t.name}"
    private fun autoQualityKey(t: OutputRoute.Type) = "autoQ.${t.name}"
    private fun autoSpreadKey(t: OutputRoute.Type) = "autoSpread.${t.name}"
    private fun autoAtKey(t: OutputRoute.Type) = "autoAt.${t.name}"
    private fun autoAlgoKey(t: OutputRoute.Type) = "autoAlgo.${t.name}"
    private const val CALIBRATION_ALGO = "v6"

    enum class Role { HOST, GUEST }

    data class UiState(
        val active: Boolean = false,
        val role: Role? = null,
        val connected: Boolean = false,
        val metronome: MetronomeState = MetronomeState(),
        val clockSynced: Boolean = false,
        val rttMs: Double? = null,
        val hostUrls: List<String> = emptyList(),
        val message: String? = null,
        /** Ajuste fino deste aparelho em ms (positivo = tocar mais cedo). */
        val offsetMs: Int = 0,
        /** Ajuste da calibração automática em uso, em ms (positivo = tocar mais cedo). */
        val autoOffsetMs: Double = 0.0,
        /** Há calibração válida (contra o anfitrião atual) para a saída em uso. O anfitrião conta como calibrado. */
        val autoCalibrated: Boolean = false,
        /** Nome da saída de áudio em uso. */
        val route: String = "",
        val routeWarning: String? = null,
        /** Confiança da calibração em uso: "high", "medium", "low" ou "" se não há. */
        val autoQuality: String = "",
        val autoSpreadMs: Double = 0.0,
        /** Diagnóstico de sincronização (relógio, áudio, música). */
        val sync: SyncQuality = SyncQuality(),
        /** Volume próprio deste aparelho (0..1). */
        val ownVolume: Double = 1.0,
        /** Volume final definido pelo anfitrião para este aparelho (individual × do grupo), de 0 a 1. */
        val hostVolume: Double = 1.0,
        /** Grupo ("ilha") a que este aparelho pertence e o nome dele. */
        val groupId: String = "g1",
        val groupName: String = "Geral",
    )

    /** [beat] é 1-based; 0 significa contagem regressiva antes do início. */
    data class BeatInfo(val beat: Int, val countdownMs: Long)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var scope: CoroutineScope? = null
    private var server: SessionServer? = null
    private var client: SessionClient? = null
    private var clock: ClockSync? = null
    private var engine: ClickEngine? = null
    private var calibrationManager: CalibrationManager? = null
    private var audioPlayer: AudioFilePlayer? = null
    private var audioPlayback: AudioPlayback? = null
    private var mixClient: MixClient? = null
    private var deviceIdInUse = ""
    private var discoveryInstance: Discovery? = null
    private var appContext: Context? = null
    private var currentRole: Role? = null
    private var sessionHostId = ""
    private var currentRoute = OutputRoute.Type.SPEAKER
    private var currentAuto = 0.0
    private var currentCalibrated = false
    private var unwatchRoute: (() -> Unit)? = null
    private var unbindNetwork: (() -> Unit)? = null

    private val _calibration = MutableStateFlow(CalibrationManager.Ui())
    val calibration: StateFlow<CalibrationManager.Ui> = _calibration.asStateFlow()

    /** Volume de cada aparelho definido pelo anfitrião (id -> 0..1), como visto por este aparelho. */
    private val _mixLevels = MutableStateFlow<Map<String, Double>>(emptyMap())
    val mixLevels: StateFlow<Map<String, Double>> = _mixLevels.asStateFlow()

    /** Grupos da sessão e a atribuição dos aparelhos, como vistos por este aparelho. */
    private val _groups = MutableStateFlow(GroupsInfo())
    val groups: StateFlow<GroupsInfo> = _groups.asStateFlow()

    /** Descoberta automática (mDNS): anúncio do anfitrião e lista de sessões para os convidados. */
    @Synchronized
    fun discovery(context: Context): Discovery =
        discoveryInstance ?: Discovery(context.applicationContext).also { discoveryInstance = it }

    private val _audio = MutableStateFlow(AudioPlayback.Ui())
    val audio: StateFlow<AudioPlayback.Ui> = _audio.asStateFlow()

    @Synchronized
    fun startHost(context: Context, preferredPort: Int = DEFAULT_PORT) {
        if (_ui.value.active) return
        val srv = SessionServer(preferredPort, context.applicationContext.assets)
        val port = try {
            srv.start()
        } catch (e: IOException) {
            _ui.value = UiState(message = "Não foi possível abrir o servidor: ${e.message}")
            return
        }
        server = srv
        // Restaura os volumes que este anfitrião tinha definido para cada aparelho.
        val hostPrefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            val saved = JSONObject(hostPrefs.getString(KEY_MIX_LEVELS, null) ?: "{}")
            saved.keys().forEach { id -> srv.mix.set(id, saved.optDouble(id, 1.0)) }
        }
        runCatching {
            val saved = JSONObject(hostPrefs.getString(KEY_GROUPS, null) ?: "{}")
            val names = HashMap<String, String>()
            val assign = HashMap<String, String>()
            saved.optJSONObject("names")?.let { o -> o.keys().forEach { k -> names[k] = o.optString(k) } }
            saved.optJSONObject("assign")?.let { o -> o.keys().forEach { k -> assign[k] = o.optString(k) } }
            srv.groups.restore(names, assign)
        }
        srv.onGroupsChanged = {
            val o = JSONObject()
            val names = JSONObject()
            srv.groups.ids().filter { it != "g1" }.forEach { names.put(it, srv.groups.name(it)) }
            val assign = JSONObject()
            srv.groups.assignments().forEach { (k, v) -> assign.put(k, v) }
            hostPrefs.edit().putString(KEY_GROUPS, o.put("names", names).put("assign", assign).toString()).apply()
        }
        discovery(context).register(port, Build.MODEL ?: "anfitrião")
        val urls = localAddresses().map { "http://$it:$port" }
        begin(context, Role.HOST, "http://127.0.0.1:$port", urls)
    }

    @Synchronized
    fun join(context: Context, address: String) {
        if (_ui.value.active) return
        val url = normalizeUrl(address)
        if (url == null) {
            _ui.value = UiState(message = "Endereço inválido. Exemplo: 192.168.0.15:3000")
            return
        }
        begin(context, Role.GUEST, url, emptyList())
    }

    @Synchronized
    fun leave(context: Context) {
        discoveryInstance?.unregister()
        mixClient?.stop()
        mixClient = null
        Mix.reset()
        _mixLevels.value = emptyMap()
        client?.stop()
        engine?.stop()
        audioPlayback?.stop()
        audioPlayer?.stop()
        audioPlayback = null
        audioPlayer = null
        _audio.value = AudioPlayback.Ui()
        server?.clearAllAudio()
        _groups.value = GroupsInfo()
        server?.stop()
        scope?.cancel()
        client = null
        engine = null
        server = null
        calibrationManager = null
        unwatchRoute?.invoke()
        unwatchRoute = null
        unbindNetwork?.invoke()
        unbindNetwork = null
        currentRole = null
        sessionHostId = ""
        currentAuto = 0.0
        currentCalibrated = false
        _calibration.value = CalibrationManager.Ui()
        scope = null
        clock = null
        _ui.value = UiState()
        val app = context.applicationContext
        app.stopService(Intent(app, SessionService::class.java))
    }

    fun sendCommand(type: String, bpm: Int, beatsPerBar: Int) {
        val c = client ?: return
        scope?.launch {
            val error = c.sendCommand(type, bpm, beatsPerBar)
            _ui.update { it.copy(message = error) }
        }
    }

    /** Soma [delta] ms ao ajuste fino da saída em uso e guarda para as próximas sessões. */
    fun changeOffset(context: Context, delta: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = (_ui.value.offsetMs + delta).coerceIn(-200, 200)
        prefs.edit().putInt(manualKey(currentRoute), next).apply()
        engine?.userOffsetMs = next.toDouble()
        audioPlayer?.userOffsetMs = next.toDouble()
        _ui.update { it.copy(offsetMs = next) }
    }

    /**
     * Pede ao anfitrião para iniciar a calibração automática (este aparelho será o que inicia).
     * Sem [partnerId]: todos contra o anfitrião. Com [partnerId]: só este aparelho, contra o
     * anfitrião ou contra um aparelho que já está calibrado. Com [cable], o som vai por cabo a uma
     * mesa de som que o soma, e só este aparelho grava (pela entrada ligada à saída da mesa).
     */
    fun startCalibration(partnerId: String? = null, cable: Boolean = false) {
        val cm = calibrationManager ?: return
        _calibration.update { it.copy(status = "Iniciando…", lines = emptyList()) }
        scope?.launch {
            val error = cm.requestStart(partnerId, cable)
            if (error != null) _calibration.update { it.copy(running = false, status = error) }
        }
    }

    /**
     * Guarda o resultado da calibração para a saída em uso, junto com o anfitrião de referência,
     * e zera o ajuste manual dessa saída (a calibração o substitui). O anfitrião é a própria
     * referência e não se ajusta.
     */
    @Synchronized
    fun applyAutoOffset(context: Context, ms: Double, hostId: String, quality: String, spreadMs: Double): Boolean {
        if (currentRole == Role.HOST) return true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (hostId.isNotEmpty()) sessionHostId = hostId
        // Um resultado de baixa confiança não substitui uma calibração válida (contra este anfitrião) já em uso.
        if (quality == "low" && currentCalibrated) {
            refreshProfile()
            return false
        }
        prefs.edit()
            .putFloat(autoKey(currentRoute), ms.toFloat())
            .putString(autoRefKey(currentRoute), hostId)
            .putString(autoQualityKey(currentRoute), quality)
            .putFloat(autoSpreadKey(currentRoute), spreadMs.toFloat())
            .putLong(autoAtKey(currentRoute), System.currentTimeMillis()) // só informativo
            .putString(autoAlgoKey(currentRoute), CALIBRATION_ALGO)
            .putInt(manualKey(currentRoute), 0)
            .apply()
        refreshProfile()
        return true
    }

    /** Remove a calibração da saída em uso. */
    @Synchronized
    fun clearAutoOffset(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(autoKey(currentRoute)).remove(autoRefKey(currentRoute))
            .remove(autoQualityKey(currentRoute)).remove(autoSpreadKey(currentRoute))
            .remove(autoAtKey(currentRoute)).remove(autoAlgoKey(currentRoute)).apply()
        refreshProfile()
    }

    private fun onHostKnown(hostId: String) {
        synchronized(this) {
            if (hostId == sessionHostId) return
            sessionHostId = hostId
            refreshProfile()
        }
    }

    /**
     * Recalcula o perfil da saída em uso: ajuste manual, calibração válida (só vale se foi feita
     * contra o anfitrião desta sessão) e avisos. Chamado ao entrar, ao trocar de saída e quando o
     * anfitrião é conhecido.
     */
    @Synchronized
    private fun refreshProfile() {
        val app = appContext ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val route = OutputRoute.current(app)
        currentRoute = route

        val manual = if (prefs.contains(manualKey(route))) {
            prefs.getInt(manualKey(route), 0)
        } else if (route != OutputRoute.Type.BLUETOOTH) {
            prefs.getInt(KEY_OFFSET, 0)
        } else {
            0
        }

        var auto = 0.0
        var calibrated = false
        var quality = ""
        var spread = 0.0
        var warning: String? = null
        val warnings = ArrayList<String>()

        if (currentRole == Role.HOST) {
            calibrated = true // o anfitrião é a referência
            if (route != OutputRoute.Type.SPEAKER) {
                warnings.add(
                    "Como anfitrião, a saída deste aparelho é a referência das calibrações. " +
                        "Calibre os outros aparelhos com esta mesma saída que você vai usar.",
                )
            }
        } else {
            var source = route
            var usingSpeakerFallback = false
            if (route == OutputRoute.Type.WIRED && !prefs.contains(autoKey(route)) && prefs.contains(autoKey(OutputRoute.Type.SPEAKER))) {
                source = OutputRoute.Type.SPEAKER
                usingSpeakerFallback = true
            }
            if (prefs.contains(autoKey(source))) {
                val ref = prefs.getString(autoRefKey(source), "") ?: ""
                if (sessionHostId.isNotEmpty() && ref == sessionHostId) {
                    auto = prefs.getFloat(autoKey(source), 0f).toDouble()
                    calibrated = true
                    quality = prefs.getString(autoQualityKey(source), "medium") ?: "medium"
                    spread = prefs.getFloat(autoSpreadKey(source), 0f).toDouble()
                    if (quality == "low") warnings.add("A calibração em uso tem baixa confiança. Refaça-a com os aparelhos mais próximos e em silêncio.")
                    if (usingSpeakerFallback) {
                        warnings.add("Usando a calibração do alto-falante, que é só aproximada para fone com fio. Calibre com o fone para maior precisão.")
                    }
                } else if (sessionHostId.isNotEmpty()) {
                    warnings.add("A calibração guardada desta saída foi feita com outro anfitrião e não está sendo usada. Calibre de novo.")
                }
            } else if (route != OutputRoute.Type.SPEAKER) {
                warnings.add("Esta saída ainda não foi calibrada neste aparelho.")
            }
        }
        if (route == OutputRoute.Type.BLUETOOTH) {
            warnings.add(
                "Bluetooth: o atraso é grande e pode mudar a cada conexão ou durante o uso. " +
                    "A calibração pode não se manter; para tocar junto, prefira fone com fio.",
            )
        }
        if (warnings.isNotEmpty()) warning = warnings.joinToString("\n")

        currentAuto = auto
        currentCalibrated = calibrated
        engine?.userOffsetMs = manual.toDouble()
        engine?.autoOffsetMs = auto
        audioPlayer?.userOffsetMs = manual.toDouble()
        audioPlayer?.autoOffsetMs = auto
        _ui.update {
            it.copy(
                offsetMs = manual,
                autoOffsetMs = auto,
                autoCalibrated = calibrated,
                autoQuality = quality,
                autoSpreadMs = spread,
                route = route.label,
                routeWarning = warning,
            )
        }
    }

    /** Volume do anfitrião para este aparelho = individual × do grupo (cada um de 0 a 1). */
    private fun recomputeHostVolume() {
        val levels = _mixLevels.value
        val group = _groups.value.groupOf(deviceIdInUse)
        val v = ((levels[deviceIdInUse] ?: 1.0) * (levels["g:$group"] ?: 1.0)).coerceIn(0.0, 1.0)
        Mix.host = v
        _ui.update { if (it.hostVolume == v) it else it.copy(hostVolume = v) }
    }

    /** Só o anfitrião: operações de grupo (criar, renomear, remover, mover aparelho). */
    private fun groupOp(build: JSONObject.() -> Unit) {
        val mc = mixClient ?: return
        scope?.launch {
            val ok = mc.groupOp(JSONObject().apply(build))
            if (!ok) _ui.update { it.copy(message = "Não foi possível alterar os grupos.") }
        }
    }

    fun addGroup(name: String) = groupOp { put("op", "add").put("name", name) }
    fun renameGroup(id: String, name: String) = groupOp { put("op", "rename").put("id", id).put("name", name) }
    fun removeGroup(id: String) = groupOp { put("op", "remove").put("id", id) }
    fun assignDevice(deviceId: String, group: String) = groupOp { put("op", "assign").put("device", deviceId).put("group", group) }

    /** Só o anfitrião: volume (0..1) de um grupo inteiro; multiplica o volume de cada aparelho dele. */
    fun setGroupVolume(groupId: String, level: Double) {
        val mc = mixClient ?: return
        val v = level.coerceIn(0.0, 1.0)
        _mixLevels.update { it + ("g:$groupId" to v) }
        recomputeHostVolume()
        scope?.launch { mc.set("g:$groupId", v) }
    }

    /** Volume próprio deste aparelho (0..1). Vale junto com o volume que o anfitrião definiu. */
    fun setOwnVolume(context: Context, level: Double) {
        val v = level.coerceIn(0.0, 1.0)
        Mix.own = v
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_OWN_VOLUME, v.toFloat()).apply()
        _ui.update { it.copy(ownVolume = v) }
    }

    /** Só o anfitrião: define o volume (0..1) de um aparelho da sessão (inclusive o próprio). */
    fun setDeviceVolume(context: Context, id: String, level: Double) {
        val mc = mixClient ?: return
        val srv = server ?: return
        val v = level.coerceIn(0.0, 1.0)
        // Mostra já na tela; a resposta do servidor confirma em seguida.
        _mixLevels.update { it + (id to v) }
        if (id == deviceIdInUse) recomputeHostVolume()
        val app = context.applicationContext
        scope?.launch {
            if (mc.set(id, v)) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_MIX_LEVELS, srv.mix.toJson().let { JSONObject(it).getJSONObject("levels").toString() }).apply()
            }
        }
    }

    /** Posição atual do áudio sincronizado em ms (null se não há arquivo). */
    fun audioPositionMs(): Double? = audioPlayback?.positionMs()

    /** Comando para o áudio sincronizado: play, pause, stop (qualquer aparelho pode enviar). */
    fun audioCommand(type: String, targetGroup: String? = null) {
        val pb = audioPlayback ?: return
        scope?.launch {
            val error = pb.command(type, null, targetGroup)
            _ui.update { it.copy(message = error) }
        }
    }

    /** Pula [deltaMs] a partir da posição atual (negativo volta); [toStart] volta ao início. */
    fun audioSeekBy(deltaMs: Double, toStart: Boolean = false) {
        val pb = audioPlayback ?: return
        val duration = _audio.value.durationMs
        val target = if (toStart) 0.0 else ((pb.positionMs() ?: 0.0) + deltaMs).let {
            if (duration > 0) it.coerceIn(0.0, duration) else it.coerceAtLeast(0.0)
        }
        scope?.launch {
            val error = pb.command("seek", target)
            _ui.update { it.copy(message = error) }
        }
    }

    /** Só o anfitrião: copia o arquivo escolhido para o app e o publica para os outros aparelhos. */
    fun pickAudio(context: Context, uri: Uri, groupId: String = "g1") {
        val srv = server ?: return
        val app = context.applicationContext
        scope?.launch(Dispatchers.IO) {
            try {
                var name = "áudio"
                var size = -1L
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val z = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0) name = c.getString(n) ?: name
                        if (z >= 0 && !c.isNull(z)) size = c.getLong(z)
                    }
                }
                if (size > MAX_AUDIO_BYTES) {
                    _ui.update { it.copy(message = "Arquivo grande demais (máximo ${MAX_AUDIO_BYTES / 1_000_000} MB).") }
                    return@launch
                }
                val dir = File(app.cacheDir, "audio-host").also { it.mkdirs() }
                val target = File(dir, "host-${System.currentTimeMillis()}.bin")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().buffered(1 shl 16).use { input.copyTo(it) }
                } ?: throw IOException("Não foi possível abrir o arquivo.")
                val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
                val hub = srv.hub(groupId)
                if (hub == null) {
                    runCatching { target.delete() }
                    _ui.update { it.copy(message = "Esse grupo não existe mais.") }
                    return@launch
                }
                hub.setFile(target, name, mime)
                _ui.update { it.copy(message = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "Não foi possível ler o arquivo de áudio: ${e.message}") }
            }
        }
    }

    fun beatInfo(): BeatInfo? {
        val s = _ui.value.metronome
        val ck = clock ?: return null
        val start = s.startAt ?: return null
        if (!s.playing) return null
        val now = ck.nowServerMs()
        if (now < start) return BeatInfo(0, (start - now).toLong())
        val index = floor((now - start) / (60000.0 / s.bpm)).toLong()
        return BeatInfo((index % s.beatsPerBar).toInt() + 1, 0)
    }

    private fun begin(context: Context, role: Role, url: String, hostUrls: List<String>) {
        val app = context.applicationContext
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ck = ClockSync()
        val en = ClickEngine(ck)
        val ap = AudioFilePlayer(ck)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cl = SessionClient(
            baseUrl = url,
            clock = ck,
            scope = sc,
            onState = { s ->
                en.setState(s)
                _ui.update { it.copy(metronome = s) }
            },
            onConnection = { connected -> _ui.update { it.copy(connected = connected) } },
        )
        scope = sc
        clock = ck
        engine = en
        audioPlayer = ap
        client = cl
        _ui.value = UiState(active = true, role = role, hostUrls = hostUrls)
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
        appContext = app
        currentRole = role
        sessionHostId = if (role == Role.HOST) deviceId else ""
        refreshProfile()
        unwatchRoute = OutputRoute.watch(app) { refreshProfile() }
        // Convidados fixam o tráfego no Wi-Fi mesmo se o Android preferir dados móveis por a rede
        // não ter internet. O anfitrião não faz isso para não afetar o acesso local (127.0.0.1).
        if (role == Role.GUEST) unbindNetwork = NetworkBinder.start(app)
        deviceIdInUse = deviceId
        val own = prefs.getFloat(KEY_OWN_VOLUME, 1f).toDouble().coerceIn(0.0, 1.0)
        Mix.own = own
        Mix.reset()
        _ui.update { it.copy(ownVolume = own, hostVolume = 1.0) }
        val mc = MixClient(
            baseUrl = url,
            scope = sc,
            deviceId = deviceId,
            onLevels = { levels ->
                _mixLevels.value = levels
                recomputeHostVolume()
            },
            onGroups = { info ->
                _groups.value = info
                val mine = info.groupOf(deviceId)
                audioPlayback?.setGroup(mine)
                val name = info.groups.firstOrNull { it.id == mine }?.name ?: "Geral"
                _ui.update { if (it.groupId == mine && it.groupName == name) it else it.copy(groupId = mine, groupName = name) }
                recomputeHostVolume()
            },
        )
        mixClient = mc
        mc.start()
        val cm = CalibrationManager(
            baseUrl = url,
            clock = ck,
            scope = sc,
            deviceId = deviceId,
            deviceName = "${Build.MODEL} (${deviceId.take(4)})",
            isHost = role == Role.HOST,
            canRecord = {
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            autoOffset = { currentAuto },
            calibrated = { currentCalibrated },
            routeName = { currentRoute.name },
            onHost = { id -> onHostKnown(id) },
            onUi = { u -> _calibration.value = u },
            onApplyOffset = { ms, hostId, quality, spread -> applyAutoOffset(app, ms, hostId, quality, spread) },
        )
        calibrationManager = cm
        _calibration.value = CalibrationManager.Ui()

        val playback = AudioPlayback(
            context = app,
            baseUrl = url,
            clock = ck,
            scope = sc,
            deviceId = deviceId,
            player = ap,
            onUi = { u -> _audio.value = u },
        )
        audioPlayback = playback
        _audio.value = AudioPlayback.Ui()

        en.start()
        ap.start()
        playback.start()
        cl.start()
        cm.start()
        sc.launch {
            while (isActive) {
                delay(500)
                val quality = computeQuality(ck, en, ap)
                _ui.update {
                    it.copy(clockSynced = ck.isSynced, rttMs = ck.rttMs.takeIf { v -> !v.isNaN() }, sync = quality)
                }
            }
        }
        ContextCompat.startForegroundService(app, Intent(app, SessionService::class.java))
    }

    /** Reúne o diagnóstico de sincronização. Roda na corrotina de interface, nunca na thread de áudio. */
    private fun computeQuality(ck: ClockSync, en: ClickEngine, ap: AudioFilePlayer): SyncQuality {
        val stats = ck.stats()
        // Entre o metrônomo e a música, vale o pior estado do relógio de áudio de quem está tocando.
        val states = listOfNotNull(en.clockState, ap.clockState)
        // ordem do enum: WARMING_UP < PRECISE < FALLBACK < UNSTABLE
        val audio = states.maxByOrNull { it.ordinal }
        val penalty = when (audio) {
            AudioClockTracker.State.FALLBACK -> 1
            AudioClockTracker.State.UNSTABLE -> 2
            else -> 0
        }
        val level = SyncMath.level(ck.isSynced, ck.lastGoodAgeMs(), stats, penalty)
        val musicPlaying = ap.clockState != null
        return SyncQuality(
            level = level,
            rttMinMs = stats?.rttMin ?: Double.NaN,
            rttMedianMs = stats?.rttMedian ?: Double.NaN,
            rttLastMs = stats?.rttLast ?: Double.NaN,
            jitterMs = stats?.jitter ?: Double.NaN,
            precisionMs = stats?.precision ?: Double.NaN,
            lastGoodAgeMs = ck.lastGoodAgeMs(),
            accepted = ck.acceptedCount,
            rejected = ck.rejectedCount,
            audioClock = audio,
            audioJumps = maxOf(en.clockJumps, ap.clockJumps),
            musicErrorMs = if (musicPlaying) ap.diagErrorMs else Double.NaN,
            musicRate = if (musicPlaying) ap.diagRate else 1.0,
            musicJumps = if (musicPlaying) ap.diagJumps else 0,
            musicSaturatedPercent = if (musicPlaying) ap.diagSaturatedPercent else 0.0,
        )
    }

    private fun normalizeUrl(input: String): String? {
        var t = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (t.isEmpty() || t.contains(' ') || t.contains('/')) return null
        if (!t.contains(':')) t += ":$DEFAULT_PORT"
        return "http://$t"
    }

    private fun localAddresses(): List<String> = try {
        (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }
}
