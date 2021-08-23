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
import com.epam.drill.plugins.test2code.storage.*
import com.epam.kodux.*
import kotlinx.serialization.*

@Serializable
internal data class Method(
    val ownerClass: String,
    val name: String,
    val desc: String,
    val hash: String,
) : Comparable<Method>, JvmSerializable {
    val signature = "$name$desc".intern()
    val key = "$ownerClass:$signature".intern()
    override fun compareTo(
        other: Method,
    ): Int = ownerClass.compareTo(other.ownerClass).takeIf {
        it != 0
    } ?: name.compareTo(other.name).takeIf {
        it != 0
    } ?: desc.compareTo(other.desc)
}

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
                    new.add(left)
                }
                while (lastRight != null) {
                    val right = lastRight
                    val cmp = left.compareTo(right)
                    if (cmp <= 0) {
                        when {
                            cmp == 0 -> {
                                (unaffected.takeIf { left.hash == right.hash } ?: modified).add(left)
                                lastRight = otherItr.nextOrNull()
                            }
                            cmp < 0 -> {
                                new.add(left)
                            }
                        }
                        break
                    }
                    deleted.add(right)
                    lastRight = otherItr.nextOrNull()
                    if (lastRight == null) {
                        new.add(left)
                    }
                }
            }
            lastRight?.let { deleted.add(it) }
            while (otherItr.hasNext()) {
                deleted.add(otherItr.next())
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

internal fun BuildMethods.toSummaryDto() = MethodsSummaryDto(
    all = totalMethods.run { Count(coveredCount, totalCount).toDto() },
    new = newMethods.run { Count(coveredCount, totalCount).toDto() },
    modified = allModifiedMethods.run { Count(coveredCount, totalCount).toDto() },
    unaffected = unaffectedMethods.run { Count(coveredCount, totalCount).toDto() },
    deleted = deletedMethods.run { Count(coveredCount, totalCount).toDto() }
)

internal suspend fun StoreClient.calculateRisks(
    diffMethods: DiffMethods,
    bundleCounter: BundleCounter,
    baseline: String,
): TypedRisks = bundleCounter.coveredMethods(diffMethods.new + diffMethods.modified).let { covered ->
    val (newCovered, newUncovered) = diffMethods.new.partition { it in covered }
    val (modifiedCovered, modifiedUncovered) = diffMethods.modified.partition { it in covered }
    val baselineCoveredRisks = loadRisksByBaseline(baseline).let { risks ->
        val coveredRisks = (risks.covered + (newCovered + modifiedCovered).map {
            Risk(it, RiskStatus.COVERED)
        }).distinct()
        val coveredMethods = coveredRisks.map { it.method }
        val uncoveredRisks = (risks.uncovered.filter {
            it.method in coveredMethods
        } + (newUncovered + modifiedUncovered).map {
            Risk(it)
        }).distinct()
        store(
            risks.copy(
                covered = coveredRisks,
                uncovered = uncoveredRisks
            )
        )
        coveredMethods
    }
    mapOf(
        RiskType.NEW to newUncovered.filter { it !in baselineCoveredRisks },
        RiskType.MODIFIED to modifiedUncovered.filter { it !in baselineCoveredRisks }
    )
}

internal fun TypedRisks.toCounts() = RiskCounts(
    new = this[RiskType.NEW]?.count() ?: 0,
    modified = this[RiskType.MODIFIED]?.count() ?: 0
).run { copy(total = new + modified) }

internal fun TypedRisks.toListDto(): List<RiskDto> = flatMap { (type, methods) ->
    methods.map { method ->
        RiskDto(
            type = type,
            ownerClass = method.ownerClass,
            name = method.name,
            desc = method.desc
        )
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
    predicate: (Method) -> Boolean,
) = filter { predicate(it.key) }.values.toList()

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) {
    next()
} else null
