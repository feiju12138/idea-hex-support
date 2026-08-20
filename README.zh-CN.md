# Hex Support

[English](README.md) | 简体中文

Hex Support 是一款 IntelliJ Platform 插件，可用于查看、编辑、搜索、检查和比较二进制文件。3.0.1 版本提供可扩展的二进制结构宿主和公共 Marketplace 发现机制，同时保证未安装任何模板语言插件时 Hex 编辑器仍可完整使用。

## 功能

### Hex 编辑器

- 可直接打开已关联到 **十六进制文件** 类型的文件，也可为其他文件选择 Hex 作为备用编辑器。
- 通过支持 64 位偏移的虚拟化字节表查看数据，并使用同步文本预览；每行字节数、字符集、字体和配色均可配置。
- 支持覆盖编辑、多区间选择，以及复制、剪切、粘贴、插入、删除、置零和反选。
- 可从编辑器工具栏执行保存、另存为、重新加载、在当前位置导入字节，以及导出选中字节。
- 采用分页存储处理大文件，不需要把整个文件加载到 Swing 表格模型中。

### 搜索与批量替换

- 按 `Ctrl/Command+F` 搜索，按 `Ctrl/Command+R` 打开替换控件；按 `Ctrl/Command+G` 跳转到绝对或相对偏移。
- 可搜索十六进制字节序列，也可输入文本并按当前字符编码转换成待搜索字节。
- 支持前后切换匹配项、替换当前或全部匹配项，以及删除或置零当前或全部匹配项；替换内容使用十六进制字节表示。
- 大文件搜索在后台执行，批量修改会作为编辑操作记录，可撤销或重做。

### 历史窗口、历史文件与设置

- 每次编辑都会进入 IntelliJ 的撤销/重做体系，并显示在 **Hex 操作历史** 窗口中。
- 选择历史条目可查看偏移、修改前后字节和时间。每个条目的右键菜单固定提供 **回退到当前**（保留所选操作的结果）和 **回退到之前**（恢复到所选操作执行前）两项。
- **Hex 操作历史** 工具栏中导出按钮位于导入按钮左侧。手动导出会打开另存为对话框，默认文件名为 `<原文件名>.hex-history.txt`，但可自由选择保存位置和文件名；手动导入不限制文件名，只要文件是 Hex Support 导出的有效历史格式且与当前源文件版本匹配即可。
- 只有启用自动导出时，历史文件才会直接写入原文件同一目录并使用 `<原文件名>.hex-history.txt`。首次打开源文件时，如果同目录存在与源文件匹配的该规范文件名历史文件，则会自动载入。
- 使用 `Ctrl+S` 保存以及在已打开的 Hex 编辑器标签之间切换时，各编辑器的内存历史和撤销/重做状态都会保留。
- 打开 **设置 | 工具 | Hex Support**，启用 **自动导出操作历史记录文件** 后，历史发生变化时会自动更新历史文件。
- 如果希望保存 Hex 文件后移除历史文件，可启用 **保存 Hex 文件时删除操作历史记录文件**。

### 可扩展二进制结构

Hex Support 本身不再包含模板解析器。需要结构化分析时，请打开 **二进制结构** 窗口并安装一个或多个兼容的 Structure Provider 插件。第一方的 **Binary Template Support** Provider 用于处理 010 Editor `.bt` 文件。

- 未安装 Provider 时，Hex 编辑、搜索、历史记录和 Diff 功能仍可完整使用。
- **查找 Structure 扩展** 会使用公共 Provider 发现关键字打开 JetBrains Marketplace；各个 Provider 插件均可独立安装。
- **导入模板** 会接受所有已安装 Provider 声明的扩展名。如果有多个 Provider 支持同一文件，Hex Support 会要求选择并记住所选 Provider。
- 分析在后台针对当前编辑器的只读快照执行，包括尚未保存的字节修改。
- Provider 无关的结果以树形结构展示 **名称**、**值**、**偏移**、**大小** 和 **类型**，结构行与字节选区保持双向联动。
- 编辑后会自动重新分析；清除操作会移除模板、结果树、高亮和范围联动。
- Provider 可以返回诊断信息、文本输出和背景高亮，但当前只读 API 不允许 Provider 修改 Hex 文档。

