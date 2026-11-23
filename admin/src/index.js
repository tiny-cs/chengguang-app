// 城光管理后台 — Cloudflare Worker（单文件，无依赖）
// 部署：见同目录 DEPLOY.md。R2 桶名 chengguang，绑定名 CHENGGUANG_BUCKET。
// 管理员密码存在 Worker Secret ADMIN_PASSWORD 里，不在代码里。
// 目录存在 R2 的 catalog.json；音视频文件存在 R2 的 media/ 下，App 只存链接。
// 预留字段：每条记录有 status（published / pending）和 uploadedBy，
// 以后“指定人上传、管理员审核”就是：上传的人写 status=pending，
// 管理员在后台点通过改成 published；App 只拉 published 的。

const CATEGORIES = ["属灵书籍", "生命诗歌", "讲道音频", "讲道视频"];
const TYPE_BY_CATEGORY = { "属灵书籍": "book", "生命诗歌": "poem", "讲道音频": "audio", "讲道视频": "video" };
const TYPE_PREFIX = { book: "b", poem: "p", audio: "a", video: "v" };
const MAX_FILE_BYTES = 100 * 1024 * 1024; // Workers 免费版单次请求体上限 100MB
const AUDIO_EXTS = ["mp3", "m4a", "wav", "ogg", "aac", "flac"];
const VIDEO_EXTS = ["mp4", "mov", "webm", "mkv", "m4v"];
const CATALOG_KEY = "catalog.json";

// 首次部署时自动写入的示例目录（与 App 内置 catalog.json 同源，见 build 时注入）。
const SEED_CATALOG = [{"category": "属灵书籍", "content": "认识基督，不是一套道理，乃是一个人。\n\n我们越认识祂，就越认识自己；越亲近祂，就越有力量走前面的路。\n\n这是一段示例文字，正式内容由管理员上传替换。", "description": "认识基督，是属灵生命的起头，也是每天的功课。", "duration": "", "id": "b1", "size": "", "title": "认识基督", "type": "book", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "属灵书籍", "content": "真实的属灵，不在乎人看得见的热心，乃在乎里面有没有基督的成分。\n\n生命长大是一天一天的事，急不得，也假不得。\n\n这是一段示例文字，正式内容由管理员上传替换。", "description": "属灵的事不在外面，乃在里面；不在知识，乃在生命。", "duration": "", "id": "b2", "size": "", "title": "属灵的实际", "type": "book", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "属灵书籍", "content": "环境来的时候，人总想问为什么；等走过去了，才看见那是恩典的手。\n\n经历不是白白得来的，是在顺服里一点一点积攒的。\n\n这是一段示例文字，正式内容由管理员上传替换。", "description": "每一处环境，都是量给我们的功课。", "duration": "", "id": "b3", "size": "", "title": "生命的经历", "type": "book", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "属灵书籍", "content": "祷告的起头很简单：停下来，把心里的话告诉主。\n\n天天这样操练，里面就渐渐有了安息和亮光。\n\n这是一段示例文字，正式内容由管理员上传替换。", "description": "祷告不是仪式，乃是与主交通的生活。", "duration": "", "id": "b4", "size": "", "title": "祷告的操练", "type": "book", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "生命诗歌", "content": "清晨的光照进窗来，\n我的心向你歌唱；\n荣耀、尊贵、权柄、能力，\n都归于坐宝座的羔羊。\n\n（示例诗歌，正式内容由管理员上传替换。）", "description": "清晨醒来，第一句就是赞美。", "duration": "", "id": "p1", "size": "", "title": "荣耀归于主", "type": "poem", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "生命诗歌", "content": "长阔高深的爱，\n把我这个人全然包围；\n在软弱中祂扶持，\n在黑夜里祂是光。\n\n（示例诗歌，正式内容由管理员上传替换。）", "description": "祂的爱，量不尽，也说不完。", "duration": "", "id": "p2", "size": "", "title": "主爱长阔高深", "type": "poem", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "生命诗歌", "content": "羔羊无论往哪里去，\n我都愿意跟随；\n路虽窄，心却安，\n因为祂与我同行。\n\n（示例诗歌，正式内容由管理员上传替换。）", "description": "无论往哪里去，都跟随羔羊的脚踪。", "duration": "", "id": "p3", "size": "", "title": "跟随羔羊", "type": "poem", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "生命诗歌", "content": "一座城立在山上，\n光从城里透出来；\n愿这光照亮四方，\n叫人看见就归荣耀给父。\n\n（示例诗歌，正式内容由管理员上传替换。）", "description": "城造在山上，是不能隐藏的。", "duration": "", "id": "p4", "size": "", "title": "城上的光", "type": "poem", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "讲道音频", "description": "信心不是感觉，乃是扎根在神的话上。（示例音频）", "duration": "32:10", "id": "a1", "size": "45 MB", "title": "信心的根基", "type": "audio", "url": "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "讲道音频", "description": "人活着不是单靠食物，乃是靠神口里所出的一切话。（示例音频）", "duration": "28:45", "id": "a2", "size": "41 MB", "title": "生命的粮", "type": "audio", "url": "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "讲道音频", "description": "彼此相爱，是主给我们的新命令。（示例音频）", "duration": "41:20", "id": "a3", "size": "59 MB", "title": "爱的命令", "type": "audio", "url": "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "讲道音频", "description": "等候耶和华的，必重新得力。（示例音频）", "duration": "25:30", "id": "a4", "size": "36 MB", "title": "等候主", "type": "audio", "url": "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "讲道视频", "description": "城光 App 视频栏目示例，正式讲道视频由管理员上传替换。", "duration": "09:56", "id": "v1", "size": "158 MB", "title": "城光：序", "type": "video", "url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "讲道视频", "description": "讲道视频示例，正式内容由管理员上传替换。", "duration": "10:53", "id": "v2", "size": "176 MB", "title": "生命的粮", "type": "video", "url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "讲道视频", "description": "讲道视频示例，正式内容由管理员上传替换。", "duration": "14:48", "id": "v3", "size": "200 MB", "title": "山上的城", "type": "video", "url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "讲道视频", "description": "讲道视频示例，正式内容由管理员上传替换。", "duration": "12:14", "id": "v4", "size": "190 MB", "title": "光照在黑暗里", "type": "video", "url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}];

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...extraHeaders },
  });
}
function err(message, status = 400) { return json({ ok: false, error: message }, status); }

