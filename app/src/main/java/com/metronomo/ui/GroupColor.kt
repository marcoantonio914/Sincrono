package com.metronomo.ui

import androidx.compose.ui.graphics.Color

private val GROUP_COLORS = listOf(
    0xFF475569, 0xFF1D4ED8, 0xFFB45309, 0xFF0F766E, 0xFF7C3AED,
    0xFFBE123C, 0xFF15803D, 0xFFBE185D, 0xFF0369A1, 0xFFA16207,
)

/** Cor de cada grupo: a mesma em todos os aparelhos (vem do número do id: g1, g2, g3…). Geral = cinza. Igual a web/groupcolor.js. */
fun groupColor(id: String): Color {
    val n = id.filter { it.isDigit() }.toIntOrNull() ?: 1
    return Color(GROUP_COLORS[(n - 1).mod(GROUP_COLORS.size)])
}
