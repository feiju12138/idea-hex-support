plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "cn.fj.loli"
version = "2.0.0"

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "cn.fj.loli.hexsupport"
        name = "Hex Support"
        version = project.version.toString()
        description = """
            <p>Open and edit files as hexadecimal data inside IntelliJ IDEA.</p>
            <p>Provides an editable hex byte table with offset column, raw data preview, hex/ASCII pattern search, offset navigation, multi-selection editing, undo/redo, and fragment import/export.</p>
        """.trimIndent()
        changeNotes = """
            <ul>
                <li>2.0.0: Rework the editor backend around HexDocument, using in-memory editing for small files and piece/history-based editing for large local files.</li>
                <li>2.0.0: Add 64-bit offsets, adaptive bytes-per-row limits, 16-digit offset rendering, and paged reads so very large files can be opened, searched, edited, saved, imported, and exported.</li>
                <li>2.0.0: Add streaming save, Save As, fragment export, file import at head/tail/after selection, and safer clipboard handling for large selections.</li>
                <li>2.0.0: Replace raw snapshot undo/redo with document-state undo/redo integrated with IntelliJ UndoManager and visible operation records.</li>
                <li>2.0.0: Add the Hex History tool window, operation history export/import files, automatic history export, optional history deletion on save, and settings under Tools | Hex Support.</li>
                <li>2.0.0: Add Hex History context menu actions to undo or redo directly to the selected history entry.</li>
                <li>2.0.0: Add debounced background streaming search with loading feedback and capped match reporting for large files.</li>
                <li>2.0.0: Limit the editor provider to valid local files and add large-file/history sample assets for validation.</li>
                <li>1.0.1: Fix plugin configuration defect reported by the Plugin Verifier (remove until-build; set since-build to 251).</li>
                <li>1.0.0: Initial release.</li>
            </ul>
        """.trimIndent()
        vendor {
            name = "feiju12138"
            url = "https://github.com/feiju12138/idea-hex-support"
        }
        ideaVersion {
            sinceBuild = "251"
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.encoding = "UTF-8"
    }

    named("buildSearchableOptions") {
        enabled = false
    }

    named("prepareJarSearchableOptions") {
        enabled = false
    }

    named("jarSearchableOptions") {
        enabled = false
    }
}
