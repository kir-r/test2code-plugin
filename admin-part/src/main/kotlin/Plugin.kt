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


import com.epam.drill.common.*
import com.epam.drill.plugin.api.*
import com.epam.drill.plugin.api.end.*
import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.api.routes.*
import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.drill.plugins.test2code.group.*
import com.epam.drill.plugins.test2code.storage.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.kodux.*
import com.epam.kodux.util.*
import com.github.luben.zstd.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.*
import java.io.*
import java.rmi.*
import java.rmi.registry.*
import java.rmi.server.*
import java.util.*
import java.util.concurrent.*


object AsyncJobDispatcher : CoroutineScope {
    override val coroutineContext =
        Executors.newFixedThreadPool(availableProcessors).asCoroutineDispatcher() + SupervisorJob()
}

@Suppress("unused")
class Plugin(
    adminData: AdminData,
    sender: Sender,
    val storeClient: StoreClient,
    agentInfo: AgentInfo,
    id: String,
) : AdminPluginPart<Action>(
    id = id,
    agentInfo = agentInfo,
    adminData = adminData,
    sender = sender
), Closeable, RMI {
    companion object {
        val json = Json { encodeDefaults = true }
    }

    init {
        val registry: Registry = LocateRegistry.createRegistry(2732)
        val stub = UnicastRemoteObject.exportObject(this, 0)
        registry.bind("UNIQUE_BINDING_NAME", stub)
    }

    internal val logger = logger(agentInfo.id)

    internal val runtimeConfig = RuntimeConfig(id)

    internal val state: AgentState get() = _state.value!!

    val buildVersion = agentInfo.buildVersion

    val activeScope: ActiveScope get() = state.activeScope

    private val agentId = agentInfo.id

    private val _state = atomic<AgentState?>(null)

    override suspend fun initialize() {
        logger.debug { "agent(id=$id, version=$buildVersion) initializing from admin..." }
        changeState()
        state.loadFromDb {
            processInitialized()
        }
    }

    override fun close() {
        _state.getAndUpdate { null }?.close()
    }

    override suspend fun applyPackagesChanges() {
        state.scopeManager.deleteByVersion(buildVersion)
        storeClient.removeClassData(buildVersion)
        changeState()
    }

    override fun parseAction(
        rawAction: String,
    ): Action = json.decodeFromString(Action.serializer(), rawAction)

    override suspend fun doAction(
        action: Action,
    ): ActionResult = when (action) {
        is ToggleBaseline -> toggleBaseline()
        is SwitchActiveScope -> changeActiveScope(action.payload)
        is RenameScope -> renameScope(action.payload)
        is ToggleScope -> toggleScope(action.payload.scopeId)
        is RemoveBuild -> {
            val version = action.payload.version
            if (version != buildVersion && version != state.coverContext().parentBuild?.version) {
                storeClient.removeBuildData(version, state.scopeManager)
                ActionResult(code = StatusCodes.OK, data = "")
            } else ActionResult(code = StatusCodes.BAD_REQUEST, data = "Can not remove a current or baseline build")
        }
        is DropScope -> dropScope(action.payload.scopeId)
        is UpdateSettings -> updateSettings(action.payload)
        is StartNewSession -> action.payload.run {
            val newSessionId = sessionId.ifEmpty(::genUuid)
            val isRealtimeSession = runtimeConfig.realtime && isRealtime
            activeScope.startSession(
                newSessionId,
                testType,
                isGlobal,
                isRealtimeSession,
                initSessionHandler().takeIf { !isRealtimeSession }
            )?.run {
                StartAgentSession(
                    payload = StartSessionPayload(
                        sessionId = id,
                        testType = testType,
                        testName = testName,
                        isGlobal = isGlobal,
                        isRealtime = isRealtime
                    )
                ).toActionResult()
            } ?: if (isGlobal && activeScope.hasActiveGlobalSession()) {
                ActionResult(
                    code = StatusCodes.CONFLICT,
                    data = listOf(
                        "Error! Only one active global session is allowed.",
                        "Please finish the active one in order to start new."
                    ).joinToString(" ")
                )
            } else FieldErrorDto(
                field = "sessionId",
                message = "Session with such ID already exists. Please choose a different ID."
            ).toActionResult(StatusCodes.CONFLICT)
        }
        is AddSessionData -> action.payload.run {
            activeScope.activeSessionOrNull(sessionId)?.let { session ->
                AddAgentSessionData(
                    payload = AgentSessionDataPayload(sessionId = session.id, data = data)
                ).toActionResult()
            } ?: ActionResult(StatusCodes.NOT_FOUND, "Active session '$sessionId' not found.")
        }
        is AddCoverage -> action.payload.run {
            activeScope.addProbes(sessionId) {
                data.map { probes ->
                    ExecClassData(className = probes.name, testName = probes.test, probes = probes.probes.toBitSet())
                }
            }?.run {
                if (isRealtime) {
                    activeScope.probesChanged()
                }
                ActionResult(StatusCodes.OK, "")
            } ?: ActionResult(StatusCodes.NOT_FOUND, "Active session '$sessionId' not found.")
        }
        is ExportCoverage -> exportCoverage(action.payload.version)
        is CancelSession -> action.payload.run {
            activeScope.cancelSession(action.payload.sessionId)?.let { session ->
                CancelAgentSession(payload = AgentSessionPayload(session.id)).toActionResult()
            } ?: ActionResult(StatusCodes.NOT_FOUND, "Active session '$sessionId' not found.")
        }
        is CancelAllSessions -> {
            activeScope.cancelAllSessions()
            CancelAllAgentSessions.toActionResult()
        }
        is AddTests -> action.payload.run {
            activeScope.activeSessionOrNull(sessionId)?.let { session ->
                testRun?.let { session.setTestRun(it) }
                ActionResult(StatusCodes.OK, "")
            } ?: ActionResult(StatusCodes.NOT_FOUND, "Active session '$sessionId' not found.")
        }
        is StopSession -> action.payload.run {
            activeScope.activeSessionOrNull(sessionId)?.let { session ->
                testRun?.let { session.setTestRun(it) }
                StopAgentSession(
                    payload = AgentSessionPayload(session.id)
                ).toActionResult()
            } ?: ActionResult(StatusCodes.NOT_FOUND, "Active session '$sessionId' not found.")
        }
        is StopAllSessions -> StopAllAgentSessions.toActionResult()
        else -> "Action '$action' is not supported!".let { message ->
            logger.error { message }
            ActionResult(StatusCodes.BAD_REQUEST, message)
        }
    }

    override suspend fun processData(
        instanceId: String,
        content: String,
    ): Any = run {
        val message = if (content.startsWith("{"))
            json.decodeFromString(CoverMessage.serializer(), content)
        else {
            val decode = Base64.getDecoder().decode(content)
            val decompress = Zstd.decompress(decode, Zstd.decompressedSize(decode).toInt())
            ProtoBuf.decodeFromByteArray(CoverMessage.serializer(), decompress)
        }
        processData(instanceId, message)
            .let { "" } //TODO eliminate magic empty strings from API
    }

    private suspend fun processData(
        instanceId: String,
        message: CoverMessage,
    ) = when (message) {
        is InitInfo -> {
            if (message.init) {
                state.init()
            }
            logger.info { "$instanceId: ${message.message}" } //log init message
            logger.info { "$instanceId: ${message.classesCount} classes to load" }
        }
        is InitDataPart -> {
            (state.data as? DataBuilder)?.also {
                logger.info { "$instanceId: $message" }
                it += message.astEntities
            }
        }
        is Initialized -> state.initialized {
            processInitialized()
        }
        is ScopeInitialized -> scopeInitialized(message.prevId)
        is SessionStarted -> logger.info { "$instanceId: Agent session ${message.sessionId} started." }
            .also { logPoolStats() }
        is SessionCancelled -> logger.info { "$instanceId: Agent session ${message.sessionId} cancelled." }
        is SessionsCancelled -> message.run {
            activeScope.let { ids.forEach { id: String -> it.cancelSession(id) } }
            logger.info { "$instanceId: Agent sessions cancelled: $ids." }
        }
        is CoverDataPart -> activeScope.addProbes(message.sessionId) { message.data }
        is SessionChanged -> activeScope.probesChanged()
        is SessionFinished -> {
            delay(500L) //TODO remove after multi-instance core is implemented
            state.finishSession(message.sessionId) ?: logger.info {
                "$instanceId: No active session with id ${message.sessionId}."
            }
        }
        is SessionsFinished -> {
            delay(500L) //TODO remove after multi-instance core is implemented
            message.ids.forEach { state.finishSession(it) }
        }
        else -> logger.info { "$instanceId: Message is not supported! $message" }
    }

    private suspend fun Plugin.processInitialized(): Boolean {
        initGateSettings()
        sendGateSettings()
        sendParentBuild()
        sendBaseline()
        sendParentTestsToRunStats()
        state.classDataOrNull()?.sendBuildStats()
        sendScopes(buildVersion)
        calculateAndSendCachedCoverage()
        return initActiveScope()
    }

    private suspend fun sendParentBuild() = send(
        buildVersion,
        destination = Routes.Data().let(Routes.Data::Parent),
        message = state.coverContext().parentBuild?.version?.let(::BuildVersionDto) ?: ""
    )

    internal suspend fun sendBaseline() = send(
        buildVersion,
        destination = Routes.Data().let(Routes.Data::Baseline),
        message = storeClient.findById<GlobalAgentData>(agentId)?.baseline?.version?.let(::BuildVersionDto) ?: ""
    )

    private suspend fun sendParentTestsToRunStats() = send(
        buildVersion,
        destination = Routes.Build().let(Routes.Build::TestsToRun).let(Routes.Build.TestsToRun::ParentTestsToRunStats),
        message = state.storeClient.loadTestsToRunSummary(
            buildVersion = buildVersion,
            parentVersion = state.coverContext().build.parentVersion
        ).map { it.toTestsToRunSummaryDto() }
    )

    private suspend fun ClassData.sendBuildStats() {
        send(buildVersion, Routes.Data().let(Routes.Data::Build), state.coverContext().toBuildStatsDto())
    }

    private suspend fun calculateAndSendCachedCoverage() = state.coverContext().build.let { build ->
        val scopes = state.scopeManager.byVersion(
            buildVersion, withData = true
        )
        state.updateProbes(scopes.enabled())
        val coverContext = state.coverContext()
        build.bundleCounters.calculateAndSendBuildCoverage(coverContext, build.stats.scopeCount)
        scopes.forEach { scope ->
            val bundleCounters = scope.calcBundleCounters(coverContext, adminData.loadClassBytes())
            val coverageInfoSet = bundleCounters.calculateCoverageData(coverContext, scope)
            coverageInfoSet.sendScopeCoverage(buildVersion, scope.id)
            bundleCounters.assocTestsJob(scope)
            bundleCounters.byTest.coveredMethodsJob(scope.id)
        }
    }

    internal suspend fun sendScopeMessages(buildVersion: String = this.buildVersion) {
        sendActiveScope()
        sendScopes(buildVersion)
    }

    internal suspend fun sendActiveSessions() {
        val sessions = activeScope.activeSessions.values.map {
            ActiveSessionDto(
                id = it.id,
                agentId = agentId,
                testType = it.testType,
                isGlobal = it.isGlobal,
                isRealtime = it.isRealtime
            )
        }
        val summary = ActiveSessions(
            count = sessions.count(),
            testTypes = sessions.groupBy { it.testType }.keys
        )
        Routes.ActiveScope().let {
            send(buildVersion, Routes.ActiveScope.ActiveSessionSummary(it), summary)
            send(buildVersion, Routes.ActiveScope.ActiveSessions(it), sessions)
        }
        val serviceGroup = agentInfo.serviceGroup
        if (serviceGroup.any()) {
            val aggregatedSessions = sessionAggregator(serviceGroup, agentId, sessions) ?: sessions
            sendToGroup(
                destination = Routes.Group.ActiveSessions(Routes.Group()),
                message = aggregatedSessions
            )
        }
    }

    internal suspend fun sendActiveScope() {
        val summary = activeScope.summary
        send(buildVersion, Routes.ActiveScope(), summary)
        sendScopeSummary(summary)
    }

    internal suspend fun sendScopeSummary(scopeSummary: ScopeSummary, buildVersion: String = this.buildVersion) {
        send(buildVersion, scopeById(scopeSummary.id), scopeSummary)
    }

    internal suspend fun sendScopes(buildVersion: String = this.buildVersion) {
        val scopes = state.scopeManager.byVersion(buildVersion)
        sendScopes(buildVersion, scopes)
    }

    private suspend fun sendScopes(
        buildVersion: String,
        scopes: Sequence<FinishedScope>,
    ) = sender.send(
        context = AgentSendContext(
            agentId,
            buildVersion
        ),
        destination = Routes.Build.Scopes(Routes.Build()).let { Routes.Build.Scopes.FinishedScopes(it) },
        message = scopes.summaries()
    )

    internal suspend fun calculateAndSendBuildCoverage() {
        val pereScope = state.prevScope.takeIf { it?.enabled == true }
        val scopes = state.scopeManager.run {
            byVersion(buildVersion, withData = true).enabled()
        }.filter { it.id != pereScope?.id }.let { if (pereScope != null) it.plusElement(pereScope) else it }
        scopes.calculateAndSendBuildCoverage(state.coverContext())
    }

    private suspend fun Sequence<FinishedScope>.calculateAndSendBuildCoverage(context: CoverContext) {
        state.updateProbes(this)
        logger.debug { "Start to calculate BundleCounters of build" }
        val bundleCounters = flatten().calcBundleCounters(context, adminData.loadClassBytes())
        state.updateBundleCounters(bundleCounters)
        logger.debug { "Start to calculate build coverage" }
        bundleCounters.calculateAndSendBuildCoverage(context, scopeCount = count())
    }

    private suspend fun BundleCounters.calculateAndSendBuildCoverage(
        context: CoverContext,
        scopeCount: Int,
    ) {
        val coverageInfoSet = calculateCoverageData(context)
        val parentCoverageCount = context.parentBuild?.let { context.parentBuild.stats.coverage } ?: zeroCount
        val risks = context.methodChanges.risks(all)
        val buildCoverage = (coverageInfoSet.coverage as BuildCoverage).copy(
            finishedScopesCount = scopeCount,
            riskCount = Count(
                risks.values.sumBy { it.count() },
                context.methodChanges.run { new.count() + modified.count() }
            )
        )
        state.updateBuildStats(buildCoverage, context)
        val cachedBuild = state.updateBuildTests(
            byTest.keys.groupBy(TypedTest::type, TypedTest::name),
        )
        val summary = cachedBuild.toSummary(
            agentInfo.name,
            context.testsToRun,
            risks,
            coverageInfoSet.coverageByTests,
            parentCoverageCount
        )
        coverageInfoSet.sendBuildCoverage(buildVersion, buildCoverage, summary)
        assocTestsJob()
        byTest.coveredMethodsJob()
        state.storeBuild()
        val stats = summary.toStatsDto()
        val qualityGate = checkQualityGate(stats)
        send(buildVersion, Routes.Build().let(Routes.Build::Summary), summary.toDto())
        Routes.Data().let {
            send(buildVersion, Routes.Data.Stats(it), stats)
            send(buildVersion, Routes.Data.QualityGate(it), qualityGate)
            send(buildVersion, Routes.Data.Recommendations(it), summary.recommendations())
            send(buildVersion, Routes.Data.Tests(it), summary.tests.toDto())
            send(buildVersion, Routes.Data.TestsToRun(it), summary.testsToRun.toDto())
        }
        sendGroupSummary(summary)
    }

    private suspend fun CoverageInfoSet.sendBuildCoverage(
        buildVersion: String,
        buildCoverage: BuildCoverage,
        summary: AgentSummary,
    ) = Routes.Build().let { buildRoute ->
        val coverageRoute = Routes.Build.Coverage(buildRoute)
        send(buildVersion, coverageRoute, buildCoverage)
        val methodSummaryDto = buildMethods.toSummaryDto().copy(risks = summary.riskCounts)
        send(buildVersion, Routes.Build.Methods(buildRoute), methodSummaryDto)
        sendBuildTree(packageCoverage, associatedTests)
        send(buildVersion, Routes.Build.Tests(buildRoute), tests)
        Routes.Build.Summary.Tests(Routes.Build.Summary(buildRoute)).let {
            send(buildVersion, Routes.Build.Summary.Tests.All(it), coverageByTests.all)
            send(buildVersion, Routes.Build.Summary.Tests.ByType(it), coverageByTests.byType)
        }
        send(buildVersion, Routes.Build.Risks(buildRoute), summary.risks.toListDto())
        val context = state.coverContext() //TODO remove context from this method
        send(buildVersion, Routes.Build.TestsToRun(buildRoute), context.testsToRunDto())
        val testsToRunSummary = context.toTestsToRunSummary()
        state.storeClient.store(testsToRunSummary)
        Routes.Build.Summary(buildRoute).let {
            send(buildVersion, Routes.Build.Summary.TestsToRun(it), testsToRunSummary.toTestsToRunSummaryDto())
        }
    }

    private suspend fun sendBuildTree(
        treeCoverage: List<JavaPackageCoverage>,
        associatedTests: List<AssociatedTests>,
    ) {
        val coverageRoute = Routes.Build.Coverage(Routes.Build())
        val pkgsRoute = Routes.Build.Coverage.Packages(coverageRoute)
        val packages = treeCoverage.takeIf { runtimeConfig.sendPackages } ?: emptyList()
        send(buildVersion, pkgsRoute, packages.map { it.copy(classes = emptyList()) })
        packages.forEach {
            AsyncJobDispatcher.launch {
                send(buildVersion, Routes.Build.Coverage.Packages.Package(it.name, pkgsRoute), it)
            }
        }
        if (associatedTests.isNotEmpty()) {
            logger.info { "Assoc tests - ids count: ${associatedTests.count()}" }
            associatedTests.forEach {
                AsyncJobDispatcher.launch {
                    send(buildVersion, Routes.Build.AssociatedTests(it.id, Routes.Build()), it)
                }
            }
        }
    }

    private suspend fun Plugin.sendGroupSummary(summary: AgentSummary) {
        val serviceGroup = agentInfo.serviceGroup
        if (serviceGroup.any()) {
            val aggregated = summaryAggregator(serviceGroup, agentId, summary)
            val summaries = summaryAggregator.getSummaries(serviceGroup)
            Routes.Group().let { groupParent ->
                sendToGroup(
                    destination = Routes.Group.Summary(groupParent),
                    message = ServiceGroupSummaryDto(
                        name = serviceGroup,
                        aggregated = aggregated.toDto(),
                        summaries = summaries.map { (id, summary) ->
                            summary.toDto(id)
                        }
                    )
                )
                Routes.Group.Data(groupParent).let {
                    sendToGroup(
                        destination = Routes.Group.Data.Tests(it),
                        message = aggregated.tests.toDto()
                    )

                    sendToGroup(
                        destination = Routes.Group.Data.TestsToRun(it),
                        message = aggregated.testsToRun.toDto()
                    )
                    sendToGroup(
                        destination = Routes.Group.Data.Recommendations(it),
                        message = aggregated.recommendations()
                    )
                }
            }
        }
    }

    internal suspend fun calculateAndSendScopeCoverage() = activeScope.let { scope ->
        val context = state.coverContext()
        val bundleCounters = scope.calcBundleCounters(context, adminData.loadClassBytes())
        val coverageInfoSet = bundleCounters.calculateCoverageData(context, scope)
        activeScope.updateSummary {
            it.copy(coverage = coverageInfoSet.coverage as ScopeCoverage)
        }
        coverageInfoSet.sendScopeCoverage(buildVersion, scope.id)
        bundleCounters.assocTestsJob(scope)
        bundleCounters.byTest.coveredMethodsJob(scope.id)
    }

    private suspend fun sendScopeTree(
        scopeId: String,
        associatedTests: List<AssociatedTests>,
        treeCoverage: List<JavaPackageCoverage>,
    ) {
        val scopeRoute = Routes.Build.Scopes.Scope(scopeId, Routes.Build.Scopes(Routes.Build()))
        if (associatedTests.isNotEmpty()) {
            logger.info { "Assoc tests - ids count: ${associatedTests.count()}" }
            associatedTests.forEach { assocTests ->
                AsyncJobDispatcher.launch {
                    val assocTestsRoute = Routes.Build.Scopes.Scope.AssociatedTests(assocTests.id, scopeRoute)
                    send(buildVersion, assocTestsRoute, assocTests)
                }
            }
        }
        val coverageRoute = Routes.Build.Scopes.Scope.Coverage(scopeRoute)
        val pkgsRoute = Routes.Build.Scopes.Scope.Coverage.Packages(coverageRoute)
        val packages = treeCoverage.takeIf { runtimeConfig.sendPackages } ?: emptyList()
        send(buildVersion, pkgsRoute, packages.map { it.copy(classes = emptyList()) })
        packages.forEach {
            AsyncJobDispatcher.launch {
                send(buildVersion, Routes.Build.Scopes.Scope.Coverage.Packages.Package(it.name, pkgsRoute), it)
            }
        }
    }

    internal suspend fun CoverageInfoSet.sendScopeCoverage(
        buildVersion: String,
        scopeId: String,
    ) = scopeById(scopeId).let { scope ->
        val coverageRoute = Routes.Build.Scopes.Scope.Coverage(scope)
        send(buildVersion, coverageRoute, coverage)
        send(buildVersion, Routes.Build.Scopes.Scope.Methods(scope), buildMethods.toSummaryDto())
        sendScopeTree(scopeId, associatedTests, packageCoverage)
        send(buildVersion, Routes.Build.Scopes.Scope.Tests(scope), tests)
        Routes.Build.Scopes.Scope.Summary.Tests(Routes.Build.Scopes.Scope.Summary(scope)).let {
            send(buildVersion, Routes.Build.Scopes.Scope.Summary.Tests.All(it), coverageByTests.all)
            send(buildVersion, Routes.Build.Scopes.Scope.Summary.Tests.ByType(it), coverageByTests.byType)
        }
    }

    internal suspend fun send(buildVersion: String, destination: Any, message: Any) {
        sender.send(AgentSendContext(agentInfo.id, buildVersion), destination, message)
    }

    private fun changeState() {
        logger.debug { "agent(id=$agentId, version=$buildVersion) changing state..." }
        _state.getAndUpdate {
            AgentState(
                storeClient = storeClient,
                agentInfo = agentInfo,
                adminData = adminData,
            )
        }?.close()
    }

    internal suspend fun BundleCounters.assocTestsJob(
        scope: Scope? = null,
    ) = AsyncJobDispatcher.launch {
        trackTime("assocTestsJob") {
            logger.debug { "Calculating all associated tests..." }
            val assocTestsMap = trackTime("assocTestsJob getAssocTestsMap") {
                byTest.associatedTests(onlyPackages = false)
            }
            val associatedTests = trackTime("assocTestsJob getAssociatedTests") {
                assocTestsMap.getAssociatedTests()
            }
            val treeCoverage = trackTime("assocTestsJob getTreeCoverage") {
                state.coverContext().packageTree.packages.treeCoverage(all, assocTestsMap)
            }
            logger.debug { "Sending all associated tests..." }
            scope?.let {
                trackTime("assocTestsJob sendScopeTree") {
                    sendScopeTree(it.id, associatedTests, treeCoverage)
                }
            } ?: trackTime("assocTestsJob sendBuildTree") { sendBuildTree(treeCoverage, associatedTests) }
        }
    }

    internal suspend fun Map<TypedTest, BundleCounter>.coveredMethodsJob(
        scopeId: String? = null,
        context: CoverContext = state.coverContext(),
    ) = AsyncJobDispatcher.launch {
        trackTime("coveredByTestJob") {
            entries.parallelStream().forEach { (typedTest, bundle) ->
                val coveredMethods = context.methods.toCoverMap(bundle, true)
                val summary = coveredMethods.toSummary(typedTest, context)
                val all = coveredMethods.values.toList()
                val modified = coveredMethods.filterValues { it in context.methodChanges.modified }
                val new = coveredMethods.filterValues { it in context.methodChanges.new }
                val unaffected = coveredMethods.filterValues { it in context.methodChanges.unaffected }
                AsyncJobDispatcher.launch {
                    scopeId?.let {
                        Routes.Build.Scopes.Scope.MethodsCoveredByTest(typedTest.id(), scopeById(it)).let { test ->
                            send(buildVersion, Routes.Build.Scopes.Scope.MethodsCoveredByTest.Summary(test), summary)
                            send(buildVersion, Routes.Build.Scopes.Scope.MethodsCoveredByTest.All(test), all)
                            send(buildVersion, Routes.Build.Scopes.Scope.MethodsCoveredByTest.Modified(test), modified)
                            send(buildVersion, Routes.Build.Scopes.Scope.MethodsCoveredByTest.New(test), new)
                            send(buildVersion,
                                Routes.Build.Scopes.Scope.MethodsCoveredByTest.Unaffected(test),
                                unaffected)
                        }
                    } ?: run {
                        Routes.Build.MethodsCoveredByTest(typedTest.id(), Routes.Build()).let { test ->
                            send(buildVersion, Routes.Build.MethodsCoveredByTest.Summary(test), summary)
                            send(buildVersion, Routes.Build.MethodsCoveredByTest.All(test), all)
                            send(buildVersion, Routes.Build.MethodsCoveredByTest.Modified(test), modified)
                            send(buildVersion, Routes.Build.MethodsCoveredByTest.New(test), new)
                            send(buildVersion, Routes.Build.MethodsCoveredByTest.Unaffected(test), unaffected)
                        }
                    }
                }
            }
        }
    }

    override fun sendProbes(data: String) {
        val decode = Base64.getDecoder().decode(data)
        val decompress = Zstd.decompress(decode, Zstd.decompressedSize(decode).toInt())
        val message = ProtoBuf.decodeFromByteArray(CoverMessage.serializer(), decompress) as CoverDataPart
        activeScope.addProbes(message.sessionId) { message.data }
    }

}

interface RMI : Remote {
    @Throws(RemoteException::class)
    fun sendProbes(
        data: String,
    )
}