function humanSize(bytes) {
  if (!bytes) return "";
  const mb = bytes / 1024 / 1024;
  return mb < 1 ? (bytes / 1024).toFixed(1) + " KB" : mb.toFixed(1) + " MB";
}
function newId(type) {
  return TYPE_PREFIX[type] + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}
function extOf(name) {
  const m = String(name || "").toLowerCase().match(/\.([a-z0-9]{2,5})$/);
  return m ? m[1] : "";
}

// ---- 目录读写（R2） ----
async function readCatalog(env) {
  const obj = await env.CHENGGUANG_BUCKET.get(CATALOG_KEY);
  if (obj) {
    try {
      const items = JSON.parse(await obj.text());
      if (Array.isArray(items)) return items;
    } catch (e) { /* 损坏则回落到种子 */ }
  }
  const seed = SEED_CATALOG.map((it) => ({
    ...it, status: it.status || "published", uploadedBy: it.uploadedBy || "admin",
    createdAt: it.createdAt || "2026-11-23T00:00:00.000Z",
  }));
  await env.CHENGGUANG_BUCKET.put(CATALOG_KEY, JSON.stringify(seed, null, 2), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
  });
  return seed;
}
async function writeCatalog(env, items) {
  await env.CHENGGUANG_BUCKET.put(CATALOG_KEY, JSON.stringify(items, null, 2), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
  });
}

