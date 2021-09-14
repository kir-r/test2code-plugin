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
package com.epam.drill.plugins.test2code.common.api

import kotlinx.serialization.*

@Serializable
data class AstEntity(
    val path: String,
    val name: String,
    val methods: List<AstMethod>
)

@Serializable
data class AstMethod(
    val name: String,
    val params: List<String>,
    val returnType: String,
    val count: Int = 0,
    val probes: List<Int> = emptyList(),
    val checksum: String = ""
)

@Serializable
data class InitScopePayload(
    val id: String,
    val name: String,
    val prevId: String
)

@Serializable
data class StartSessionPayload(
    val sessionId: String,
    val testType: String,
    val testName: String?,
    val isRealtime: Boolean,
    val isGlobal: Boolean
)

@Serializable
data class AgentSessionPayload(
    val sessionId: String
)

@Serializable
data class AgentSessionDataPayload(val sessionId: String, val data: String)

@Serializable
data class AgentSessionTestsPayload(val sessionId: String, val tests: List<String>)

@Serializable
data class ExecClassData(
    val id: Long? = null,
    val className: String,
    @Serializable(with = BitSetSerializer::class)
    val probes: Probes,
    val testName: String = ""
)


