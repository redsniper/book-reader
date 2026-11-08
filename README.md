# 墨阅 · 离线小说阅读器

> 纸间有墨，静读有声。

墨阅是一款完全离线的本地小说阅读器，面向 Xiaomi 15 Ultra / Xiaomi HyperOS 3 及以上设备。支持导入 TXT、EPUB、PDF，在书架中管理书籍，以可定制的排版沉浸阅读，并自动保存进度。隐藏书架通过书架大幅下拉 + 密码进入，密码只以加盐慢哈希保存在本机。

> 后续开发与 AI Agent 请先阅读 **[AGENTS.md](AGENTS.md)**，其中固化了设计流程、OOP 架构、测试规范、安全红线与质量门禁。

## 功能

### 阅读
- TXT / EPUB / PDF 本地导入，文件复制到应用私有目录。
- 左右翻页、上下连续滚动两种模式，共享同一份全书页索引。
- 目录、按章跳转、按页数跳转、自动保存/恢复阅读进度。
- 阅读主题：羊皮纸（日间黄底黑字）、夜读（黑底浅字）、青竹、宣白。
- 字体族、字号、行距、左右边距可调；改动排版后按字符锚点保持阅读位置。
- 页索引按“内容 + 章节 + 排版参数 + 引擎版本”缓存到应用私有目录：重开同一本书、同排版跳页不再重新排版；字号等排版变化会重排一次并自动更新缓存。
- TXT 自动识别 UTF-8 / UTF-16 / GB18030 编码并按中英文关键字分章；无标题文本按段落回退分节。
- PDF 自动判断文本层：文本 PDF 提取文字并重排分章；扫描版 PDF 自动降级为逐页位图渲染。

### 书架
- 两列自适应网格，显示封面、书名、作者、格式、百分比与当前章节。
- 多选导入、按 SHA-256 去重、删除确认、最近阅读排序。
- 长按或卡片“⋯”进入书籍编辑：隐藏到隐藏书架 / 移出隐藏书架 / 删除。

### 隐藏书架
- 普通书架顶部大幅下拉 140dp 进入密码框；仅本次前台会话有效，切到后台（Home/最近任务/锁屏）立即锁定，返回需重新输入密码。
- 隐藏书阅读中锁定后显示“隐藏书架已锁定，请返回书架重新输入密码”；隐藏书架则退回普通书架并提示重新解锁。
- 密码在设置页设置/修改（修改需旧密码），PBKDF2WithHmacSHA256 + 随机盐 + 120,000 次迭代，常量时间比较。
- 忘记密码可“清空隐藏书架并重置密码”（二次确认输入“清空”），不可恢复。
- 应用无 INTERNET 权限、`allowBackup=false`，隐藏书籍不会进入云备份。

## 构建

### 环境
- macOS / Linux（本项目在 macOS 12 x86_64 上验证）
- JDK 21
- Android SDK Platform 35 + Build-Tools 35.0.0
- Gradle 8.9（由 wrapper 自动下载）

### 配置 SDK
在项目根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

或设置 `ANDROID_HOME`。SDK 目录至少需要：
```
platforms/android-35
build-tools/35.0.0
platform-tools
```

### 依赖镜像
- Gradle wrapper 默认使用腾讯云镜像：`gradle/wrapper/gradle-wrapper.properties`
- Maven 依赖在 `settings.gradle.kts` 中优先使用阿里云镜像，`google()/mavenCentral()` 兜底。
- 若网络环境不同，可自行调整镜像或代理。

### 命令

```bash
# Debug APK
./gradlew assembleDebug

# 单元 + Robolectric 集成测试
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

安装到设备/模拟器：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.moyue.reader.debug/com.moyue.reader.MainActivity
```

## 测试

