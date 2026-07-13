plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "cn.fj.loli"
version = "2.0.1"

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
                <li>2.0.1: Fix the Find/Replace ASCII string field converting non-Latin-1 characters (e.g. CJK) to 0x3F by using the file's detected charset instead of ISO-8859-1, so multi-byte text searches match the file's actual bytes.</li>
                <li>2.0.0: Rework the editor around HexDocument with in-memory and piece-based large-file editing, 64-bit offsets with paged reads, streaming save/import/export, IntelliJ UndoManager integration with a Hex History tool window, and debounced background search — enabling very large files to be opened, searched, edited, saved, imported, and exported.</li>
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
