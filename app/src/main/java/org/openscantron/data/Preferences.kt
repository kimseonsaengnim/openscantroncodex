package org.openscantron.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferences by preferencesDataStore("classroom")

@Singleton
class Preferences @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.preferences
    private val themeKey = stringPreferencesKey("theme")
    private val resolutionKey = intPreferencesKey("archive_resolution")
    val theme = store.data.map { it[themeKey] ?: "System" }
    val resolution = store.data.map { it[resolutionKey] ?: 3000 }
    suspend fun theme(value: String) { store.edit { it[themeKey] = value } }
    suspend fun resolution(value: Int) { store.edit { it[resolutionKey] = value } }
}
