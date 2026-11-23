# 城光 Chengguang

属灵内容 App 原型（Android 空壳版），对标生命粮仓：属灵书籍、生命诗歌、讲道音频、讲道视频。

- 应用名：城光（中文界面）
- 包名：`com.maihe.chengguang`
- 技术栈：Kotlin + Jetpack Compose（与麦禾 VPN 同款：AGP 8.5.2 / Kotlin 2.0.21 / Gradle 8.7 / JDK 17）
- 无登录、无用户上传、无广告、无后端

## 目录从哪里来

App 启动时读 `app/src/main/assets/catalog.json`，不把内容写死在 Kotlin 代码里。
以后正式版只要把这个文件换成从 Cloudflare R2 拉取的同一份 JSON，App 不用改代码。

`catalog.json` 每条记录的字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 唯一编号，如 `b1` / `p1` / `a1` / `v1` |
| `title` | 标题 |
| `category` | 栏目：`属灵书籍` / `生命诗歌` / `讲道音频` / `讲道视频` |
| `type` | 类型：`book` / `poem` / `audio` / `video` |
| `url` | 音频/视频播放链接；书籍/诗歌为空字符串 |
| `duration` | 时长，如 `32:10`；书籍/诗歌为空字符串 |
| `size` | 文件大小，如 `45 MB`；可为空 |
| `description` | 一句话简介 |
| `content` | （可选）书籍/诗歌的正文，多段用 `\n` 分隔 |

## 现在的示例内容

原型自带 16 条示例（每栏目 4 条），标题为通用示例、正文为原创短句，
音频/视频链接是公开示例文件（SoundHelix / Google 示例视频），仅用于验证播放器，
正式发布前必须替换为自己的 R2 链接。

## 启动图标（v1.1.1 起）

用户选定的白底圆形城光标（`chengguang-icon-2048-white.png`）。仓库里只存两张 512px
原图的 base64 文本（`app/icon-sources/*.b64`，因为 push 不接受二进制文件），构建时
`decodeLauncherIcons` 任务解码并生成各密度 mipmap 和自适应图标前景：传统图标用白底整图，
自适应前景用透明底版、圆形控制在 66dp 安全区内、白底为 `@color/chengguang_white`，
在任何形状的启动器遮罩下都不会被裁切。

## 离线下载（v1.1.0 新增）

- 音频/视频详情页有“下载到手机”：用系统 DownloadManager 下载，带进度条、取消；
  下载完显示“已下载，可离线播放”，播放自动走本地文件。
- “我的”页有已下载列表（个数、总大小）、逐条删除、“仅在 WiFi 下下载”开关（默认开）。
- 下载文件放在 App 专属目录（`getExternalFilesDir(DOWNLOADS)`），不需要申请存储权限，
  随 App 卸载一起删除。书籍/诗歌正文已在 `catalog.json` 里随 App 自带，本来就能离线读，
  不做下载按钮。
- 只做下载，不做用户上传。

## 构建

推到 GitHub 后由 Actions 自动编译 debug APK 并发布到 Releases，
在 Releases 页下载 `chengguang-debug.apk` 直接安装即可。

本地构建（需 Android SDK + JDK 17）：

```sh
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 管理后台（v1.2.0 起）

内容不再只靠 App 内置目录。管理后台是一个 Cloudflare Worker + R2 桶，代码在 `admin/`：

- `admin/src/index.js`：Worker 全部代码（管理页 + 接口 + 文件代理），单文件。
- `admin/wrangler.toml`：Worker 名 `chengguang-admin`，绑定 R2 桶 `chengguang`。
- `admin/DEPLOY.md`：部署步骤（中文）。**R2 开通需要在 Cloudflare 账号里放一张卡或 PayPal**
  （免费额度内不扣费）；Workers 免费版不用绑卡。部署前请先读它。
- `admin/README.md`：接口和设计说明。

App 启动时先显示内置目录，再从 `Catalog.kt` 里的 `REMOTE_CATALOG_URL`
（`<Worker 地址>/catalog.json`）拉远程目录；拉不到就继续用内置的，不会白屏。
`REMOTE_CATALOG_URL` 现在是空字符串（后台还没部署），部署好 Worker 后填上、重新打包即可。

管理页功能：密码登录、上传（音频/视频传文件，书籍/诗歌填正文）、改标题/简介/栏目、
删除（目录 + R2 文件一起删）、看存储占用。以后“指定人上传、管理员审核”不用重做：
记录里已有 `status` / `uploadedBy` 字段，上传的人写 `pending`、管理员通过改 `published` 即可。

## 报错收集 Sentry（v1.2.0 起，待启用）

- 组织：`maihe`（和麦禾 VPN Windows 版同一个 Sentry 账号，https://maihe.sentry.io，美国区，
  免费 Developer 套餐，每月 5000 条，不用绑卡），账号 zxq4455@outlook.com。
- **项目 `chengguang-android` 还没建**——建项目要登录 sentry.io（浏览器操作），
  本版只把接 Sentry 的代码和构建注入做好了，DSN 为空时 Sentry 不初始化，App 照常用。
  建好项目后：① 在 Sentry 里新建项目 `chengguang-android`（平台 Android），拿到 DSN；
  ② 在 GitHub 仓库 `tiny-cs/chengguang-app` 加 Secret `SENTRY_DSN`；
  ③ 工作流 `.github/workflows/build-apk.yml` 的构建命令改成
  `gradle assembleDebug -PsentryDsn=${{ secrets.SENTRY_DSN }}`（工作流文件只能在网页上改，
  API 推不上去）；④ 重新推一次代码触发构建。
- 收集范围：App 闪退（SDK 自动）、音频/视频播放失败、下载启动失败、远程目录拉取失败。
- 脱敏（`ErrorReporting.kt` 的 `beforeSend`）：只发报错堆栈，不发内容、链接、UUID、账号信息；
  请求信息和用户信息清掉，消息里的链接、UUID、文件路径替换成占位符。
  DSN 是客户端标识（Sentry 设计如此，可进公开仓库），但本项目仍按构建时注入处理，不写死在代码里。
- 验证：装好带 DSN 的新版后，故意断网打开一条远程音频（或在 Sentry 里看有没有收到
  `[catalog]` / `[audio-playback]` 的事件），Sentry 项目的 Issues 里出现对应条目即接通。
  注意：本版（DSN 为空）装上后 Sentry 里**不会**有任何事件，这是正常的——先建项目、配好 DSN 再验证。
