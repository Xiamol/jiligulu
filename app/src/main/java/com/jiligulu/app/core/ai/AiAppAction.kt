package com.jiligulu.app.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Deliberately narrow contract; new JSON fields are optional for old stored cards/responses. */
@Serializable
data class AiAppAction(
    val kind: String = "",
    val enabled: Boolean? = null,
    @SerialName("interval_minutes") val intervalMinutes: Int? = null,
    @SerialName("quiet_enabled") val quietEnabled: Boolean? = null,
    @SerialName("quiet_start_minutes") val quietStartMinutes: Int? = null,
    @SerialName("quiet_end_minutes") val quietEndMinutes: Int? = null
) {
    val hasSettings: Boolean get() = enabled != null || intervalMinutes != null || quietEnabled != null ||
        quietStartMinutes != null || quietEndMinutes != null

    val isValid: Boolean get() = when (kind) {
        WATER_SETTINGS -> hasSettings &&
            (intervalMinutes == null || intervalMinutes in 1..779) &&
            (quietStartMinutes == null || quietStartMinutes in 0..1439) &&
            (quietEndMinutes == null || quietEndMinutes in 0..1439) &&
            (quietEnabled != false || (quietStartMinutes == null && quietEndMinutes == null))
        EMPTY_TRASH -> !hasSettings
        else -> false
    }

    companion object {
        const val WATER_SETTINGS = "water_settings"
        const val EMPTY_TRASH = "empty_trash"
    }
}