#### 开发 Structure Provider

Hex Support 提供动态扩展点 `cn.fj.loli.hexsupport.binaryStructureProvider`，公共 API 位于 `cn.fj.loli.hexsupport.structure`。Provider 插件应可选依赖 `cn.fj.loli.hexsupport`，并在可选描述文件中注册实现：

```xml
<extensions defaultExtensionNs="cn.fj.loli.hexsupport">
    <binaryStructureProvider implementation="com.example.MyStructureProvider"/>
</extensions>
```

实现 `BinaryStructureProvider`、声明支持的模板扩展名并返回 `StructureAnalysisResult`。采用可选依赖后，即使未安装 Hex Support，Provider 插件自身的语言支持仍可独立使用。

为了让尚未安装的 Provider 能被 **查找 Structure 扩展** 发现，请在 JetBrains Marketplace 插件描述中包含完整短语 `Hex Support structure analysis`（即 `BinaryStructureProvider.MARKETPLACE_DISCOVERY_KEYWORD`）。Hex Support 会使用这个公共关键字搜索 Marketplace 元数据，不再维护 Provider 插件 ID；以后发布新的 Provider 无需修改或发布 Hex Support。

### Hex 差异比较

- 可将当前 Hex 编辑器与另一个文件进行基于数据块的差异计算。
- 使用 `F7` 和 `Shift+F7` 跳转到下一处或上一处差异，查看变化范围，并在两侧之间复制改动。

### 将 Hex 设为某个扩展名的默认编辑器

1. 打开 **设置 | 编辑器 | 文件类型**。
2. 在 **已识别的文件类型** 中选择 **十六进制文件**。
3. 在 **文件名模式** 中添加 `*.exe` 等模式并应用。

匹配该模式的文件会直接在 Hex Support 中打开。未关联到此文件类型的文件仍可选择 Hex 作为备用编辑器。

## 快捷键

快捷键使用平台菜单修饰键：Windows/Linux 为 `Ctrl`，macOS 为 `Command`。

| 操作 | 快捷键 |
| --- | --- |
| 保存 | Ctrl/Command+S |
| 撤销 / 重做 | Ctrl/Command+Z / Ctrl/Command+Shift+Z |
| 复制 / 剪切 / 粘贴 | Ctrl/Command+C / Ctrl/Command+X / Ctrl/Command+V |
| 全选 | Ctrl/Command+A |
| 反选 | Ctrl/Command+Shift+I |
| 跳转到偏移 | Ctrl/Command+G |
| 查找 / 替换 | Ctrl/Command+F / Ctrl/Command+R |
| 下一个 / 上一个匹配项 | Enter / Shift+Enter |
| 清除选择或关闭查找栏 | Esc |
| 将选中字节置零 | Backspace |
| 删除选中字节 | Delete |
| 开始编辑字节 | Enter、Space 或 `0`-`9` / `A`-`F` |
| 提交字节并移动到下一单元格 | Tab |
| 下一处 / 上一处差异 | F7 / Shift+F7 |

## 构建

项目要求使用 JDK 21 和 Gradle 9 或更高版本：

```shell
gradle test buildPlugin
```

若要使用已安装的 IDE 构建，可传入其安装目录：

```shell
gradle test buildPlugin -PlocalIdePath=/path/to/IntelliJ-IDEA
```

生成的 ZIP 位于 `build/distributions/`。

## 兼容性

- IntelliJ IDEA 2025.1 或更高版本（build 251+）
- 从源码构建需要 JDK 21
- Gradle 9 或更高版本
