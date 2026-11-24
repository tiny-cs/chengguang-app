# 城光 · Google Play 上架资料草稿

> 状态：草稿，仅供用户审阅。**不要直接提交到 Play Console，不要创建上架清单，不要打正式 AAB。**
> 版本：1.2.3（versionCode 7）· 包名：`com.maihe.chengguang` · 开发者账号：麦禾云创（用户说商家声明、身份验证已全部通过）
> 最后更新：2026-11-24

---

## 一、上架文案

### 应用名称

- 中文：城光
- 英文：Chengguang（Play 上如已有同名应用，Console 会在创建时提示，再改英文名或加副标题，此处先用 Chengguang）

### 简短说明（中文，主用）

> 属灵书籍、敬拜赞美、主日信息与灵修信息，离线也能听。无登录，无广告。

### 简短说明（英文）

> Spiritual books, hymns, sermons and videos — offline too. No sign-in, no ads.

### 完整说明（中文，主用）

> 城光是一个安静的属灵内容应用，收录属灵书籍、敬拜赞美，以及主日信息与灵修信息。
>
> 六个栏目：
> - 属灵书籍：慢慢读，一段一段读；
> - 敬拜赞美：清晨醒来，第一句就是赞美；
> - 主日信息：主日讲道音频与讲章；
> - 灵修信息：每日灵修音频；
> - 灵修信息：每天的灵修音频。
>
> 不用登录，不用注册，打开就能看、能听。音频和视频可以下载到手机里，离线也能播放；下载只在 WiFi 下进行（可在“我的”里改），下载的内容存在手机 App 自己的目录里，卸载 App 时一并删除。
>
> 应用里没有广告，不收集账号信息。内容由管理员在后台统一整理上传，App 打开时自动拉取最新目录。

### 完整说明（英文）

> Chengguang is a quiet place for spiritual reading and listening: spiritual books, hymns, sermon audio, sermon videos, Sunday messages and daily devotionals.
>
> Six sections: Spiritual Books, Hymns, Sermon Audio, Sermon Video, Sunday Messages and Devotionals. Open the app and start reading or listening — no sign-in, no account, no ads.
>
> Audio and video can be downloaded for offline playback (Wi-Fi only by default, changeable in "Mine"). Downloads live in the app's own storage and are removed when the app is uninstalled. Content is curated by the administrator; the app fetches the latest catalogue when it opens.

### 本次更新（1.2.3，写在 Play 的“新功能”里）

> 新增两个音频栏目：主日信息、灵修信息。修复了打开闪退的问题。

---

## 二、素材清单（Play Console 要填的）

| 素材 | 要求 | 状态 |
|---|---|---|
| 应用图标 512×512 | PNG，32 位，最大 1 MB | ✅ 已有：`~/workspace/chengguang-icon/chengguang-icon-512.png`，实测 512×512、158,342 字节。但**四个角是透明的**，Play 图标建议用不透明底。建议改用白底版：`chengguang-icon-2048-white.png`（或 `chengguang-icon-1024-white.jpg`）缩小到 512×512 再上传，不要直接用透明底的 512 图 |
| 宣传图 Feature Graphic 1024×500 | JPG/PNG，无透明 | ❌ 缺失。不要用假图凑数，需要时再做一张（城光圆标 + “城光”二字 + 一句简介，深蓝底金字，与 App 主色一致） |
| 手机截图（至少 2 张，最多 8 张） | JPG/PNG，每边 320–3840 px，建议 1080×1920 | ❌ 缺失，见下表，按顺序在已修好的 1.2.3 真机上截 |
| 隐私政策链接 | 必须填一个可公开访问的网址 | ❌ 缺失，政策正文见第三节，托管位置待用户决定 |
| 分类 | Console 里选一个 | 建议：图书与参考（Books & Reference），理由见第五节 |
| 内容分级问卷 | Console 里在线答题 | 待填，预期结果见第五节 |
| 目标受众 | Console 里选 | 成人为主；不针对儿童（不勾选“主要面向儿童”） |

