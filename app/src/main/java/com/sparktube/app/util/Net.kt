package com.sparktube.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** v1.9.8: tiny connectivity probe for offline-first behavior. */
object Net {
    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
