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

## 以后接 R2 的计划（暂不做）

1. 管理员把音视频文件传到 Cloudflare R2 桶
2. 在 R2 旁放一份同结构的 `catalog.json`，或用 Worker + D1 提供目录接口
3. App 把本地 `catalog.json` 的读取地址改成 R2 公开链接，其余不动

<!-- 重新触发构建：首个 Actions 运行失败（Build debug APK 步骤 exit 1），原因待查 -->
