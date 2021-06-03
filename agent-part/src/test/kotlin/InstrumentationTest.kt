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

import com.epam.drill.plugins.test2code.InstrumentationForTest.Companion.sessionId
import org.junit.jupiter.api.*
import test.*
import java.io.*
import kotlin.test.*
import kotlin.test.Test

class InstrumentationTest {

    @Test
    fun `instrumented class should be larger the the original`() {
        val instrumentation = InstrumentationForTest(TestTarget::class)
        val instrumentedBytes = instrumentation.instrumentClass()
        File("xx.class").writeBytes(instrumentedBytes)
        assertTrue { instrumentedBytes.count() > instrumentation.originalBytes.count() }
    }

    @Test
    fun `should provide coverage with the instrumented class of empty body`() {
        val counter = InstrumentationForTest(EmptyBody::class).collectCoverage()
        assertEquals(4, counter?.coveredCount)
        assertEquals(0, counter?.missedCount)
    }

    @Test
    fun `should provide coverage with the instrumented class with loops`() {
        val counter = InstrumentationForTest(ClassWithLoop::class).collectCoverage()
        assertEquals(60, counter?.coveredCount)
        assertEquals(0, counter?.missedCount)
    }

    @Test
    fun `should provide coverage for run with the instrumented class`() {
        val counter = InstrumentationForTest(TestTarget::class).collectCoverage()
        assertEquals(27, counter?.coveredCount)
        assertEquals(2, counter?.missedCount)
    }

    @Test
    fun `should provide coverage with the Java instrumented with empty methods`() {
        val counter = InstrumentationForTest(ClassWithVoid::class).collectCoverage()
        println("covered ${counter?.coveredCount} missed: ${counter?.missedCount}")
        assertEquals(4, counter?.coveredCount)
        assertEquals(5, counter?.missedCount)
    }

    @Test
    fun `should provide coverage with the Java instrumented with timeout`() {
        val counter = InstrumentationForTest(ClassWithTimeout::class).collectCoverage()
        assertEquals(13, counter?.coveredCount)//todo why coveredCount=0 if set timeout on 2 sec?
        assertEquals(3, counter?.missedCount)
    }

    @Test
    fun `should associate execution data with test name and type gathered from request headers`() {
        val instrumentation = InstrumentationForTest(TestTarget::class)
        val instrumentedClass = instrumentation.instrumentedClass
        InstrumentationForTest.TestProbeArrayProvider.start(sessionId, false)
        @Suppress("DEPRECATION") val runnable = instrumentedClass.newInstance() as Runnable
        runnable.run()
        val runtimeData = InstrumentationForTest.TestProbeArrayProvider.stop(sessionId)!!.toList()
        val assertions = runtimeData.map { execDatum ->
            { assertEquals("test", execDatum.testName) }
        }.toTypedArray()
        assertAll(*assertions)
    }

    @Test
    fun `should create instance and invoke instrumented class`() {
        val instrumentation = InstrumentationForTest(TestTarget::class)
        instrumentation.runClass()
    }
}

