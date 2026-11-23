package com.metronomo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.metronomo.SessionController
import com.metronomo.SessionController.BeatInfo
import com.metronomo.SessionController.UiState
import com.metronomo.core.MetronomeState
import com.metronomo.net.GroupInfo
import com.metronomo.net.ScoreInfo
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun MetronomoApp() {
    val ui by SessionController.ui.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.Canvas)
            .safeDrawingPadding(),
    ) {
        SincroOpenDialog(ui)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            if (!ui.active) StartScreen(ui)
            else if (ui.role == SessionController.Role.HOST) SessionScreen(ui, tab)
            else GuestScreen(ui)
        }
        AdBanner()
        // Só o anfitrião tem todas as seções; o cliente vê uma tela única e simples.
        if (ui.active && ui.role == SessionController.Role.HOST) BottomTabs(tab) { tab = it }
    }
}

private val TAB_NAMES = listOf("SYNC", "MÚSICA", "PLAYLIST", "MIXER", "REDE")

/** Navegação inferior: só texto, o item ativo em tinta escura e com um filete no alto. */
@Composable
private fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Pal.Surface)) {
        HorizontalDivider(color = Pal.Hairline)
        Row(Modifier.fillMaxWidth()) {
            TAB_NAMES.forEachIndexed { i, name ->
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(i) }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.height(2.dp).size(width = 24.dp, height = 2.dp).background(if (i == selected) Pal.Ink else Color.Transparent))
                    Text(
                        name,
                        style = LabelStyle,
                        color = if (i == selected) Pal.Ink else Pal.Graphite,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Pal.Ink,
    unfocusedBorderColor = Pal.Hairline,
    focusedContainerColor = Pal.Surface,
    unfocusedContainerColor = Pal.Surface,
    cursorColor = Pal.Ink,
)

/** Ícone do app: cinco barras verticais (forma de onda). */
@Composable
private fun WaveIcon() {
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Pal.Recessed),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp)) {
            val heights = listOf(0.35f, 0.7f, 1f, 0.55f, 0.3f)
            val step = size.width / (heights.size + 1)
            heights.forEachIndexed { i, h ->
                val x = step * (i + 1)
                val half = size.height * h / 2
                drawLine(Pal.Ink, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun StartScreen(ui: UiState) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("metronomo", Context.MODE_PRIVATE) }
    var address by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    val discovery = remember { SessionController.discovery(context) }
    val found by discovery.found.collectAsState()
    val searching by discovery.searching.collectAsState()
    // Procura sessões na rede enquanto esta tela está aberta.
    DisposableEffect(Unit) {
        discovery.startBrowsing()
        onDispose { discovery.stopBrowsing() }
    }
    val permissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    // Notificações (serviço em primeiro plano) e microfone (para ajudar a medir os outros na calibração).
    fun askNotifications() {
        val missing = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty()) permissionsLauncher.launch(missing.toTypedArray())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Badge(
                when {
                    found.isNotEmpty() -> "Sessão encontrada"
                    searching -> "Procurando na rede"
                    else -> "Rede local"
                },
                dot = if (found.isNotEmpty()) Pal.Ok else Pal.Graphite,
            )
            Text("v0.5", style = TabularStyle, color = Pal.Graphite)
        }

        WaveIcon()
        Text("Sincrono", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Vários aparelhos na mesma rede Wi-Fi tocando juntos, cada um no seu fone.",
            style = MaterialTheme.typography.bodyLarge,
            color = Pal.Graphite,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    askNotifications()
                    SessionController.startHost(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Criar sessão (anfitrião)", fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("→", fontSize = 20.sp)
            }
            Text("MODO ANFITRIÃO", style = LabelStyle)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(Modifier.weight(1f), color = Pal.Hairline)
            Text("OU", style = LabelStyle)
            HorizontalDivider(Modifier.weight(1f), color = Pal.Hairline)
        }

        Module("Sessões na rede", if (searching) "procurando…" else null) {
            if (found.isEmpty()) {
                Text(
                    if (searching) "Nenhuma sessão por enquanto." else "Nenhuma sessão encontrada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Pal.Graphite,
                )
            }
            found.forEach { f ->
                FilledTonalButton(
                    onClick = {
                        askNotifications()
                        SessionController.join(context, f.address)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) { Text("Entrar: ${f.name.removePrefix("Metronomo ")} (${f.address})") }
            }
            Text(
                "Se a sessão não aparecer (alguns roteadores e redes de visitantes bloqueiam a descoberta), use o QR code ou digite o endereço.",
                style = MaterialTheme.typography.bodySmall,
                color = Pal.Graphite,
            )
        }

        Module("Endereço do anfitrião", "IP:PORTA") {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                placeholder = { Text("192.168.0.15:3000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors(),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    prefs.edit().putString("address", address).apply()
                    askNotifications()
                    SessionController.join(context, address)
                },
                enabled = address.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text("Entrar na sessão") }
            OutlinedButton(
                onClick = {
                    scanMessage = null
                    scanQr(
                        context,
                        onResult = { text ->
                            address = text.trim()
                            prefs.edit().putString("address", address).apply()
                            askNotifications()
                            SessionController.join(context, address)
                        },
                        onError = { scanMessage = it },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text("Ler QR code do anfitrião") }
            scanMessage?.let { Text(it, color = Pal.Bad, style = MaterialTheme.typography.bodySmall) }
        }

        ui.message?.let { Text(it, color = Pal.Bad) }

        Text(
            "Também é possível entrar numa sessão criada pelo servidor do computador (veja a pasta servidor-pc) ou pelo servidor Node.js do protótipo web: use o endereço que ele mostra.",
            style = MaterialTheme.typography.bodySmall,
            color = Pal.Graphite,
        )
    }
}

@Composable
private fun SessionScreen(ui: UiState, tab: Int) {
    val context = LocalContext.current
    val state = ui.metronome

    // Valores em edição; só vão para a sessão depois de uma pausa nos toques.
    var bpm by remember { mutableIntStateOf(state.bpm.roundToInt()) }
    var beats by remember { mutableIntStateOf(state.beatsPerBar) }
    LaunchedEffect(state.revision) {
        bpm = state.bpm.roundToInt()
        beats = state.beatsPerBar
    }
    LaunchedEffect(bpm, beats) {
        if (bpm != state.bpm.roundToInt() || beats != state.beatsPerBar) {
            delay(700)
            SessionController.sendCommand("settings", bpm, beats)
        }
    }

    var beatInfo by remember { mutableStateOf<BeatInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            beatInfo = SessionController.beatInfo()
            delay(20)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StageDialog(ui)
        StatusModule(ui)
        when (tab) {
            0 -> {
                SyncTab(ui, bpm, beats, beatInfo, { bpm = it }, { beats = it })
                ScoreCard(ui)
            }
            1 -> AudioTab(ui)
            2 -> SetlistCard()
            3 -> MixerTab(ui)
            else -> NetworkTab(ui)
        }
        ui.message?.let { Text(it, color = Pal.Bad, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = { SessionController.leave(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sair da sessão", color = Pal.Bad) }
    }
}

@Composable
private fun StatusModule(ui: UiState) {
    Module("Sessão", ui.rttMs?.let { String.format(Locale.ROOT, "Rede: %.0f ms", it) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (ui.connected && ui.clockSynced) Pal.Ok else Pal.Bad)
            Text(if (ui.connected) "Conectado à sessão" else "Reconectando à sessão…", fontWeight = FontWeight.Medium)
        }
        Text("Grupo: ${ui.groupName}   ·   Saída: ${ui.route}", style = MaterialTheme.typography.bodySmall, color = Pal.Graphite)
        ui.routeWarning?.let { Text(it, color = Pal.Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SyncTab(
    ui: UiState,
    bpm: Int,
    beats: Int,
    info: BeatInfo?,
    setBpm: (Int) -> Unit,
    setBeats: (Int) -> Unit,
) {
    val context = LocalContext.current
    val state = ui.metronome
    // Cartão escuro, no mesmo visual da playlist: pontos dos tempos, BPM grande, botões redondos.
    val dim = Color(0xFF9CA3AF)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Ink).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("METRÔNOMO", style = LabelStyle, color = dim)
            Text(
                info?.let { if (it.beat == 0) "começa em ${ceil(it.countdownMs / 1000.0).toInt()}" else "${it.beat} / ${state.beatsPerBar}" } ?: "",
                style = LabelStyle, color = Color.White,
            )
        }
        // Uma bolinha por tempo; a do tempo atual fica acesa.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 1..state.beatsPerBar.coerceAtMost(12)) {
                val active = info != null && info.beat == i
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (active) Color.White else Color(0xFF374151)))
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$bpm", color = Color.White, style = TabularStyle.copy(fontSize = 88.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp))
            Text("  BPM", style = LabelStyle, color = dim, modifier = Modifier.padding(bottom = 18.dp))
        }
        StepRow(listOf(-5, -1, 1, 5), dark = true) { delta -> setBpm((bpm + delta).coerceIn(MetronomeState.MIN_BPM, MetronomeState.MAX_BPM)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            RoundStep("−", true) { setBeats((beats - 1).coerceIn(MetronomeState.MIN_BEATS, MetronomeState.MAX_BEATS)) }
            Column(Modifier.padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$beats", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                Text("TEMPOS POR COMPASSO", style = LabelStyle, color = dim)
            }
            RoundStep("+", true) { setBeats((beats + 1).coerceIn(MetronomeState.MIN_BEATS, MetronomeState.MAX_BEATS)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            TransportButton(
                Glyph.PLAY, "Iniciar o metrônomo (se já estiver tocando, recomeça do primeiro tempo)",
                { SessionController.sendCommand("play", bpm, beats) }, primary = true, onDark = true,
            )
            TransportButton(Glyph.STOP, "Parar o metrônomo", { SessionController.sendCommand("pause", bpm, beats) }, onDark = true)
        }
    }

    Module("Ajuste fino de latência", "${if (ui.offsetMs > 0) "+" else ""}${ui.offsetMs} ms") {
        StepRow(listOf(-5, -1, 1, 5)) { delta -> SessionController.changeOffset(context, delta) }
        Text(
            "Se este aparelho soa atrasado em relação aos outros, aumente (toca mais cedo). Se soa adiantado, diminua.",
            style = MaterialTheme.typography.bodySmall,
            color = Pal.Graphite,
        )
    }

    Module("Calibração automática", if (ui.role == SessionController.Role.HOST && !ui.autoSelf) "referência" else if (ui.autoCalibrated) String.format(Locale.ROOT, "%+.1f ms", ui.autoOffsetMs) else "não calibrado") {
        CalibrationSection(ui, state.playing, groupCalibration = true)
    }
}

/** Botão redondo de ajuste (−5, +1, ...). [dark]: sobre o cartão escuro. */
@Composable
private fun RoundStep(label: String, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (dark) Color(0xFF374151) else Pal.Recessed).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (dark) Color.White else Pal.Ink, fontWeight = FontWeight.Medium) }
}

@Composable
private fun StepRow(steps: List<Int>, dark: Boolean = false, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
        steps.forEach { delta -> RoundStep(if (delta > 0) "+$delta" else "−${-delta}", dark) { onChange(delta) } }
    }
}

@Composable
private fun AudioTab(ui: UiState) {
    // O anfitrião também está num grupo: o áudio dele é o do cartão do seu grupo, logo abaixo. Só os convidados veem este cartão.
    if (ui.role != SessionController.Role.HOST) {
        Module("Áudio do meu grupo", ui.groupName) {
            AudioSection(ui)
        }
    }
    // O que o grupo toca e o que ele lê são uma coisa só: arquivo de áudio + parte da partitura, no cartão do grupo.
    if (ui.role == SessionController.Role.HOST) GroupsPanel()
}

/** Só o anfitrião: cria grupos, escolhe o arquivo de áudio e a parte da partitura de cada um, move aparelhos entre eles e toca todos juntos. */
@Composable
private fun GroupsPanel() {
    val context = LocalContext.current
    val info by SessionController.groups.collectAsState()
    val levels by SessionController.mixLevels.collectAsState()
    val calib by SessionController.calibration.collectAsState()
    val score by SessionController.score.collectAsState()
    var newName by remember { mutableStateOf("") }
    var pickTarget by remember { mutableStateOf("g1") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) SessionController.pickAudio(context, uri, pickTarget)
    }
    val scorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) SessionController.importScore(context, uri)
    }

    Module("Grupos de aparelhos", "${info.groups.size}") {
        Text(
            "Cada grupo toca o seu arquivo; todos seguem o mesmo relógio, então começam e param juntos.",
            style = MaterialTheme.typography.bodySmall,
            color = Pal.Graphite,
        )
        // Partitura da sessão (MusicXML do MuseScore): importa-se uma vez; cada grupo escolhe a sua parte no cartão abaixo.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (score.fileName.isEmpty()) "Partitura: nenhuma" else "Partitura: ${score.fileName}",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
            )
            FilledTonalButton(onClick = { scorePicker.launch(arrayOf("*/*")) }) { Text(if (score.fileName.isEmpty()) "Importar" else "Trocar") }
            if (score.fileName.isNotEmpty()) OutlinedButton(onClick = { SessionController.clearScore() }) { Text("Remover", color = Pal.Bad) }
        }
        if (score.fileName.isNotEmpty()) {
            OutlinedButton(onClick = { SessionController.applyScoreTempo() }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (score.bpm > 0) "Metrônomo = partitura (${if (score.bpmEstimated) "~" else ""}${Math.round(score.bpm)} bpm, ${if (score.beats > 0) score.beats else "?"} tempos)"
                    else "Metrônomo = partitura",
                )
            }
        }
        if (score.fileName.isEmpty()) {
            Text(
                "No MuseScore: Arquivo → Exportar → MusicXML (a partitura completa, uma vez só). Depois de importar, cada grupo ganha a opção \"Parte da partitura\".",
                style = MaterialTheme.typography.bodySmall,
                color = Pal.Graphite,
            )
        }
        info.groups.forEach { g ->
            HorizontalDivider(color = Pal.Hairline)
            GroupRow(g, (levels["g:${g.id}"] ?: 1.0).toFloat(), score,
                onPick = {
                    pickTarget = g.id
                    picker.launch(arrayOf("audio/*"))
                },
            )
        }
        HorizontalDivider(color = Pal.Hairline)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text("Novo grupo (ex.: Violinos)") },
                singleLine = true,
                colors = fieldColors(),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    SessionController.addGroup(newName)
                    newName = ""
                },
                enabled = newName.isNotBlank(),
                modifier = Modifier.height(56.dp),
            ) { Text("Criar") }
        }
    }

    Module("Aparelhos e grupos", "${calib.peers.size}") {
        if (calib.peers.isEmpty()) {
            Text("Os aparelhos aparecem aqui quando entram na sessão.", style = MaterialTheme.typography.bodySmall, color = Pal.Graphite)
        }
        calib.peers.forEach { p ->
            PeerGroupPicker(
                name = p.name + if (p.me) " (este)" else "",
                current = info.groupOf(p.id),
                groups = info.groups,
                onPick = { SessionController.assignDevice(p.id, it) },
            )
        }
    }
}

