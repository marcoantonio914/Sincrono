package com.metronomo.net

/** Um grupo ("ilha"): tem o seu próprio arquivo de áudio. */
data class GroupInfo(val id: String, val name: String, val file: String?, val playing: Boolean, val color: String = "")

/** Grupos da sessão e a que grupo cada aparelho pertence (sem atribuição = grupo padrão "g1"). */
data class GroupsInfo(
    val rev: Long = 0,
    val groups: List<GroupInfo> = listOf(GroupInfo("g1", "Geral", null, false)),
    val assign: Map<String, String> = emptyMap(),
) {
    fun groupOf(deviceId: String): String = assign[deviceId]?.takeIf { id -> groups.any { it.id == id } } ?: "g1"
}
