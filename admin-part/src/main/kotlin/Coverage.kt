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

import com.epam.drill.plugin.api.end.*
import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.drill.plugins.test2code.jvm.*
import com.epam.drill.plugins.test2code.util.*
import kotlinx.collections.immutable.*
import org.jacoco.core.data.*
import java.io.*
import java.util.stream.*
import kotlin.streams.*

private val logger = logger {}

internal fun Sequence<Session>.calcBundleCounters(
    context: CoverContext,
    classBytes: Map<String, ByteArray>,
) = run {
    logger.trace {
        "CalcBundleCounters for ${context.build.version} sessions(size=${this.toList().size}, ids=${
            this.toList().map { it.id + " " }
        })..."
    }
    val probesByTestType = groupBy(Session::testType)
    val testTypeOverlap: Sequence<ExecClassData> = if (probesByTestType.size > 1) {
        probesByTestType.values.asSequence().run {
            val initial: PersistentMap<Long, ExecClassData> = first().asSequence().flatten().merge()
            drop(1).fold(initial) { intersection, sessions ->
                intersection.intersect(sessions.asSequence().flatten())
            }
        }.values.asSequence()
    } else emptySequence()
    logger.trace { "Starting to create the bundle with probesId count ${context.probeIds.size} and classes ${classBytes.size}..." }
    val execClassData = flatten()
    BundleCounters(
        all = execClassData.bundle(context, classBytes),
        testTypeOverlap = testTypeOverlap.bundle(context, classBytes),
        overlap = execClassData.overlappingBundle(context, classBytes),
        byTestType = probesByTestType.mapValues {
            it.value.asSequence().flatten().bundle(context, classBytes)
        },
        byTest = trackTime("bundlesByTests") { probesByTestType.bundlesByTests(context, classBytes) },
        statsByTest = fold(mutableMapOf()) { map, session ->
            session.testStats.forEach { (test, stats) ->
                map[test] = map[test]?.run {
                    copy(duration = duration + stats.duration, result = stats.result)
                } ?: stats
            }
            map
        }
    )
}

internal fun BundleCounters.calculateCoverageData(
    context: CoverContext,
    scope: Scope? = null,
): CoverageInfoSet {
    val bundle = all
    val bundlesByTests = byTest

    val assocTestsMap = trackTime("assocTestsMap") { bundlesByTests.associatedTests() }
    val associatedTests = trackTime("associatedTests") { assocTestsMap.getAssociatedTests() }

    val tree = context.packageTree
    val coverageCount = bundle.count.copy(total = tree.totalCount)
    val totalCoveragePercent = coverageCount.percentage()

    val coverageByTests = trackTime("coverageByTests") {
        CoverageByTests(
            all = TestSummary(
                coverage = bundle.toCoverDto(tree),
                testCount = bundlesByTests.keys.count(),
                duration = statsByTest.values.sumOf { it.duration }
            ),
            byType = byTestType.coveragesByTestType(byTest, context, statsByTest)
        )
    }
    logger.info { coverageByTests.byType }

    val methodCount = bundle.methodCount.copy(total = tree.totalMethodCount)
    val classCount = bundle.classCount.copy(total = tree.totalClassCount)
    val packageCount = bundle.packageCount.copy(total = tree.packages.count())
    val coverageBlock: Coverage = when (scope) {
        null -> {
            BuildCoverage(
                percentage = totalCoveragePercent,
                count = coverageCount,
                methodCount = methodCount,
                classCount = classCount,
                packageCount = packageCount,
                testTypeOverlap = testTypeOverlap.toCoverDto(tree),
                byTestType = coverageByTests.byType
            )
        }
        is FinishedScope -> scope.summary.coverage
        else -> ScopeCoverage(
            percentage = totalCoveragePercent,
            count = coverageCount,
            overlap = overlap.toCoverDto(tree),
            methodCount = methodCount,
            classCount = classCount,
            packageCount = packageCount,
            testTypeOverlap = testTypeOverlap.toCoverDto(tree),
            byTestType = coverageByTests.byType
        )
    }
    logger.info { coverageBlock }

    val buildMethods = trackTime("calculateBundleMethods") { context.calculateBundleMethods(bundle) }

    val packageCoverage = tree.packages.treeCoverage(bundle, assocTestsMap)

    val tests = bundlesByTests.map { (typedTest, bundle) ->
        TestCoverageDto(
            id = typedTest.id(),
            type = typedTest.type,
            name = typedTest.name,
            coverage = bundle.toCoverDto(tree),
            stats = statsByTest[typedTest] ?: TestStats(0, TestResult.PASSED)
        )
    }.sortedBy { it.type }

    return CoverageInfoSet(
        associatedTests,
        coverageBlock,
        buildMethods,
        packageCoverage,
        tests,
        coverageByTests,
    )
}

