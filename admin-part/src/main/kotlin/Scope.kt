/**
 * Copyright 2020 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.drill.plugins.test2code

import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.api.routes.*
import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.plugins.test2code.common.api.JvmSerializable
import com.epam.drill.plugins.test2code.util.*
import com.epam.kodux.*
import com.epam.kodux.util.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.serialization.*

interface Scope : Sequence<FinishedSession> {
    val id: String
    val buildVersion: String
    val name: String
    val summary: ScopeSummary
}

fun Sequence<Scope>.summaries(): List<ScopeSummary> = map(Scope::summary).toList()


typealias ActiveScopeHandler = suspend ActiveScope.(Boolean, Sequence<Session>?) -> Unit

typealias ActiveSessionHandler = suspend ActiveSession.(Map<TypedTest, Sequence<ExecClassData>>) -> Unit

class ActiveScope(
    override val id: String = genUuid(),
    override val buildVersion: String,
    val nth: Int = 1,
    name: String = "$DEFAULT_SCOPE_NAME $nth".weakIntern(),
    sessions: List<FinishedSession> = emptyList(),
) : Scope {

    private enum class Change(val sessions: Boolean, val probes: Boolean) {
        ONLY_SESSIONS(true, false),
        ONLY_PROBES(false, true),
        ALL(true, true)
    }

    override val summary get() = _summary.value

    override val name get() = summary.name

    val activeSessions = AtomicCache<String, ActiveSession>()

    private val _sessions = atomic(sessions.toMutableList())

    //TODO remove summary for this class
    private val _summary = atomic(
        ScopeSummary(
            id = id,
            name = name,
            started = currentTimeMillis(),
            sessionsFinished = sessions.size,
        )
    )

    private val _handler = atomic<ActiveScopeHandler?>(null)


    private val _change = atomic<Change?>(null)

    private val changeJob = AsyncJobDispatcher.launch {
        while (true) {
            delay(250)
            _change.value?.let {
                delay(250)
                _change.getAndUpdate { null }?.let { change ->
                    _handler.value?.let { handler ->
                        val probes: Sequence<Session>? = if (change.probes) {
                            this@ActiveScope + activeSessions.values.filter { it.isRealtime }
                        } else null
                        handler(change.sessions, probes)
                        delay(500)
                    }
                }
            }
        }
    }

    fun initScopeHandler(handler: ActiveScopeHandler): Boolean = _handler.getAndUpdate {
        it ?: handler.also { _change.value = Change.ALL }
    } == null


    //TODO remove summary related stuff from the active scope
    fun updateSummary(updater: (ScopeSummary) -> ScopeSummary) = _summary.updateAndGet(updater)

    fun rename(name: String): ScopeSummary = _summary.updateAndGet { it.copy(name = name) }

    fun finish(enabled: Boolean) = FinishedScope(
        id = id,
        buildVersion = buildVersion,
        name = summary.name,
        enabled = enabled,
        summary = summary.copy(
            finished = currentTimeMillis(),
            active = false,
            enabled = enabled
        ),
        data = toList().let { sessions ->
            ScopeData(
                sessions = sessions,
                typedTests = sessions.flatMapTo(mutableSetOf(), Session::tests),
            )
        }
    )

    override fun iterator(): Iterator<FinishedSession> = _sessions.value.iterator()

    fun startSession(
        sessionId: String,
        testType: String,
        isGlobal: Boolean = false,
        isRealtime: Boolean = false,
        activeSessionHandler: ActiveSessionHandler? = null,
    ) = ActiveSession(sessionId,
        testType,
        isGlobal,
        isRealtime,
        activeSessionHandler
    ).takeIf { newSession ->
        val key = if (isGlobal) "" else sessionId
        activeSessions(key) { existing ->
            existing ?: newSession.takeIf { activeSessionOrNull(it.id) == null }
        } === newSession
    }?.also {
        sessionsChanged()
    }

    fun activeSessionOrNull(id: String): ActiveSession? = activeSessions.run {
        this[""]?.takeIf { it.id == id } ?: this[id]
    }

    fun hasActiveGlobalSession(): Boolean = "" in activeSessions

    fun addProbes(
        sessionId: String,
        probeProvider: () -> Collection<ExecClassData>,
    ): ActiveSession? = activeSessionOrNull(sessionId)?.apply { addAll(probeProvider()) }

    fun probesChanged() = _change.update {
        when (it) {
            Change.ONLY_SESSIONS, Change.ALL -> Change.ALL
            else -> Change.ONLY_PROBES
        }
    }

    fun cancelSession(
        sessionId: String,
    ): ActiveSession? = removeSession(sessionId)?.also {
        if (it.any()) {
            _change.value = Change.ALL
        } else sessionsChanged()
    }

    fun cancelAllSessions() {
        activeSessions.clear().also { map ->
            if (map.values.any { it.any() }) {
                _change.value = Change.ALL
            } else sessionsChanged()
        }
    }

    suspend fun finishSession(
        sessionId: String,
    ): FinishedSession? = removeSession(sessionId)?.run {
        finish().also { finished ->
            if (finished.probes.any()) {
                val updatedSessions = _sessions.updateAndGet { it.apply { add(finished) } }
                _summary.update { it.copy(sessionsFinished = updatedSessions.count()) }
                _change.value = Change.ALL
            } else sessionsChanged()
        }
    }


    fun close() {
        _change.value = null
        activeSessions.clear()
        changeJob.cancel()
    }

    override fun toString() = "act-scope($id, $name)"

    private fun sessionsChanged() {
        _change.update {
            when (it) {
                Change.ONLY_PROBES, Change.ALL -> Change.ALL
                else -> Change.ONLY_SESSIONS
            }
        }
    }

    private fun removeSession(id: String): ActiveSession? = activeSessions.run {
        if (this[""]?.id == id) {
            remove("")
        } else remove(id)
    }
}

@Serializable
data class ScopeData(
    @Transient
    @kotlin.jvm.Transient
    val sessions: List<FinishedSession> = emptyList(),
    val typedTests: Set<TypedTest> = emptySet(),
) : JvmSerializable {
    companion object {
        val empty = ScopeData()
    }
}

@Serializable
data class FinishedScope(
    @Id override val id: String,
    override val buildVersion: String,
    override val name: String,
    override val summary: ScopeSummary,
    val enabled: Boolean,
    val data: ScopeData,
) : Scope, JvmSerializable {
    override fun iterator() = data.sessions.iterator()

    override fun toString() = "fin-scope($id, $name)"
}

@Serializable
internal data class ActiveScopeInfo(
    @Id val buildVersion: String,
    val id: String = genUuid(),
    val nth: Int = 1,
    val name: String = "",
    val startedAt: Long = 0L,
) : JvmSerializable

internal fun ActiveScopeInfo.inc() = copy(nth = nth.inc())

fun scopeById(scopeId: String) = Routes.Build.Scopes(Routes.Build()).let {
    Routes.Build.Scopes.Scope(scopeId, it)
}
