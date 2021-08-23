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
package com.epam.drill.plugins.test2code.storage

import com.epam.drill.plugins.test2code.*
import com.epam.kodux.*
import kotlinx.serialization.*

@Serializable
internal data class Risk(
    val method: Method,
    val status: RiskStatus = RiskStatus.NOT_COVERED,
    //TODO Mb add additional info for instance "This risk covered in build 0.2.0"
)

@Serializable
internal data class BaselineRisks(
    @Id
    val baseline: String,
    val covered: List<Risk> = emptyList(), //TODO mb store in one map and filter on status / or stay this and remove status
    val uncovered: List<Risk> = emptyList(),
    //TODO half covered or smt like this
)

internal enum class RiskStatus {
    COVERED,
    NOT_COVERED
    //TODO half covered or smt like this
}

internal suspend fun StoreClient.loadRisksByBaseline(
    baseline: String,
) = findById(baseline) ?: store(BaselineRisks(baseline))

