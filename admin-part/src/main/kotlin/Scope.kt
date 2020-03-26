package com.epam.drill.plugins.test2code

import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.common.*
import com.epam.drill.plugins.test2code.common.api.*
import com.epam.kodux.*
import kotlinx.atomicfu.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.serialization.*

interface Scope : Sequence<FinishedSession> {
    val id: String
    val buildVersion: String
    val summary: ScopeSummary
}

fun Sequence<Scope>.summaries(): List<ScopeSummary> = map(Scope::summary).toList()

class ActiveScope(
    val nth: Int = 1,
    name: String = "$DEFAULT_SCOPE_NAME $nth",
    override val buildVersion: String
) : Scope {

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

    private val changes get() = _changes.value
    private val _changes = atomic<Channel<Unit>?>(null)

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
        sessionChanged()
    }

    fun cancelSession(msg: SessionCancelled) = activeSessions.remove(msg.sessionId)?.also {
        sessionChanged()
    }

    fun cancelAllSessions() = activeSessions.clear().also {
        if (it.any()) {
            sessionChanged()
        }
    }

    fun finishSession(
        sessionId: String,
        onSuccess: ActiveScope.(FinishedSession) -> Unit
    ): FinishedSession? = activeSessions.remove(sessionId)?.run {
        finish().also { finished ->
            if (finished.probes.any()) {
                _sessions.update { it.add(finished) }
                onSuccess(finished)
            }
        }
    }

    fun subscribeOnChanges(
        clb: suspend ActiveScope.(Sequence<Session>) -> Unit
    ) = _changes.update {
        it ?: Channel<Unit>().also {
            GlobalScope.launch {
                it.consumeEach {
                    val actSessionSeq = activeSessions.values.asSequence()
                    clb(this@ActiveScope + actSessionSeq)
                }
            }
        }
    }

    fun close() = changes?.close()

    override fun toString() = "act-scope($id, $name)"

    private fun sessionChanged() = changes?.takeIf { !it.isClosedForSend }?.run {
        offer(Unit)
    }
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

    override fun iterator() = probes.values.asSequence().flatten().iterator()

    override fun toString() = "fin-scope($id, $name)"
}

@Serializable
data class ScopeCounter(
    @Id val id: AgentBuildId,
    val count: Int = 1
)

@Serializable
data class AgentBuildId(
    val agentId: String,
    val buildVersion: String
)

fun ScopeCounter.inc() = copy(count = count.inc())
