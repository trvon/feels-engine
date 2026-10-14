package dev.fengine.wallpaper.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract: screens use design tokens and components, never raw dimensions.
 * Guards structure, not behaviour, like the architecture tests in the NuWayze repo.
 */
class DesignLanguageTest {
    private val uiDir = sequenceOf("app/src/main/kotlin/dev/fengine/wallpaper/ui", "src/main/kotlin/dev/fengine/wallpaper/ui")
        .map(::File).first { it.isDirectory }

    private val rawDp = Regex("""\b\d+(\.\d+)?\.dp\b""")

    @Test
    fun screensUseTokensNotRawDp() {
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in setOf("Tokens.kt", "Layout.kt") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (rawDp.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null
                }
            }.toList()
        assertTrue("Raw dp values outside Tokens.kt:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun screensDoNotBuildTheirOwnThumbnails() {
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name != "design" }
            .filter { it.readText().contains("loadThumbnail(") }
            .map { it.name }.toList()
        assertTrue("Thumbnail loading belongs in design/Components.kt: $offenders", offenders.isEmpty())
    }
}