// ---- 鉴权：单管理员密码，存在 Secret ADMIN_PASSWORD ----
async function adminToken(env) {
  const pw = env.ADMIN_PASSWORD || "";
  if (!pw) return "";
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("chengguang-admin:" + pw));
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function cookieValue(request, name) {
  const raw = request.headers.get("Cookie") || "";
  for (const part of raw.split(";")) {
    const [k, ...rest] = part.trim().split("=");
    if (k === name) return rest.join("=");
  }
  return "";
}
async function isAuthed(request, env) {
  const token = await adminToken(env);
  if (!token) return false;
  if (cookieValue(request, "cg_admin") === token) return true;
  const auth = request.headers.get("Authorization") || "";
  return auth === "Bearer " + token;
}
async function requireAuth(request, env) {
  if (!env.ADMIN_PASSWORD) return err("未设置管理员密码：请先在 Worker 设置里添加 Secret ADMIN_PASSWORD", 500);
  if (!(await isAuthed(request, env))) return err("未登录", 401);
  return null;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    // ---- 公开：App 拉目录（只给 published 的） ----
    if (path === "/catalog.json" && request.method === "GET") {
      const items = await readCatalog(env);
      const published = items.filter((it) => (it.status || "published") === "published");
      return new Response(JSON.stringify(published), {
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Cache-Control": "public, max-age=60",
          "Access-Control-Allow-Origin": "*",
        },
      });
    }

    // ---- 公开：播放 / 下载文件（走 Worker 代理，R2 流出免费；支持断点续传） ----
    if (path.startsWith("/file/") && request.method === "GET") {
      const key = decodeURIComponent(path.slice("/file/".length));
      if (!/^media\/[A-Za-z0-9._-]+$/.test(key)) return err("文件不存在", 404);
      const range = request.headers.get("Range");
      const obj = range
        ? await env.CHENGGUANG_BUCKET.get(key, { range })
        : await env.CHENGGUANG_BUCKET.get(key);
      if (!obj) return err("文件不存在", 404);
      const headers = new Headers();
      obj.writeHttpMetadata(headers);
      headers.set("etag", obj.httpEtag);
      headers.set("Cache-Control", "public, max-age=31536000, immutable");
      headers.set("Access-Control-Allow-Origin", "*");
      if (obj.range) {
        const end = obj.range.offset + obj.range.length - 1;
        headers.set("Content-Range", "bytes " + obj.range.offset + "-" + end + "/" + obj.size);
        return new Response(obj.body, { status: 206, headers });
      }
      return new Response(obj.body, { headers });
    }

    // ---- 管理页 ----
    if (path === "/" && request.method === "GET") {
      return new Response(ADMIN_HTML, {
        headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" },
      });
    }

    // ---- 登录 ----
    if (path === "/api/login" && request.method === "POST") {
      if (!env.ADMIN_PASSWORD) return err("未设置管理员密码", 500);
      let body = {};
      try { body = await request.json(); } catch (e) { /* ignore */ }
      if (body.password !== env.ADMIN_PASSWORD) return err("密码不对", 401);
      const token = await adminToken(env);
      return json({ ok: true }, 200, {
        "Set-Cookie": "cg_admin=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000",
      });
    }
    if (path === "/api/logout" && request.method === "POST") {
      return json({ ok: true }, 200, {
        "Set-Cookie": "cg_admin=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0",
      });
    }
    if (path === "/api/me" && request.method === "GET") {
      const authErr = await requireAuth(request, env);
      if (authErr) return authErr;
      return json({ ok: true });
    }

    // ---- 以下 API 全部要登录 ----
    const authErr = await requireAuth(request, env);
    if (authErr) return authErr;

    // 列表 + 存储占用
    if (path === "/api/items" && request.method === "GET") {
      const items = await readCatalog(env);
      let totalBytes = 0, fileCount = 0, cursor = undefined;
      do {
        const listed = await env.CHENGGUANG_BUCKET.list({ cursor, limit: 1000 });
        for (const o of listed.objects) { totalBytes += o.size; fileCount++; }
        cursor = listed.truncated ? listed.cursor : undefined;
      } while (cursor);
      return json({ ok: true, items, totalBytes, fileCount, totalBytesText: humanSize(totalBytes) });
    }

    // 上传：multipart 表单。书籍/诗歌传正文 content；音频/视频必须传文件。
    if (path === "/api/upload" && request.method === "POST") {
      let form;
      try { form = await request.formData(); } catch (e) { return err("表单解析失败"); }
      const title = String(form.get("title") || "").trim();
      const category = String(form.get("category") || "").trim();
      const description = String(form.get("description") || "").trim();
      const duration = String(form.get("duration") || "").trim();
      const content = String(form.get("content") || "");
      const file = form.get("file");
      if (!title) return err("请填写标题");
      if (!CATEGORIES.includes(category)) return err("栏目不对");
      const type = TYPE_BY_CATEGORY[category];
      const now = new Date().toISOString();
      const item = {
        id: newId(type), title, category, type, url: "", duration,
        size: "", description, content: "",
        status: "published", uploadedBy: "admin", createdAt: now,
      };
      if (type === "book" || type === "poem") {
        if (!content.trim() && !(file && file.size > 0)) return err("书籍/诗歌请填写正文，或上传一个文件");
        item.content = content;
      } else {
        if (!file || file.size === 0) return err("音频/视频请先选一个文件");
        if (file.size > MAX_FILE_BYTES) return err("文件超过 100MB：Workers 免费版单次上传上限 100MB，请先压缩或剪短");
        const ext = extOf(file.name);
        const allowed = type === "audio" ? AUDIO_EXTS : VIDEO_EXTS;
        if (!allowed.includes(ext)) return err("不支持的文件格式：请传 " + allowed.join("、"));
        const key = "media/" + item.id + "." + ext;
        await env.CHENGGUANG_BUCKET.put(key, file.stream(), {
          httpMetadata: { contentType: file.type || (type === "audio" ? "audio/mpeg" : "video/mp4") },
        });
        item.url = url.origin + "/file/" + key;
        item.size = humanSize(file.size);
        if (!duration) item.duration = "";
      }
      // 书籍/诗歌如附了文件（PDF 等），也存 R2，链接放 url
      if ((type === "book" || type === "poem") && file && file.size > 0) {
        if (file.size > MAX_FILE_BYTES) return err("文件超过 100MB");
        const ext = extOf(file.name) || "pdf";
        const key = "media/" + item.id + "." + ext;
        await env.CHENGGUANG_BUCKET.put(key, file.stream(), {
          httpMetadata: { contentType: file.type || "application/pdf" },
        });
        item.url = url.origin + "/file/" + key;
        item.size = humanSize(file.size);
      }
      const items = await readCatalog(env);
      items.unshift(item);
      await writeCatalog(env, items);
      return json({ ok: true, item });
    }

    // 编辑：只改标题 / 栏目 / 简介 / 时长 / 正文（不改文件和类型）
    if (path.startsWith("/api/items/") && request.method === "PUT") {
      const id = decodeURIComponent(path.slice("/api/items/".length));
      let body = {};
      try { body = await request.json(); } catch (e) { return err("请求不对"); }
      const items = await readCatalog(env);
      const item = items.find((it) => it.id === id);
      if (!item) return err("找不到这条内容", 404);
      if (body.title !== undefined) item.title = String(body.title).trim() || item.title;
      if (body.category !== undefined && CATEGORIES.includes(body.category)) {
        item.category = body.category;
        item.type = TYPE_BY_CATEGORY[body.category];
      }
      if (body.description !== undefined) item.description = String(body.description);
      if (body.duration !== undefined) item.duration = String(body.duration);
      if (body.content !== undefined) item.content = String(body.content);
      await writeCatalog(env, items);
      return json({ ok: true, item });
    }

    // 删除：目录里删掉，R2 里的文件也删掉
    if (path.startsWith("/api/items/") && request.method === "DELETE") {
      const id = decodeURIComponent(path.slice("/api/items/".length));
      const items = await readCatalog(env);
      const idx = items.findIndex((it) => it.id === id);
      if (idx < 0) return err("找不到这条内容", 404);
      const [item] = items.splice(idx, 1);
      await writeCatalog(env, items);
      if (item.url && item.url.includes("/file/media/")) {
        const key = decodeURIComponent(item.url.split("/file/")[1] || "");
        if (/^media\/[A-Za-z0-9._-]+$/.test(key)) await env.CHENGGUANG_BUCKET.delete(key);
      }
      return json({ ok: true });
    }

    return err("接口不存在", 404);
  },
};

