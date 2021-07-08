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
package com.epam.drill.plugins.test2code.jvm

import com.epam.drill.jacoco.*
import com.epam.drill.plugins.test2code.*
import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.kodux.util.*
import org.jacoco.core.analysis.*
import org.jacoco.core.data.*
import org.jacoco.core.internal.analysis.*
import java.util.concurrent.*

private val logger = logger {}

internal typealias ClassBytes = Map<String, ByteArray>

internal fun Sequence<ExecClassData>.bundle(
    probeIds: Map<String, Long>,
    classBytes: ClassBytes,
): BundleCounter = bundle(probeIds) { analyzer ->
    contents.parallelStream().forEach { execData ->
        classBytes[execData.name]?.let { classesBytes ->
            runCatching {
                analyzer.analyzeClass(classesBytes, execData.name)
            }.onFailure {
                logger.error(it) { "Error while analyzing ${execData.name}." }
            }
        } ?: println("WARN No class data for ${execData.name}, id=${execData.id}")
    }
}.toCounter()

internal fun Iterable<String>.bundle(
    classBytes: Map<String, ByteArray>,
    probeIds: Map<String, Long>,
): BundleCounter = emptySequence<ExecClassData>().bundle(probeIds) { analyzer ->
    forEach { name -> analyzer.analyzeClass(classBytes.getValue(name), name) }
}.toCounter(false)

private fun Sequence<ExecClassData>.bundle(
    probeIds: Map<String, Long>,
    analyze: ExecutionDataStore.(Analyzer) -> Unit,
): IBundleCoverage = CustomCoverageBuilder().also { coverageBuilder ->
    val dataStore = execDataStore(probeIds)
    val analyzer = threadSafeAnalyzer(dataStore, coverageBuilder)
    dataStore.analyze(analyzer)
}.getBundle("")


internal fun Sequence<ExecClassData>.execDataStore(
    probeIds: Map<String, Long>,
): ExecutionDataStore = mapNotNull {
    it.toExecutionData(probeIds)
}.fold(ExecutionDataStore()) { store, execData ->
    store.apply {
        runCatching { put(execData) }.onFailure { e ->
            val expected = store[execData.id]?.probes?.size
            logger.error {
                "Error adding ${execData}, probes=(${execData.probes?.size}), expected size $expected"
            }
        }
    }
}

internal fun IBundleCoverage.toCounter(filter: Boolean = true) = BundleCounter(
    name = "",
    count = instructionCounter.toCount(),
    methodCount = methodCounter.toCount(),
    classCount = classCounter.toCount(),
    packageCount = packages.run { Count(count { it.classCounter.coveredCount > 0 }, count()) },
    packages = packages.mapNotNull { p ->
        val classesWithMethods = p.classes.filter { c ->
            c.methods.any().also {
                if (!it) {
                    logger.warn { "Class without methods - ${c.name}." }
                }
            } && c.methods.takeIf { filter }?.any { it.instructionCounter.coveredCount > 0 } ?: true
        }
        if (classesWithMethods.any()) {
            PackageCounter(
                name = p.name.weakIntern(),
                count = p.instructionCounter.toCount(),
                classCount = p.classCounter.toCount(),
                methodCount = p.methodCounter.toCount(),
                classes = classesWithMethods.map { c ->
                    val classFullName = c.name.weakIntern()
                    ClassCounter(
                        path = p.name.weakIntern(),
                        name = c.name.toShortClassName(),
                        count = c.instructionCounter.toCount(),
                        fullName = classFullName,
                        methods = c.methods.map { m ->
                            val sign = "${m.name}${m.desc}".weakIntern()
                            MethodCounter(
                                name = m.name.weakIntern(),
                                desc = m.desc.weakIntern(),
                                decl = m.desc.weakIntern(),//declaration(m.desc), //TODO Regex has a big impact on performance
                                sign = sign,
                                fullName = "$classFullName:$sign".weakIntern(),
                                count = m.instructionCounter.toCount()
                            )
                        }
                    )
                }.run { takeIf { filter }?.filter { it.count.covered > 0 } ?: this }
            )
        } else null
    }
)

fun ICoverageNode.coverage(total: Int = instructionCounter.totalCount): Double =
    instructionCounter.coveredCount percentOf total

internal fun ICounter.toCount(total: Int = totalCount) = Count(covered = coveredCount, total = total)

/**
 * Converts ASM method description to declaration in java style with kotlin style of return type.
 *
 * Examples:
 * - ()V -> (): void
 * - (IZ)V -> (int, boolean): void
 * - (ILjava/lang/String;)V -> (int, String): void
 * - ([Ljava/lang/String;IJ)V -> (String[], int, long): void
 * - ([[IJLjava/lang/String;)Ljava/lang/String; -> (int[][], long, String): String
 * @param desc
 * ASM method description
 * @return declaration in java style with kotlin style of return type
 *
 */
fun declaration(desc: String): String {
    return """\((.*)\)(.+)""".toRegex().matchEntire(desc)?.run {
        val argDesc = groupValues[1]
        val returnTypeDesc = groupValues[2]
        val argTypes = parseDescTypes(argDesc).joinToString(separator = ", ")
        val returnType = parseDescTypes(returnTypeDesc).first()
        "($argTypes): $returnType"
    } ?: ""
}

fun parseDescTypes(argDesc: String): List<String> {
    val types = mutableListOf<String>()
    val descItr = argDesc.iterator()
    while (descItr.hasNext()) {
        val char = descItr.nextChar()
        val arg = parseDescType(char, descItr).weakIntern()
        types.add(arg)
    }
    return types
}

fun parseDescType(char: Char, charIterator: CharIterator): String = when (char) {
    'V' -> "void"
    'J' -> "long"
    'Z' -> "boolean"
    'I' -> "int"
    'F' -> "float"
    'B' -> "byte"
    'D' -> "double"
    'S' -> "short"
    'C' -> "char"
    '[' -> "${parseDescType(charIterator.nextChar(), charIterator)}[]"
    'L' -> {
        val objectDescSeq = charIterator.asSequence().takeWhile { it != ';' }
        val objectDesc = objectDescSeq.fold(StringBuilder()) { sBuilder, c -> sBuilder.append(c) }.toString()
        objectDesc.substringAfterLast("/")
    }
    else -> "!Error"
}

private fun ExecClassData.toExecutionData(probeIds: Map<String, Long>): ExecutionData? = probeIds[className]?.let {
    ExecutionData(it, className, probes.toBooleanArray())
}

private fun threadSafeAnalyzer(
    dataStore: ExecutionDataStore,
    coverageBuilder: CustomCoverageBuilder,
): Analyzer = Analyzer(dataStore, coverageBuilder).also { analyzer ->
    val newPool = StringPool()
    StringPool::class.java.getDeclaredField("pool").apply {
        isAccessible = true
        set(newPool, ConcurrentHashMap<String, String>())
        isAccessible = false
    }
    Analyzer::class.java.getDeclaredField("stringPool").apply {
        isAccessible = true
        set(analyzer, newPool)
        isAccessible = false
    }
}
