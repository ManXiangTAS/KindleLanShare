# Kindle 局域网传书（Android）

这是一个无第三方依赖的 Android 原生小工具。你在手机里选择电子书，开启临时 HTTP 网址，然后在 Kindle 自带浏览器中输入网址并点击书名下载。**全程局域网直传，不经过云端，也不上传到外部服务器。**

## 功能

- 一次选择一本或多本文件，直接通过 Android 系统文件选择器读取；无需复制到软件专用目录。
- 每次开启共享，自动生成新的短期随机网址；关闭后立即断开连接，原网址失效。
- 简单、无需 JavaScript 的 HTML 页面，尽量兼容 Kindle 的简化浏览器。
- 文件分块流式传输（64 KiB 缓冲区），不把整本书载入手机内存。
- 使用 `long`（64 位）表示文件长度，支持 `Content-Length`、`HEAD` 和单区间 `Range` 请求，程序自身没有 2 GB、100 MB 等人为的文件大小限制。
- 仅提供只读下载，不允许 Kindle 修改、删除或上传手机文件。
- 不要求读取整个手机存储的权限；只能共享你主动选中的文件。

## GitHub Actions 自动编译 APK

工程附带 `.github/workflows/build-apk.yml`，适用于 GitHub 新仓库的 `main` 分支。**推送到 `main` 后即自动触发**，也可以在仓库的 **Actions → Build Android APK → Run workflow** 手动编译。它先执行 HTTP 核心 JVM 测试，然后在 Ubuntu 上安装 Java 17、Android SDK 35、Gradle 8.13 并编译 Debug APK，最后把安装文件放到该运行页面的 **Artifacts → KindleLanShare-debug-APK**（下载得到一个 ZIP，解压后是 `app-debug.apk`）。

该 APK 是 Android 调试签名，可直接用于测试与个人安装，并非正式商店发布签名。首次使用 GitHub Actions 时，如果 GitHub 提示工作流未启用，请在仓库 Actions 页面启用它。若新仓库默认分支不是 `main`，请同步修改工作流中的 `branches: [main]`。

**本项目没有包含 Gradle Wrapper JAR：**CI 使用 `gradle/actions/setup-gradle` 安装固定版本的 Gradle，所以不依赖 `gradlew` 或 `gradle-wrapper.jar`。不要把打包的 ZIP 作为单个文件提交，须将里面的工程文件（包括隐藏的 `.github` 文件夹）置于仓库根目录。

## 安装构建

这是 **Android Studio 源码工程**，不是已经签名的安装包。该工程针对 Android 6.0+（API 23+），使用 Java 17、Android Gradle Plugin 8.13.0、Gradle 8.13、Android SDK 35，不依赖第三方运行时库。

1. 用电脑解压 ZIP，启动 Android Studio，**File → Open**，选中 `KindleLanShare` 文件夹。
2. 让 Android Studio 下载需要的 SDK 35 与 Gradle 8.13 并完成项目同步。
3. **Build → Build APK(s)**，在 `app/build/outputs/apk/debug/app-debug.apk` 找到安装包。
4. 把 APK 传到 Android 手机并安装。若 Android 阻止侧载，需要在手机系统里允许该来源安装应用。

**说明：** 当前源码包没有官方 `gradle-wrapper.jar`（运行环境没有 Gradle/Android SDK，无法在此直接生成）。如果 Android Studio 提示 *Gradle wrapper JAR missing*，请在装有 Gradle 8.13 的电脑上进入项目目录执行：

```bash
gradle wrapper --gradle-version 8.13
```

随后重新打开工程；也可在 Android Studio 的 Gradle 设置中指定本机 Gradle 8.13，再生成 wrapper。本项目并没有在真机上完成 Android 安装测试，HTTP 核心已使用电脑端 Java 测试。

## 手机上如何使用

