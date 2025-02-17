/**
 * Copyright 2020 - 2022 EPAM Systems
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
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.drill.plugins.test2code.jvm.*
import com.epam.drill.plugins.test2code.storage.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.dsm.*
import kotlinx.serialization.*
import kotlin.math.*

@Serializable
data class Method(
    val ownerClass: String,
    val name: String,
    val desc: String,
    val hash: String,
    @Transient
    val lambdasHash: Map<String, String> = emptyMap(),
) : Comparable<Method> {
    val signature = signature(ownerClass, name, desc).intern()
    val key = fullMethodName(ownerClass, name, desc).intern()
    override fun compareTo(
        other: Method,
    ): Int = ownerClass.compareTo(other.ownerClass).takeIf {
        it != 0
    } ?: name.compareTo(other.name).takeIf {
        it != 0
    } ?: desc.compareTo(other.desc)
}

@Serializable
internal data class LambdaHash(
    @Id val agentKey: AgentKey,
    val hash: Map<String, Map<String, String>> = emptyMap(),
)

internal typealias TypedRisks = Map<RiskType, List<Risk>>

/**
 * Methods are sorted to improve performance of difference calculation
 */
internal fun List<Method>.diff(otherMethods: List<Method>): DiffMethods = if (any()) {
    if (otherMethods.any()) {
        val new = mutableListOf<Method>()
        val modified = mutableListOf<Method>()
        val deleted = mutableListOf<Method>()
        val unaffected = mutableListOf<Method>()
        val otherItr = otherMethods.sorted().iterator()
        sorted().iterator().run {
            var lastRight: Method? = otherItr.next()
            while (hasNext()) {
                val left = next()
                if (lastRight == null) {
                    new.addMethod(left)
                }
                while (lastRight != null) {
                    val right = lastRight
                    val cmp = left.compareTo(right)
                    if (cmp <= 0) {
                        when {
                            cmp == 0 -> {
                                (unaffected.takeIf {
                                    left.hash == right.hash
                                            && left.lambdasHash.all { right.lambdasHash.containsValue(it.value) }
                                } ?: modified).add(left)
                                lastRight = otherItr.nextOrNull()
                            }
                            cmp < 0 -> {
                                new.addMethod(left)
                            }
                        }
                        break
                    }
                    deleted.addMethod(right)
                    lastRight = otherItr.nextOrNull()
                    if (lastRight == null) {
                        new.addMethod(left)
                    }
                }
            }
            lastRight?.let { deleted.addMethod(it) }
            while (otherItr.hasNext()) {
                deleted.addMethod(otherItr.next())
            }
        }
        DiffMethods(
            new = new,
            modified = modified,
            deleted = deleted,
            unaffected = unaffected
        )
    } else DiffMethods(new = this)
} else DiffMethods(deleted = otherMethods)

private fun MutableList<Method>.addMethod(value: Method) {
    if (LAMBDA !in value.name) add(value)
}

internal fun BuildMethods.toSummaryDto() = MethodsSummaryDto(
    all = totalMethods.run { Count(coveredCount, totalCount).toDto() },
    new = newMethods.run { Count(coveredCount, totalCount).toDto() },
    modified = allModifiedMethods.run { Count(coveredCount, totalCount).toDto() },
    unaffected = unaffectedMethods.run { Count(coveredCount, totalCount).toDto() },
    deleted = deletedMethods.run { Count(coveredCount, totalCount).toDto() }
)

/**
 * 1. Check whether new/modified methods have coverage
 * 2. Store covered/uncovered methods for this baseline (for example method «foo» was covered in 0.2.0 build for 0.1.0 baseline)
 * 3. Filter risks for current build compare with stored baseline risks
 */
