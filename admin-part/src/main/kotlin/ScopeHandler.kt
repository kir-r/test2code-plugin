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
import com.epam.drill.plugins.test2code.api.routes.*
import com.epam.drill.plugins.test2code.common.api.*
import com.epam.drill.plugins.test2code.coverage.*
import com.epam.drill.plugins.test2code.util.*
import com.epam.kodux.util.*
import kotlinx.atomicfu.*

internal fun Plugin.initActiveScope(): Boolean = activeScope.initScopeHandler { sessionChanged, sessions ->
    if (sessionChanged) {
        sendActiveSessions()
    }
    sessions?.let {
        val context = state.coverContext()
        val bundleCounters = trackTime("bundleCounters") {
            sessions.calcBundleCounters(context, adminData.loadClassBytes())
        }.also { logPoolStats() }
        val coverageInfoSet = trackTime("coverageInfoSet") {
            bundleCounters.calculateCoverageData(context, this)
        }.also { logPoolStats() }
        updateSummary { it.copy(coverage = coverageInfoSet.coverage as ScopeCoverage) }
        sendActiveScope()
        coverageInfoSet.sendScopeCoverage(buildVersion, id)
        if (sessionChanged) {
            bundleCounters.assocTestsJob(this)
            bundleCounters.byTest.coveredMethodsJob(id)
        }
    }
}

fun Plugin.initSessionHandler(): ActiveSessionHandler = { tests ->
    val context = state.coverContext()
    val bytes = classBytes.value ?: adminData.loadClassBytes().also { bytes -> classBytes.updateAndGet { bytes } }
    val preparedBundle = tests.keys.associateWithTo(mutableMapOf()) {
        BundleCounter.empty
    }
    val calculated = tests.mapValuesTo(preparedBundle) {
        it.value.bundle(context, bytes)
    }
    bundleByTests.putAll(calculated)
}


internal suspend fun Plugin.changeActiveScope(
    scopeChange: ActiveScopeChangePayload,
): ActionResult = if (state.scopeByName(scopeChange.scopeName) == null) {
    trackTime("changeActiveScope") {
        val prevScope = state.changeActiveScope(scopeChange.scopeName.trim())
        state.storeActiveScopeInfo()
        sendActiveSessions()
        sendActiveScope()
        if (scopeChange.savePrevScope) {
            if (prevScope.any()) {
                logger.debug { "finish scope with id=${prevScope.id}" }.also { logPoolStats() }
                val finishedScope = prevScope.finish(scopeChange.prevScopeEnabled)
                state.scopeManager.store(finishedScope)
                sendScopeSummary(finishedScope.summary)
                logger.info { "$finishedScope has been saved." }.also { logPoolStats() }
            } else {
                cleanTopics(prevScope.id)
                logger.info { "$prevScope is empty, it won't be added to the build." }.also { logPoolStats() }
            }
        } else cleanTopics(prevScope.id)
        InitActiveScope(
            payload = InitScopePayload(
                id = activeScope.id,
                name = activeScope.name,
                prevId = prevScope.id
            )
        ).toActionResult()
    }
} else ActionResult(
    code = StatusCodes.CONFLICT,
    data = "Failed to switch to a new scope: name ${scopeChange.scopeName} is already in use"
)

internal suspend fun Plugin.scopeInitialized(prevId: String) {
    if (initActiveScope()) {
        sendScopes()
        val prevScope = state.scopeManager.byId(prevId)
        prevScope?.takeIf { it.enabled }.apply {
            calculateAndSendBuildCoverage()
        }
        calculateAndSendScopeCoverage()
        logger.info { "Current active scope - $activeScope" }.also { logPoolStats() }
    }
}

internal suspend fun Plugin.renameScope(
    payload: RenameScopePayload,
): ActionResult = state.scopeById(payload.scopeId)?.let { scope ->
    val scopeName = payload.scopeName
    if (state.scopeByName(scopeName) == null) {
        state.renameScope(payload.scopeId, scopeName)?.let { summary ->
            sendScopeMessages(scope.buildVersion)
            sendScopeSummary(summary, scope.buildVersion)
        }
        ActionResult(StatusCodes.OK, "Renamed scope with id ${payload.scopeId} -> $scopeName")
    } else ActionResult(
        StatusCodes.CONFLICT,
        "Scope with such name already exists. Please choose a different name."
    )
} ?: ActionResult(
    StatusCodes.NOT_FOUND,
    "Failed to rename scope with id ${payload.scopeId}: scope not found"
)

internal suspend fun Plugin.toggleScope(scopeId: String): ActionResult {
    state.toggleScope(scopeId)
    return state.scopeManager.byId(scopeId)?.let { scope ->
        handleChange(scope)
        ActionResult(
            StatusCodes.OK,
            "Scope with id $scopeId toggled to 'enabled' value '${scope.enabled}'"
        )
    } ?: ActionResult(
        StatusCodes.NOT_FOUND,
        "Failed to toggle scope with id $scopeId: scope not found"
    )
}

internal suspend fun Plugin.dropScope(scopeId: String): ActionResult {
    return state.scopeManager.deleteById(scopeId)?.let { scope ->
        cleanTopics(scope.id)
        handleChange(scope)
        ActionResult(
            StatusCodes.OK,
            "Scope with id $scopeId was removed"
        )
    } ?: ActionResult(
        StatusCodes.NOT_FOUND,
        "Failed to drop scope with id $scopeId: scope not found"
    )
}

private suspend fun Plugin.cleanTopics(scopeId: String) = scopeById(scopeId).let { scope ->
    val coverageRoute = Routes.Build.Scopes.Scope.Coverage(scope)
    send(buildVersion, coverageRoute, "")
    state.classDataOrNull()?.let { classData ->
        val pkgsRoute = Routes.Build.Scopes.Scope.Coverage.Packages(coverageRoute)
        classData.packageTree.packages.forEach { p ->
            send(buildVersion, Routes.Build.Scopes.Scope.Coverage.Packages.Package(p.name, pkgsRoute), "")
            send(buildVersion, Routes.Build.Scopes.Scope.AssociatedTests(p.id, scope), "")
            p.classes.forEach { c ->
                send(buildVersion, Routes.Build.Scopes.Scope.AssociatedTests(c.id, scope), "")
                c.methods.forEach { m ->
                    send(buildVersion, Routes.Build.Scopes.Scope.AssociatedTests(m.id, scope), "")
                }
            }
        }
    }
    send(buildVersion, Routes.Build.Scopes.Scope.Tests(scope), "")
}

private suspend fun Plugin.handleChange(scope: FinishedScope) {
    if (scope.buildVersion == buildVersion) {
        calculateAndSendBuildCoverage()
        activeScope.probesChanged()
    }
    sendScopes(scope.buildVersion)
    sendScopeSummary(scope.summary, scope.buildVersion)
}