- 137 项 JVM/Robolectric 测试全部通过，覆盖：
  - 编码探测、文本规范化、自动分章、页索引、进度换算；
  - PBKDF2 密码哈希、失败节流、下拉解锁状态机；
  - 真实 Room DAO、DataStore、SharedPreferences；
  - TXT/EPUB/PDF 解析（含真实 PDFBox 文本提取、加密 PDF、扫描版判定）；
  - 导入 → 书架 → 隐藏/解锁 → 进度往返 → 删除/重置的端到端集成流程；
  - Shelf / Reader / Settings ViewModel；
  - 隐藏会话后台锁定：阅读器立即锁定并保存进度、书架退回普通书架、普通书不受影响；
  - 页索引文件缓存（往返、签名失效、损坏回退、LRU、删除级联）；
  - 阅读视口/字号度量与分页同源（Compose 解析 px 驱动重排、系统字体缩放）；
  - StaticLayout 分页器（Robolectric 原生图形）。
- **按仓库要求，测试源码不纳入 Git 跟踪**（见 `.gitignore` 的 `app/src/test/`、`app/src/androidTest/`）；本地工作区仍保留完整测试。
- 测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`

## 真机反馈与诊断日志

真机验收时无需连接 adb：应用内置低容量诊断日志，可在设置页一键导出。

1. 复现问题后打开 **设置 → 诊断与反馈**。
2. 点击 **导出诊断日志**，选择“下载”并保存 `moyue-diagnostics-*.txt`。
3. 把文件附在反馈中；按钮下方同时显示日志目录，便于 `adb pull`。

日志只记录设备/系统/屏幕/字体缩放、导入格式与耗时、分页视口与字号/行高 px、缓存命中、
页数、跳页/进度等**元数据**，不包含书名、正文、文件路径或密码。单份上限约 512 KB
（`moyue.log` + `moyue.log.1`），高频设置事件会节流。

## 架构

```
UI (Compose + MVVM) → domain ← data / platform
```

- `domain/`：模型、仓库接口、解析契约、分章/进度/密码/下拉检测等领域服务。
- `data/`：Room、DataStore、SharedPreferences、私有文件存储、页索引缓存、TXT/EPUB/PDF 解析。
- `platform/`：StaticLayout 分页器、PDFBox 文本提取、PdfRenderer 扫描页渲染。
- `ui/`：书架、阅读器、设置、导航、设计系统。
- `AppContainer` 手写组合根；ViewModel 只依赖接口。
- 详细设计见 [docs/design.md](docs/design.md)，分步计划见 [docs/implementation-plan.md](docs/implementation-plan.md)。

## 目录

```
app/src/main/java/com/moyue/reader/
├── core/            # 调度器、时钟
├── domain/          # 模型、仓库接口、解析契约、领域服务
├── data/            # Room、DataStore、文件、页索引缓存、解析器、诊断日志
├── platform/        # 分页、PDF 文本/渲染适配
└── ui/              # 书架、阅读器、设置、导航、主题
docs/
├── design.md
├── implementation-plan.md
└── black-box-test-guide.md
```

## 隐私与安全

- 不申请 INTERNET 权限；无在线书源、无统计 SDK。
- 诊断日志只写元数据（设备、排版、分页、导入结果），不含书名/正文/路径/密码；容量有硬上限。
- 书籍文件、封面、阅读进度、设置、密码哈希都保存在应用私有目录。
- 隐藏书架是**界面隐藏**，不是文件加密；获得 root 权限的攻击者仍可读取私有目录。请勿将其宣传为加密保险箱。
- 忘记密码只能清空隐藏书架，无法找回原密码。

## 已知限制

- PDF 文本重排依赖原 PDF 的文字层与编码；扫描版仅逐页显示，字体/行距设置对页面位图不生效。
- EPUB 仅提取可重排文字，固定版式/漫画类 EPUB 未支持；同文档内多级锚点会合并为一章。
- 隐藏密码是界面访问控制，不加密书籍文件。
- 单文件限制：TXT/EPUB ≤ 40 MB，PDF ≤ 300 MB。
- 阅读设置中的字体族使用系统字体，暂不支持导入字体文件。
- 页索引缓存只保存在本机应用私有目录；清除应用数据、重装、修改源文件或升级页边界算法后，首次打开会重新排版。

## 许可

本项目为私有交付项目，未附带开源许可证。