internal suspend fun CoverContext.calculateRisks(
    storeClient: StoreClient,
    bundleCounter: BundleCounter = build.bundleCounters.all,
): TypedRisks = bundleCounter.coveredMethods(methodChanges.new + methodChanges.modified).let { covered ->
    val buildVersion = build.agentKey.buildVersion
    val baselineBuild = parentBuild?.agentKey ?: build.agentKey

    val baselineCoveredRisks = storeClient.loadRisksByBaseline(baselineBuild)
    val riskByMethod = baselineCoveredRisks.risks.associateByTo(mutableMapOf()) { it.method }
    val (newCovered, newUncovered) = methodChanges.new.partitionByCoverage(covered)
    val (modifiedCovered, modifiedUncovered) = methodChanges.modified.partitionByCoverage(covered)

    riskByMethod.putRisks(newCovered + modifiedCovered, buildVersion, RiskStatus.COVERED)
    riskByMethod.putRisks(newUncovered + modifiedUncovered, buildVersion, RiskStatus.NOT_COVERED)

    val totalBaselineRisks = riskByMethod.values.toSet()

    storeClient.store(baselineCoveredRisks.copy(risks = totalBaselineRisks))

    mapOf(
        RiskType.NEW to totalBaselineRisks.filter { it.method in methodChanges.new },
        RiskType.MODIFIED to totalBaselineRisks.filter { it.method in methodChanges.modified }
    )
}

internal fun List<Method>.partitionByCoverage(
    covered: Map<Method, Count>,
) = map { it to (covered[it] ?: zeroCount) }.partition { it.first in covered }

internal fun MutableMap<Method, Risk>.putRisks(
    methods: List<Pair<Method, Count>>,
    buildVersion: String,
    status: RiskStatus,
) = methods.forEach { (method, coverage) ->
    get(method)?.let { risk ->
        val highestCoverage = max(risk.coverage, coverage)
        put(method, risk.copy(coverage = highestCoverage, status = risk.status + (buildVersion to status)))
    } ?: put(method, Risk(method, coverage, mapOf(buildVersion to status)))
}

internal fun TypedRisks.toCounts() = RiskCounts(
    new = this[RiskType.NEW]?.count() ?: 0,
    modified = this[RiskType.MODIFIED]?.count() ?: 0
).run { copy(total = new + modified) }

internal fun TypedRisks.notCovered() = asSequence().mapNotNull { (type, risks) ->
    val uncovered = risks.filter { RiskStatus.COVERED !in it.status.values }
    uncovered.takeIf { it.any() }?.let { type to it }
}.toMap()

internal fun TypedRisks.count() = values.sumOf { it.count() }

internal suspend fun CoverContext.risksDto(
    storeClient: StoreClient,
    associatedTests: Map<CoverageKey, List<TypedTest>> = emptyMap(),
): List<RiskDto> = calculateRisks(storeClient).flatMap { (type, risks) ->
    risks.map { risk ->
        val count = risk.coverage
        val id = risk.method.key.crc64
        RiskDto(
            id = id,
            type = type,
            ownerClass = risk.method.ownerClass,
            name = risk.method.name,
            desc = risk.method.desc,
            coverage = count.percentage(),
            count = count.toDto(),
            status = risk.status,
            coverageRate = count.coverageRate(),
            assocTestsCount = associatedTests[CoverageKey(id)]?.count() ?: 0,
        )
    }
}


internal fun Map<Method, CoverMethod>.toSummary(
    id: String,
    typedTest: TypedTest,
    context: CoverContext,
) = TestedMethodsSummary(
    id = id,
    testName = typedTest.details,
    testType = typedTest.type,
    methodCounts = CoveredMethodCounts(
        all = size,
        modified = context.methodChanges.modified.count { it in this },
        new = context.methodChanges.new.count { it in this },
        unaffected = context.methodChanges.unaffected.count { it in this }
    )
)

internal fun Map<Method, CoverMethod>.filterValues(
    predicate: (Method) -> Boolean,
) = filter { predicate(it.key) }.values.toList()

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) {
    next()
} else null