1. 手机和 Kindle 连接同一个 Wi-Fi。或者让 Kindle 连接 Android 手机开的 Wi-Fi 热点。
2. 打开此 App，选择一本或多本电子书。电子书可以来自手机本地，也可以来自允许提供输入流的文件管理器/文档提供方。
3. 点击「开启临时共享」，页面会显示类似：

   `http://192.168.1.23:54321/75a9d20c3e11/`

   此网址**仅是示例**，每次实际开启时都会不同。
4. 在 Kindle 浏览器地址栏中逐字输入完整的 `http://` 网址（不是 `https://`）。
5. 点击书名下载。传输过程中保持手机软件在前台、屏幕亮着；在 App 中点击「关闭共享」即可结束，网址随即失效。

如果打不开网页，请检查：设备是否同一 Wi-Fi/热点、路由器是否开启了 AP/客户端隔离、Android 是否为 App 禁止本地网络访问、是否使用了正确的手机 IP、端口和完整访问代码。移动网络或者 VPN 地址可能不适合 Kindle 访问。

## Kindle 格式限制（很重要）

**程序可以提供任何文件，但 Kindle 浏览器不一定允许下载任何文件。** 各代 Kindle 的浏览器、固件对于 MOBI、AZW/AZW3、TXT、PDF、EPUB 等格式的处理不同，部分新版 Kindle 拒绝直接下载 AZW3，而 EPUB 经浏览器直接下载通常不被支持。能从「Send to Kindle」导入 EPUB，**不代表**浏览器能直接下载 EPUB。

如果网页可以访问而点击文件不能下载，先用一个**无 DRM 的 MOBI** 文件测试。需要 EPUB 的 Kindle 导入，可以使用 Amazon 的 Send to Kindle 服务（它有另外的上传限制），或者电脑 USB 传输。此 App 不会自动把 EPUB 转成 MOBI/AZW3，也不能突破 Kindle 的固件、存储空间、网络稳定性限制。

## 文件大小、稳定性和安全

- “无大小限制”意为**服务端程序没有人为设置的文件大小上限**。书籍的真实大小、手机剩余空间/存储提供方、Kindle 可用空间、浏览器下载能力和传输期间的稳定性仍然是实际限制。
- 长时间下载需要手机保持此 App 在前台、屏幕常亮；为了简单，本版本没有后台前台服务、关屏持续传输保证。
- 临时 URL 带随机令牌；但 HTTP 流量**不加密**。只在信任的私人局域网中使用，不要在公共 Wi-Fi 上共享敏感文件。
- 此工具只在局域网开放，无公共互联网域名，不依赖第三方服务器。不支持跨城、不同网络直接访问。
- 如果 Android 显示不恰当的本地 IP（多网卡/VPN/热点设备），可断开 VPN，只保留 Wi-Fi 再重启共享。

## 在电脑上独立测试 HTTP 核心

HTTP 服务完全使用 Java 标准库，在普通 JDK 17+ 中即可编译测试，不需要 Android SDK：

```bash
mkdir -p /tmp/kindle-server-test
javac -encoding UTF-8 -d /tmp/kindle-server-test \
  app/src/main/java/app/kindlelan/LocalBookServer.java \
  tests/LocalBookServerJvmTest.java
java -cp /tmp/kindle-server-test LocalBookServerJvmTest
```

已覆盖：索引页面、中文文件名、完整下载、Range 局部下载、后缀 Range、HEAD、错误令牌、只读方法限制、超过 2GB 的 Content-Length。测试不代表每一款 Kindle 固件都已验证。

## 技术结构

- `app/src/main/java/app/kindlelan/MainActivity.java` — Android 原生选择文件、临时网址及界面
- `app/src/main/java/app/kindlelan/LocalBookServer.java` — 仅标准 Java 的临时 HTTP 服务器
- `app/src/main/AndroidManifest.xml` — 基础 Android 应用声明与网络权限
- `tests/LocalBookServerJvmTest.java` — 电脑端的纯 JVM 接口测试

本工程供个人使用、学习和按需修改。没有远程遥测、统计和账户系统。