### 要截的图（在红米真机、1.2.3 上，按这个顺序截）

1. 首页：六个栏目都看得见
2. 任一栏目列表页（如“主日信息”）
3. 书籍阅读页（如《认识基督》正文）
4. 音频播放页（如“信心的根基”，带播放进度）
5. 视频播放页（如“城光：序”）
6. “我的”页：已下载列表 + 总大小 + “仅在 WiFi 下下载”开关
7. （可选）主日信息、灵修信息两个新栏目列表页（各一条已上传的测试音频时再截）

截图要求：不要截到系统状态栏里的 VPN 图标和调试信息；内容用正式标题，不要用“测试-远程目录”这种测试标题。

---

## 三、隐私政策（草稿正文）

> 托管位置待定（二选一，见第六节）：发布到 `https://maihe334.ccwu.cc/` 下的一个页面，或 GitHub Pages。政策一经发布，把链接填进 Play Console 的隐私政策栏；政策里的日期和联系方式要与发布时一致。

### 中文版

# 城光隐私政策

最后更新：2026 年 11 月 24 日

城光（以下简称“本应用”）由麦禾云创开发。本应用不要求注册、不要求登录，不收集姓名、电话、邮箱、位置等个人信息，不投放广告，不使用第三方广告 SDK。

1. **我们收集什么**：什么都不收集。本应用没有账号系统，不记录你的阅读、收听记录，也不把这些记录上传到任何服务器。
2. **网络请求**：本应用打开时会向 `https://chengguang-admin.zxq4455.workers.dev/catalog.json` 请求一次内容目录（只有标题、栏目、时长、简介、播放链接等公开内容信息）；播放或下载音视频时，会直接从内容存储地址读取音视频文件。除此之外，本应用不向其他服务器发送请求。
3. **下载内容**：你下载的音频、视频只保存在本机 App 自己的目录里，不会上传；卸载本应用时，这些文件会随之一并删除。
4. **崩溃上报**：本应用内置了崩溃上报代码，但目前没有配置上报地址，处于关闭状态，不会发送任何崩溃信息。将来如需开启，会先更新本政策。
5. **联系我们**：如对本政策有疑问，请通过应用商店页面上的开发者联系方式与我们联系。

### 英文版（简版，与中文版同义）

# Chengguang Privacy Policy

Last updated: 24 November 2026

Chengguang requires no sign-in and no account. It collects no personal information — no name, email, phone or location — shows no ads and uses no advertising SDK.

1. The app fetches a public content catalogue from `https://chengguang-admin.zxq4455.workers.dev/catalog.json` when opened, and streams or downloads audio/video files from the content storage when you play or download them. It sends no other network requests.
2. Downloaded audio and video are stored only in the app's own storage on your device and are deleted when the app is uninstalled.
3. Crash reporting code is included but currently disabled (no reporting address configured), so it sends nothing.
4. Contact the developer through the contact details on the store listing if you have questions about this policy.

---

## 四、正式版构建计划（先列步骤，不执行）

Play 上架必须用**签名的 Release AAB**，现在发的调试 APK（`chengguang-debug.apk`）不能上架。