@Composable
private fun GroupRow(g: GroupInfo, level: Float, score: ScoreInfo, onPick: () -> Unit) {
    var editing by remember(g.id) { mutableStateOf(false) }
    var name by remember(g.id) { mutableStateOf(g.name) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (editing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    colors = fieldColors(),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        SessionController.renameGroup(g.id, name.trim())
                        editing = false
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.height(56.dp),
                ) { Text("Salvar") }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (g.playing) Pal.Ok else Pal.Recessed, 8.dp)
                    Text(g.name, fontWeight = FontWeight.Bold, color = groupColor(g))
                }
                Text(g.file ?: "sem arquivo", style = MaterialTheme.typography.bodySmall, color = Pal.Graphite)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPick, modifier = Modifier.weight(1.4f)) { Text(if (g.file == null) "Escolher arquivo" else "Trocar") }
            OutlinedButton(
                onClick = {
                    name = g.name
                    editing = !editing
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (editing) "Cancelar" else "Renomear") }
        }
        // Cor do grupo: o anfitrião escolhe (a mesma cor aparece em todos os aparelhos)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cor", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
            val chosen = groupHex(g).lowercase()
            for (hex in GROUP_PALETTE) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(hex)))
                        .border(if (hex == chosen) 3.dp else 1.dp, if (hex == chosen) Pal.Ink else Pal.Hairline, CircleShape)
                        .clickable { SessionController.setGroupColor(g.id, hex) },
                )
            }
        }
        // Parte da partitura que este grupo vê (só aparece depois de importar a partitura na aba MÚSICA).
        if (score.partNames.isNotEmpty()) {
            var openParts by remember(g.id) { mutableStateOf(false) }
            val current = (score.parts[g.id] ?: 0).coerceIn(0, score.partNames.size - 1)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Parte da partitura", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Box {
                    FilledTonalButton(onClick = { openParts = true }) { Text(score.partNames[current]) }
                    DropdownMenu(expanded = openParts, onDismissRequest = { openParts = false }) {
                        score.partNames.forEachIndexed { i, n ->
                            DropdownMenuItem(
                                text = { Text(n) },
                                onClick = {
                                    openParts = false
                                    SessionController.setScorePart(g.id, i)
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            TransportButton(Glyph.TO_START, "Voltar ao início", { SessionController.audioToStart(g.id) }, Modifier.weight(1f), enabled = g.file != null)
            TransportButton(Glyph.PLAY, "Tocar", { SessionController.audioCommand("play", g.id) }, Modifier.weight(1f), enabled = g.file != null && !g.playing, primary = true)
            TransportButton(Glyph.PAUSE, "Pausar", { SessionController.audioCommand("pause", g.id) }, Modifier.weight(1f), enabled = g.playing)
            TransportButton(Glyph.STOP, "Parar e voltar ao início", { SessionController.audioCommand("stop", g.id) }, Modifier.weight(1f), enabled = g.file != null)
        }
        Fader("Volume do grupo", level, key = g.id, onFinish = { SessionController.setGroupVolume(g.id, it.toDouble()) })
        if (g.id != "g1") {
            OutlinedButton(onClick = { SessionController.removeGroup(g.id) }, modifier = Modifier.fillMaxWidth()) { Text("Remover grupo", color = Pal.Bad) }
        }
    }
}

@Composable
private fun PeerGroupPicker(name: String, current: String, groups: List<GroupInfo>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            androidx.compose.material3.FilledTonalButton(
                onClick = { open = true },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = groups.firstOrNull { it.id == current }?.let { groupColor(it) } ?: groupColor(current), contentColor = androidx.compose.ui.graphics.Color.White),
            ) { Text(groups.firstOrNull { it.id == current }?.name ?: "Geral") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                groups.forEach { g ->
                    DropdownMenuItem(
                        text = { Text(g.name, color = groupColor(g), fontWeight = FontWeight.Bold) },
                        onClick = {
                            open = false
                            onPick(g.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MixerTab(ui: UiState) {
    val context = LocalContext.current
    val levels by SessionController.mixLevels.collectAsState()
    val cal by SessionController.calibration.collectAsState()
    Module("Meu volume", "${(ui.ownVolume * 100).roundToInt()}%") {
        Fader("Este aparelho", ui.ownVolume.toFloat(), key = "own", onFinish = { SessionController.setOwnVolume(context, it.toDouble()) })
        if (ui.hostVolume < 0.995) {
            val final = (ui.ownVolume * ui.hostVolume * 100).roundToInt()
            Text(
                "O anfitrião ajustou este aparelho para ${(ui.hostVolume * 100).roundToInt()}%. Volume final: $final%.",
                style = MaterialTheme.typography.bodySmall,
                color = Pal.Graphite,
            )
        }
        Text(
            "O volume vale para o metrônomo e a música, não para os sons da calibração.",
            style = MaterialTheme.typography.bodySmall,
            color = Pal.Graphite,
        )
    }
    if (ui.role == SessionController.Role.HOST) {
        Module("Mixer do anfitrião", "${cal.peers.size}") {
            if (cal.peers.isEmpty()) {
                Text("Os aparelhos aparecem aqui quando entram na sessão.", style = MaterialTheme.typography.bodySmall, color = Pal.Graphite)
            }
            cal.peers.forEach { p ->
                Fader(
                    "${p.name}${if (p.me) " (este)" else ""}",
                    (levels[p.id] ?: 1.0).toFloat(),
                    key = p.id,
                    onFinish = { SessionController.setDeviceVolume(context, p.id, it.toDouble()) },
                )
            }
        }
    }
}

@Composable
private fun NetworkTab(ui: UiState) {
    Module("Qualidade da sincronização") {
        SyncQualityBlock(ui)
    }
    if (ui.hostUrls.isNotEmpty()) {
        Module("Convite", "QR code") {
            Text("No outro celular, ou no navegador do computador, entre em:", style = MaterialTheme.typography.bodySmall, color = Pal.Graphite)
            SelectionContainer {
                Column {
                    ui.hostUrls.forEach {
                        Text(it, style = TabularStyle.copy(fontSize = 16.sp))
                    }
                }
            }
            pickLanUrl(ui.hostUrls)?.let { url ->
                Text("Ou aponte o leitor de QR code (no app, ou a câmera do celular para abrir no navegador):", style = MaterialTheme.typography.bodySmall, color = Pal.Graphite)
                QrImage(url, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun CalibrationSection(ui: UiState, playing: Boolean, groupCalibration: Boolean) {
    val context = LocalContext.current
    val calib by SessionController.calibration.collectAsState()
    // Inicia mesmo sem a permissão: o aparelho ainda toca o som, só não grava (calibração em conjunto).
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        SessionController.startCalibration(null)
    }
    // A autocalibração sempre grava o próprio microfone: sem a permissão não há o que medir.
    val selfPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) SessionController.startSelfCalibration()
    }

    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun beginSelf() {
        if (hasMic()) SessionController.startSelfCalibration() else selfPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun beginGroup() {
        if (hasMic()) SessionController.startCalibration(null) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val busy = calib.running || playing
    val isHost = ui.role == SessionController.Role.HOST
    val pm = context.packageManager
    val lowLatency = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
    val proAudio = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "O aparelho toca um som e o próprio microfone o escuta: não precisa de outro aparelho. " +
                "Fique em silêncio, com o aparelho parado e volume médio. Com fone com fio, conecte-o e encoste a concha no microfone do aparelho. " +
                "Cada tipo de saída (alto-falante, fone) guarda o seu valor. Para o grupo ficar alinhado, todos os aparelhos, o anfitrião inclusive, devem estar autocalibrados.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            when {
                proAudio -> "Este aparelho declara áudio profissional (ida e volta de até 20 ms)."
                lowLatency -> "Este aparelho declara baixa latência de áudio (saída de até 45 ms)."
                else -> "Atenção: este aparelho não declara baixa latência de áudio. A latência pode ser maior e menos estável: " +
                    "meça duas vezes e, se os valores diferirem mais que 2 ms, não confie neles."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { beginSelf() },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text(if (playing) "Pare o metrônomo para calibrar" else "Autocalibrar este aparelho") }

        if (calib.status.isNotBlank()) Text(calib.status, fontWeight = FontWeight.Bold)
        calib.lines.forEach { Text(it) }

        Text(
            if (ui.autoCalibrated && (!isHost || ui.autoSelf)) {
                (if (ui.autoSelf) "Autocalibração desta saída (${ui.route}): " else "Calibração desta saída (${ui.route}): ") +
                    String.format(Locale.ROOT, "%+.1f ms", ui.autoOffsetMs) +
                    " (confiança ${qualityLabel(ui.autoQuality)}, variação " + String.format(Locale.ROOT, "%.1f ms)", ui.autoSpreadMs)
            } else {
                "Esta saída (${ui.route}) ainda não está calibrada."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (!isHost || ui.autoSelf) {
            OutlinedButton(
                onClick = { SessionController.clearAutoOffset(context) },
                enabled = ui.autoCalibrated && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Remover calibração desta saída") }
        }

        if (groupCalibration && calib.supported) {
            HorizontalDivider()
            Text("Calibração em conjunto (só o anfitrião)", fontWeight = FontWeight.Bold)
            Text(
                "Alternativa: calibra todos os aparelhos de uma vez, medindo as diferenças entre eles pelo microfone, com o anfitrião como referência. " +
                    "Coloque os aparelhos perto uns dos outros e fique em silêncio. Não misture com a autocalibração, a menos que o anfitrião também esteja autocalibrado.",
                style = MaterialTheme.typography.bodySmall,
            )
            FilledTonalButton(
                onClick = { beginGroup() },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text("Calibrar todos os aparelhos") }
        }
    }
}

private fun formatTime(ms: Double): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

@Composable
private fun AudioSection(ui: UiState) {
    val context = LocalContext.current
    val audio by SessionController.audio.collectAsState()
    val isHost = ui.role == SessionController.Role.HOST
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) SessionController.pickAudio(context, uri, ui.groupId)
    }
    var position by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            position = SessionController.audioPositionMs()
            delay(200)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!audio.supported) {
            Text(
                "O anfitrião desta sessão não oferece áudio sincronizado. Use este app como anfitrião.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        if (isHost) {
            Button(
                onClick = { picker.launch(arrayOf("audio/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text(if (audio.fileName.isBlank()) "Escolher arquivo de áudio" else "Trocar o arquivo") }
        }
        if (audio.fileName.isBlank()) {
            Text(
                if (isHost) "Escolha uma música (MP3, WAV, FLAC, M4A…). Os outros aparelhos baixam o arquivo daqui e tocam juntos. Prefira WAV ou FLAC para a máxima precisão."
                else "Aguardando o anfitrião escolher um arquivo.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(audio.fileName, fontWeight = FontWeight.Bold)
            Text(audio.status)
            Text("${audio.readyCount} de ${audio.deviceCount} aparelhos prontos", style = MaterialTheme.typography.bodySmall)
            val p = position
            Text(
                if (p == null) "—" else formatTime(p) + if (audio.durationMs > 0) " / " + formatTime(audio.durationMs) else "",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                TransportButton(Glyph.PLAY, "Tocar", { SessionController.audioCommand("play") }, Modifier.weight(1f), enabled = !audio.playing, primary = true)
                TransportButton(Glyph.PAUSE, "Pausar", { SessionController.audioCommand("pause") }, Modifier.weight(1f), enabled = audio.playing)
                TransportButton(Glyph.STOP, "Parar e voltar ao início", { SessionController.audioCommand("stop") }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                TransportButton(Glyph.TO_START, "Voltar ao início", { SessionController.audioToStart() }, Modifier.weight(1f))
                TransportButton(Glyph.BACK, "Voltar 10 segundos", { SessionController.audioSeekBy(-10_000.0) }, Modifier, label = "10")
                TransportButton(Glyph.FORWARD, "Avançar 10 segundos", { SessionController.audioSeekBy(10_000.0) }, Modifier, label = "10")
            }
            if (audio.deviceCount > audio.readyCount) {
                Text(
                    "Alguns aparelhos ainda estão preparando o arquivo. Quem terminar depois entra tocando no ponto certo.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun qualityLabel(q: String) = when (q) {
    "high" -> "alta"
    "low" -> "baixa"
    else -> "média"
}

/** Resumo de por que a sincronização é (ou não) confiável: relógio, Wi-Fi, áudio, calibração e música. */
@Composable
private fun SyncQualityBlock(ui: UiState) {
    val q = ui.sync
    val bad = q.level == com.metronomo.diag.SyncMath.Level.DEGRADED || q.level == com.metronomo.diag.SyncMath.Level.UNRELIABLE
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!ui.clockSynced) {
            Text("Sincronizando o relógio…")
            return@Column
        }
        Text(
            "Sincronização: ${q.levelLabel}" +
                if (!q.precisionMs.isNaN()) String.format(Locale.ROOT, " — precisão estimada ±%.1f ms", q.precisionMs) else "",
            fontWeight = FontWeight.Bold,
            color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (!q.rttMedianMs.isNaN()) {
            Text(
                String.format(
                    Locale.ROOT,
                    "Wi-Fi: RTT %.0f ms (mín. %.0f), jitter %.1f ms · amostras %d ok, %d descartadas",
                    q.rttMedianMs, q.rttMinMs, q.jitterMs, q.accepted, q.rejected,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Áudio: ${q.audioClockLabel}", style = MaterialTheme.typography.bodySmall)
        Text(
            when {
                ui.role == SessionController.Role.HOST && !ui.autoSelf -> "Calibração: este aparelho é a referência."
                ui.autoCalibrated -> "Calibração: válida (confiança ${qualityLabel(ui.autoQuality)}, variação " +
                    String.format(Locale.ROOT, "%.1f ms)", ui.autoSpreadMs)
                else -> "Calibração: ausente para esta saída (${ui.route})."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (!q.musicErrorMs.isNaN()) {
            Text(
                String.format(
                    Locale.ROOT,
                    "Música: erro %+.1f ms, velocidade %.3f%%, saltos %d, velocidade no limite %.0f%% do tempo",
                    q.musicErrorMs, (q.musicRate - 1.0) * 100.0, q.musicJumps, q.musicSaturatedPercent,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (q.musicSaturatedPercent > 30.0) {
                Text(
                    "A correção da música está no limite com frequência: relógio, timestamp de áudio ou calibração podem estar errados.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Tela do cliente: só o básico. Sem controle nenhum do metrônomo ou do áudio: apenas acompanha. */
@Composable
private fun GuestScreen(ui: UiState) {
    val context = LocalContext.current
    var beatInfo by remember { mutableStateOf<BeatInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            beatInfo = SessionController.beatInfo()
            delay(20)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StageDialog(ui)
        StatusModule(ui)
        MetronomeViewModule(ui, beatInfo)
        AudioViewModule(ui)
        ScoreCard(ui)
        Module("Autocalibração", if (ui.autoCalibrated) String.format(Locale.ROOT, "%+.1f ms", ui.autoOffsetMs) else "não calibrado") {
            CalibrationSection(ui, ui.metronome.playing, groupCalibration = false)
        }
        NicknameModule()
        ui.message?.let { Text(it, color = Pal.Bad, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = { SessionController.leave(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sair da sessão", color = Pal.Bad) }
    }
}

/** Só mostra: bolinhas dos tempos, andamento e contagem. Nenhum botão. */
@Composable
private fun MetronomeViewModule(ui: UiState, info: BeatInfo?) {
    val state = ui.metronome
    // Mesmo cartão escuro do anfitrião, sem botões.
    val dim = Color(0xFF9CA3AF)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.Ink).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("METRÔNOMO", style = LabelStyle, color = dim)
            Text(
                info?.let { if (it.beat == 0) "começa em ${ceil(it.countdownMs / 1000.0).toInt()}" else "${it.beat} / ${state.beatsPerBar}" } ?: if (state.playing) "tocando" else "parado",
                style = LabelStyle, color = Color.White,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 1..state.beatsPerBar.coerceAtMost(12)) {
                val active = info != null && info.beat == i
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (active) Color.White else Color(0xFF374151)))
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${state.bpm.roundToInt()}", color = Color.White, style = TabularStyle.copy(fontSize = 88.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp))
            Text("  BPM", style = LabelStyle, color = dim, modifier = Modifier.padding(bottom = 18.dp))
        }
    }
}

/** Só mostra: nome do arquivo, preparo, barra de progresso e tempo. Nenhum botão. */
@Composable
private fun AudioViewModule(ui: UiState) {
    val audio by SessionController.audio.collectAsState()
    var position by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            position = SessionController.audioPositionMs()
            delay(100)
        }
    }
    Module("Áudio", ui.groupName) {
        if (!audio.supported) {
            Text("O anfitrião desta sessão não oferece áudio sincronizado.", style = MaterialTheme.typography.bodySmall)
        } else if (audio.fileName.isBlank()) {
            Text("Aguardando o anfitrião escolher um arquivo.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(audio.fileName, fontWeight = FontWeight.Bold)
            if (!audio.ready) Text(audio.status, style = MaterialTheme.typography.bodySmall)
            val p = position
            val d = audio.durationMs
            LinearProgressIndicator(
                progress = { if (p != null && d > 0) (p / d).toFloat().coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = Pal.Ink,
                trackColor = Pal.Recessed,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (p == null) "—" else formatTime(p), style = TabularStyle)
                Text(if (audio.playing) "Tocando" else "Parado", style = MaterialTheme.typography.bodySmall, color = Pal.Graphite)
                Text(if (d > 0) formatTime(d) else "", style = TabularStyle)
            }
        }
    }
}

/** O cliente dá um apelido ao próprio aparelho; o anfitrião o vê nas listas de aparelhos e grupos. */
@Composable
private fun NicknameModule() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(SessionController.nickname(context)) }
    var saved by remember { mutableStateOf(false) }
    Module("Nome deste aparelho") {
        Text(
            "O anfitrião vê este nome na lista de aparelhos e grupos.",
            style = MaterialTheme.typography.bodySmall,
            color = Pal.Graphite,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(24)
                    saved = false
                },
                placeholder = { Text("Ex.: Violino da Ana") },
                singleLine = true,
                colors = fieldColors(),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    SessionController.setNickname(context, text)
                    saved = true
                },
                enabled = text.trim() != SessionController.nickname(context),
                modifier = Modifier.height(56.dp),
            ) { Text("Salvar") }
        }
        if (saved) Text("Salvo. O anfitrião verá o novo nome em instantes.", style = MaterialTheme.typography.bodySmall)
    }
}
