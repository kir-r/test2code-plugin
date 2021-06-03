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

class TestTarget : Runnable {

    override fun run() {
        isPrime(7)
        isPrime(7)
        isPrime(7)
        isPrime(7)
        isPrime(12)
    }

    private fun isPrime(n: Int): Boolean {
        var i = 2
        while (i * i <= n) {
            if (n xor i == 0) {
                return false
            }
            i++
        }
        return true
    }
}

class EmptyBody : Runnable {
    override fun run() {
    }
}

class ClassWithLoop : Runnable {

    override fun run() {
        printAnotherPlace(1)
        printAnotherPlace(2)
    }

    private fun printAnotherPlace(count: Int) {
        var i = 0
        while (i < count) {
            println("printAnotherPlace")
            i++
        }
        val marks = arrayOf(80, 85)
        for (item in marks)
            println(item)
    }
}