1. **新建一个 Release 签名密钥（keystore）**：用 `keytool -genkeypair` 生成一个专用 keystore（别名、有效期建议 25 年以上）。这是最关键的一步——**密钥一旦丢失，这个包名的应用就再也无法更新，只能重新上架一个新应用**。生成时不要用与 VPN 应用或其他项目共用的密钥。
2. **保管决定（需用户定）**：keystore 文件 + 两个密码（keystore 密码、key 密码）由谁保管。建议：用户自己保管一份原件，Muse 只在构建时用 GitHub Secret 里的副本；或者全部由用户保管、每次发布时再提供。不要把 keystore 和密码写进仓库、不要发在聊天记录里长期留存。
3. **加签名配置**：在 `app/build.gradle.kts` 里加 `signingConfigs { release { ... } }`，从环境变量或 `keystore.properties`（不进仓库）读密码；`buildTypes.release` 用该签名并开 `isMinifyEnabled = false`（与现在一致，先不开混淆，避免闪退难查）。
4. **打 AAB**：`gradle bundleRelease`，产物在 `app/build/outputs/bundle/release/app-release.aab`。或在 GitHub Actions 里加一个 release-AAB 任务，构建时从 Secret 读 keystore。
5. **GitHub Secret（到时再建，现在不要建）**：`KEYSTORE_BASE64`（keystore 文件转 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。建好后只在 Actions 里用，不在日志里打印。
6. **版本**：用已测试过的 1.2.3（versionCode 7）出第一个正式版；若 1.2.3 测试发现问题，先修好再出正式版，不要用一个已知有问题的版本上架。
7. **上架流程（Play Console，人工操作）**：创建应用 → 填本文件第一、二节的文案和素材 → 填隐私政策链接 → 内容分级问卷 → 目标受众 → 上传 AAB 到“正式版”轨道 → 提交审核。审核通常几小时到几天，宗教类内容应用一般按普通内容应用审核，不会因“宗教”本身被拒，但标题、描述、截图里不要出现夸大、医疗、敛财类承诺。

---

## 五、内容分级与分类

- **分类建议：图书与参考（Books & Reference）**。理由：城光的核心是书籍、诗歌、灵修文字阅读，音频、视频是同一内容的另一种形式；“生活方式（Lifestyle）”太宽泛，在商店里不易被想找属灵书籍的人找到。若 Console 里只能二选一，选图书与参考。
- **内容分级预期**：宗教内容，无暴力、无性内容、无赌博、无酒精毒品、无用户生成内容（内容全部由管理员上传，用户不能上传、不能评论、不能发帖）。问卷按实回答，预期为“所有人”或 3+ / Everyone 级别；不要在问卷里把“宗教”理解成敏感内容而多选。
- **目标受众**：成人；不针对儿童，不勾选“主要面向儿童”。应用内无内购、无广告，问卷里如实选“无内购、无广告”。

---

## 六、提交前测试清单（1.2.3，必须全部通过再打正式版）

- [ ] 覆盖安装 1.2.3，打开不闪退，首页正常显示
- [ ] 首页可见 5 个栏目：属灵书籍、敬拜赞美、主日信息、灵修信息、专题讲座
- [ ] **先在管理后台重新部署 Worker**：后台代码在 `admin/src/index.js` 里已加了专题讲座栏目和 /file/ Range 修复，但线上 Worker 还是旧代码，不重新部署，后台上传下拉框里看不到专题讲座（与之前登录修复那次一样，改 `index.js` → 编辑代码 → 粘贴 → 部署）
- [ ] 后台上传：主日信息上传一条音频、灵修信息上传一条音频（正式标题，不要用“测试”标题；测完如需删再删）
- [ ] 重新打开 App，两个新栏目里出现刚上传的内容，能播放
- [ ] 下载一条音频：WiFi 下能下载，断网（飞行模式）能离线播放；在“我的”里能删除
- [ ] 删除后台里那条测试内容，重新打开 App，确认它消失了
- [ ] 检查 512 图标：用白底版，不要用透明底的 512 图

---

## 七、需要用户定的事（下一步就这几件）

1. **正式签名密钥由谁保管**：用户自己保管原件、Muse 只用 GitHub Secret 副本，还是全部由用户保管？（见第四节第 2 步）
2. **截图**：按第二节的 6 张清单，在红米真机 1.2.3 上截好发来，或用户自己在 Console 里传。
3. **隐私政策托管在哪**：发布到 `maihe334.ccwu.cc` 下一个新页面，还是 GitHub Pages？（政策正文已在第三节写好，只差一个公开链接）
4. **宣传图 1024×500**：要不要做一张（城光圆标 + “城光” + 一句简介，深蓝底金字）？要的话我来做，做好后发你确认再上传。
5. **确认英文文案**：第一节的英文简短说明和完整说明是否就这样用，还是要再改。
6. **确认上架版本**：1.2.3 测试通过后，直接用它出第一个正式版，不再等新功能。
