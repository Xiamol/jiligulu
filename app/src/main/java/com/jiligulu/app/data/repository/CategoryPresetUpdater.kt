package com.jiligulu.app.data.repository

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Adds this release's new categories once, without recreating a later user deletion. */
class CategoryPresetUpdater(private val admin: CategoryAdminRepository, private val preferences: SharedPreferences) {
    private val mutex = Mutex()
    suspend fun ensure() = mutex.withLock {
        if (preferences.getInt("supplemental_version", 0) >= 1) return@withLock
        admin.addSupplementalPresets()
        withContext(Dispatchers.IO) {
            check(preferences.edit().putInt("supplemental_version", 1).commit())
        }
    }
}