const ADMIN_HTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>城光 · 内容管理</title>
<style>
body{font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;max-width:860px;margin:0 auto;padding:20px;color:#222;background:#faf7f0}
h1{color:#1f2a44} h2{color:#1f2a44;border-bottom:2px solid #9a7b2d;padding-bottom:6px}
.card{background:#fff;border-radius:10px;padding:16px;margin:14px 0;box-shadow:0 1px 4px rgba(0,0,0,.08)}
label{display:block;margin:10px 0 4px;font-weight:bold}
input,select,textarea{width:100%;box-sizing:border-box;padding:9px;border:1px solid #ccc;border-radius:6px;font-size:15px}
textarea{min-height:120px}
button{background:#9a7b2d;color:#fff;border:0;border-radius:6px;padding:10px 18px;font-size:15px;cursor:pointer;margin-right:8px}
button.ghost{background:#fff;color:#333;border:1px solid #999}
button.danger{background:#8e4a2f}
table{width:100%;border-collapse:collapse;font-size:14px}
td,th{border-bottom:1px solid #eee;padding:8px 4px;text-align:left;vertical-align:top}
.muted{color:#888;font-size:13px}
#msg{color:#8e4a2f;min-height:20px}
</style>
</head>
<body>
<h1>城光 · 内容管理</h1>
<p class="muted">只有管理员能进。上传的文件存在 Cloudflare R2，App 打开时自动拉取最新目录。</p>

<div class="card" id="loginCard">
  <h2>登录</h2>
  <label>管理员密码</label>
  <input type="password" id="pw" placeholder="输入管理员密码">
  <div style="height:10px"></div>
  <button onclick="login()">登录</button>
  <p id="msg"></p>
</div>

<div id="main" style="display:none">
  <div class="card">
    <span id="storage">存储占用：加载中…</span>
    <button class="ghost" style="float:right" onclick="logout()">退出登录</button>
  </div>

  <div class="card">
    <h2 id="formTitle">上传新内容</h2>
    <input type="hidden" id="editId">
    <label>标题 *</label><input id="title">
    <label>栏目 *</label>
    <select id="category" onchange="onCategory()">
      <option>属灵书籍</option><option>生命诗歌</option><option>讲道音频</option><option>讲道视频</option>
    </select>
    <label>简介</label><input id="description" placeholder="一句话简介">
    <div id="textBlock">
      <label>正文（书籍 / 诗歌）</label>
      <textarea id="content" placeholder="把正文粘贴在这里，多段换行即可"></textarea>
    </div>
    <div id="fileBlock" style="display:none">
      <label>音频 / 视频文件 *（单个文件不超过 100MB）</label>
      <input type="file" id="file">
      <label>时长（如 32:10，可不填）</label><input id="duration" placeholder="32:10">
    </div>
    <div style="height:12px"></div>
    <button onclick="save()">保存</button>
    <button class="ghost" onclick="resetForm()">取消编辑</button>
    <p id="msg2"></p>
  </div>

  <div class="card">
    <h2>全部内容（<span id="count">0</span> 条）</h2>
    <table><thead><tr><th>标题</th><th>栏目</th><th>大小</th><th>操作</th></tr></thead>
    <tbody id="list"></tbody></table>
  </div>
</div>

<script>
var items = [];
function showMsg(t, id) { document.getElementById(id || "msg").textContent = t || ""; }
function onCategory() {
  var c = document.getElementById("category").value;
  var isMedia = (c === "讲道音频" || c === "讲道视频");
  document.getElementById("fileBlock").style.display = isMedia ? "block" : "none";
  document.getElementById("textBlock").style.display = isMedia ? "none" : "block";
}
async function login() {
  var r = await fetch("/api/login", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ password: document.getElementById("pw").value }) });
  var d = await r.json();
  if (!d.ok) { showMsg(d.error || "登录失败"); return; }
  document.getElementById("loginCard").style.display = "none";
  document.getElementById("main").style.display = "block";
  load();
}
async function logout() {
  await fetch("/api/logout", { method: "POST" });
  location.reload();
}
async function load() {
  var r = await fetch("/api/items");
  var d = await r.json();
  if (!d.ok) { showMsg(d.error || "加载失败"); return; }
  items = d.items || [];
  document.getElementById("count").textContent = items.length;
  document.getElementById("storage").textContent = "存储占用：" + (d.totalBytesText || "0 MB") + "（" + d.fileCount + " 个文件，R2 每月免费 10GB）";
  var html = "";
  for (var i = 0; i < items.length; i++) {
    var it = items[i];
    html += "<tr><td><b>" + esc(it.title) + "</b><br><span class='muted'>" + esc(it.description || "") + "</span></td>"
      + "<td>" + esc(it.category) + "</td><td>" + esc(it.size || "-") + "</td>"
      + "<td><button onclick='editItem(\"" + it.id + "\")'>编辑</button> "
      + "<button class='danger' onclick='delItem(\"" + it.id + "\",\"" + esc(it.title) + "\")'>删除</button></td></tr>";
  }
  document.getElementById("list").innerHTML = html || "<tr><td colspan='4'>还没有内容，先上传一条试试。</td></tr>";
}
function esc(s) { return String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }
function resetForm() {
  document.getElementById("editId").value = "";
  document.getElementById("formTitle").textContent = "上传新内容";
  document.getElementById("title").value = "";
  document.getElementById("description").value = "";
  document.getElementById("content").value = "";
  document.getElementById("duration").value = "";
  document.getElementById("file").value = "";
  showMsg("", "msg2");
}
function editItem(id) {
  var it = null;
  for (var i = 0; i < items.length; i++) if (items[i].id === id) it = items[i];
  if (!it) return;
  document.getElementById("editId").value = it.id;
  document.getElementById("formTitle").textContent = "编辑：" + it.title;
  document.getElementById("title").value = it.title;
  document.getElementById("category").value = it.category;
  document.getElementById("description").value = it.description || "";
  document.getElementById("content").value = it.content || "";
  document.getElementById("duration").value = it.duration || "";
  onCategory();
  window.scrollTo(0, 0);
}
async function save() {
  var editId = document.getElementById("editId").value;
  var title = document.getElementById("title").value.trim();
  if (!title) { showMsg("请填写标题", "msg2"); return; }
  if (editId) {
    var r = await fetch("/api/items/" + encodeURIComponent(editId), { method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title: title,
        category: document.getElementById("category").value,
        description: document.getElementById("description").value,
        duration: document.getElementById("duration").value,
        content: document.getElementById("content").value }) });
    var d = await r.json();
    if (!d.ok) { showMsg(d.error || "保存失败", "msg2"); return; }
    showMsg("已保存", "msg2"); resetForm(); load(); return;
  }
  var fd = new FormData();
  fd.append("title", title);
  fd.append("category", document.getElementById("category").value);
  fd.append("description", document.getElementById("description").value);
  fd.append("duration", document.getElementById("duration").value);
  fd.append("content", document.getElementById("content").value);
  var f = document.getElementById("file").files[0];
  if (f) fd.append("file", f);
  showMsg("正在上传，请稍等…", "msg2");
  var r2 = await fetch("/api/upload", { method: "POST", body: fd });
  var d2 = await r2.json();
  if (!d2.ok) { showMsg(d2.error || "上传失败", "msg2"); return; }
  showMsg("上传成功，App 打开就能看到", "msg2"); resetForm(); load();
}
async function delItem(id, title) {
  if (!confirm("确定删除「" + title + "」吗？文件和目录都会删掉。")) return;
  var r = await fetch("/api/items/" + encodeURIComponent(id), { method: "DELETE" });
  var d = await r.json();
  if (!d.ok) { alert(d.error || "删除失败"); return; }
  load();
}
fetch("/api/me").then(function (r) {
  if (r.ok) {
    document.getElementById("loginCard").style.display = "none";
    document.getElementById("main").style.display = "block";
    load();
  }
});
onCategory();
</script>
</body>
</html>
`;
