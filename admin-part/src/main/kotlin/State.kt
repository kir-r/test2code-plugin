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
import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.drill.plugins.test2code.jvm.*
import com.epam.drill.plugins.test2code.storage.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.dsm.*
import com.epam.kodux.util.*
import kotlinx.atomicfu.*
import org.jacoco.core.internal.data.*

/**
 * Agent state.
 * The state itself holds only an atomic reference to the data.
 * The data is represented by the sealed class hierarchy AgentData.
 * In case of inconsistencies of the data a ClassCastException is thrown.
 */

internal const val DEFAULT_SCOPE_NAME = "New Scope"

internal class AgentState(
    val storeClient: StoreClient,
    val agentInfo: AgentInfo,
    val adminData: AdminData,
) {
    private val logger = logger(agentInfo.id)

    val data get() = _data.value

    val scopeManager = ScopeManager(storeClient)

    val activeScope get() = _activeScope.value

    val qualityGateSettings = AtomicCache<String, ConditionSetting>()

    private val _data = atomic<AgentData>(NoData)

    private val _coverContext = atomic<CoverContext?>(null)

    private val _activeScope = atomic(
        ActiveScope(
            buildVersion = agentInfo.buildVersion,
        )
    )

    private val _classBytes = atomic<Map<String, ByteArray>>(emptyMap())

    suspend fun classBytes(buildVersion: String) = _classBytes.value.takeIf {
        it.isNotEmpty()
    } ?: adminData.loadClassBytes(buildVersion).also { loaded ->
        _classBytes.update { loaded }
    }

    suspend fun loadFromDb(block: suspend () -> Unit = {}) {
        logger.debug { "starting load ClassData from DB..." }
        storeClient.loadClassData(agentInfo.buildVersion)?.let { classData ->
            logger.debug { "take from DB count methods ${classData.methods.size}" }
            _data.value = classData
            initialized(classData)
            block()
        }
    }

    fun init() = _data.update {
        _coverContext.value = null
        DataBuilder()
    }

    fun close() {
        logger.debug { "close active scope id=${activeScope.id}" }
        activeScope.close()
    }

    suspend fun initialized(block: suspend () -> Unit = {}) {
        logger.debug { "initialized by event from agent..." }.also { logPoolStats() }
        _data.getAndUpdate {
            when (it) {
                is ClassData -> it
                else -> ClassData(agentInfo.buildVersion)
            }
        }.takeIf { it !is ClassData }?.also { data ->
            val classData = when (data) {
                is DataBuilder -> data.flatMap { e -> e.methods.map { e to it } }.run {
                    logger.debug { "initializing DataBuilder..." }
                    val methods = map { (e, m) ->
                        Method(
                            ownerClass = fullClassname(e.path, e.name),
                            name = m.name.weakIntern(),
                            desc = m.toDesc(),
                            hash = m.checksum.weakIntern()
                        )
                    }.sorted()
                    val packages = data.toPackages()
                    PackageTree(
                        totalCount = sumOf { it.second.count },
                        totalMethodCount = count(),
                        totalClassCount = packages.sumOf { it.totalClassesCount },
                        packages = packages
                    ).toClassData(methods = methods)
                }
                is NoData -> {
                    val classBytes = adminData.loadClassBytes(agentInfo.buildVersion)
                    logger.info { "initializing noData with classBytes size ${classBytes.size}..." }
                    val probeIds: Map<String, Long> = classBytes.mapValues { CRC64.classId(it.value) }
                    val bundleCoverage = classBytes.keys.bundle(classBytes, probeIds)
                    val sortedPackages = bundleCoverage.packages.asSequence().run {
                        mapNotNull { pc ->
                            val classes = pc.classes.filter { it.methods.any() }
                            if (classes.any()) {
                                pc.copy(classes = classes.sortedBy(ClassCounter::name))
                            } else null
                        }.sortedBy(PackageCounter::name)
                    }.toList()
                    val classCounters = sortedPackages.asSequence().flatMap {
                        it.classes.asSequence()
                    }
                    val groupedMethods = classCounters.associate { classCounter ->
                        val name = classCounter.fullName
                        val bytes = classBytes.getValue(name)
                        name to classCounter.parseMethods(bytes).sorted()
                    }
                    val methods = groupedMethods.flatMap { it.value }
                    val packages = sortedPackages.toPackages(groupedMethods)
                    PackageTree(
                        totalCount = packages.sumOf { it.totalCount },
                        totalMethodCount = groupedMethods.values.sumOf { it.count() },
                        totalClassCount = packages.sumOf { it.totalClassesCount },
                        packages = packages
                    ).toClassData(methods = methods, probeIds = probeIds)
                }
                else -> data
            } as ClassData
            classData.store(storeClient)
            initialized(classData)
            block()
        }
    }

    private suspend fun initialized(classData: ClassData) {
        val buildVersion = agentInfo.buildVersion
        val build: CachedBuild = storeClient.loadBuild(buildVersion) ?: CachedBuild(buildVersion)
        val probes = scopeManager.byVersion(buildVersion, withData = true)
        val coverContext = CoverContext(
            agentType = agentInfo.agentType,
            packageTree = classData.packageTree,
            methods = classData.methods,
            probeIds = classData.probeIds,
            build = build
        )
        _coverContext.value = coverContext
        updateProbes(probes)
        val agentId = agentInfo.id
        logger.debug { "agent(id=$agentId, version=$buildVersion) initializing..." }
        storeClient.findById<GlobalAgentData>(agentId)?.baseline?.let { baseline ->
            logger.debug { "(buildVersion=$buildVersion) Current baseline=$baseline." }
            val parentVersion = when (baseline.version) {
                buildVersion -> baseline.parentVersion
                else -> baseline.version
            }.takeIf(String::any)
            logger.debug { "ParentVersion=$parentVersion." }
            parentVersion?.let { storeClient.loadClassData(it) }?.let { parentClassData ->
                val methodChanges = classData.methods.diff(parentClassData.methods)
                val parentBuild = storeClient.loadBuild(parentVersion)?.run {
                    baseline.parentVersion.takeIf(String::any)?.let {
                        copy(parentVersion = it)
                    } ?: this
                }
                val testsToRun = parentBuild?.run {
                    bundleCounters.testsWith(methodChanges.modified)
                }.orEmpty()
                val deletedWithCoverage: Map<Method, Count> = parentBuild?.run {
                    bundleCounters.all.coveredMethods(methodChanges.deleted)
                }.orEmpty()
                val testsToRunParentDurations = parentBuild?.let {
                    TestDurations(
                        all = testsToRun.totalDuration(it.bundleCounters.detailsByTest),
                        byType = testsToRun.mapValues { (type, tests) ->
                            mapOf(type to tests).totalDuration(it.bundleCounters.detailsByTest)
                        }
                    )
                } ?: TestDurations(all = 0L, byType = emptyMap())
                logger.debug { "testsToRun parent durations $testsToRunParentDurations" }
                _coverContext.value = coverContext.copy(
                    methodChanges = methodChanges.copy(deletedWithCoverage = deletedWithCoverage),
                    build = build.copy(parentVersion = parentVersion),
                    parentBuild = parentBuild,
                    testsToRun = testsToRun,
                    testsToRunParentDurations = testsToRunParentDurations
                )
            }
        } ?: run {
            val baseline = Baseline(buildVersion)
            storeClient.store(GlobalAgentData(agentId, baseline))
            logger.debug { "(buildVersion=$buildVersion) Stored initial baseline $baseline." }
        }
        initActiveScope()
    }

    internal suspend fun finishSession(
        sessionId: String
    ): FinishedSession? = activeScope.finishSession(sessionId)?.also {
        if (it.any()) {
            logger.debug { "FinishSession. size of exec data = ${it.probes.size}" }.also { logPoolStats() }
            trackTime("session storing") { storeClient.storeSession(activeScope.id, it) }
            logger.debug { "Session $sessionId finished." }.also { logPoolStats() }
        } else logger.debug { "Session with id $sessionId is empty, it won't be added to the active scope." }
        if (activeScope.activeSessions.isEmpty()) {
            _classBytes.update { emptyMap() }
            logger.trace { "Class bytes have been cleared" }
        }
    }

    internal fun updateProbes(
        buildScopes: Sequence<FinishedScope>
    ) {
        _coverContext.update {
            it?.copy(build = it.build.copy(probes = buildScopes.flatten().flatten().merge()))
        }
    }

    internal fun updateBundleCounters(
        bundleCounters: BundleCounters
    ): CachedBuild = updateBuild {
        copy(bundleCounters = bundleCounters)
    }

    internal fun updateBuildTests(
        tests: GroupedTests,
    ): CachedBuild = updateBuild {
        copy(
            tests = this.tests.copy(tests = tests)
        )
    }

    internal fun updateBuildStats(
        buildCoverage: BuildCoverage,
        context: CoverContext
    ): CachedBuild = updateBuild {
        copy(stats = buildCoverage.toCachedBuildStats(context))
    }

    private fun updateBuild(
        updater: CachedBuild.() -> CachedBuild
    ): CachedBuild = _coverContext.updateAndGet {
        it?.copy(build = it.build.updater())
    }!!.build

    suspend fun storeBuild() {
        trackTime("storeBuild") { _coverContext.value?.build?.store(storeClient) }
    }

    suspend fun renameScope(id: String, newName: String): ScopeSummary? = when (id) {
        activeScope.id -> activeScope.rename(newName.trim()).also { storeActiveScopeInfo() }
        else -> scopeManager.byId(id)?.let { scope ->
            scope.copy(name = newName, summary = scope.summary.copy(name = newName.trim())).also {
                scopeManager.store(it)
            }.summary
        }
    }

    suspend fun toggleScope(id: String) {
        scopeManager.byId(id)?.apply {
            scopeManager.store(copy(enabled = !enabled, summary = this.summary.copy(enabled = !enabled)))
        }
    }

    suspend fun scopeByName(name: String): Scope? = when (name) {
        activeScope.name -> activeScope
        else -> scopeManager.byVersion(agentInfo.buildVersion).firstOrNull { it.name == name }
    }

    suspend fun scopeById(id: String): Scope? = when (id) {
        activeScope.id -> activeScope
        else -> scopeManager.byId(id)
    }

    internal fun coverContext(): CoverContext = _coverContext.value!!

    internal fun classDataOrNull(): ClassData? = _data.value as? ClassData

    private suspend fun initActiveScope() {
        readActiveScopeInfo()?.run {
            val sessions = storeClient.loadSessions(id)
            logger.debug { "load sessions for active scope with id=$id" }.also { logPoolStats() }
            _activeScope.update {
                ActiveScope(
                    id = id,
                    nth = nth,
                    buildVersion = agentInfo.buildVersion,
                    name = name,
                    sessions = sessions,
                ).apply {
                    updateSummary {
                        it.copy(started = startedAt)
                    }
                }
            }
        } ?: storeActiveScopeInfo()
    }

    fun changeActiveScope(name: String): ActiveScope = _activeScope.getAndUpdate {
        ActiveScope(
            nth = it.nth.inc(),
            name = scopeName(name),
            buildVersion = agentInfo.buildVersion,
        )
    }.apply { close() }

    private suspend fun readActiveScopeInfo(): ActiveScopeInfo? = scopeManager.counter(agentInfo.buildVersion)

    suspend fun storeActiveScopeInfo() = trackTime("storeActiveScopeInfo") {
        scopeManager.storeCounter(
            activeScope.run {
                ActiveScopeInfo(
                    buildVersion = buildVersion,
                    id = id,
                    nth = nth,
                    name = name,
                    startedAt = summary.started
                )
            }
        )
    }

    private fun scopeName(name: String) = when (val trimmed = name.trim()) {
        "" -> "$DEFAULT_SCOPE_NAME ${activeScope.nth + 1}"
        else -> trimmed
    }

    private fun PackageTree.toClassData(
        methods: List<Method>,
        probeIds: Map<String, Long> = emptyMap()
    ) = ClassData(
        buildVersion = agentInfo.buildVersion,
        packageTree = this,
        methods = methods,
        probeIds = probeIds
    )

    internal suspend fun toggleBaseline(): String? = run {
        val agentId = agentInfo.id
        val buildVersion = agentInfo.buildVersion
        val data = storeClient.findById(agentId) ?: GlobalAgentData(agentId)
        val baseline = data.baseline
        val parentBuild = coverContext().parentBuild
        val parentVersion = coverContext().build.parentVersion
        when (baseline.version) {
            buildVersion -> parentBuild?.let {
                Baseline(
                    version = baseline.parentVersion,
                    parentVersion = it.parentVersion
                )
            }
            parentVersion -> Baseline(
                version = buildVersion,
                parentVersion = parentVersion
            )
            else -> null
        }?.also { newBaseline ->
            storeClient.store(data.copy(baseline = newBaseline))
            logger.debug { "(buildVersion=${agentInfo.buildVersion}) Toggled baseline $baseline->$newBaseline" }
        }?.version
    }
}
