package com.jiligulu.app.data.prefs

import android.content.Context
import java.io.File

/** Delete only the retired meter's private storage; provider settings and the ledger are separate. */
internal object RetiredAiCostData {
    fun remove(context: Context) {
        context.deleteDatabase("ai_usage_v2.db")
        val directory = File(context.filesDir, "datastore").canonicalFile
        listOf("ai_usage.preferences_pb", "ai_usage.preferences_pb.tmp", "ai_usage.preferences_pb.bak").forEach { name ->
            val file = File(directory, name)
            if (file.canonicalFile.parentFile == directory) file.delete()
        }
    }
}
