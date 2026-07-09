plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "cn.fj.loli"
version = "v1.0.0"

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
                <li>v1.0.0: Initial release.</li>
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
}
