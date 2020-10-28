package com.epam.drill.plugins.test2code


import com.epam.drill.common.*
import com.epam.drill.plugin.api.*
import com.epam.drill.plugin.api.end.*
import com.epam.drill.plugin.api.message.*
import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.api.routes.*
import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.drill.plugins.test2code.group.*
import com.epam.drill.plugins.test2code.storage.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.kodux.*
import kotlinx.coroutines.*
import mu.*

@Suppress("unused")
class Plugin(
    adminData: AdminData,
    sender: Sender,
    val storeClient: StoreClient,
    agentInfo: AgentInfo,
    id: String
) : AdminPluginPart<Action>(adminData, sender, storeClient, agentInfo, id) {
    internal val logger = KotlinLogging.logger("Plugin $id")

    override val serDe: SerDe<Action> = SerDe(Action.serializer())

    internal val runtimeConfig = RuntimeConfig(id)

    internal lateinit var state: AgentState

    val buildVersion = agentInfo.buildVersion

    val activeScope: ActiveScope get() = state.activeScope

    private val agentId = agentInfo.id

    private val buildInfo: BuildInfo? get() = adminData.buildManager[buildVersion]

    override suspend fun initialize() {
        state = agentState()
        state.readActiveScopeInfo()?.let { processData(Initialized("")) }
    }

    override suspend fun applyPackagesChanges() {
        state.scopeManager.deleteByVersion(buildVersion)
        storeClient.deleteById<ClassData>(buildVersion)
        state = agentState()
    }

    override suspend fun doAction(
        action: Action
    ): ActionResult = when (action) {
        is SwitchActiveScope -> changeActiveScope(action.payload)
        is RenameScope -> renameScope(action.payload)
        is ToggleScope -> toggleScope(action.payload.scopeId)
        is DropScope -> dropScope(action.payload.scopeId)
        is UpdateSettings -> updateSettings(action.payload)
        is StartNewSession -> action.payload.run {
            val newSessionId = sessionId.ifEmpty(::genUuid)
            activeScope.startSession(
                newSessionId,
                testType,
                isGlobal,
                runtimeConfig.realtime && isRealtime
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
            } ?: FieldErrorDto(
                field = "sessionId", message = if (isGlobal && activeScope.hasActiveGlobalSession()) {
                    listOf(
                        "Error! Only one active global session is allowed.",
                        "Please finish the active one in order to start new."
                    ).joinToString(" ")
                } else "Session with such ID already exists. Please choose a different ID."
            ).toActionResult(StatusCodes.CONFLICT)
        }
        is AddSessionData -> action.payload.run {
            AddAgentSessionData(
                payload = AgentSessionDataPayload(sessionId = sessionId, data = data)
            )
        }.toActionResult()
        is AddCoverage -> action.payload.run {
            activeScope.addProbes(sessionId) {
                data.map { probes ->
                    ExecClassData(className = probes.name, testName = probes.test, probes = probes.probes)
                }
            }?.run {
                if (isRealtime) {
                    activeScope.probesChanged()
                }
                ActionResult(StatusCodes.OK, "")
            } ?: ActionResult(StatusCodes.NOT_FOUND, "Active session '$sessionId' not found.")
        }
        is CancelSession -> action.payload.run {
            activeScope.cancelSession(action.payload.sessionId)?.let { session ->
                CancelAgentSession(payload = AgentSessionPayload(session.id)).toActionResult()
            } ?: ActionResult(StatusCodes.NOT_FOUND, "Active session '$sessionId' not found.")
        }
        is CancelAllSessions -> {
            activeScope.cancelAllSessions()
            CancelAllAgentSessions.toActionResult()
        }
        is StopSession -> action.payload.run {
            testRun?.let { activeScope.activeSessions[sessionId]?.setTestRun(it) }
            StopAgentSession(
                payload = AgentSessionPayload(action.payload.sessionId)
            )
        }.toActionResult()
        is StopAllSessions -> StopAllAgentSessions.toActionResult()
        else -> "Action '$action' is not supported!".let { message ->
            logger.error { message }
            ActionResult(StatusCodes.BAD_REQUEST, message)
        }
    }

    override suspend fun processData(dm: DrillMessage): Any = dm.content!!.let { content ->
        val message = CoverMessage.serializer() parse content
        processData(message)
            .let { "" } //TODO eliminate magic empty strings from API
    }

    private suspend fun processData(message: CoverMessage) = when (message) {
        is InitInfo -> {
            if (message.init) {
                state.init()
            }
            logger.info { message.message } //log init message
            logger.info { "${message.classesCount} classes to load" }
        }
        is InitDataPart -> {
            (state.data as? DataBuilder)?.also {
                logger.info { message }
                it += message.astEntities
            }
        }
        is Initialized -> {
            state.initialized()
            initGateSettings()
            sendParentBuild()
            state.classDataOrNull()?.sendBuildStats()
            sendScopes(buildVersion)
            calculateAndSendCachedCoverage()
            initActiveScope()
            adminData.buildManager.builds.filter { it.version != buildVersion }.forEach {
                cleanActiveScope(it.version)
            }
        }
        is ScopeInitialized -> scopeInitialized(message.prevId)
        is SessionStarted -> logger.info { "Agent session ${message.sessionId} started." }
        is SessionCancelled -> logger.info { "Agent session ${message.sessionId} cancelled." }
        is SessionsCancelled -> message.run {
            activeScope.let { ids.forEach { id: String -> it.cancelSession(id) } }
            logger.info { "Agent sessions cancelled: $ids." }
        }
        is CoverDataPart -> activeScope.addProbes(message.sessionId) { message.data }
        is SessionChanged -> activeScope.probesChanged()
        is SessionFinished -> {
            delay(500L) //TODO remove after multi-instance core is implemented
            state.finishSession(message.sessionId) ?: logger.info {
                "No active session with id ${message.sessionId}."
            }
        }
        is SessionsFinished -> {
            delay(500L) //TODO remove after multi-instance core is implemented
            message.ids.forEach { state.finishSession(it) }
        }
        else -> logger.info { "Message is not supported! $message" }
    }

    private suspend fun sendParentBuild() = send(
        buildVersion,
        destination = Routes.Data().let(Routes.Data::Parent),
        message = buildInfo?.parentVersion?.takeIf(String::any)?.let(::ParentBuildDto) ?: ""
    )

    private suspend fun ClassData.sendBuildStats() {
        send(buildVersion, Routes.Data().let(Routes.Data::Build), toBuildStatsDto())
    }

    private suspend fun calculateAndSendCachedCoverage() = state.builds[buildVersion]?.let { build ->
        val scopes = state.scopeManager.byVersion(
            buildVersion, withData = true
        )
        state.updateProbes(buildVersion, scopes.enabled())
        val coverContext = state.coverContext()
        build.bundleCounters.calculateAndSendBuildCoverage(coverContext, buildVersion, build.coverage.scopeCount)
        scopes.forEach { scope ->
            val coverageInfoSet = scope.data.bundleCounters.calculateCoverageData(coverContext, scope)
            coverageInfoSet.sendScopeCoverage(buildVersion, scope.id)
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
                testType = it.testType
            )
        }
        val summary = ActiveSessions(
            count = sessions.count(),
            testTypes = sessions.groupBy { it.testType }.keys
        )
        send(buildVersion, Routes.ActiveSessionStats, summary)
        send(buildVersion, Routes.ActiveSessions, sessions)
        val serviceGroup = agentInfo.serviceGroup
        if (serviceGroup.any()) {
            val aggregatedSessions = sessionAggregator(serviceGroup, agentId, sessions) ?: sessions
            sendToGroup(
                destination = Routes.ServiceGroup.ActiveSessions(Routes.ServiceGroup()),
                message = aggregatedSessions
            )
        }
    }

    internal suspend fun sendActiveScope() {
        val summary = activeScope.summary
        send(buildVersion, Routes.ActiveScope, summary)
        sendScopeSummary(summary)
    }

    private suspend fun cleanActiveScope(buildVersion: String) {
        send(buildVersion, Routes.ActiveScope, "")
    }

    internal suspend fun sendScopeSummary(scopeSummary: ScopeSummary, buildVersion: String = this.buildVersion) {
        send(buildVersion, Routes.Scope(scopeSummary.id), scopeSummary)
    }

    internal suspend fun sendScopes(buildVersion: String = this.buildVersion) {
        val scopes = state.scopeManager.byVersion(buildVersion)
        sendScopes(buildVersion, scopes)
    }

    private suspend fun sendScopes(
        buildVersion: String,
        scopes: Sequence<FinishedScope>
    ) = sender.send(
        context = AgentSendContext(
            agentId,
            buildVersion
        ),
        destination = Routes.Scopes,
        message = scopes.summaries()
    )

    internal suspend fun calculateAndSendBuildCoverage() {
        val scopes = state.scopeManager.run {
            byVersion(buildVersion, withData = true).enabled()
        }
        scopes.calculateAndSendBuildCoverage(state.coverContext(), buildVersion)
    }

    private suspend fun Sequence<FinishedScope>.calculateAndSendBuildCoverage(
        context: CoverContext,
        buildVersion: String
    ) {
        state.updateProbes(buildVersion, this)
        val bundleCounters = flatten().calcBundleCounters(context)
        state.updateBundleCounters(buildVersion, bundleCounters)
        bundleCounters.calculateAndSendBuildCoverage(context, buildVersion, scopeCount = count())
    }

    private suspend fun BundleCounters.calculateAndSendBuildCoverage(
        context: CoverContext,
        buildVersion: String,
        scopeCount: Int
    ) {
        val coverageInfoSet = calculateCoverageData(context)
        val buildMethods = coverageInfoSet.buildMethods
        val testsToRun = state.testsToRun(
            buildVersion,
            buildMethods.allModifiedMethods.methods
        )
        val parentVersion = adminData.buildManager[buildVersion]?.parentVersion?.takeIf(String::any)
        val parentCoverageCount = parentVersion?.let {
            state.builds[parentVersion]?.coverage?.count ?: zeroCount
        }
        val risks = parentVersion?.let { buildMethods.risks() } ?: Risks(emptyList(), emptyList())
        val buildCoverage = (coverageInfoSet.coverage as BuildCoverage).copy(
            finishedScopesCount = scopeCount,
            riskCount = buildMethods.run {
                Count(
                    covered = newMethods.coveredCount + allModifiedMethods.coveredCount,
                    total = newMethods.totalCount + allModifiedMethods.totalCount
                )
            },
            risks = buildMethods.toRiskSummaryDto()
        )
        coverageInfoSet.sendBuildCoverage(buildVersion, buildCoverage, risks, testsToRun)
        if (buildVersion == agentInfo.buildVersion) {
            val cachedCoverage = state.updateBuildCoverage(
                buildVersion,
                buildCoverage
            ).coverage
            state.updateBuildTests(
                buildVersion,
                byTest.keys.groupBy(TypedTest::type, TypedTest::name),
                coverageInfoSet.associatedTests
            )
            val cachedTests = state.updateTestsToRun(buildVersion, testsToRun).tests
            val summary = cachedCoverage.toSummary(agentInfo.name, cachedTests, parentCoverageCount)
            val stats = summary.toStatsDto()
            val qualityGate = checkQualityGate(stats)
            send(buildVersion, Routes.Build().let(Routes.Build::Summary), summary.toDto())
            Routes.Data().let {
                send(buildVersion, Routes.Data.Stats(it), stats)
                send(buildVersion, Routes.Data.QualityGate(it), qualityGate)
                send(buildVersion, Routes.Data.Recommendations(it), summary.recommendations)
                send(buildVersion, Routes.Data.Tests(it), summary.tests.toDto())
                send(buildVersion, Routes.Data.TestsToRun(it), summary.testsToRun.toDto())
            }
            sendGroupSummary(summary)
            state.storeBuild(buildVersion)
        }
    }

    private suspend fun CoverageInfoSet.sendBuildCoverage(
        buildVersion: String,
        buildCoverage: BuildCoverage,
        risks: Risks,
        testsToRun: GroupedTests
    ) = Routes.Build().let { buildRoute ->
        val coverageRoute = Routes.Build.Coverage(buildRoute)
        send(buildVersion, coverageRoute, buildCoverage)
        val methodSummaryDto = buildMethods.toSummaryDto().copy(risks = buildMethods.toRiskSummaryDto())
        send(buildVersion, Routes.Build.Methods(buildRoute), methodSummaryDto)
        val pkgsRoute = Routes.Build.Coverage.Packages(coverageRoute)
        val packages = packageCoverage.takeIf { runtimeConfig.sendPackages } ?: emptyList()
        send(buildVersion, pkgsRoute, packages.map { it.copy(classes = emptyList()) })
        packages.forEach {
            send(buildVersion, Routes.Build.Coverage.Packages.Package(it.name, pkgsRoute), it)
        }
        if (associatedTests.isNotEmpty()) {
            logger.info { "Assoc tests - ids count: ${associatedTests.count()}" }
            val beautifiedAssociatedTests = associatedTests.map { batch ->
                batch.copy(className = batch.className.replace("${batch.packageName}/", ""))
            }
            send(buildVersion, Routes.Build.AssociatedTests(buildRoute), beautifiedAssociatedTests)
        }
        send(buildVersion, Routes.Build.TestsUsages(buildRoute), testsUsagesInfoByType)
        Routes.Build.Summary.Tests(Routes.Build.Summary(buildRoute)).let {
            send(buildVersion, Routes.Build.Summary.Tests.All(it), coverageByTests.all)
            send(buildVersion, Routes.Build.Summary.Tests.ByType(it), coverageByTests.byType)
        }
        Routes.Build.Summary(buildRoute).let {
            send(buildVersion, Routes.Build.Summary.TestsToRun(it), testsToRun.toSummary())
        }
        //TODO remove after changes on the frontend
        send(buildVersion, Routes.Build.CoveredMethodsByTest(buildRoute), methodsCoveredByTest)
        send(buildVersion, Routes.Build.CoveredMethodsByType(buildRoute), methodsCoveredByType)

        methodsCoveredByTest.forEach {
            Routes.Build.MethodsCoveredByTest(it.id, buildRoute).let { test ->
                send(buildVersion, Routes.Build.MethodsCoveredByTest.Summary(test), it.toSummary())
                send(buildVersion, Routes.Build.MethodsCoveredByTest.All(test), it.allMethods)
                send(buildVersion, Routes.Build.MethodsCoveredByTest.Modified(test), it.modifiedMethods)
                send(buildVersion, Routes.Build.MethodsCoveredByTest.Unaffected(test), it.unaffectedMethods)
                send(buildVersion, Routes.Build.MethodsCoveredByTest.New(test), it.newMethods)
            }
        }
        methodsCoveredByType.forEach {
            Routes.Build.MethodsCoveredByTestType(it.testType, buildRoute).let { testType ->
                send(buildVersion, Routes.Build.MethodsCoveredByTestType.Summary(testType), it.toSummary())
                send(buildVersion, Routes.Build.MethodsCoveredByTestType.All(testType), it.allMethods)
                send(buildVersion, Routes.Build.MethodsCoveredByTestType.Modified(testType), it.modifiedMethods)
                send(buildVersion, Routes.Build.MethodsCoveredByTestType.Unaffected(testType), it.unaffectedMethods)
                send(buildVersion, Routes.Build.MethodsCoveredByTestType.New(testType), it.newMethods)
            }
        }
        send(buildVersion, Routes.Build.Risks(buildRoute), risks)
        send(buildVersion, Routes.Build.TestsToRun(buildRoute), TestsToRun(testsToRun))
    }

    private suspend fun Plugin.sendGroupSummary(summary: AgentSummary) {
        val serviceGroup = agentInfo.serviceGroup
        if (serviceGroup.any()) {
            val aggregated = summaryAggregator(serviceGroup, agentId, summary)
            val summaries = summaryAggregator.getSummaries(serviceGroup)
            Routes.ServiceGroup().let { groupParent ->
                sendToGroup(
                    destination = Routes.ServiceGroup.Summary(groupParent),
                    message = ServiceGroupSummaryDto(
                        name = serviceGroup,
                        aggregated = aggregated.toDto(),
                        summaries = summaries.map { (id, summary) ->
                            summary.toDto(id)
                        }
                    )
                )
                Routes.ServiceGroup.Data(groupParent).let {
                    sendToGroup(
                        destination = Routes.ServiceGroup.Data.Tests(it),
                        message = aggregated.tests.toDto()
                    )

                    sendToGroup(
                        destination = Routes.ServiceGroup.Data.TestsToRun(it),
                        message = aggregated.testsToRun.toDto()
                    )
                    sendToGroup(
                        destination = Routes.ServiceGroup.Data.Recommendations(it),
                        message = aggregated.recommendations
                    )
                }
            }
        }
    }

    internal suspend fun calculateAndSendScopeCoverage() = activeScope.let { scope ->
        val context = state.coverContext()
        val bundleCounters = scope.calcBundleCounters(context)
        val coverageInfoSet = bundleCounters.calculateCoverageData(context, scope)
        activeScope.updateSummary {
            it.copy(coverage = coverageInfoSet.coverage as ScopeCoverage)
        }
        coverageInfoSet.sendScopeCoverage(buildVersion, scope.id)
    }

    internal suspend fun CoverageInfoSet.sendScopeCoverage(
        buildVersion: String,
        scopeId: String
    ) = Routes.Scope(scopeId).let { scope ->
        val coverageRoute = Routes.Scope.Coverage(scope)
        send(buildVersion, coverageRoute, coverage)
        send(buildVersion, Routes.Scope.Methods(scope), buildMethods.toSummaryDto())
        val pkgsRoute = Routes.Scope.Coverage.Packages(coverageRoute)
        val packages = packageCoverage.takeIf { runtimeConfig.sendPackages } ?: emptyList()
        send(buildVersion, pkgsRoute, packages.map { it.copy(classes = emptyList()) })
        packages.forEach {
            send(buildVersion, Routes.Scope.Coverage.Packages.Package(it.name, pkgsRoute), it)
        }
        if (associatedTests.isNotEmpty()) {
            logger.info { "Assoc tests - ids count: ${associatedTests.count()}" }
            val beautifiedAssociatedTests = associatedTests.map { batch ->
                batch.copy(className = batch.className.replace("${batch.packageName}/", ""))
            }
            send(buildVersion, Routes.Scope.AssociatedTests(scope), beautifiedAssociatedTests)
        }
        send(buildVersion, Routes.Scope.TestsUsages(scope), testsUsagesInfoByType)
        Routes.Scope.Summary.Tests(Routes.Scope.Summary(scope)).let {
            send(buildVersion, Routes.Scope.Summary.Tests.All(it), coverageByTests.all)
            send(buildVersion, Routes.Scope.Summary.Tests.ByType(it), coverageByTests.byType)
        }
        //TODO remove after changes on the frontend
        send(buildVersion, Routes.Scope.CoveredMethodsByTest(scope), methodsCoveredByTest)
        send(buildVersion, Routes.Scope.CoveredMethodsByType(scope), methodsCoveredByType)

        methodsCoveredByTest.forEach {
            Routes.Scope.MethodsCoveredByTest(it.id, scope).let { test ->
                send(buildVersion, Routes.Scope.MethodsCoveredByTest.Summary(test), it.toSummary())
                send(buildVersion, Routes.Scope.MethodsCoveredByTest.All(test), it.allMethods)
                send(buildVersion, Routes.Scope.MethodsCoveredByTest.Modified(test), it.modifiedMethods)
                send(buildVersion, Routes.Scope.MethodsCoveredByTest.New(test), it.newMethods)
                send(buildVersion, Routes.Scope.MethodsCoveredByTest.Unaffected(test), it.unaffectedMethods)
            }
        }
        methodsCoveredByType.forEach {
            Routes.Scope.MethodsCoveredByTestType(it.testType, scope).let { testType ->
                send(buildVersion, Routes.Scope.MethodsCoveredByTestType.Summary(testType), it.toSummary())
                send(buildVersion, Routes.Scope.MethodsCoveredByTestType.All(testType), it.allMethods)
                send(buildVersion, Routes.Scope.MethodsCoveredByTestType.Modified(testType), it.modifiedMethods)
                send(buildVersion, Routes.Scope.MethodsCoveredByTestType.New(testType), it.newMethods)
                send(buildVersion, Routes.Scope.MethodsCoveredByTestType.Unaffected(testType), it.unaffectedMethods)
            }
        }
    }

    override suspend fun dropData() {
    }

    internal suspend fun send(buildVersion: String, destination: Any, message: Any) {
        sender.send(AgentSendContext(agentInfo.id, buildVersion), destination, message)
    }

    private fun agentState() = AgentState(
        storeClient = storeClient,
        agentInfo = agentInfo,
        adminData = adminData
    )
}
