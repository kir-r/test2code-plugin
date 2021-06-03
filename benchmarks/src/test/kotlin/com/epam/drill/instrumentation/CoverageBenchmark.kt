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
package com.epam.drill.instrumentation

import com.epam.drill.instrumentation.data.*
import com.epam.drill.plugins.test2code.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.infra.*
import java.util.concurrent.*

@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 10, timeUnit = TimeUnit.MILLISECONDS)
class CoverageBenchmark {

    val instrumentation = InstrumentationForTest(WtfClass::class)
        .apply {
            InstrumentationForTest.TestProbeArrayProvider.start(InstrumentationForTest.sessionId, false)
        }

    val times = 9000000

//    val arr = Array<Int>(times) { it }
//    val arrLists =arr.toList()
//    val arrList = ArrayList<Int>(arr.toList())
//    val copyOnWriteArrayList = CopyOnWriteArrayList(arr.toList())
//    val mp = arr.associateWith { it }

//    @Benchmark
//    @BenchmarkMode(Mode.Throughput)
//    @Threads(Threads.MAX)
//    fun arrayAccess(blackhole: Blackhole) {
//        (0 until times).forEach {
//            blackhole.consume(arr[it])
//        }
//    }
//
//    @Benchmark
//    @BenchmarkMode(Mode.Throughput)
//    @Threads(Threads.MAX)
//    fun copyOnWriteArrayList(blackhole: Blackhole) {
//        (0 until times).forEach {
//            blackhole.consume(copyOnWriteArrayList[it])
//        }
//    }
//
//    @Benchmark
//    @BenchmarkMode(Mode.Throughput)
//    @Threads(Threads.MAX)
//    fun arrayListSAccess(blackhole: Blackhole) {
//        (0 until times).forEach {
//            blackhole.consume(arrLists[it])
//        }
//    }
//
//    @Benchmark
//    @BenchmarkMode(Mode.Throughput)
//    @Threads(Threads.MAX)
//    fun arrayListAccess(blackhole: Blackhole) {
//        (0 until times).forEach {
//            blackhole.consume(arrList[it])
//        }
//    }
//
//    @Benchmark
//    @BenchmarkMode(Mode.Throughput)
//    @Threads(Threads.MAX)
//    fun mapAccess(blackhole: Blackhole) {
//        (0 until times).forEach {
//            blackhole.consume(mp[it])
//        }
//    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(Threads.MAX)
    fun pureClassTest() {

        repeat(times) {
            instrumentation.runNonInstrumentedClass()
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @Threads(Threads.MAX)
    fun instrumentedClassTest() {
        repeat(times) {
            instrumentation.xx()
        }
    }

}