internal fun Map<String, BundleCounter>.coveragesByTestType(
    bundleMap: Map<TypedTest, BundleCounter>,
    context: CoverContext,
    statsByTest: Map<TypedTest, TestStats>,
): List<TestTypeSummary> = map { (testType, bundle) ->
    TestTypeSummary(
        type = testType,
        summary = TestSummary(
            coverage = bundle.toCoverDto(context.packageTree),
            testCount = bundleMap.keys.filter { it.type == testType }.distinct().count(),
            duration = statsByTest.filter { it.key.type == testType }.map { it.value.duration }.sum()
        )
    )
}

private fun Map<String, List<Session>>.bundlesByTests(
    context: CoverContext,
    classBytes: Map<String, ByteArray>,
): Map<TypedTest, BundleCounter> = values.flatten().asSequence().let { sessions ->
    takeIf { sessions.any() }?.run {
        val testsWithEmptyBundle = sessions.testsWithBundle()
        forEach { (testType, sessions) ->
            sessions.forEach { session ->
                if (session is FinishedSession && !session.cached.isNullOrEmpty()) {
                    testsWithEmptyBundle.putAll(session.cached)
                } else {
                    session.asSequence()
                        .groupBy { TypedTest(it.testName, testType) }
                        .mapValuesTo(testsWithEmptyBundle) {
                            it.value.asSequence().bundle(context, classBytes)
                        }
                }
            }
        }
        testsWithEmptyBundle
    } ?: emptyMap()
}


private fun Sequence<Session>.testsWithBundle(
): MutableMap<TypedTest, BundleCounter> = flatMap { session ->
    session.tests.asSequence()
}.associateWithTo(mutableMapOf()) {
    BundleCounter.empty
}


internal fun Sequence<ExecClassData>.overlappingBundle(
    context: CoverContext,
    classBytes: Map<String, ByteArray>,
): BundleCounter = context.build.probes.intersect(this).run {
    values.asSequence()
}.bundle(context, classBytes)

internal fun Sequence<ExecClassData>.bundle(
    context: CoverContext,
    classBytes: Map<String, ByteArray>,
): BundleCounter = when (context.agentType) {
    "JAVA" -> bundle(
        probeIds = context.probeIds,
        classBytes = classBytes
    )
    else -> bundle(context.packageTree)
}

internal fun Map<TypedTest, BundleCounter>.associatedTests(
    onlyPackages: Boolean = true,
): Map<CoverageKey, List<TypedTest>> = entries.parallelStream().flatMap { (test, bundle) ->
    bundle.coverageKeys(onlyPackages).map { it to test }.distinct()
}.collect(Collectors.groupingBy({ it.first }, Collectors.mapping({ it.second }, Collectors.toList())))

internal suspend fun Plugin.exportCoverage(buildVersion: String) = runCatching {
    val coverage = File(System.getProperty("java.io.tmpdir"))
        .resolve("jacoco.exec")
    coverage.outputStream().use { outputStream ->
        val executionDataWriter = ExecutionDataWriter(outputStream)
        val classBytes = adminData.loadClassBytes()
        val allFinishedScopes = state.scopeManager.byVersion(buildVersion, true)
        allFinishedScopes.filter { it.enabled }.flatMap { finishedScope ->
            finishedScope.data.sessions.flatMap { it.probes }
        }.writeCoverage(executionDataWriter, classBytes)
        if (buildVersion == buildVersion) {
            activeScope.flatMap {
                it.probes
            }.writeCoverage(executionDataWriter, classBytes)
        }
    }
    ActionResult(StatusCodes.OK, coverage)
}.getOrElse {
    logger.error(it) { "Can't get coverage. Reason:" }
    ActionResult(StatusCodes.BAD_REQUEST, "Can't get coverage.")
}

private fun Sequence<ExecClassData>.writeCoverage(
    executionDataWriter: ExecutionDataWriter,
    classBytes: Map<String, ByteArray>,
) = forEach { execClassData ->
    executionDataWriter.visitClassExecution(
        ExecutionData(
            classBytes[execClassData.className]?.crc64() ?: execClassData.id(),
            execClassData.className,
            execClassData.probes.toBooleanArray()
        )
    )
}
