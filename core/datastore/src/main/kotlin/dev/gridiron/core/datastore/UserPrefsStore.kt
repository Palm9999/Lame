package dev.gridiron.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException

/** Where [UserPrefs] live. Repositories depend on this, so tests can hold prefs in memory. */
public interface PrefsSource {
    public val prefs: Flow<UserPrefs>

    /** Atomically replaces the document with [transform]'s result. */
    public suspend fun update(transform: (UserPrefs) -> UserPrefs): UserPrefs
}

/**
 * The user's scoring profiles, active profile and compare tray, persisted as
 * one JSON file. Create exactly one per file per process (DataStore's rule).
 */
public class UserPrefsStore private constructor(private val dataStore: DataStore<UserPrefs>) : PrefsSource {

    override val prefs: Flow<UserPrefs> get() = dataStore.data

    override suspend fun update(transform: (UserPrefs) -> UserPrefs): UserPrefs = dataStore.updateData { transform(it) }

    public companion object {
        public fun create(file: File, scope: CoroutineScope): UserPrefsStore =
            UserPrefsStore(
                DataStoreFactory.create(
                    serializer = UserPrefsSerializer,
                    corruptionHandler = ReplaceFileCorruptionHandler {
                        // Keep the unreadable file for inspection instead of
                        // silently losing what the user set up.
                        try {
                            file.copyTo(File(file.path + ".corrupt"), overwrite = true)
                        } catch (_: IOException) {
                            // Best effort; the reset itself must still happen.
                        }
                        UserPrefs.DEFAULT.copy(resetNotice = true)
                    },
                    scope = scope,
                    produceFile = { file },
                ),
            )
    }
}
