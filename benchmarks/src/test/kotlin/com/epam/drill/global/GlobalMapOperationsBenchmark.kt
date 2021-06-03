//package com.epam.drill.global
//
//import kotlinx.benchmark.*
//import kotlinx.benchmark.Scope
//import kotlinx.collections.immutable.*
//import org.openjdk.jmh.annotations.*
//import org.openjdk.jmh.annotations.Benchmark
//import org.openjdk.jmh.annotations.BenchmarkMode
//import org.openjdk.jmh.annotations.Measurement
//import org.openjdk.jmh.annotations.Mode
//import org.openjdk.jmh.annotations.OutputTimeUnit
//import org.openjdk.jmh.annotations.State
//import org.openjdk.jmh.annotations.Warmup
//import java.util.HashMap
//import java.util.concurrent.*
//import java.util.concurrent.atomic.*
//
//@Warmup(iterations = 10, time = 1)
//@Measurement(iterations = 10, time = 1)
//@BenchmarkMode(Mode.Throughput)
//@OutputTimeUnit(TimeUnit.MILLISECONDS)
//@State(Scope.Benchmark)
//class GlobalMapOperationsBenchmark {
//    val atomPersistentMap = AtomicReference(persistentMapOf<Int, Int>())
//    val concurrentHashMap = ConcurrentHashMap<Int, Int>()
//    val hashMap = HashMap<Int, Int>()
//
//    private val size = 1000000
//
//
//    @org.openjdk.jmh.annotations.Benchmark
//    @Threads(Threads.MAX)
//    fun mapConcurrent(blh: Blackhole) {
//        (0 until size).forEach {
//            concurrentHashMap[it] = it
//            blh.consume(concurrentHashMap[it])
//        }
//    }
//
//    @Benchmark
//    @Threads(Threads.MAX)
//    fun mapDirect(blh: Blackhole) {
//        (0 until size).forEach {
//            hashMap[it] = it
//            blh.consume(hashMap[it])
//        }
//    }
//
//
//    @Benchmark
//    @Threads(Threads.MAX)
//    fun mapPersistentAtomic(blh: Blackhole) {
//        (0 until size).forEach {
//            atomPersistentMap.update { x ->
//                x.put(it, it)
//            }
//            blh.consume(atomPersistentMap.get()[it])
//        }
//    }
//}
//
//
//inline fun <T> AtomicReference<T>.update(function: (T) -> T) {
//    while (true) {
//        val cur = get()
//        val upd = function(cur)
//        if (compareAndSet(cur, upd)) return
//    }
//}
