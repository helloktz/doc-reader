# 文阅 / doc-reader

Android 14+ 的离线 DOCX / PDF 阅读器。打开文件即可阅读，支持滑动、双指缩放和双击放大/还原；DOCX 可以导出 PDF。原文件保持只读。

没有账号、广告、内购、云服务或联网权限。应用名为「文阅」，包名为 `io.github.helloktz.docreader`。

## 安装

从本仓库的 Releases 下载 `doc-reader-v0.2.0.apk`，在 Android 手机上允许对应来源安装应用。

- 最低 Android 14 / API 34，目标 Android 16 / API 36。
- APK 包含 ARM64（常见手机）和 x86_64（模拟器）两种架构。
- 点击「打开文件」选择 DOCX/PDF，也可从其他应用通过「打开方式」或「分享」进入。
- DOCX 首次打开需要本地排版转换；右上角下载图标将 PDF 写入原文件同目录，只更改后缀。重名时可以覆盖，或保留为 `文件名 (1).pdf`、`文件名 (2).pdf`。
- 首次导出本地共享存储文件，需要在 Android 系统设置中授予文件管理访问；拒绝授权仍可阅读。系统文件选择器返回的本地存储、下载目录及 SD 卡文件可解析为原目录；不提供实际本地路径的第三方临时 URI 无法同目录导出。
- 首页只有「打开文件」；阅读页控件使用图标，页码位于右下角。底部依次为贴边适应、尺寸锁定、大纲、页面预览。
- 锁定保留当前缩放和横向位置，阻止横移及缩放，仍可上下阅读；解锁后可再次缩放。大纲和页面缩略图在左侧浮层中点击跳页。

## 实现范围

Kotlin 编写原生界面，系统 `PdfRenderer` 按需渲染可见页面。基础图像缓存 32 MiB，清晰度缓存 48 MiB；缩放时复用已有图像，手势结束后补充高清图像。阅读使用惯性滚动，缩略图有独立的 12 MiB 缓存。Rust 通过 JNI 调用 WordCraft 的 DOCX、字体、排版和 PDF 模块，并使用 hayro-syntax 读取 PDF 书签；没有引入 WordCraft 的桌面界面、编辑器、MCP、旧 DOC 或其他文档格式模块。

DOCX 先转换为应用私有缓存中的 PDF，阅读与导出使用同一份结果。输入通过只读流读取，输出通过同目录临时文件写入后替换，避免复制失败时截断已有 PDF。没有另选导出目录的入口。

当前限制：

- 不支持旧 DOC、编辑、搜索、文字选择/复制及密码文档。
- DOCX 输入上限 64 MiB，PDF 输入上限 256 MiB；复杂文档可能受设备内存限制。
- 内置开源中西文字体。缺少原字体时会替换，复杂对象、公式或特殊分页可能与 Microsoft Word 不同；不承诺所有 DOCX 的版式完全一致。
- 断词使用 WordCraft 内建规则；没有额外打包整套界面翻译和拼写词典。
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

产物：`deliverables/doc-reader-v0.2.0.apk`。

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

`tests/ui_smoke.py` 验证首页只有一个打开按钮、图标工具栏、大纲/预览跳页、系统选择器导入 DOCX/PDF、首次文件权限授权、同目录导出/覆盖/尾缀，以及输入文件不变。该脚本面向英文界面的测试模拟器，并需要 `test-output/sample.pdf`（可用 `native/examples/convert.rs` 从 `app/src/androidTest/assets/sample.docx` 生成）。

详细交付检查见 `VALIDATION.md`。
