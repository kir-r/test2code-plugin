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

import com.epam.drill.plugin.api.processing.*
import com.epam.drill.plugins.test2code.common.api.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import java.util.concurrent.*
import kotlin.coroutines.*

/**
 * Provides boolean array for the probe.
 * Implementations must be kotlin singleton objects.
 */
typealias ProbeArrayProvider = (Long, Int, String, Int) -> AgentProbes

typealias RealtimeHandler = (Sequence<ExecDatum>) -> Unit

interface SessionProbeArrayProvider : ProbeArrayProvider {

    fun start(
        sessionId: String,
        isGlobal: Boolean,
        testName: String? = null,
        realtimeHandler: RealtimeHandler = {}
    )

    fun stop(sessionId: String): Sequence<ExecDatum>?
    fun stopAll(): List<Pair<String, Sequence<ExecDatum>>>
    fun cancel(sessionId: String)
    fun cancelAll(): List<String>
}

const val DRIlL_TEST_NAME = "drill-test-name"

class ExecDatum(
    val id: Long,
    val name: String,
    val probes: AgentProbes,
    val testName: String = ""
)

class ProbeDescriptor(
    val id: Long,
    val name: String,
    val probeCount: Int
)

fun ExecDatum.toExecClassData() = ExecClassData(
    id = id,
    className = name,
    probes = probes.bool.toBitSet(),
    testName = testName
)

typealias ExecData = Array<ExecDatum?>

internal object ProbeWorker : CoroutineScope {
    override val coroutineContext: CoroutineContext = run {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher() + SupervisorJob()
    }
}

/**
 * A container for session runtime data and optionally runtime data of tests
 * TODO ad hoc implementation, rewrite to something more descent
 */
class ExecRuntime(
    realtimeHandler: RealtimeHandler
) {
    val _execData = ConcurrentHashMap<String, ExecData>()

    private val job = ProbeWorker.launch {
        while (true) {
            delay(6000L)
            realtimeHandler(collect())
        }
    }

    fun collect(): Sequence<ExecDatum> = _execData.keys.mapNotNull {
        _execData.remove(it)
    }.asSequence().run {
        flatMap {
            it
                .filterNotNull()
                .asSequence()
        }
    }

    fun close() {
        job.cancel()
    }
}

val glb = arrayOfNulls<ExecDatum?>(50_000)

val stubProbes = StubAgentProbes()

val runtimes = mutableMapOf<String, ExecRuntime>()

object ProbeManager {
    val probesDescriptor = arrayOfNulls<ProbeDescriptor?>(50_000)

    fun addDescriptor(inx: Int, probeDescriptor: ProbeDescriptor) {
        probesDescriptor[inx] = probeDescriptor

        glb[inx] = ExecDatum(
            id = probeDescriptor.id,
            name = probeDescriptor.name,
            probes = AgentProbes(probeDescriptor.probeCount)
            //todo testname
        )


        runtimes.values.forEach {
            it._execData.forEach { (testName, v) ->
                v[inx] = ExecDatum(
                    id = probeDescriptor.id,
                    name = probeDescriptor.name,
                    probes = AgentProbes(probeDescriptor.probeCount),
                    testName = testName
                )
            }
        }
    }
}


class GlobalExecRuntime(
    var testName: String?,
    realtimeHandler: RealtimeHandler
) {


    private val job = ProbeWorker.launch {
        while (true) {
            delay(6000L)
            realtimeHandler(collect())
        }
    }

    fun collect(): Sequence<ExecDatum> = glb.asSequence().filterNotNull()

    fun close(): Sequence<ExecDatum> {
        val filterNotNull = glb.asSequence().filterNotNull()
        job.cancel()
        return filterNotNull
    }
}


/**
 * Simple probe array provider that employs a lock-free map for runtime data storage.
 * This class is intended to be an ancestor for a concrete probe array provider object.
 * The provider must be a Kotlin singleton object, otherwise the instrumented probe calls will fail.
 */
