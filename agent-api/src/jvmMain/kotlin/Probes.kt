package com.epam.drill.plugins.test2code.common.api

import java.util.*

actual typealias Probes = BitSet

open class AgentProbes(size: Int = 0) {
    val bool = BooleanArray(size)

    open fun set(index: Int) {
        if (!bool[index])
            bool[index] = true
    }

    fun get(index: Int): Boolean {
        return bool[index]
    }

    fun reset() {
        (bool.indices).forEach {
            bool[it] = false
        }
    }

}

class StubAgentProbes(size: Int = 0) : AgentProbes(size) {
    override fun set(index: Int) {
    }

}
