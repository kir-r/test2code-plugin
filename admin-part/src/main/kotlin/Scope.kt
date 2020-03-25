package com.epam.drill.plugins.test2code

import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.common.*
import com.epam.drill.plugins.test2code.common.api.*
import com.epam.kodux.*
import kotlinx.atomicfu.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.channels.*
import kotlinx.serialization.*

interface Scope : Sequence<FinishedSession> {
    val id: String
    val buildVersion: String
    val summary: ScopeSummary
}

fun Sequence<Scope>.summaries(): List<ScopeSummary> = map(Scope::summary).toList()

class ActiveScope(name: String, override val buildVersion: String) : Scope {

    override val id = genUuid()

    override val summary get() = _summary.value

    val name get() = summary.name

    val activeSessions = AtomicCache<String, ActiveSession>()

    private val _sessions = atomic(persistentListOf<FinishedSession>())

    //TODO remove summary for this class
    private val _summary = atomic(
        ScopeSummary(
            id = id,
            name = name,
            started = currentTimeMillis()
        )
    )

    private val changes = Channel<Unit>()

    //TODO remove summary related stuff from the active scope
    fun updateSummary(updater: (ScopeSummary) -> ScopeSummary) = _summary.updateAndGet(updater)

    fun rename(name: String): ScopeSummary = _summary.getAndUpdate { it.copy(name = name) }

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
        probes = groupBy(Session::testType)
    )

    override fun iterator(): Iterator<FinishedSession> = _sessions.value.iterator()

    fun startSession(sessionId: String, testType: String) {
        activeSessions(sessionId) { ActiveSession(sessionId, testType) }
    }

    fun addProbes(sessionId: String, probes: Collection<ExecClassData>) {
        activeSessions[sessionId]?.apply { addAll(probes) }
        if (!changes.isClosedForSend) {
            changes.offer(Unit)
        }
    }

    fun cancelSession(msg: SessionCancelled) = activeSessions.remove(msg.sessionId)

    fun finishSession(
        sessionId: String,
        onSuccess: ActiveScope.(FinishedSession) -> Unit
    ): FinishedSession? = activeSessions.remove(sessionId)
        ?.let(ActiveSession::finish)
        ?.also { session ->
            _sessions.update { it.add(session) }
            onSuccess(session)
        }

    suspend fun subscribeOnChanges(clb: suspend ActiveScope.(Sequence<FinishedSession>) -> Unit) {
        changes.consumeEach {
            val actSessionSeq = activeSessions.values.asSequence()
            clb(this + actSessionSeq.map(ActiveSession::finish))
        }
    }

    fun close() = changes.close()

    override fun toString() = "act-scope($id, $name)"
}

@Serializable
data class FinishedScope(
    @Id override val id: String,
    override val buildVersion: String,
    val name: String,
    override val summary: ScopeSummary,
    val probes: Map<String, List<FinishedSession>>,
    var enabled: Boolean = true
) : Scope {

    override fun iterator() = probes.values.flatten().iterator()

    override fun toString() = "fin-scope($id, $name)"
}