open class SimpleSessionProbeArrayProvider(
    defaultContext: AgentContext? = null
) : SessionProbeArrayProvider {


    val requestThreadLocal = ThreadLocal<Array<ExecDatum?>>()

    var defaultContext: AgentContext?
        get() = _defaultContext.value
        set(value) {
            _defaultContext.value = value
        }

    private val _defaultContext = atomic(defaultContext)

    private var _context: AgentContext? = null

    private var _globalContext: AgentContext? = null

    private var global: GlobalExecRuntime? = null

    override fun invoke(
        id: Long,
        num: Int,
        name: String,
        probeCount: Int
    ): AgentProbes = global?.let { checkGlobalProbes(num) }
        ?: checkLocalProbes(num)
        ?: stubProbes

    private fun checkLocalProbes(num: Int) = requestThreadLocal.get()?.get(num)?.probes

    private fun checkGlobalProbes(num: Int) = glb[num]?.probes

    override fun start(
        sessionId: String,
        isGlobal: Boolean,
        testName: String?,
        realtimeHandler: RealtimeHandler
    ) {
        if (isGlobal) {
            _globalContext = GlobalContext(sessionId, testName)
            addGlobal(testName, realtimeHandler)
        } else {
            _context = _context ?: defaultContext
            add(sessionId, realtimeHandler)
        }
    }

    override fun stop(sessionId: String): Sequence<ExecDatum>? {
        return if (sessionId == "global") {
            removeGlobal(sessionId)
        } else {
            remove(sessionId)?.collect()
        }
    }

    override fun stopAll(): List<Pair<String, Sequence<ExecDatum>>> = run {
        val copyOf = runtimes.map { it }
        _context = null
        _globalContext = null
        runtimes.clear()
        copyOf.map { (id, runtime) ->
            runtime.close()
            id to runtime.collect()
        }
    }

    override fun cancel(sessionId: String) {
        remove(sessionId)
    }

    override fun cancelAll(): List<String> = run {
        val copyOf = runtimes.map { it }
        _context = null
        _globalContext = null
        runtimes.clear()
        copyOf.map { (id, runtime) ->
            runtime.close()
            id
        }
    }

    private fun add(sessionId: String, realtimeHandler: RealtimeHandler) {
        if (sessionId !in runtimes) {
            val value = ExecRuntime(realtimeHandler)
            runtimes.put(sessionId, value)
        } else runtimes
    }

    private fun addGlobal(testName: String?, realtimeHandler: RealtimeHandler) {
        global = GlobalExecRuntime(testName, realtimeHandler)
        ProbeManager.probesDescriptor.forEachIndexed { inx, probeDescriptor ->
            if (probeDescriptor != null) {
                glb[inx] = ExecDatum(
                    id = probeDescriptor.id,
                    name = probeDescriptor.name,
                    probes = AgentProbes(probeDescriptor.probeCount)
                )

            }
        }
    }

    private fun removeGlobal(sessionId: String): Sequence<ExecDatum>? {
        _globalContext = null
        val close = global?.close()
        glb.indices.forEach {
            glb[it] = null
        }
        return close
    }

    private fun remove(sessionId: String): ExecRuntime? = run {
        val copyOf: Map<String, ExecRuntime> = runtimes.map { it }.associate { it.key to it.value }


        (runtimes - sessionId).also { map ->
            if (map.none()) {
                _context = null
                _globalContext = null
            } else {
                val globalSessionId = _globalContext?.invoke()
                if (map.size == 1 && globalSessionId in map) {
                    _context = null
                }
                if (globalSessionId == sessionId) {
                    _globalContext = null
                }
            }
        }

        val also = copyOf[sessionId]?.also(ExecRuntime::close)
        also

    }
}

private class GlobalContext(
    private val sessionId: String,
    private val testName: String?
) : AgentContext {
    override fun get(key: String): String? = testName?.takeIf { key == DRIlL_TEST_NAME }

    override fun invoke(): String? = sessionId
}
