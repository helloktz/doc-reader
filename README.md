# 文阅 / doc-reader

Android 14+ 的离线 DOCX / PDF 阅读器。打开文件即可阅读，支持滑动、双指缩放和双击放大/还原；DOCX 可以导出 PDF。原文件保持只读。

没有账号、广告、内购、云服务或联网权限。应用名为「文阅」，包名为 `io.github.helloktz.docreader`。

## 安装

从本仓库的 Releases 下载 `doc-reader-v1.0.1.apk`，在 Android 手机上允许对应来源安装应用。

- 最低 Android 14 / API 34，目标 Android 16 / API 36。
- APK 包含 ARM64（常见手机）和 x86_64（模拟器）两种架构。
- 点击「打开文件」选择 DOCX/PDF，也可从其他应用通过「打开方式」或「分享」进入。
- DOCX 首次打开需要本地排版转换；右上角下载图标将 PDF 保存到系统 `Documents` 目录，正常 DOCX 文件只更改后缀。重名时可以覆盖，或保留为 `文件名 (1).pdf`、`文件名 (2).pdf`。
- 首次导出需要在 Android 系统设置中授予文件管理访问；拒绝授权仍可阅读。导出使用已经转换的缓存和文件名，不依赖原文件目录，第三方临时 URI 导入的 DOCX 也可导出。
- 首页只有「打开文件」；阅读页控件使用图标，页码位于右下角。底部依次为恢复默认、适应文字、尺寸锁定、大纲、页面预览。窄屏或大字体下，页码移到下一行右侧，按钮仍保持 48dp 触摸区域。
- 回转箭头恢复原来的整张纸贴边视图。宽度箭头按整份文档所有页面的文字字形左右极值统一缩放，屏幕两侧各留约 2mm（12dp）；含页眉、页脚及页码，图片和表格边线不参与计算。混合纸张尺寸按各页显示宽度归一化，翻页时保持统一比例。
- 适应文字按需在后台计算并缓存，复位、缩放或锁定可取消尚未完成的计算。纯图片扫描件或全部文字已转为普通图形、且没有文字层时，会保留原视图并提示；不执行 OCR。
- 锁定保留当前缩放和横向位置，阻止横移及缩放，仍可上下阅读；解锁后可再次缩放。大纲和页面缩略图在左侧浮层中点击跳页。
- 点击适应文字并成功完成后，会自动开启尺寸锁定；计算失败或被取消时不自动锁定。手动解锁后，旋转或恢复阅读状态不会自行重新锁定。
- 界面使用统一的 IconPark 双色矢量图标、浅色阅读主题、原生按压反馈和选中标记；跟随系统字体大小，系统关闭动画时不播放浮层入场动画。没有增加功能介绍或标语。

## 实现范围

Kotlin 编写原生界面，系统 `PdfRenderer` 按需渲染可见页面。基础图像缓存 32 MiB，清晰度缓存 48 MiB；缩放时复用已有图像，手势结束后补充高清图像。极窄文字放大超过 4 倍时，另以 16 MiB 缓存渲染可见区域，单张位图限制约 400 万像素。阅读使用惯性滚动，缩略图有独立的 12 MiB 缓存。Rust 通过 JNI 调用 WordCraft 的 DOCX、字体、排版和 PDF 模块，并使用 hayro-syntax 读取 PDF 书签、hayro-interpret 解释文字字形位置；没有引入 WordCraft 的桌面界面、编辑器、MCP、旧 DOC 或其他文档格式模块。

DOCX 先转换为应用私有缓存中的 PDF，阅读与导出使用同一份结果。输入通过只读流读取，输出先写入 Documents 中的临时文件，再提交或替换，避免复制失败时截断已有 PDF。导出后更新系统文件索引，便于从文件选择器找到 PDF。没有另选导出目录的入口。

当前限制：

- 不支持旧 DOC、编辑、搜索、文字选择/复制及密码文档。
- DOCX 输入上限 64 MiB，PDF 输入上限 256 MiB；复杂文档可能受设备内存限制。
- 内置开源中西文字体。缺少原字体时会替换，复杂对象、公式或特殊分页可能与 Microsoft Word 不同；不承诺所有 DOCX 的版式完全一致。
- 断词使用 WordCraft 内建规则；没有额外打包整套界面翻译和拼写词典。
- 文字适应依赖 PDF 的字形信息；没有文字层的扫描件、文字已转曲或不支持的特殊字体可能无法适应，复杂剪裁也可能影响边界估算。
- 当前完成 Android 16 QPR2 x86_64 模拟器验证，尚未进行 Android 14 真机和 ARM64 真机测试。

## 获取源码

```powershell
gh repo clone helloktz/doc-reader -- --recurse-submodules --shallow-submodules
```

`vendor/wordcraft` 是固定提交的浅子模块。Cargo 的依赖版本固定在 `native/Cargo.lock` 中，保留了上游要求的 Krilla 补丁。应用代码采用 MIT，第三方代码和字体遵循各自许可，详见 `NOTICE` 及 APK 内的 `assets/open-source-licenses.txt`。

## 在本机构建

已有环境位置：`D:\Developer\tools`，包括 Rust 1.95.0、cargo-ndk 4.1.2、Android SDK 36.1、NDK 30.0.14904198、JDK 25 和 Gradle 9.4.1。

```powershell
.\build.ps1 -Offline
```

产物：`deliverables/doc-reader-v1.0.1.apk`。

首次换机构建需要联网获取锁定的 Cargo/Gradle 依赖。脚本不安装 Android SDK/NDK。也可以通过 `-ToolsRoot` 指向另一个同结构工具目录；已有 native 库时可用 `-SkipNative`。

正式签名密钥保存在工具目录的 `doc-reader\signing` 下，不进入 Git。密码由当前 Windows 用户的 DPAPI 加密保存。请备份密钥；迁移 Windows 用户或电脑之前需先安全导出密码。丢失原密钥后无法用同一应用身份覆盖更新。

## 验证

`app/src/androidTest` 中的集成测试直接调用 JNI，检查双页 DOCX 转换、PDF 实际渲染、损坏输入、极简首页、文档打开、双指缩放、双击还原和 Activity 重建。示例文档只打包到测试 APK。

```powershell
. 'D:\Developer\tools\rust\activate.ps1'
.\gradlew.bat assembleDebug assembleDebugAndroidTest lint --offline
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w io.github.helloktz.docreader.test/io.github.helloktz.docreader.DocumentIntegrationTest
```

测试使用独立调试签名，已安装正式签名版本时，需要先卸载正式版本或使用单独的模拟器。

`tests/ui_smoke.py` 验证首页只有一个打开按钮、图标工具栏、适应文字后自动锁定、大纲/预览跳页、系统选择器导入 DOCX、首次文件权限授权、Documents 导出/覆盖/尾缀、从文件选择器打开新导出的 PDF，以及输入文件不变。该脚本面向英文界面的测试模拟器。

`tests/ui_layout.py` 在专用的 `emulator-5554` 上检查 320dp/375dp 手机、手机横屏及 800dp 平板横竖屏布局，并覆盖 2 倍系统字体、系统深色模式及关闭系统动画。它需要先通过上一项流程授予测试应用文件访问权限，检查后恢复原来的模拟器显示和字体设置。

详细交付检查见 `VALIDATION.md`。
