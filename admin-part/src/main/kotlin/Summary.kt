package com.epam.drill.plugins.test2code

import com.epam.kodux.*

private val emptySummary = SummaryDto(
    coverage = 0.0,
    arrow = null,
    risks = 0,
    testsToRun = 0
)

suspend fun StoreClient.summaryOf(agentid: String, buildVersion: String): SummaryDto {
    return readLastBuildCoverage(agentid, buildVersion)?.toSummary() ?: emptySummary
}

private fun LastBuildCoverage.toSummary() = SummaryDto(
    coverage = coverage,
    arrow = arrow?.let { ArrowType.valueOf(it) },
    risks = risks,
    testsToRun = testsToRun
)
