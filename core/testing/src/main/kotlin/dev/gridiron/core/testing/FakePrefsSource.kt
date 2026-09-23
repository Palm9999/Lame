package dev.gridiron.core.testing

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.datastore.UserPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Preferences held in memory, for tests on virtual time. Updates are serialized, like DataStore's. */
public class FakePrefsSource(initial: UserPrefs = UserPrefs.DEFAULT) : PrefsSource {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    public val current: UserPrefs get() = state.value

    override val prefs: Flow<UserPrefs> get() = state

    override suspend fun update(transform: (UserPrefs) -> UserPrefs): UserPrefs =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
