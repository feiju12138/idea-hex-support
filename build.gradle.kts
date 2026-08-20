plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "cn.fj.loli"
version = "3.0.1"

val localIdePath = providers.gradleProperty("localIdePath")

dependencies {
    intellijPlatform {
        if (localIdePath.isPresent) {
            local(localIdePath.get())
        } else {
            intellijIdea("2025.1")
        }
    }
    testRuntimeOnly("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "cn.fj.loli.hexsupport"
        name = "Hex Support"
        version = project.version.toString()
        description = """
            <p>Open, edit, compare, and inspect files as hexadecimal data inside IntelliJ IDEA.</p>
            <p>Provides an editable hex byte table, an extensible Binary Structure host for optional format-provider plugins, and side-by-side and unified Hex viewers for the IDE Diff window.</p>
        """.trimIndent()
        changeNotes = """
            <ul>
                <li>3.0.1: Replace hard-coded Structure provider plugin IDs with shared Marketplace keyword discovery, including Kaitai Struct Support, and document the public discovery convention for third-party providers.</li>
                <li>3.0.0: Replace the built-in .bt interpreter with a public, dynamic Binary Structure Provider extension point; keep all Hex editing and Diff features fully standalone; add localized provider discovery, installation guidance, generic template import, multi-provider selection, and provider-neutral structure results; fix custom-editor Undo scoping; add two-target history restoration, arbitrary-name history import, and Save As-style manual history export while keeping standard-name sidecars for automatic export/import; and retain in-memory Undo/Redo history across saves and open-editor switches.</li>
                <li>2.4.0: Add sandboxed read-only analysis with user-supplied .bt templates, localized Structure and History tool-window titles, automatic refresh after Hex edits, a focused Import/Clear/Expand/Collapse toolbar, supported background highlights, and bidirectional byte-range navigation.</li>
                <li>2.3.1: Restore IntelliJ IDEA 2025.1+ compatibility with stable platform APIs, and synchronize unchanged selections from both unified viewers to both old/new sides across all four Diff modes.</li>
                <li>2.3.0: Synchronize all selection ranges bidirectionally between Hex and text editors, including Text Editor and Markdown Split Editor multi-caret selections and mixed line separators; share one multi-range selection state across all four Diff viewers (Side-by-side, Unified Viewer, Hex-by-Hex, and Unified Hex), so switching from any viewer to any other preserves the old/new side and exact byte offsets.</li>
                <li>2.2.0: Add an assignable Hexadecimal file type under Settings | Editor | File Types, allowing filename patterns to make Hex the exclusive default editor while keeping Hex available as an alternate editor for other supported local files.</li>
                <li>2.1.0: Add selectable side-by-side and unified Hex Diff viewers with byte-level alignment, synchronized scrolling, difference highlighting/navigation, configurable bytes per row, native IDEA colors and fonts, and Jump to Source support that follows the exact active byte and source side, including insert/delete gap fallback and selection restoration.</li>
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

    pluginVerification {
        ides {
            current()
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
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
