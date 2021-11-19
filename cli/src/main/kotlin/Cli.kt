package com.epam.drill.plugins.test2code.cli

import com.epam.drill.plugins.test2code.jvm.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import java.io.*
import java.net.*
import java.util.jar.*

fun main(args: Array<String>) = ClassParser().main(args)

private class ClassParser : CliktCommand() {
    private val classpath: List<String>? by option(help = "Classpath (comma separated)").split(",")
    private val packages: List<String> by option(help = "Project packeges (comma separated)")
        .split(",")
        .default(emptyList())
    private val output: String? by option(help = "Output file, if not specified stdout is used")

    override fun run() {
        val classBytes = mutableMapOf<String, ByteArray>()
        val files = classpath?.map { File(it).toURI().toURL() } ?: emptyList()
        files.forEach { file ->
            file.scan(packages) { classname, bytes -> classBytes[classname] = bytes }
        }
        val parsed = classBytes.parseClassBytes()
        val classCount = parsed.packageTree.totalClassCount
        val methodCount = parsed.packageTree.totalMethodCount
        val lambdas = parsed.methods.mapNotNull { method ->
            method.lambdasHash.takeIf { it.any() }?.let { method.signature to it.keys }
        }.joinToString(separator = "\n", prefix = "[", postfix = "]") { "${it.first}:${it.second}" }
        output?.let {
            File(it).bufferedWriter().use { writer ->
                writer.appendLine("classCount: $classCount")
                writer.appendLine("methodCount: $methodCount")
                writer.append("lambdas: $lambdas")
            }
        } ?: println("classCount: $classCount\n methodCount: $methodCount\n lambdas: $lambdas")
    }
}

private fun URL.scan(
    filters: List<String>,
    block: (String, ByteArray) -> Unit
): Unit? = runCatching { File(toURI()) }.getOrNull()?.takeIf { it.exists() }?.let { file ->
    if (file.isDirectory) {
        file.scan(filters, block)
    } else JarInputStream(file.inputStream()).use {
        it.scan(filters, block)
        it.manifest?.classPath(this)?.forEach { url ->
            url.scan(filters, block)
        }
    }
}

private fun Manifest.classPath(jarUrl: URL): Set<URL>? = mainAttributes.run {
    getValue(Attributes.Name.CLASS_PATH.toString())?.split(" ")
}?.mapNotNullTo(mutableSetOf()) { path ->
    runCatching { URL(jarUrl, path) }.getOrNull()?.takeIf { it.protocol == "file" }
}

internal fun JarInputStream.scan(
    packages: List<String>,
    block: (String, ByteArray) -> Unit
): Unit = forEachFile { entry: JarEntry ->
    val name = entry.name
    when (name.substringAfterLast('.')) {
        "class" -> run {
            val source = name.toClassName()
            if (source.isAllowed(packages)) {
                val bytes = readBytes()
                block(source, bytes)
            }
        }
        "jar" -> JarInputStream(ByteArrayInputStream(readBytes())).scan(packages, block)
    }
}

private tailrec fun JarInputStream.forEachFile(block: JarInputStream.(JarEntry) -> Unit) {
    val entry = nextJarEntry ?: return
    if (!entry.isDirectory) {
        block(entry)
    }
    forEachFile(block)
}

internal fun File.scan(
    packages: List<String>,
    block: (String, ByteArray) -> Unit
) = walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
    val source = f.path.toClassName()
    if (source.isAllowed(packages)) {
        val bytes = f.readBytes()
        block(source, bytes)
    }
}

private fun String.isAllowed(filters: List<String>): Boolean = run {
    '$' !in this && matches(filters)
}

private fun String.toClassName() = replace(File.separatorChar, '/')
    .substringAfterLast("classes/")
    .removeSuffix(".class")

fun String.matches(
    filters: Iterable<String>, thisOffset: Int = 0
): Boolean = filters.any {
    regionMatches(thisOffset, it, 0, it.length)
} && filters.none {
    it.startsWith('!') && regionMatches(thisOffset, it, 1, it.length - 1)
}