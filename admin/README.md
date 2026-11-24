# 城光管理后台 · 设计说明

## 结构

```
admin/
  src/index.js      Worker 全部代码（单文件：接口 + 管理页 HTML）
  wrangler.toml     Worker 配置：名字 chengguang-admin，绑定 R2 桶 chengguang
  DEPLOY.md         部署步骤（中文，含 R2 绑卡说明和备选 B2）
  README.md         本文件
```

一个 Worker 干三件事：① 托管管理页（`/`）；② 给 App 提供目录（`/catalog.json`）和文件播放（`/file/...`）；
③ 管理接口（`/api/*`，要登录）。

## 数据放哪里

- **文件**：R2 桶 `chengguang`，`media/<id>.<扩展名>`。桶保持私有，不开公开访问。
- **目录**：同一桶里的 `catalog.json`，结构和 App 内置 `catalog.json` 完全一样，
  每条多三个字段：`status`（published / pending）、`uploadedBy`、`createdAt`。
  App 读的时候只认 published 的；Worker 返回 `/catalog.json` 时也只给 published 的。
- **密码**：Worker Secret `ADMIN_PASSWORD`，不在代码、不在仓库里。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 管理页（中文） |
| GET | `/catalog.json` | App 拉目录（公开，只含 published） |
| GET | `/file/media/<id>.<ext>` | 播放 / 下载文件（公开走 Worker 代理，支持断点续传 Range） |
| POST | `/api/login` | `{password}` → 写 HttpOnly Cookie `cg_admin`（30 天） |
| POST | `/api/logout` | 清 Cookie |
| GET | `/api/me` | 是否已登录 |
| GET | `/api/items` | 全部目录 + 存储占用（要登录） |
| POST | `/api/upload` | multipart 小文件上传（书籍 / 诗歌附件，要登录；音视频已改走分块上传） |
| POST | `/api/upload/init` | 分块上传第 1 步：建分块上传，返回 uploadId / key / partSize=10MB（要登录） |
| POST | `/api/upload/part?uploadId=&key=&partNumber=` | 分块上传第 2 步：body 为一块原始数据（10MB），返回 etag（要登录） |
| POST | `/api/upload/complete` | 分块上传第 3 步：合并分块并写目录（要登录） |
| POST | `/api/upload/abort` | 取消未完成的分块上传（要登录） |
| POST | `/api/feedback` | 读者提建议（公开，不用登录）：`{text, contact?}` → 存 R2 `feedback.json`，最多 500 条 |
| GET | `/api/feedback` | 读者建议列表（要登录） |
| DELETE | `/api/feedback/:id` | 删一条建议（要登录） |
| PUT | `/api/items/:id` | 改标题 / 栏目 / 简介 / 时长 / 正文（要登录） |
| DELETE | `/api/items/:id` | 删目录 + 删 R2 文件（要登录） |

## 上传规则

- 书籍 / 诗歌：填正文即可，文件可选（PDF 等附件）。
  也可以点“从文件导入正文”选一个 TXT、PDF 或 Word `.docx`，浏览器自动提取文字填进正文框，
  检查后点保存即可（TXT 直接读；PDF 用 pdf.js 按页提文字，扫描图片版提不出；Word 用 mammoth 提 `.docx` 纯文本，
  老格式 `.doc` 请另存为 `.docx` 再传；超过 100 万字请按章节拆开传）。原文件不存进 R2，只存提取出的正文。
- 音频 / 视频：必须传文件；只收 mp3/m4a/wav/ogg/aac/flac 和 mp4/mov/webm/mkv/m4v。
- 音视频走分块上传：浏览器把文件切成 10MB 一块顺序传到 Worker，Worker 经 R2 分块上传接口写进桶，
  绕过 Workers 单次 100MB 请求体上限。音频上限 500MB，视频上限 2GB。
  书籍 / 诗歌的附件仍走旧的单次上传（上限 100MB）。
- 上传后立即进目录，App 下次打开就能看到，不用重新打包 App。

## 以后加“指定人上传、管理员审核”

不用重做：记录里已有 `status` 和 `uploadedBy` 字段。到时：

1. 上传接口按上传者身份写 `status: "pending"`（现在管理员上传直接写 `published`）；
2. 管理页加一栏“待审核”，管理员点“通过”把 `status` 改成 `published`，点“不通过”删除；
3. App 和 `/catalog.json` 本来就只返回 published，不用改。

## 安全说明

- 管理接口只认 Cookie（HttpOnly，30 天），密码本身不经过前端存储。
- 文件播放走 Worker 代理，不暴露 R2 的公开地址和桶名以外的信息。
- 上传只按扩展名白名单收文件，文件名不直接用用户传的文件名（用 `media/<id>.<ext>`）。
- 目录 JSON 里的 `url` 是 Worker 代理地址，不是 R2 直链。
