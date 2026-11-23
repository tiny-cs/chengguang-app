# 城光管理后台 · 部署步骤（中文）

管理后台 = 一个 Cloudflare Worker（`src/index.js`，单文件）+ 一个 R2 桶（`chengguang`）。
文件存在 R2，目录存在 R2 的 `catalog.json`，App 打开时从 Worker 拉目录。

## 前置条件

- Cloudflare 账号（用户已有，邮箱 zxq4455@outlook.com）。
- **R2 开通需要账号里放一个付款方式（信用卡或 PayPal），免费额度内不扣费。**
  用户已有 PayPal（zxq4455@outlook.com），在 Dashboard → Billing → Payment Methods 里加 PayPal 即可。
  只加付款方式、不买任何东西；R2 每月免费 10GB 存储、100 万次写入、1000 万次读取，流出流量免费，
  超出后存储 $0.015/GB/月。Cloudflare 没有“到额度自动停”的开关，超了会扣款，
  所以后台首页会显示已用存储，自己留意。
- Workers 免费版（每天 10 万次请求）**不需要绑卡**，只开 Workers 不够用——文件必须放 R2。

## 第 1 步：开通 R2，建桶

1. 打开 https://dash.cloudflare.com → 左边 **R2 Object Storage**。
2. 如果提示购买 R2 套餐，先在 Billing 里加好付款方式（卡或 PayPal），再点 **Purchase R2 Plan**（免费额度内 $0）。
3. 点 **Create bucket**，桶名填 `chengguang`，位置选默认（Automatic），点 Create。
4. 桶建好后**不用**开公开访问（Public）：App 和播放都走 Worker 代理 `/file/...`，桶保持私有更安全。

## 第 2 步：部署 Worker（二选一）

### 办法 A：命令行（推荐，有 wrangler 时）

```sh
cd admin
npm install -g wrangler   # 已装过可跳过
npx wrangler login        # 浏览器里登录 Cloudflare 账号
npx wrangler deploy       # 发布，得到 https://chengguang-admin.<你的子域名>.workers.dev
npx wrangler secret put ADMIN_PASSWORD   # 输入管理员密码（只设一次，存在 Cloudflare，不在代码里）
```

### 办法 B：网页后台（不用命令行）

1. 打开 https://dash.cloudflare.com → **Workers & Pages** → **Create** → **Worker**，
   名字填 `chengguang-admin`，点 Deploy。
2. 点 **Edit code**，把 `admin/src/index.js` 的全部内容粘贴进去，点 **Deploy**。
3. 回到 Worker 页 → **Settings** → **Variables** → **Add variable**，
   选 **Secret**，名字 `ADMIN_PASSWORD`，值填管理员密码，保存。
4. 同一页往下找 **Bindings** → **Add binding** → **R2 bucket**，
   Variable name 填 `CHENGGUANG_BUCKET`，Bucket 选 `chengguang`，保存后重新部署一次。

## 第 3 步：记下后台地址，填进 App

部署成功后 Worker 地址形如：

```
https://chengguang-admin.<你的子域名>.workers.dev
```

- 管理后台地址就是这个（打开就是登录页）。
- App 拉目录的地址是：`<Worker 地址>/catalog.json`

把这个地址填进 App 代码 `app/src/main/java/com/maihe/chengguang/Catalog.kt`
里的 `REMOTE_CATALOG_URL`（现在是空字符串，占位），重新打包 App（推到 GitHub 自动打），
App 下次打开就会从后台拉目录；拉不到（没网、后台没部署）时自动用 App 内置的示例目录，不会白屏。

## 第 4 步：测试

1. 打开管理后台地址，输入管理员密码登录。
2. 上传一条讲道音频（选文件、填标题、点保存），列表里出现它，存储占用变了。
3. 打开 App（新版），首页 / 全部里出现这条音频，点开能播放，点“下载到手机”能离线播。
4. 在后台点“删除”，App 重新打开后这条就没了。

## 以后改密码

- 命令行：`npx wrangler secret put ADMIN_PASSWORD` 再输新密码。
- 网页：Worker → Settings → Variables → 找到 ADMIN_PASSWORD → Edit。

## 备选方案（如果不想给 Cloudflare 加付款方式）

R2 不加付款方式开不了。备选是 Backblaze B2：同样 10GB 免费、S3 兼容、**不用绑卡**，
但免费下载流量只到存储量的 3 倍（R2 是无限），且要把 Worker 里的 R2 调用改成 S3 兼容调用、
再加 B2 的密钥——等于换一套后端，没做。想用 B2 的话先定下来，我再改。
