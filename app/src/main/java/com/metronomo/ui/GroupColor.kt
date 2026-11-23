package com.metronomo.ui

import androidx.compose.ui.graphics.Color
import com.metronomo.net.GroupInfo

/** As 10 cores oferecidas ao anfitrião (a mesma tabela da página). */
val GROUP_PALETTE = listOf("#475569", "#1d4ed8", "#b45309", "#0f766e", "#7c3aed", "#be123c", "#15803d", "#be185d", "#0369a1", "#a16207")

private val GROUP_COLORS = listOf(
    0xFF475569, 0xFF1D4ED8, 0xFFB45309, 0xFF0F766E, 0xFF7C3AED,
    0xFFBE123C, 0xFF15803D, 0xFFBE185D, 0xFF0369A1, 0xFFA16207,
)

/** Cor de cada grupo: a mesma em todos os aparelhos (vem do número do id: g1, g2, g3…). Geral = cinza. Igual a web/groupcolor.js. */
fun groupColor(id: String): Color {
    val n = id.filter { it.isDigit() }.toIntOrNull() ?: 1
    return Color(GROUP_COLORS[(n - 1).mod(GROUP_COLORS.size)])
}

/** Cor do grupo: a que o anfitrião escolheu (vem em [GroupInfo.color]) ou, sem escolha, a padrão do id. */
fun groupColor(g: GroupInfo): Color {
    val c = g.color
    if (c.length == 7 && c[0] == '#') runCatching { return Color(android.graphics.Color.parseColor(c)) }
    return groupColor(g.id)
}

/** Cor em texto (#rrggbb) para mandar à página da partitura. */
fun groupHex(g: GroupInfo): String = if (g.color.length == 7 && g.color[0] == '#') g.color else com.metronomo.diag.Groups.defaultColor(g.id)
