package com.example.tv_caller_app.ui.util

import android.content.Context
import com.example.tv_caller_app.R
import java.util.Locale

/** Translates the canonical task labels stored by the web application. */
object TaskTranslation {
    private val labels = mapOf(
        "medisin" to R.string.task_medicine,
        "medication" to R.string.task_medicine,
        "bading" to R.string.task_bathing,
        "bathing" to R.string.task_bathing,
        "matlaging" to R.string.task_cooking,
        "cooking" to R.string.task_cooking,
        "handel" to R.string.task_shopping,
        "shopping" to R.string.task_shopping,
        "husholdning" to R.string.task_housekeeping,
        "housekeeping" to R.string.task_housekeeping,
        "digital hjelp" to R.string.task_digital_help,
        "digitalhjelp" to R.string.task_digital_help,
        "digital help" to R.string.task_digital_help,
        "transport" to R.string.task_transport,
        "medisinhåndtering" to R.string.task_medication_management,
        "medication management" to R.string.task_medication_management,
        "blodtrykksmåling" to R.string.task_blood_pressure,
        "blood pressure measurement" to R.string.task_blood_pressure,
        "tilsyn" to R.string.task_check_in,
        "check-in" to R.string.task_check_in
    )

    fun translate(context: Context, raw: String): String =
        labels[raw.trim().lowercase(Locale.ROOT)]?.let(context::getString) ?: raw.trim()

    fun translateList(context: Context, raw: String): String = raw
        .split(',')
        .map { translate(context, it) }
        .filter(String::isNotBlank)
        .joinToString(", ")
}
