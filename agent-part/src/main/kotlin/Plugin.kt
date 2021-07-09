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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.*
import org.jacoco.core.internal.data.*
import org.mapdb.*
import java.io.*
import java.rmi.*
import java.rmi.Remote
import java.rmi.RemoteException
import java.rmi.registry.*
import java.rmi.server.*
import java.util.*
import kotlin.random.Random


@Suppress("unused")
class Plugin(
    id: String,
    agentContext: AgentContext,
    sender: Sender,
    logging: LoggerFactory,
) : AgentPart<AgentAction>(id, agentContext, sender, logging), Instrumenter{
    internal val logger = logging.logger("Plugin $id")

    internal val json = Json { encodeDefaults = true }

    private val _enabled = atomic(false)

    private val enabled: Boolean get() = _enabled.value

    private val instrContext: SessionProbeArrayProvider = DrillProbeArrayProvider.apply {
        defaultContext = agentContext
    }

    private val instrumenter: DrillInstrumenter = instrumenter(instrContext, logger)

    private val _retransformed = atomic(false)
    private var db: DB
    var probesDb: HTreeMap.KeySet<Any>

    init {
        Thread.currentThread().contextClassLoader = ClassLoader.getSystemClassLoader()
        db = DBMaker.fileDB("test2code.db").closeOnJvmShutdown().make()
        probesDb = db.hashSet("probes")
            .serializer(Serializer.JAVA)
            .createOrOpen()
        ProbeWorker.launch {
            while (true) {
                //todo if the size of probesDb >= N then delay longer or need to chunk send??
                probesDb.map {
                    val coverDataPartJava = it as CoverageInfo
                    probesDb.remove(coverDataPartJava)
                    coverDataPartJava
                }.forEach { storageElement: CoverageInfo ->
                    val coverMessage = storageElement.coverMessage
                    method.sendProbes(Base64.getEncoder().encodeToString(coverMessage))
                    val count = storageElement.count
                    if (storageElement.sendChanged && count > 0) {
                        //todo it not need so often
                        sendMessage(SessionChanged(storageElement.sessionId, count))
                    }
                }//todo SessionChanged for what sent count?
                //.takeIf { sendChanged && it > 0 }?.let {
//                    sendMessage(SessionChanged(sessionId, it))
//                }
                logger.trace { "probes from database were sent" }
                delay(3000L)//todo need to custom
            }
        }
    }

    val method: RMI

    init {
        val registry = LocateRegistry.getRegistry(2732)
        method = registry.lookup("UNIQUE_BINDING_NAME") as RMI
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
                val handler = probeCollector(sessionId)
                instrContext.start(sessionId, isGlobal, testName, handler)
                sendMessage(SessionStarted(sessionId, testType, isRealtime, currentTimeMillis()))
            }
            is AddAgentSessionData -> {
                //ignored
            }
            is StopAgentSession -> {
                val sessionId = action.payload.sessionId
                logger.info { "End of recording for session $sessionId" }
                val runtimeData = instrContext.stop(sessionId) ?: emptySequence()
                if (runtimeData.any()) {
                    probeCollector(sessionId)(runtimeData)
                } else logger.info { "No data for session $sessionId" }
                sendMessage(SessionFinished(sessionId, currentTimeMillis()))
            }
            is StopAllAgentSessions -> {
                val stopped = instrContext.stopAll()
                logger.info { "End of recording for sessions $stopped" }
                for ((sessionId, data) in stopped) {
                    if (data.any()) {
                        probeCollector(sessionId)(data)
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


    fun processServerRequest() {
        (instrContext as DrillProbeArrayProvider).run {
            val sessionId = context()
            val testName = context[DRIlL_TEST_NAME] ?: "unspecified"
            runtimes[sessionId]?.run {
                val execDatum = getOrPut(testName) {
                    arrayOfNulls<ExecDatum>(MAX_CLASS_COUNT).apply { fillFromMeta(testName) }
                }
                requestThreadLocal.set(execDatum)
            }
        }
    }

    fun processServerResponse() {
        (instrContext as DrillProbeArrayProvider).run {
            requestThreadLocal.remove()
        }
    }

    init {
        println("[tmp] ${File(System.getProperty("java.io.tmpdir"))}")
        ProbeWorker.launch {
//            for (her in queue) {
//                probeSender(her)
//                delay(5000)
//            }
        }
    }

    override fun parseAction(
        rawAction: String,
    ): AgentAction = json.decodeFromString(AgentAction.serializer(), rawAction)
}

val queue = Channel<String>()

fun Plugin.probeSender(value: String) {
    runCatching {
        File(value).run {
            send(readText())
            delete()
        }
    }.onFailure {
        logger.error { "Can't read data from file $value" }
    }
}

fun Plugin.probeCollector(sessionId: String, sendChanged: Boolean = false): RealtimeHandler = { execData ->
    execData
        .map(ExecDatum::toExecClassData)
        .chunked(0xffff)
        .map { chunk -> CoverDataPart(sessionId, chunk) }
        .forEach { message ->
            val encoded = ProtoBuf.encodeToByteArray(CoverMessage.serializer(), message)
            val compressed = Zstd.compress(encoded)
            probesDb.add(compressed.toCoverageInfo(sessionId, sendChanged, message.data.count()))
        }
}

fun Plugin.sendMessage(message: CoverMessage) {
    val messageStr = json.encodeToString(CoverMessage.serializer(), message)
    send(messageStr)
}

private fun ByteArray.toCoverageInfo(sessionId: String, sendChanged: Boolean, count: Int) =
    CoverageInfo(sessionId, this, sendChanged, count)

data class CoverageInfo(
    val sessionId: String,
    val coverMessage: ByteArray,
    val sendChanged: Boolean,
    val count: Int,
) : JvmSerializable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CoverageInfo

        if (sessionId != other.sessionId) return false
        if (!coverMessage.contentEquals(other.coverMessage)) return false
        if (sendChanged != other.sendChanged) return false
        if (count != other.count) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + coverMessage.contentHashCode()
        result = 31 * result + sendChanged.hashCode()
        result = 31 * result + count
        return result
    }
}


interface RMI : Remote {
    @Throws(RemoteException::class)
    fun sendProbes(
        data: String,
    )
}
