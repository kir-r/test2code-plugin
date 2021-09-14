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

import com.epam.drill.logger.api.*
import com.epam.drill.plugin.api.*
import com.epam.drill.plugin.api.processing.*
import com.epam.drill.plugins.test2code.common.api.*
import com.github.luben.zstd.*
import kotlinx.atomicfu.*
import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.*
import org.jacoco.core.internal.data.*
import java.util.*

@Suppress("unused")
class Plugin(
    id: String,
    agentContext: AgentContext,
    sender: Sender,
    logging: LoggerFactory,
) : AgentPart<AgentAction>(id, agentContext, sender, logging), Instrumenter {
    internal val logger = logging.logger("Plugin $id")

    internal val json = Json { encodeDefaults = true }

    private val _enabled = atomic(false)

    private val enabled: Boolean get() = _enabled.value

    private val instrContext: SessionProbeArrayProvider = DrillProbeArrayProvider.apply {
        defaultContext = agentContext
        logger = this@Plugin.logger
    }

    private val instrumenter: DrillInstrumenter = instrumenter(instrContext, logger)

    private val _retransformed = atomic(false)

    override fun onConnect() {
        val ids = instrContext.getActiveSessions()
        logger.info { "Send active sessions after reconnect: ${ids.count()}" }
        ids.takeIf { it.any() }?.let {
            sendMessage(SessionsState(ids))
        }
    }

    //TODO remove
    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    //TODO remove
    override fun isEnabled(): Boolean = _enabled.value

    override fun on() {
        val initInfo = InitInfo(message = "Initializing plugin $id...")
        sendMessage(initInfo)
        if (_retransformed.compareAndSet(expect = false, update = true)) {
            retransform()
        }
        sendMessage(Initialized(msg = "Initialized"))
        logger.info { "Plugin $id initialized!" }
    }

    override fun off() {
        logger.info { "Enabled $enabled" }
        val cancelledCount = instrContext.cancelAll()
        logger.info { "Plugin $id is off" }
        if (_retransformed.compareAndSet(expect = true, update = false)) {
            retransform()
        }
        sendMessage(SessionsCancelled(cancelledCount, currentTimeMillis()))
    }

    /**
     * Retransforming does not require an agent part instance.
     * This method is used in integration tests.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun retransform() {
        try {
            Native.RetransformClassesByPackagePrefixes(byteArrayOf())
        } catch (ex: Throwable) {
            logger.error(ex) { "Error retransforming classes." }
        }
    }

    override fun instrument(
        className: String,
        initialBytes: ByteArray,
    ): ByteArray? = takeIf { enabled }?.run {
        val idFromClassName = CRC64.classId(className.encodeToByteArray())
        instrumenter(className, idFromClassName, initialBytes)
    }

    override fun destroyPlugin(unloadReason: UnloadReason) {}

    override fun initPlugin() {
        logger.info { "Plugin $id: initializing..." }
        retransform()
        _retransformed.value = true
    }

    override suspend fun doAction(action: AgentAction) {
        when (action) {
            is InitActiveScope -> action.payload.apply {
                logger.info { "Initializing scope $id, $name, prevId=$prevId" }
                instrContext.cancelAll()
                sendMessage(
                    ScopeInitialized(
                        id = id,
                        name = name,
                        prevId = prevId,
                        ts = currentTimeMillis()
                    )
                )
            }
            is StartAgentSession -> action.payload.run {
                logger.info { "Start recording for session $sessionId (isGlobal=$isGlobal)" }
                val handler = probeSender(sessionId, isRealtime)
                instrContext.start(sessionId, isGlobal, testName, handler)
                sendMessage(SessionStarted(sessionId, testType, isRealtime, currentTimeMillis()))
            }
            is AddAgentSessionData -> {
                //ignored
            }
            is AddAgentSessionTests -> action.payload.run {
                instrContext.addCompletedTests(sessionId, tests)
            }
            is StopAgentSession -> {
                val sessionId = action.payload.sessionId
                logger.info { "End of recording for session $sessionId" }
                val runtimeData = instrContext.stop(sessionId) ?: emptySequence()
                if (runtimeData.any()) {
                    probeSender(sessionId)(runtimeData)
                } else logger.info { "No data for session $sessionId" }
                sendMessage(SessionFinished(sessionId, currentTimeMillis()))
            }
            is StopAllAgentSessions -> {
                val stopped = instrContext.stopAll()
                logger.info { "End of recording for sessions $stopped" }
                for ((sessionId, data) in stopped) {
                    if (data.any()) {
                        probeSender(sessionId)(data)
                    }
                }
                val ids = stopped.map { it.first }
                sendMessage(SessionsFinished(ids, currentTimeMillis()))
            }
            is CancelAgentSession -> {
                val sessionId = action.payload.sessionId
                logger.info { "Cancellation of recording for session $sessionId" }
                instrContext.cancel(sessionId)
                sendMessage(SessionCancelled(sessionId, currentTimeMillis()))
            }
            is CancelAllAgentSessions -> {
                val cancelled = instrContext.cancelAll()
                logger.info { "Cancellation of recording for sessions $cancelled" }
                sendMessage(SessionsCancelled(cancelled, currentTimeMillis()))
            }
            else -> Unit
        }
    }

    /**
     * For each request we fill the thread local variable with an array of [ExecDatum]
     */
    fun processServerRequest() {
        (instrContext as DrillProbeArrayProvider).run {
            val sessionId = context()
            val testName = context[DRIlL_TEST_NAME] ?: "unspecified"
            runtimes[sessionId]?.run {
                val execDatum = getOrPut(testName) {
                    arrayOfNulls<ExecDatum>(MAX_CLASS_COUNT).apply { fillFromMeta(testName) }
                }
                logger?.trace { "processServerRequest. thread '${Thread.currentThread().id}' sessionId '$sessionId' testName '$testName'" }
                requestThreadLocal.set(execDatum)
            }
        }
    }

    fun processServerResponse() {
        (instrContext as DrillProbeArrayProvider).run {
            requestThreadLocal.remove()
        }
    }

    override fun parseAction(
        rawAction: String,
    ): AgentAction = json.decodeFromString(AgentAction.serializer(), rawAction)
}

fun Plugin.probeSender(
    sessionId: String,
    sendChanged: Boolean = false,
): RealtimeHandler = { execData ->
    execData
        .map(ExecDatum::toExecClassData)
        .chunked(0xffff)
        .map { chunk -> CoverDataPart(sessionId, chunk) }
        .sumOf { message ->
            logger.trace { "send to admin-part '$message'..." }
            val encoded = ProtoBuf.encodeToByteArray(CoverMessage.serializer(), message)
            val compressed = Zstd.compress(encoded)
            send(Base64.getEncoder().encodeToString(compressed))
            message.data.count()
        }.takeIf { sendChanged && it > 0 }?.let {
            sendMessage(SessionChanged(sessionId, it))
        }
}

fun Plugin.sendMessage(message: CoverMessage) {
    val messageStr = json.encodeToString(CoverMessage.serializer(), message)
    send(messageStr)
}
