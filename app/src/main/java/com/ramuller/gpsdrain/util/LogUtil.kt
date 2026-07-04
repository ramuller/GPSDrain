// Util.kt
package com.ramuller.gpsdrain.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

const val LOG_ACTION = "com.ramuller.gpsdrain.LOG"
const val LOG_EXTRA = "message"

fun sendLog(context: Context, message: String) {
    Log.d("GPSDrain", message)

    val intent = Intent(LOG_ACTION).apply {
        putExtra(LOG_EXTRA, message)
    }
    LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
}