package com.epam.drill.plugins.test2code.jvm

import com.epam.drill.plugins.test2code.*
import com.epam.drill.plugins.test2code.api.*
import com.epam.drill.plugins.test2code.coverage.*
import org.jacoco.core.internal.data.*

data class ParsedClassBytes(
    val methods: List<Method>,
    val probeIds: Map<String, Long>,
    val packageTree: PackageTree,
)

fun Map<String, ByteArray>.parseClassBytes(): ParsedClassBytes = run {
    com.epam.drill.plugins.test2code.util.logger.info { "initializing noData with classBytes size ${size}..." }
    val probeIds: Map<String, Long> = mapValues { CRC64.classId(it.value) }
    val bundleCoverage = keys.bundle(this, probeIds)
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
        val bytes = getValue(name)
        name to classCounter.parseMethods(bytes).sorted()
    }
    val methods = groupedMethods.flatMap { it.value }
    val packages = sortedPackages.toPackages(groupedMethods)
    ParsedClassBytes(
        methods = methods,
        probeIds = probeIds,
        packageTree = PackageTree(
            totalCount = packages.sumBy { it.totalCount },
            totalMethodCount = groupedMethods.values.sumBy { it.count() },
            totalClassCount = packages.sumBy { it.totalClassesCount },
            packages = packages,
        ),
    )
}