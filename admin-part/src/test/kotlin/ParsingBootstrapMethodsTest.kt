package com.epam.drill.plugins.test2code

import com.epam.drill.bcel.test.*
import com.epam.drill.plugins.test2code.jvm.*
import org.apache.bcel.classfile.*
import org.junit.jupiter.api.Test
import java.io.*
import kotlin.test.*

class ParsingBootstrapMethodsTest {
    private val loader = this::class.java.classLoader
    private val instructionRegex = ".*invokedynamic\\t(\\d+).*".toRegex()

    private fun getParsedClass(className: String): JavaClass = run {
        val bytes = loader.loadClass(className).readBytes()
        val classParser = ClassParser(ByteArrayInputStream(bytes), className)
        classParser.parse()
    }

    @Test
    fun `method with lambdas`() {
        val clazz = getParsedClass(ClassWithLambda::class.java.name)
        val bootstrapMethods = clazz.getParsedBootstrapMethods()
        assertTrue { bootstrapMethods.any() }
        clazz.methods.forEach { method ->
            instructionRegex.findAll(method.code.toString(true)).forEach {
                val index = it.groupValues[1].toInt()
                assertNotNull(ClassWithLambda.methodsMap[method.name])
                assertTrue { ClassWithLambda.methodsMap[method.name]!! in bootstrapMethods[index] }
            }
        }
    }

    @Test
    fun `method with inner lambda`() {
        val clazz = getParsedClass(ClassWithInnerLambda::class.java.name)
        val bootstrapMethods = clazz.getParsedBootstrapMethods()
        assertTrue { bootstrapMethods.any() }
        clazz.methods.forEach { method ->
            instructionRegex.findAll(method.code.toString(true)).forEach {
                val index = it.groupValues[1].toInt()
                assertNotNull(ClassWithInnerLambda.methodsMap[method.name])
                assertTrue { ClassWithInnerLambda.methodsMap[method.name]!! in bootstrapMethods[index] }
            }
        }
    }

    @Test
    fun `method with inner lambdas`() {
        val clazz = getParsedClass(ClassWithInnerLambdas::class.java.name)
        val bootstrapMethods = clazz.getParsedBootstrapMethods()
        assertTrue { bootstrapMethods.any() }
        clazz.methods.forEach { method ->
            instructionRegex.findAll(method.code.toString(true)).forEach {
                val index = it.groupValues[1].toInt()
                assertTrue { ClassWithInnerLambdas.methodsMap[method.name]!! in bootstrapMethods[index] }
            }
        }
    }

    @Test
    fun `method with references`() {
        val clazz = getParsedClass(ClassWithReferences::class.java.name)
        val bootstrapMethods = clazz.getParsedBootstrapMethods()
        assertTrue { bootstrapMethods.any() }
        clazz.methods.forEach { method ->
            instructionRegex.findAll(method.code.toString(true)).forEach {
                val index = it.groupValues[1].toInt()
                assertTrue { ClassWithReferences.methodsMap[method.name]!! in bootstrapMethods[index] }
            }
        }
    }

    @Test
    fun `method with lambdas and references`() {
        val clazz = getParsedClass(ClassWithRefAndLambdas::class.java.name)
        val bootstrapMethods = clazz.getParsedBootstrapMethods()
        assertTrue { bootstrapMethods.any() }
        clazz.methods.forEach { method ->
            instructionRegex.findAll(method.code.toString(true)).forEach {
                val index = it.groupValues[1].toInt()
                assertTrue { ClassWithRefAndLambdas.methodsMap[method.name]!! in bootstrapMethods[index] }
            }
        }
    }

    @Test
    fun `method with method that returns lambda`() {
        val clazz = getParsedClass(ClassWithLambdaReturning::class.java.name)
        val bootstrapMethods = clazz.getParsedBootstrapMethods()
        assertTrue { bootstrapMethods.any() }
        clazz.methods.forEach { method ->
            instructionRegex.findAll(method.code.toString(true)).forEach {
                val index = it.groupValues[1].toInt()
                assertTrue { ClassWithLambdaReturning.methodsMap[method.name]!! in bootstrapMethods[index] }
            }
        }
    }

    @Test
    fun `method with interface reference`() {
        val clazz = getParsedClass(ClassWithReferenceToInterface::class.java.name)
        val bootstrapMethods = clazz.getParsedBootstrapMethods()
        assertTrue { bootstrapMethods.any() }
        clazz.methods.forEach { method ->
            instructionRegex.findAll(method.code.toString(true)).forEach {
                val index = it.groupValues[1].toInt()
                assertTrue { ClassWithReferenceToInterface.methodsMap[method.name]!! in bootstrapMethods[index] }
            }
        }
    }

    @Test
    fun `method with different references`() {
        val clazz = getParsedClass(ClassWithDifferentReferences::class.java.name)
        val bootstrapMethods = clazz.getParsedBootstrapMethods()
        assertTrue { bootstrapMethods.any() }
        clazz.methods.forEach { method ->
            instructionRegex.findAll(method.code.toString(true)).forEach {
                val index = it.groupValues[1].toInt()
                assertNotNull(ClassWithDifferentReferences.methodsMap[method.name])
                assertTrue { ClassWithDifferentReferences.methodsMap[method.name]!! in bootstrapMethods[index] }
            }
        }
    }
}
