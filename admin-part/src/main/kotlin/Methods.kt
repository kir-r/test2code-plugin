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

import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.kodux.*
import kotlinx.serialization.*
import kotlin.jvm.Transient

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
    @Id val buildVersion: String,
    val hash: Map<String, Map<String, String>> = emptyMap(),
)

internal typealias TypedRisks = Map<RiskType, List<Method>>

internal fun List<Method>.diff(otherMethods: List<Method>): DiffMethods = if (any()) {
    if (otherMethods.any()) {
        val new = mutableListOf<Method>()
        val modified = mutableListOf<Method>()
        val deleted = mutableListOf<Method>()
        val unaffected = mutableListOf<Method>()
        val otherItr = otherMethods.iterator()
        iterator().run {
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
                                } ?: modified).addMethod(left)
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
    if ("lambda" !in value.name) add(value)
}

internal fun BuildMethods.toSummaryDto() = MethodsSummaryDto(
    all = totalMethods.run { Count(coveredCount, totalCount).toDto() },
    new = newMethods.run { Count(coveredCount, totalCount).toDto() },
    modified = allModifiedMethods.run { Count(coveredCount, totalCount).toDto() },
    unaffected = unaffectedMethods.run { Count(coveredCount, totalCount).toDto() },
    deleted = deletedMethods.run { Count(coveredCount, totalCount).toDto() }
)

internal val CoverContext.risks: TypedRisks
    get() = methodChanges.run {
        mapOf(
            RiskType.NEW to new,
            RiskType.MODIFIED to modified
        )
    }

internal fun TypedRisks.toCounts() = RiskCounts(
    new = this[RiskType.NEW]?.count() ?: 0,
    modified = this[RiskType.MODIFIED]?.count() ?: 0
).run { copy(total = new + modified) }

internal fun TypedRisks.filter(
    predicate: (Method) -> Boolean,
): TypedRisks = asSequence().mapNotNull { (type, testData) ->
    val filtered = testData.filter { predicate(it) }
    filtered.takeIf { it.any() }?.let { type to it }
}.toMap()

internal fun TypedRisks.notCovered(
    bundleCounter: BundleCounter,
) = bundleCounter.coveredMethods(values.flatten()).let { covered ->
    filter { method -> method !in covered }
}

internal fun TypedRisks.count() = values.sumBy { it.count() }

internal fun CoverContext.risksDto(
    associatedTests: Map<CoverageKey, List<TypedTest>> = emptyMap()
): List<RiskDto> = build.bundleCounters.all.coveredMethods(risks.values.flatten()).let { covered ->
    risks.flatMap { (type, methods) ->
        methods.map { method ->
            val count = covered[method] ?: zeroCount
            val id = method.key.crc64
            RiskDto(
                id = id,
                type = type,
                ownerClass = method.ownerClass,
                name = method.name,
                desc = method.desc,
                coverage = count.percentage(),
                count = count.toDto(),
                coverageRate = count.coverageRate(),
                assocTestsCount = associatedTests[CoverageKey(id)]?.count() ?: 0,
            )
        }
    }
}

internal fun Map<Method, CoverMethod>.toSummary(
    typedTest: TypedTest,
    context: CoverContext,
) = TestedMethodsSummary(
    id = typedTest.id(),
    testName = typedTest.name,
    testType = typedTest.type,
    methodCounts = CoveredMethodCounts(
        all = size,
        modified = context.methodChanges.modified.count { it in this },
        new = context.methodChanges.new.count { it in this },
        unaffected = context.methodChanges.unaffected.count { it in this }
    )
)

internal fun Map<Method, CoverMethod>.filterValues(
    predicate: (Method) -> Boolean
) = filter { predicate(it.key) }.values.toList()

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) {
    next()
} else null
