import com.epam.drill.plugins.test2code.cli.*
import java.io.*
import kotlin.test.*
import kotlin.test.Test

class Test {

    @Test
    fun `specified path is jar file`() {
        val rootDir = File("..", "admin-part")
        val jarFile = rootDir.walkTopDown().first { it.name.endsWith(".jar") }
        val file = File.createTempFile("test2code-", "-parsed-class.txt")
        val args = listOf(
            "--classpath=$jarFile",
            "--packages=com/epam",
            "--output=${file.path}"
        )
        main(args.toTypedArray())
        assertTrue { file.length() > 0 }
    }

}