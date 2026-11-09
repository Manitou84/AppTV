package com.vodtv.client.data.rsi

import java.time.OffsetDateTime

enum class MotorSport(
    val label: String,
    /** Ricerche lanciate sull'archivio RSI per questo sport. */
    val queries: List<String>,
    /** Parole chiave per filtrare dirette e palinsesto (minuscolo). */
    val keywords: List<String>,
) {
    MOTOGP(
        label = "MotoGP",
        queries = listOf("MotoGP", "Motomondiale"),
        keywords = listOf("motogp", "moto gp", "motomondiale", "moto2", "moto3"),
    ),
    FORMULA1(
        label = "Formula 1",
        queries = listOf("Formula 1", "F1 Gran Premio"),
        keywords = listOf("formula 1", "formula uno", "formula1", " f1 ", "f1:", "f1 -", "gp di f1"),
    ),
}

enum class RsiStatus { LIVE, UPCOMING, AVAILABLE, BLOCKED }

data class RsiMedia(
    val id: String,
    val urn: String,
    val title: String,
    val showTitle: String?,
    val description: String?,
    val imageUrl: String?,
    val date: OffsetDateTime?,
    val durationMs: Long?,
    /** EPISODE, CLIP, LIVESTREAM, SCHEDULED_LIVESTREAM… */
    val type: String?,
    /** GEOBLOCK, LEGAL, STARTDATE, ENDDATE, AGERATING… null = nessun blocco dichiarato. */
    val blockReason: String?,
    val validFrom: OffsetDateTime?,
    val validTo: OffsetDateTime?,
) {
    val isGeoBlocked: Boolean get() = blockReason == "GEOBLOCK"

    val isLiveType: Boolean get() = type == "LIVESTREAM" || type == "SCHEDULED_LIVESTREAM"

    fun status(now: OffsetDateTime = OffsetDateTime.now()): RsiStatus = when {
        blockReason == "STARTDATE" -> RsiStatus.UPCOMING
        type == "SCHEDULED_LIVESTREAM" && validFrom?.isAfter(now) == true -> RsiStatus.UPCOMING
        blockReason != null -> RsiStatus.BLOCKED
        isLiveType -> RsiStatus.LIVE
        else -> RsiStatus.AVAILABLE
    }

    fun searchableText(): String = listOfNotNull(title, showTitle, description).joinToString(" ").lowercase()
}

data class RsiFeed(
    /** Dirette in corso e prossime dirette, già filtrate per sport. */
    val live: List<RsiMedia>,
    /** Highlight, repliche, servizi. */
    val onDemand: List<RsiMedia>,
)
