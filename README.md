# Hex Support

English | [简体中文](README.zh-CN.md)

Hex Support is an IntelliJ Platform plugin for viewing, editing, searching, inspecting, and comparing binary files. Version 3.0.0 turns the Binary Structure window into an extensible host while keeping the Hex editor completely usable without any template-language plugin.

## Features

### Hex editor

- Open files assigned to the **Hexadecimal files** type directly, or select Hex as an alternate editor for another file.
- Inspect bytes in a virtualized 64-bit table with a synchronized text preview, configurable bytes per row, character set, font, and color scheme.
- Edit bytes in overwrite mode, select one or more ranges, and use copy, cut, paste, insert, delete, zero-fill, or invert-selection operations.
- Save, Save As, reload, import bytes at the current position, or export selected bytes from the editor toolbar.
- Work with large files through paged storage instead of loading the complete file into a Swing table model.

### Search and batch replacement

- Press `Ctrl/Command+F` to search or `Ctrl/Command+R` to open replacement controls; use `Ctrl/Command+G` to jump to an absolute or relative offset.
- Search for hexadecimal byte sequences or enter text that is converted with the selected character encoding.
- Move between matches, replace the current match or all matches with hexadecimal bytes, and delete or zero-fill the current match or every match.
- Large searches run in the background. Batch changes are recorded as editor operations and can be undone or redone.

### History window, history files, and settings

- Every editing operation participates in IntelliJ Undo/Redo and appears in the **Hex History** tool window.
- Select a history entry to review its offset, before/after bytes, and time. Every entry's context menu provides **Restore Through This Operation** (keep the selected operation applied) and **Restore Before This Operation** (restore the state immediately before it).
- The **Hex History** toolbar places Export before Import. Manual export opens a Save As dialog and defaults to `<source-name>.hex-history.txt`, while allowing any destination and filename. Manual import accepts any filename as long as it is a valid Hex Support history export for the current source version.
- Automatic export is the only mode that writes directly beside the source as `<source-name>.hex-history.txt`. When a source file is first opened, a matching sidecar with that standard filename is loaded automatically.
- Saving with `Ctrl+S` and switching between open Hex editor tabs retain each editor's in-memory history and Undo/Redo states.
- Open **Settings | Tools | Hex Support** and enable **Automatically export operation history files** to update the sidecar shortly after history changes.
- Enable **Delete operation history file when saving the hex file** if the sidecar should be removed after the edited source is saved.

### Extensible Binary Structure

Hex Support does not contain a template parser. Open the **Binary Structure** tool window and install one or more compatible Structure provider plugins when structured analysis is needed. **Binary Template Support** is the first-party provider for 010 Editor `.bt` files.

- Hex editing, search, history, and Diff remain fully available when no provider is installed.
- **Install BT Provider** opens the IDE plugin installer for Binary Template Support. Other provider plugins can be installed independently.
- **Import Template** accepts every extension advertised by installed providers. If multiple providers support the selected file, Hex Support asks which provider to use and remembers the selection.
- Analysis runs in the background against a read-only snapshot of the current editor revision, including unsaved byte changes.
- Provider-neutral results are displayed as a hierarchy with **Name**, **Value**, **Offset**, **Size**, and **Type** columns. Row and byte selections remain linked in both directions.
- Results refresh automatically after edits. Clearing removes the template, result tree, highlights, and automatic range linkage.
- A provider may return diagnostics, textual output, and background highlights. It cannot mutate the Hex document through the current read-only API.

#### Developing a Structure provider

Hex Support publishes the dynamic `cn.fj.loli.hexsupport.binaryStructureProvider` extension point and the API in `cn.fj.loli.hexsupport.structure`. A provider plugin should declare an optional dependency on `cn.fj.loli.hexsupport`, then register its implementation from the optional descriptor:

```xml
<extensions defaultExtensionNs="cn.fj.loli.hexsupport">
    <binaryStructureProvider implementation="com.example.MyStructureProvider"/>
</extensions>
```

Implement `BinaryStructureProvider`, advertise the supported template extensions, and return a `StructureAnalysisResult`. Depending on Hex Support optionally keeps the provider's own language support usable when Hex Support is absent.

### Hex diff

- Compare the active Hex editor with another file using block-based diff computation.
- Navigate differences with `F7` and `Shift+F7`, inspect changed ranges, and copy changes between sides.

### Make Hex the default editor for a file extension

1. Open **Settings | Editor | File Types**.
2. Select **Hexadecimal files** under **Recognized File Types**.
3. Add a filename pattern such as `*.exe` under **File Name Patterns** and apply the change.

Files matching that pattern open directly in Hex Support. Files not assigned to this file type still offer Hex as an alternate editor.

## Keyboard shortcuts

Shortcuts use the platform menu modifier: `Ctrl` on Windows/Linux and `Command` on macOS.

| Action | Shortcut |
| --- | --- |
| Save | Ctrl/Command+S |
| Undo / Redo | Ctrl/Command+Z / Ctrl/Command+Shift+Z |
| Copy / Cut / Paste | Ctrl/Command+C / Ctrl/Command+X / Ctrl/Command+V |
| Select all | Ctrl/Command+A |
| Invert selection | Ctrl/Command+Shift+I |
| Go to offset | Ctrl/Command+G |
| Find / Replace | Ctrl/Command+F / Ctrl/Command+R |
| Next / previous match | Enter / Shift+Enter |
| Clear selection or close the find bar | Esc |
| Zero selected bytes | Backspace |
| Delete selected bytes | Delete |
| Start editing a byte | Enter, Space, or `0`-`9` / `A`-`F` |
| Commit the byte and move to the next cell | Tab |
| Next / previous difference | F7 / Shift+F7 |

## Build

The project requires JDK 21 and Gradle 9 or later:

```shell
gradle test buildPlugin
```

To build against an installed IDE, pass its installation directory:

```shell
gradle test buildPlugin -PlocalIdePath=/path/to/IntelliJ-IDEA
```

The resulting ZIP is written to `build/distributions/`.

## Compatibility

- IntelliJ IDEA 2025.1 or later (build 251+)
- JDK 21 for building from source
- Gradle 9 or later
