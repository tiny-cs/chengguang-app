// 城光管理后台 — Cloudflare Worker（单文件，无依赖）
// 部署：见同目录 DEPLOY.md。R2 桶名 chengguang，绑定名 CHENGGUANG_BUCKET。
// 管理员密码存在 Worker Secret ADMIN_PASSWORD 里，不在代码里。
// 目录存在 R2 的 catalog.json；音视频文件存在 R2 的 media/ 下，App 只存链接。
// 预留字段：每条记录有 status（published / pending）和 uploadedBy，
// 以后“指定人上传、管理员审核”就是：上传的人写 status=pending，
// 管理员在后台点通过改成 published；App 只拉 published 的。

const CATEGORIES = ["属灵书籍", "敬拜赞美", "主日信息", "灵修信息", "专题讲座"];
const TYPE_BY_CATEGORY = { "属灵书籍": "book", "敬拜赞美": "poem", "主日信息": "audio", "灵修信息": "audio", "专题讲座": "audio" };
// 媒体栏目（主日信息 / 灵修信息 / 专题讲座）不按栏目定类型，按文件后缀定：
// mp3、m4a 等是音频，mp4、mov 等是视频——同一个栏目里音频视频都能传，
// App 按每条记录的 type 显示音频页或视频页，下载、讲章都一样有。
const MEDIA_CATEGORIES = ["主日信息", "灵修信息", "专题讲座"];
function mediaTypeForExt(ext) {
  if (AUDIO_EXTS.includes(ext)) return "audio";
  if (VIDEO_EXTS.includes(ext)) return "video";
  return "";
}
const TYPE_PREFIX = { book: "b", poem: "p", audio: "a", video: "v" };
const MAX_FILE_BYTES = 100 * 1024 * 1024; // 旧的单次上传路径：Workers 免费版单次请求体上限 100MB
const PART_SIZE = 10 * 1024 * 1024; // 分块上传：每块 10MB（R2 要求除最后一块外每块 >= 5MB）
const MAX_AUDIO_BYTES = 500 * 1024 * 1024; // 音频分块上传上限 500MB
const MAX_VIDEO_BYTES = 2 * 1024 * 1024 * 1024; // 视频分块上传上限 2GB
const AUDIO_EXTS = ["mp3", "m4a", "wav", "ogg", "aac", "flac"];
const VIDEO_EXTS = ["mp4", "mov", "webm", "mkv", "m4v"];
const CATALOG_KEY = "catalog.json";
const SLIDES_EXTS = ["pdf", "ppt", "pptx"]; // 讲章附件：PDF 优先，也收 PPT/PPTX（App 里在线预览）
const MAX_SLIDES_BYTES = 100 * 1024 * 1024; // 讲章单次上传上限 100MB
const SLIDES_CONTENT_TYPE = { pdf: "application/pdf", ppt: "application/vnd.ms-powerpoint", pptx: "application/vnd.openxmlformats-officedocument.presentationml.presentation" };
const SHEET_EXTS = ["jpg", "jpeg", "png", "webp"]; // 歌谱图片：敬拜赞美一首歌一张
const MAX_SHEET_BYTES = 20 * 1024 * 1024; // 谱图单次上传上限 20MB
const SHEET_CONTENT_TYPE = { jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", webp: "image/webp" };
const FEEDBACK_KEY = "feedback.json"; // 读者建议：App“给开发者建议”提交的，只管理员能看
const MAX_FEEDBACK_CHARS = 2000;
const MEMBERS_KEY = "members.json"; // 成员账号：{ users: { 账号名: { salt, hash, disabled, createdAt } } }，密码只存加盐 SHA-256，不存明文
const SESSIONS_KEY = "sessions.json"; // 成员会话：{ tokens: { token: { username, expiresAt } } }
const MEMBER_SESSION_DAYS = 30; // 成员登录一次管 30 天

// 首次部署时自动写入的示例目录（与 App 内置 catalog.json 同源，见 build 时注入）。
const SEED_CATALOG = [{"category": "属灵书籍", "content": "认识基督，不是一套道理，乃是一个人。\n\n我们越认识祂，就越认识自己；越亲近祂，就越有力量走前面的路。\n\n这是一段示例文字，正式内容由管理员上传替换。", "description": "认识基督，是属灵生命的起头，也是每天的功课。", "duration": "", "id": "b1", "size": "", "title": "认识基督", "type": "book", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "属灵书籍", "content": "真实的属灵，不在乎人看得见的热心，乃在乎里面有没有基督的成分。\n\n生命长大是一天一天的事，急不得，也假不得。\n\n这是一段示例文字，正式内容由管理员上传替换。", "description": "属灵的事不在外面，乃在里面；不在知识，乃在生命。", "duration": "", "id": "b2", "size": "", "title": "属灵的实际", "type": "book", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "属灵书籍", "content": "环境来的时候，人总想问为什么；等走过去了，才看见那是恩典的手。\n\n经历不是白白得来的，是在顺服里一点一点积攒的。\n\n这是一段示例文字，正式内容由管理员上传替换。", "description": "每一处环境，都是量给我们的功课。", "duration": "", "id": "b3", "size": "", "title": "生命的经历", "type": "book", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "属灵书籍", "content": "祷告的起头很简单：停下来，把心里的话告诉主。\n\n天天这样操练，里面就渐渐有了安息和亮光。\n\n这是一段示例文字，正式内容由管理员上传替换。", "description": "祷告不是仪式，乃是与主交通的生活。", "duration": "", "id": "b4", "size": "", "title": "祷告的操练", "type": "book", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "敬拜赞美", "content": "清晨的光照进窗来，\n我的心向你歌唱；\n荣耀、尊贵、权柄、能力，\n都归于坐宝座的羔羊。\n\n（示例诗歌，正式内容由管理员上传替换。）", "description": "清晨醒来，第一句就是赞美。", "duration": "", "id": "p1", "size": "", "title": "荣耀归于主", "type": "poem", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "敬拜赞美", "content": "长阔高深的爱，\n把我这个人全然包围；\n在软弱中祂扶持，\n在黑夜里祂是光。\n\n（示例诗歌，正式内容由管理员上传替换。）", "description": "祂的爱，量不尽，也说不完。", "duration": "", "id": "p2", "size": "", "title": "主爱长阔高深", "type": "poem", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "敬拜赞美", "content": "羔羊无论往哪里去，\n我都愿意跟随；\n路虽窄，心却安，\n因为祂与我同行。\n\n（示例诗歌，正式内容由管理员上传替换。）", "description": "无论往哪里去，都跟随羔羊的脚踪。", "duration": "", "id": "p3", "size": "", "title": "跟随羔羊", "type": "poem", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}, {"category": "敬拜赞美", "content": "一座城立在山上，\n光从城里透出来；\n愿这光照亮四方，\n叫人看见就归荣耀给父。\n\n（示例诗歌，正式内容由管理员上传替换。）", "description": "城造在山上，是不能隐藏的。", "duration": "", "id": "p4", "size": "", "title": "城上的光", "type": "poem", "url": "", "status": "published", "uploadedBy": "admin", "createdAt": "2026-11-23T00:00:00.000Z"}];

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
function slidesKey(itemId, ext) { return "media/" + itemId + "-slides." + ext; }
function sheetKey(itemId, ext) { return "media/" + itemId + "-sheet." + ext; }
function keyFromFileUrl(u) {
  if (!u || !u.includes("/file/media/")) return "";
  const k = decodeURIComponent(u.split("/file/")[1] || "");
  return /^media\/[A-Za-z0-9._-]+$/.test(k) ? k : "";
}
/** 解析 Range 头（bytes=A-B / bytes=A- / bytes=-N），返回 {offset, length, start, end}；越界返回 {invalid: true}；没有 Range 返回 null。 */
function parseRange(header, size) {
  const m = /^bytes=(\d*)-(\d*)$/.exec(String(header || "").trim());
  if (!m) return null;
  let start = m[1] === "" ? null : parseInt(m[1], 10);
  let end = m[2] === "" ? null : parseInt(m[2], 10);
  if (start === null && end === null) return null;
  if (start === null) {
    start = Math.max(0, size - end);
    end = size - 1;
  } else {
    if (start >= size) return { invalid: true };
    end = (end === null || end >= size) ? size - 1 : end;
  }
  if (start > end) return { invalid: true };
  return { offset: start, length: end - start + 1, start, end };
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

// ---- 读者建议读写（R2 的 feedback.json，最新的在前面，最多留 500 条） ----
async function readFeedback(env) {
  const obj = await env.CHENGGUANG_BUCKET.get(FEEDBACK_KEY);
  if (!obj) return [];
  try {
    const list = JSON.parse(await obj.text());
    return Array.isArray(list) ? list : [];
  } catch (e) { return []; }
}
async function writeFeedback(env, list) {
  await env.CHENGGUANG_BUCKET.put(FEEDBACK_KEY, JSON.stringify(list, null, 2), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
  });
}

// ---- 成员账号（App 登录用）：账号密码存在 R2，密码只存加盐 SHA-256，不存明文 ----
// 会话存在 R2 的 sessions.json；每次查目录 / 取文件都要先验会话，停用的账号立刻断开。
function hexBuf(bytes) { return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join(""); }
async function sha256Hex(text) {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return hexBuf(hash);
}
function randomHex(nBytes) {
  const arr = new Uint8Array(nBytes);
  crypto.getRandomValues(arr);
  return hexBuf(arr);
}
async function readMembers(env) {
  const obj = await env.CHENGGUANG_BUCKET.get(MEMBERS_KEY);
  if (!obj) return { users: {} };
  try {
    const d = JSON.parse(await obj.text());
    return d && typeof d.users === "object" && d.users ? d : { users: {} };
  } catch (e) { return { users: {} }; }
}
async function writeMembers(env, data) {
  await env.CHENGGUANG_BUCKET.put(MEMBERS_KEY, JSON.stringify(data, null, 2), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
  });
}
async function readSessions(env) {
  const obj = await env.CHENGGUANG_BUCKET.get(SESSIONS_KEY);
  if (!obj) return { tokens: {} };
  try {
    const d = JSON.parse(await obj.text());
    return d && typeof d.tokens === "object" && d.tokens ? d : { tokens: {} };
  } catch (e) { return { tokens: {} }; }
}
async function writeSessions(env, data) {
  await env.CHENGGUANG_BUCKET.put(SESSIONS_KEY, JSON.stringify(data, null, 2), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
  });
}
// 成员凭证：优先认 Authorization: Bearer，其次认 cg_member Cookie（浏览器里登录用）。
function memberTokenFrom(request) {
  const auth = request.headers.get("Authorization") || "";
  if (auth.startsWith("Bearer ")) return auth.slice(7).trim();
  return cookieValue(request, "cg_member") || "";
}
// 验成员：会话存在、没过期、账号没被停用，才算登录。返回账号名，验不过返回 null。
async function validMember(request, env) {
  const token = memberTokenFrom(request);
  if (!token) return null;
  const sessions = await readSessions(env);
  const s = sessions.tokens[token];
  if (!s || !s.username || !s.expiresAt || s.expiresAt < Date.now()) return null;
  const members = await readMembers(env);
  const u = members.users[s.username];
  if (!u || u.disabled) return null;
  return s.username;
}
async function requireMember(request, env) {
  const username = await validMember(request, env);
  if (!username) return err("请先登录", 401);
  return null;
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

    // ---- 成员登录后才能拉目录（只给 published 的） ----
    // 注意：这里不能再用 public 缓存——边缘缓存一旦把 200 存下来，没登录的人也能直接拿到目录。
    if (path === "/catalog.json" && request.method === "GET") {
      const memberErr = await requireMember(request, env);
      if (memberErr) return memberErr;
      const items = await readCatalog(env);
      const published = items.filter((it) => (it.status || "published") === "published");
      return new Response(JSON.stringify(published), {
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Cache-Control": "private, max-age=60",
          "Access-Control-Allow-Origin": "*",
        },
      });
    }

    // ---- 成员登录后才能播放 / 下载文件（走 Worker 代理，R2 流出免费；支持断点续传） ----
    // 注意：R2 的 get() 不接受原始 Range 头字符串（会抛错、整个 Worker 报 500），
    // 必须自己解析成 {offset, length} 再传。播放器（MediaPlayer）和下载器都会发 Range 请求。
    if (path.startsWith("/file/") && (request.method === "GET" || request.method === "HEAD")) {
      const memberErr = await requireMember(request, env);
      if (memberErr) return memberErr;
      const key = decodeURIComponent(path.slice("/file/".length));
      if (!/^media\/[A-Za-z0-9._-]+$/.test(key)) return err("文件不存在", 404);
      const head = await env.CHENGGUANG_BUCKET.head(key);
      if (!head) return err("文件不存在", 404);
      const size = head.size;
      const range = parseRange(request.headers.get("Range"), size);
      if (range && range.invalid) {
        return new Response(null, {
          status: 416,
          headers: { "Content-Range": "bytes */" + size, "Accept-Ranges": "bytes", "Access-Control-Allow-Origin": "*" },
        });
      }
      const headers = new Headers();
      head.writeHttpMetadata(headers);
      headers.set("etag", head.httpEtag);
      headers.set("Cache-Control", "private, max-age=31536000, immutable");
      headers.set("Access-Control-Allow-Origin", "*");
      headers.set("Accept-Ranges", "bytes");
      if (request.method === "HEAD") {
        headers.set("Content-Length", String(size));
        return new Response(null, { headers });
      }
      const obj = range
        ? await env.CHENGGUANG_BUCKET.get(key, { range: { offset: range.offset, length: range.length } })
        : await env.CHENGGUANG_BUCKET.get(key);
      if (!obj) return err("文件不存在", 404);
      if (range) {
        headers.set("Content-Range", "bytes " + range.start + "-" + range.end + "/" + size);
        headers.set("Content-Length", String(range.length));
        return new Response(obj.body, { status: 206, headers });
      }
      headers.set("Content-Length", String(size));
      return new Response(obj.body, { headers });
    }

    // ---- 公开：读者给开发者提建议（App“我的”页，不用登录；只有管理员能在后台看到） ----
    if (path === "/api/feedback" && request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "POST, OPTIONS", "Access-Control-Allow-Headers": "Content-Type" },
      });
    }
    if (path === "/api/feedback" && request.method === "POST") {
      let body = {};
      try { body = await request.json(); } catch (e) { return err("请求不对"); }
      const text = String(body.text || "").trim();
      const contact = String(body.contact || "").trim().slice(0, 100);
      if (!text) return err("请先写一点建议再提交");
      if (text.length > MAX_FEEDBACK_CHARS) return err("建议太长了，请控制在 2000 字以内");
      const list = await readFeedback(env);
      list.unshift({
        id: "f" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
        text, contact, createdAt: new Date().toISOString(),
      });
      await writeFeedback(env, list.slice(0, 500));
      return json({ ok: true }, 200, { "Access-Control-Allow-Origin": "*" });
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

    // ---- 成员登录（App 用）：账号由管理员在后台建，密码加盐存 R2；登录一次管 30 天 ----
    // 返回的 token App 存在本机，每次拉目录 / 取文件都带 Authorization: Bearer 发过去；
    // Cookie 是给浏览器里登录用的，App 不走 Cookie。
    if (path === "/api/member/login" && request.method === "POST") {
      let body = {};
      try { body = await request.json(); } catch (e) { /* ignore */ }
      const username = String(body.username || "").trim();
      const password = String(body.password || "");
      if (!username || !password) return err("请填写账号和密码", 400);
      const members = await readMembers(env);
      const u = members.users[username];
      if (!u || u.disabled) return err("账号或密码不对", 401);
      const hash = await sha256Hex(u.salt + ":" + password);
      if (hash !== u.hash) return err("账号或密码不对", 401);
      // 记最后登录时间：后台成员列表里能看到谁最近在用（写失败也不影响登录）
      try {
        members.users[username].lastLoginAt = new Date().toISOString();
        await writeMembers(env, members);
      } catch (e) { /* ignore */ }
      const token = randomHex(32);
      const sessions = await readSessions(env);
      const now = Date.now();
      for (const t of Object.keys(sessions.tokens)) {
        if (!sessions.tokens[t].expiresAt || sessions.tokens[t].expiresAt < now) delete sessions.tokens[t];
      }
      sessions.tokens[token] = { username, expiresAt: now + MEMBER_SESSION_DAYS * 24 * 3600 * 1000 };
      await writeSessions(env, sessions);
      return json({ ok: true, username, token }, 200, {
        "Set-Cookie": "cg_member=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (MEMBER_SESSION_DAYS * 24 * 3600),
        "Access-Control-Allow-Origin": "*",
      });
    }
    if (path === "/api/member/logout" && request.method === "POST") {
      const token = memberTokenFrom(request);
      if (token) {
        const sessions = await readSessions(env);
        if (sessions.tokens[token]) { delete sessions.tokens[token]; await writeSessions(env, sessions); }
      }
      return json({ ok: true }, 200, {
        "Set-Cookie": "cg_member=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0",
        "Access-Control-Allow-Origin": "*",
      });
    }
    if (path === "/api/member/me" && request.method === "GET") {
      const username = await validMember(request, env);
      if (!username) return err("请先登录", 401);
      // 打开 App 校验时也更新一次：后台看到的就是谁最近在用（写失败不影响校验结果）
      try {
        const members = await readMembers(env);
        if (members.users[username]) {
          members.users[username].lastLoginAt = new Date().toISOString();
          await writeMembers(env, members);
        }
      } catch (e) { /* ignore */ }
      return json({ ok: true, username }, 200, { "Access-Control-Allow-Origin": "*" });
    }

    // ---- 以下 API 全部要登录 ----
    const authErr = await requireAuth(request, env);
    if (authErr) return authErr;

    // ---- 分块上传（大文件直传 R2，绕过 Workers 单次 100MB 请求体上限） ----
    // 流程：init 建分块上传 -> 每个 part 单独 POST 原始块（每块 10MB） -> complete 合并写目录。
    // 每块都走 Worker 转发到 R2，不需要 S3 密钥；admin.maihe334.ccwu.cc 在国内也能打开。
    // 音频上限 500MB，视频上限 2GB；每块 10MB，2GB 共 200 块，远低于 R2 的 10000 块上限。
    function mediaLimit(type) { return type === "audio" ? MAX_AUDIO_BYTES : MAX_VIDEO_BYTES; }
    function mediaLimitText(type) { return type === "audio" ? "500MB" : "2GB"; }

    // POST /api/upload/init {title, category, description, duration, filename, fileSize, contentType}
    if (path === "/api/upload/init" && request.method === "POST") {
      let body = {};
      try { body = await request.json(); } catch (e) { return err("请求不对"); }
      const title = String(body.title || "").trim();
      const category = String(body.category || "").trim();
      const filename = String(body.filename || "");
      const fileSize = Number(body.fileSize);
      if (!title) return err("请填写标题");
      if (!CATEGORIES.includes(category)) return err("栏目不对");
      const baseType = TYPE_BY_CATEGORY[category];
      if (baseType !== "audio" && baseType !== "video") return err("这个栏目不支持分块上传");
      const ext = extOf(filename);
      const type = mediaTypeForExt(ext);
      if (!type) return err("不支持的文件格式：请传音频（" + AUDIO_EXTS.join("、") + "）或视频（" + VIDEO_EXTS.join("、") + "）");
      if (!Number.isFinite(fileSize) || fileSize <= 0) return err("文件大小不对");
      if (fileSize > mediaLimit(type)) return err("文件超过 " + mediaLimitText(type) + "：请先压缩或剪短");
      if (Math.ceil(fileSize / PART_SIZE) > 10000) return err("文件太大");
      const itemId = newId(type);
      const key = "media/" + itemId + "." + ext;
      const contentType = String(body.contentType || "") || (type === "audio" ? "audio/mpeg" : "video/mp4");
      let mpu;
      try {
        mpu = await env.CHENGGUANG_BUCKET.createMultipartUpload(key, {
          httpMetadata: { contentType },
        });
      } catch (e) { return err("创建分块上传失败：" + (e && e.message ? e.message : "R2 错误")); }
      return json({ ok: true, uploadId: mpu.uploadId, key, itemId, partSize: PART_SIZE });
    }

    // POST /api/upload/part?uploadId=...&key=media%2F....ext&partNumber=N  body = 一块原始数据
    if (path === "/api/upload/part" && request.method === "POST") {
      const uploadId = url.searchParams.get("uploadId") || "";
      const key = url.searchParams.get("key") || "";
      const partNumber = Number(url.searchParams.get("partNumber"));
      if (!uploadId) return err("缺少 uploadId");
      if (!/^media\/[A-Za-z0-9._-]+$/.test(key)) return err("key 不对");
      if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > 10000) return err("partNumber 不对");
      let buf;
      try { buf = await request.arrayBuffer(); } catch (e) { return err("读取分块失败"); }
      if (buf.byteLength === 0) return err("分块是空的");
      if (buf.byteLength > PART_SIZE) return err("分块超过 10MB");
      let part;
      try {
        const mpu = env.CHENGGUANG_BUCKET.resumeMultipartUpload(key, uploadId);
        part = await mpu.uploadPart(partNumber, buf);
      } catch (e) { return err("上传分块失败：" + (e && e.message ? e.message : "R2 错误")); }
      return json({ ok: true, etag: part.etag, partNumber: part.partNumber });
    }

    // POST /api/upload/complete {uploadId, key, parts:[{partNumber, etag}], title, category, description, duration, fileSize}
    if (path === "/api/upload/complete" && request.method === "POST") {
      let body = {};
      try { body = await request.json(); } catch (e) { return err("请求不对"); }
      const uploadId = String(body.uploadId || "");
      const key = String(body.key || "");
      const parts = Array.isArray(body.parts) ? body.parts : [];
      const title = String(body.title || "").trim();
      const category = String(body.category || "").trim();
      const description = String(body.description || "").trim();
      const duration = String(body.duration || "").trim();
      const mustRead = body.mustRead === true;
      const fileSize = Number(body.fileSize);
      if (!uploadId) return err("缺少 uploadId");
      if (!/^media\/[A-Za-z0-9._-]+$/.test(key)) return err("key 不对");
      if (!parts.length) return err("没有任何分块");
      if (!title) return err("请填写标题");
      if (!CATEGORIES.includes(category)) return err("栏目不对");
      const baseType = TYPE_BY_CATEGORY[category];
      if (baseType !== "audio" && baseType !== "video") return err("这个栏目不支持分块上传");
      const ext = extOf(key);
      const type = mediaTypeForExt(ext);
      if (!type) return err("文件格式不对");
      if (!Number.isFinite(fileSize) || fileSize <= 0) return err("文件大小不对");
      if (fileSize > mediaLimit(type)) return err("文件超过 " + mediaLimitText(type));
      const sorted = parts
        .map((p) => ({ partNumber: Number(p.partNumber), etag: String(p.etag || "") }))
        .filter((p) => Number.isInteger(p.partNumber) && p.partNumber >= 1 && p.etag)
        .sort((a, b) => a.partNumber - b.partNumber);
      if (!sorted.length) return err("分块信息不对");
      let obj;
      try {
        const mpu = env.CHENGGUANG_BUCKET.resumeMultipartUpload(key, uploadId);
        obj = await mpu.complete(sorted);
      } catch (e) { return err("合并分块失败：" + (e && e.message ? e.message : "R2 错误")); }
      const itemId = key.slice("media/".length, -("." + ext).length);
      const item = {
        id: itemId, title, category, type,
        url: url.origin + "/file/" + key, pdfUrl: "", imageUrl: "", duration, mustRead,
        size: humanSize(fileSize), description, content: "",
        status: "published", uploadedBy: "admin", createdAt: new Date().toISOString(),
      };
      const items = await readCatalog(env);
      items.unshift(item);
      await writeCatalog(env, items);
      return json({ ok: true, item });
    }

    // POST /api/upload/abort {uploadId, key}  尽力取消，未完成的 R2 分块上传 7 天后也会自动取消
    if (path === "/api/upload/abort" && request.method === "POST") {
      let body = {};
      try { body = await request.json(); } catch (e) { return err("请求不对"); }
      const uploadId = String(body.uploadId || "");
      const key = String(body.key || "");
      if (!uploadId || !/^media\/[A-Za-z0-9._-]+$/.test(key)) return err("参数不对");
      try {
        const mpu = env.CHENGGUANG_BUCKET.resumeMultipartUpload(key, uploadId);
        await mpu.abort();
      } catch (e) { /* 已取消或已过期，忽略 */ }
      return json({ ok: true });
    }

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

    // 轻量列表：去掉 content 全文（慢网下列表几秒就能出来；编辑时再按条取全文）
    if (path === "/api/items-lite" && request.method === "GET") {
      const items = await readCatalog(env);
      let totalBytes = 0, fileCount = 0, cursor = undefined;
      do {
        const listed = await env.CHENGGUANG_BUCKET.list({ cursor, limit: 1000 });
        for (const o of listed.objects) { totalBytes += o.size; fileCount++; }
        cursor = listed.truncated ? listed.cursor : undefined;
      } while (cursor);
      const lite = items.map((it) => {
        const copy = { ...it };
        delete copy.content;
        return copy;
      });
      return json({ ok: true, items: lite, totalBytes, fileCount, totalBytesText: humanSize(totalBytes) });
    }

    // 单条（含 content 全文，给编辑表单用）
    if (path.startsWith("/api/items/") && request.method === "GET") {
      const id = decodeURIComponent(path.slice("/api/items/".length));
      const items = await readCatalog(env);
      const item = items.find((it) => it.id === id);
      if (!item) return err("找不到这条内容", 404);
      return json({ ok: true, item });
    }

    // 读者建议：查看（要登录）+ 删除（要登录）
    if (path === "/api/feedback" && request.method === "GET") {
      const list = await readFeedback(env);
      return json({ ok: true, items: list });
    }
    if (path.startsWith("/api/feedback/") && request.method === "DELETE") {
      const id = decodeURIComponent(path.slice("/api/feedback/".length));
      const list = await readFeedback(env);
      const idx = list.findIndex((it) => it.id === id);
      if (idx < 0) return err("找不到这条建议", 404);
      list.splice(idx, 1);
      await writeFeedback(env, list);
      return json({ ok: true });
    }

    // ---- 成员账号管理（要管理员登录）：建账号 / 停用 / 启用 / 删除 / 列表 ----
    // 停用、删除账号时，把这个账号名下的会话也清掉，正在用的手机立刻断开。
    if (path === "/api/members" && request.method === "GET") {
      const members = await readMembers(env);
      const list = Object.keys(members.users).sort().map((name) => ({
        username: name, disabled: !!members.users[name].disabled, createdAt: members.users[name].createdAt || "", lastLoginAt: members.users[name].lastLoginAt || "",
      }));
      return json({ ok: true, items: list });
    }
    if (path === "/api/members" && request.method === "POST") {
      let body = {};
      try { body = await request.json(); } catch (e) { return err("请求不对"); }
      const username = String(body.username || "").trim();
      const password = String(body.password || "");
      if (!username || username.length > 30) return err("账号名不对（1-30 字）");
      if (!/^[A-Za-z0-9_\u4e00-\u9fa5.-]+$/.test(username)) return err("账号名只能用字母、数字、中文、下划线、点、横杠");
      if (password.length < 6) return err("密码至少 6 位，请设长一点");
      const members = await readMembers(env);
      if (members.users[username]) return err("这个账号已经有了");
      const salt = randomHex(16);
      const hash = await sha256Hex(salt + ":" + password);
      members.users[username] = { salt, hash, disabled: false, createdAt: new Date().toISOString() };
      await writeMembers(env, members);
      return json({ ok: true, username });
    }
    if (path.startsWith("/api/members/") && request.method === "POST") {
      const rest = decodeURIComponent(path.slice("/api/members/".length));
      const m = rest.match(/^(.+)\/(disable|enable)$/);
      if (!m) return err("接口不对", 404);
      const username = m[1], action = m[2];
      const members = await readMembers(env);
      const u = members.users[username];
      if (!u) return err("找不到这个账号", 404);
      u.disabled = action === "disable";
      await writeMembers(env, members);
      if (u.disabled) {
        const sessions = await readSessions(env);
        let changed = false;
        for (const t of Object.keys(sessions.tokens)) {
          if (sessions.tokens[t].username === username) { delete sessions.tokens[t]; changed = true; }
        }
        if (changed) await writeSessions(env, sessions);
      }
      return json({ ok: true, username, disabled: u.disabled });
    }
    if (path.startsWith("/api/members/") && request.method === "DELETE") {
      const username = decodeURIComponent(path.slice("/api/members/".length));
      if (!username || username.includes("/")) return err("账号名不对");
      const members = await readMembers(env);
      if (!members.users[username]) return err("找不到这个账号", 404);
      delete members.users[username];
      await writeMembers(env, members);
      const sessions = await readSessions(env);
      let changed = false;
      for (const t of Object.keys(sessions.tokens)) {
        if (sessions.tokens[t].username === username) { delete sessions.tokens[t]; changed = true; }
      }
      if (changed) await writeSessions(env, sessions);
      return json({ ok: true });
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
      const mustRead = form.get("mustRead") === "true";
      const file = form.get("file");
      if (!title) return err("请填写标题");
      if (!CATEGORIES.includes(category)) return err("栏目不对");
      const baseType = TYPE_BY_CATEGORY[category];
      // 媒体栏目按文件后缀定类型（音频还是视频），先定类型再生成 id（id 前缀 a/v 跟类型走）
      let type = baseType;
      if (MEDIA_CATEGORIES.includes(category)) {
        if (!file || file.size === 0) return err("音频/视频请先选一个文件");
        type = mediaTypeForExt(extOf(file.name));
        if (!type) return err("不支持的文件格式：请传音频（" + AUDIO_EXTS.join("、") + "）或视频（" + VIDEO_EXTS.join("、") + "）");
      } else if (category === "敬拜赞美" && file && file.size > 0) {
        // 敬拜赞美：带音频文件就是歌（audio），只填文字就是诗（poem）；非音频文件直接报错，别悄悄存成诗
        type = mediaTypeForExt(extOf(file.name));
        if (type !== "audio") return err("敬拜赞美只收音频文件（" + AUDIO_EXTS.join("、") + "），或只填歌词不传文件");
      }
      const now = new Date().toISOString();
      const item = {
        id: newId(type), title, category, type, url: "", pdfUrl: "", imageUrl: "", duration, mustRead,
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
        if (category === "敬拜赞美") item.content = content; // 歌的歌词存在 content 里，音频页下面显示
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
        // 扫描版书籍：原书 PDF 同时写 pdfUrl，App 阅读器里的“查看原书 PDF”按钮用它
        if (ext === "pdf") item.pdfUrl = item.url;
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
        const newBase = TYPE_BY_CATEGORY[body.category];
        if (newBase === "book" || newBase === "poem") {
          item.type = newBase;
        } else if (item.url) {
          // 媒体栏目：按已有文件的后缀定类型，免得把视频条目改栏目时错变成音频
          const t = mediaTypeForExt(extOf(item.url));
          if (t) item.type = t;
        }
      }
      if (body.description !== undefined) item.description = String(body.description);
      if (body.duration !== undefined) item.duration = String(body.duration);
      if (body.content !== undefined) item.content = String(body.content);
      if (typeof body.pdfUrl === "string") item.pdfUrl = body.pdfUrl;
      if (typeof body.imageUrl === "string") item.imageUrl = body.imageUrl;
      if (typeof body.mustRead === "boolean") item.mustRead = body.mustRead;
      await writeCatalog(env, items);
      return json({ ok: true, item });
    }

    // 讲章附件：上传（要登录）+ 删除（要登录）。PDF 优先，也收 PPT/PPTX。
    if (path === "/api/upload/attachment" && request.method === "POST") {
      let form;
      try { form = await request.formData(); } catch (e) { return err("表单解析失败"); }
      const itemId = String(form.get("itemId") || "");
      const file = form.get("file");
      if (!itemId) return err("缺少 itemId");
      if (!file || file.size === 0) return err("请先选一个讲章文件");
      if (file.size > MAX_SLIDES_BYTES) return err("讲章文件超过 100MB");
      const ext = extOf(file.name);
      if (!SLIDES_EXTS.includes(ext)) return err("讲章只收 PDF、PPT、PPTX");
      const items = await readCatalog(env);
      const item = items.find((it) => it.id === itemId);
      if (!item) return err("找不到这条内容", 404);
      const key = slidesKey(itemId, ext);
      const oldKey = keyFromFileUrl(item.pdfUrl || "");
      if (oldKey && oldKey !== key) await env.CHENGGUANG_BUCKET.delete(oldKey);
      await env.CHENGGUANG_BUCKET.put(key, file.stream(), {
        httpMetadata: { contentType: SLIDES_CONTENT_TYPE[ext] || "application/pdf" },
      });
      item.pdfUrl = url.origin + "/file/" + key;
      await writeCatalog(env, items);
      return json({ ok: true, item });
    }
    if (path.startsWith("/api/items/") && path.endsWith("/attachment") && request.method === "DELETE") {
      const id = decodeURIComponent(path.slice("/api/items/".length, -"/attachment".length));
      const items = await readCatalog(env);
      const item = items.find((it) => it.id === id);
      if (!item) return err("找不到这条内容", 404);
      const oldKey = keyFromFileUrl(item.pdfUrl || "");
      if (oldKey) await env.CHENGGUANG_BUCKET.delete(oldKey);
      item.pdfUrl = "";
      await writeCatalog(env, items);
      return json({ ok: true, item });
    }

    // 歌谱图片：上传（要登录）+ 删除（要登录）。一首歌一张，jpg / png / webp，≤20MB。
    if (path === "/api/upload/sheet" && request.method === "POST") {
      let form;
      try { form = await request.formData(); } catch (e) { return err("表单解析失败"); }
      const itemId = String(form.get("itemId") || "");
      const file = form.get("file");
      if (!itemId) return err("缺少 itemId");
      if (!file || file.size === 0) return err("请先选一张歌谱图片");
      if (file.size > MAX_SHEET_BYTES) return err("歌谱图片超过 20MB");
      const ext = extOf(file.name);
      if (!SHEET_EXTS.includes(ext)) return err("歌谱只收 JPG、PNG、WebP 图片");
      const items = await readCatalog(env);
      const item = items.find((it) => it.id === itemId);
      if (!item) return err("找不到这条内容", 404);
      const key = sheetKey(itemId, ext);
      const oldKey = keyFromFileUrl(item.imageUrl || "");
      if (oldKey && oldKey !== key) await env.CHENGGUANG_BUCKET.delete(oldKey);
      await env.CHENGGUANG_BUCKET.put(key, file.stream(), {
        httpMetadata: { contentType: SHEET_CONTENT_TYPE[ext] || "image/jpeg" },
      });
      item.imageUrl = url.origin + "/file/" + key;
      await writeCatalog(env, items);
      return json({ ok: true, item });
    }
    if (path.startsWith("/api/items/") && path.endsWith("/sheet") && request.method === "DELETE") {
      const id = decodeURIComponent(path.slice("/api/items/".length, -"/sheet".length));
      const items = await readCatalog(env);
      const item = items.find((it) => it.id === id);
      if (!item) return err("找不到这条内容", 404);
      const oldKey = keyFromFileUrl(item.imageUrl || "");
      if (oldKey) await env.CHENGGUANG_BUCKET.delete(oldKey);
      item.imageUrl = "";
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
      if (item.pdfUrl) {
        const skey = keyFromFileUrl(item.pdfUrl);
        if (skey) await env.CHENGGUANG_BUCKET.delete(skey);
      }
      if (item.imageUrl) {
        const shkey = keyFromFileUrl(item.imageUrl);
        if (shkey) await env.CHENGGUANG_BUCKET.delete(shkey);
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
.fb{border-bottom:1px solid #eee;padding:10px 0}
.fb .meta{color:#888;font-size:12px;margin-top:4px}
</style>
<script src="https://cdn.jsdelivr.net/npm/pdfjs-dist@3.11.174/build/pdf.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/mammoth@1.6.0/mammoth.browser.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js"></script>
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
    <label style="display:flex;align-items:center;gap:6px;font-weight:normal"><input type="checkbox" id="mustRead" style="width:auto"> 必读书籍（标题显示红色）</label>
    <label>栏目 *</label>
    <select id="category" onchange="onCategory()">
      <option>属灵书籍</option><option>敬拜赞美</option><option>主日信息</option><option>灵修信息</option><option>专题讲座</option>
    </select>
    <label>简介</label><input id="description" placeholder="一句话简介">
    <div id="textBlock">
      <label id="contentLabel">正文（书籍 / 诗歌）</label>
      <textarea id="content" placeholder="把正文粘贴在这里，多段换行即可"></textarea>
      <div id="docBlock">
        <label>或从文件导入正文（TXT / PDF / Word .docx / EPUB，自动提取文字填到上面）</label>
        <input type="file" id="docFile" accept=".txt,.pdf,.docx,.epub" onchange="importDocFile()">
        <div id="docImportMsg" class="muted" style="margin-top:4px"></div>
        <p class="muted">老格式 .doc 不支持，请在 Word 里另存为 .docx 再传。PDF 是扫描图片的提不出文字，请用文字版 PDF。EPUB 按章节顺序提正文，超过 100 万字会自动截断到 100 万字。TXT 会自动识别 UTF-8 / GBK 编码。</p>
      </div>
      <div id="bookBlock" style="display:none">
        <label>原书 PDF（扫描版，可选）：没有文字层的扫描版直接传这里，App 里点“查看原书 PDF”打开看原书；以后有了文字版再重新做</label>
        <input type="file" id="bookPdfFile" accept=".pdf">
        <div id="bookPdfNow" class="muted" style="margin-top:4px"></div>
        <button type="button" class="ghost" id="delBookPdfBtn" style="display:none;margin-top:6px" onclick="delBookPdf()">删除已有原书 PDF</button>
      </div>
    </div>
    <div id="worshipBlock" style="display:none">
      <label>音频文件（歌）：MP3 等；不传就是纯文字诗歌</label>
      <input type="file" id="songFile" accept=".mp3,.m4a,.wav,.ogg,.aac,.flac">
      <label>歌谱图片（可选）：一首歌一张，jpg / png / webp，不超过 20MB</label>
      <input type="file" id="sheetFile" accept=".jpg,.jpeg,.png,.webp">
      <div id="sheetNow" class="muted" style="margin-top:4px"></div>
      <button type="button" class="ghost" id="delSheetBtn" style="display:none;margin-top:6px" onclick="delSheet()">删除已有歌谱</button>
    </div>
    <div id="fileBlock" style="display:none">
      <label>音频或视频文件 *（音频不超过 500MB，视频不超过 2GB；大文件自动分块上传）</label>
      <input type="file" id="file">
      <label>讲章文件（可选）：PDF 最好；PPT 请先在 PowerPoint / WPS 里另存为 PDF 再传，直接传 PPT 的话 App 里是在线预览，要联网</label>
      <input type="file" id="slidesFile" accept=".pdf,.ppt,.pptx">
      <div id="slidesNow" class="muted" style="margin-top:4px"></div>
      <button type="button" class="ghost" id="delSlidesBtn" style="display:none;margin-top:6px" onclick="delSlides()">删除已有讲章</button>
      <progress id="upProg" max="100" value="0" style="width:100%;display:none;margin-top:8px"></progress>
      <div id="upProgText" class="muted" style="margin-top:4px"></div>
      <label>时长（如 1:05:30，可不填）</label><input id="duration" placeholder="1:05:30">
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

  <div class="card">
    <h2>读者建议（<span id="fbCount">0</span> 条）</h2>
    <p class="muted">App“我的”页里读者提交的建议，不用登录也能发。处理完点删除。</p>
    <div id="fbList">加载中…</div>
  </div>

  <div class="card">
    <h2>成员账号（<span id="memberCount">0</span> 个）</h2>
    <p class="muted">App 登录用的账号：在这里建账号、停用账号。App 从下一版起必须登录才能看内容；停用一个账号后，那台手机立刻断开。账号名可以用中文，密码请设长一点。</p>
    <label>新账号名</label><input id="memberName" placeholder="如：恩清">
    <label>密码（至少 6 位）</label><input type="password" id="memberPw" placeholder="给这个账号设一个密码">
    <div style="height:10px"></div>
    <button onclick="createMember()">创建账号</button>
    <p id="memberMsg"></p>
    <div id="memberList">加载中…</div>
  </div>
</div>

<script>
var items = [];
function showMsg(t, id) { document.getElementById(id || "msg").textContent = t || ""; }
function onCategory() {
  var c = document.getElementById("category").value;
  var isMedia = (c === "主日信息" || c === "灵修信息" || c === "专题讲座");
  var isWorship = (c === "敬拜赞美");
  var isBook = (c === "属灵书籍");
  document.getElementById("fileBlock").style.display = isMedia ? "block" : "none";
  document.getElementById("textBlock").style.display = isMedia ? "none" : "block";
  document.getElementById("worshipBlock").style.display = isWorship ? "block" : "none";
  document.getElementById("bookBlock").style.display = isBook ? "block" : "none";
  document.getElementById("contentLabel").textContent = isWorship ? "歌词（文字格式的歌谱）" : "正文（书籍 / 诗歌）";
  // 切栏目时清掉藏起来的那组文件选择，免得旧文件被误传到新栏目里
  if (isWorship) {
    document.getElementById("file").value = "";
    document.getElementById("slidesFile").value = "";
  } else {
    document.getElementById("songFile").value = "";
    document.getElementById("sheetFile").value = "";
  }
  if (!isBook) {
    document.getElementById("bookPdfFile").value = "";
  }
  // 离开媒体栏目时也清掉音频/视频和讲章的选择，免得旧文件被误传到新栏目里
  if (!isMedia) {
    document.getElementById("file").value = "";
    document.getElementById("slidesFile").value = "";
  }
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
  // 先给加载态，别让条数一直停在初始的 0 上
  document.getElementById("count").textContent = "…";
  document.getElementById("list").innerHTML = "<tr><td colspan='4'>正在加载内容…</td></tr>";
  // 轻量列表（不含正文），慢网也能几秒出来；60 秒还没回来就当失败
  var ctl = new AbortController();
  var timer = setTimeout(function () { ctl.abort(); }, 60000);
  var r, d;
  try {
    r = await fetch("/api/items-lite", { signal: ctl.signal });
    d = await r.json();
  } catch (e) {
    clearTimeout(timer);
    document.getElementById("count").textContent = "—";
    document.getElementById("list").innerHTML = "<tr><td colspan='4'>加载失败：网络太慢或断开了。 <button onclick='load()'>重试</button></td></tr>";
    return;
  }
  clearTimeout(timer);
  if (!d.ok) {
    document.getElementById("count").textContent = "—";
    document.getElementById("list").innerHTML = "<tr><td colspan='4'>加载失败：" + esc(d.error || "") + " <button onclick='load()'>重试</button></td></tr>";
    return;
  }
  items = d.items || [];
  document.getElementById("count").textContent = items.length;
  document.getElementById("storage").textContent = "存储占用：" + (d.totalBytesText || "0 MB") + "（" + d.fileCount + " 个文件，R2 每月免费 10GB）";
  var html = "";
  for (var i = 0; i < items.length; i++) {
    var it = items[i];
    html += "<tr><td><b>" + esc(it.title) + "</b><br><span class='muted'>" + esc(it.description || "") + "</span>" + (it.pdfUrl ? "<br><span class='muted'>有讲章</span>" : "") + (it.imageUrl ? "<br><span class='muted'>有歌谱</span>" : "") + "</td>"
      + "<td>" + esc(it.category) + "</td><td>" + esc(it.size || "-") + "</td>"
      + "<td><button data-id='" + it.id + "' onclick='editItem(this.dataset.id)'>编辑</button> "
      + "<button class='danger' data-id='" + it.id + "' data-title='" + esc(it.title) + "' onclick='delItem(this.dataset.id,this.dataset.title)'>删除</button></td></tr>";
  }
  document.getElementById("list").innerHTML = html || "<tr><td colspan='4'>还没有内容，先上传一条试试。</td></tr>";
  loadFeedback();
  loadMembers();
}
async function loadFeedback() {
  var box = document.getElementById("fbList");
  var r = await fetch("/api/feedback");
  var d = await r.json();
  if (!d.ok) { box.textContent = "加载失败：" + (d.error || ""); return; }
  var list = d.items || [];
  document.getElementById("fbCount").textContent = list.length;
  if (!list.length) { box.textContent = "还没有读者建议。"; return; }
  var html = "";
  for (var i = 0; i < list.length; i++) {
    var fb = list[i];
    var when = fb.createdAt ? String(fb.createdAt).slice(0, 10) + " " + String(fb.createdAt).slice(11, 16) : "";
    html += "<div class='fb'><div>" + esc(fb.text) + "</div>"
      + "<div class='meta'>" + esc(when) + (fb.contact ? " · 联系：" + esc(fb.contact) : " · 匿名")
      + " <button class='danger' data-id='" + fb.id + "' onclick='delFeedback(this.dataset.id)'>删除</button></div></div>";
  }
  box.innerHTML = html;
}
async function delFeedback(id) {
  if (!confirm("确定删除这条建议吗？")) return;
  var r = await fetch("/api/feedback/" + encodeURIComponent(id), { method: "DELETE" });
  var d = await r.json();
  if (!d.ok) { alert(d.error || "删除失败"); return; }
  loadFeedback();
}
// 最后登录时间：服务器存的是 UTC 的 ISO 时间，浏览器按本地时间显示（年月日 时:分）
function fmtMemberTime(iso) {
  var d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  var p = function (n) { return (n < 10 ? "0" : "") + n; };
  return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes());
}
// 成员账号：建账号、停用/启用、删除、列表（都要管理员登录）
async function loadMembers() {
  var box = document.getElementById("memberList");
  var r = await fetch("/api/members");
  var d = await r.json();
  if (!d.ok) { box.textContent = "加载失败：" + (d.error || ""); return; }
  var list = d.items || [];
  document.getElementById("memberCount").textContent = list.length;
  if (!list.length) { box.textContent = "还没有成员账号，先建一个。"; return; }
  var html = "";
  for (var i = 0; i < list.length; i++) {
    var m = list[i];
    var last = m.lastLoginAt ? fmtMemberTime(m.lastLoginAt) : "从未登录";
    html += "<div class='fb'><div><b>" + esc(m.username) + "</b> " + (m.disabled ? "<span style='color:#8e4a2f'>已停用</span>" : "<span class='muted'>正常</span>") + "</div>"
      + "<div class='meta'>建于 " + esc((m.createdAt || "").slice(0, 10)) + " · 最后登录 " + esc(last)
      + " <button data-u='" + esc(m.username) + "' onclick='setMemberDisabled(this.dataset.u," + (m.disabled ? "false" : "true") + ")'>" + (m.disabled ? "启用" : "停用") + "</button>"
      + " <button class='danger' data-u='" + esc(m.username) + "' onclick='delMember(this.dataset.u)'>删除</button></div></div>";
  }
  box.innerHTML = html;
}
async function createMember() {
  var name = document.getElementById("memberName").value.trim();
  var pw = document.getElementById("memberPw").value;
  if (!name || !pw) { document.getElementById("memberMsg").textContent = "请填写账号名和密码"; return; }
  var r = await fetch("/api/members", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: name, password: pw }) });
  var d = await r.json();
  if (!d.ok) { document.getElementById("memberMsg").textContent = d.error || "创建失败"; return; }
  document.getElementById("memberMsg").textContent = "已创建账号 " + name + "，把账号和密码告诉对方，对方在 App 里登录就能用。";
  document.getElementById("memberName").value = "";
  document.getElementById("memberPw").value = "";
  loadMembers();
}
async function setMemberDisabled(username, disabled) {
  var r = await fetch("/api/members/" + encodeURIComponent(username) + "/" + (disabled ? "disable" : "enable"), { method: "POST" });
  var d = await r.json();
  if (!d.ok) { alert(d.error || "操作失败"); return; }
  loadMembers();
}
async function delMember(username) {
  if (!confirm("确定删除账号「" + username + "」吗？这个账号会立刻不能登录。")) return;
  var r = await fetch("/api/members/" + encodeURIComponent(username), { method: "DELETE" });
  var d = await r.json();
  if (!d.ok) { alert(d.error || "删除失败"); return; }
  loadMembers();
}
// 书籍/诗歌从文件导入正文：TXT 按 UTF-8 / GB18030 自动识别编码读，PDF 用 pdf.js 按页提文字，
// Word .docx 用 mammoth 提纯文本，EPUB 用 JSZip 解包、按 spine 顺序提各章正文。
// 只在浏览器里提取，提取完填进正文框，检查没问题再点保存；原文件不存进 R2。
async function importDocFile() {
  var input = document.getElementById("docFile");
  var msg = document.getElementById("docImportMsg");
  var f = input.files[0];
  if (!f) return;
  var name = f.name || "";
  var lower = name.toLowerCase();
  msg.textContent = "正在提取文字，请稍等…";
  try {
    var text = "";
    var encNote = "";
    var isEpub = false;
    if (lower.endsWith(".txt")) {
      var dec = decodeTextAuto(await f.arrayBuffer());
      text = dec.text;
      encNote = dec.label;
    } else if (lower.endsWith(".pdf")) {
      if (typeof pdfjsLib === "undefined") throw new Error("PDF 解析库没加载上，请检查网络后刷新再试");
      pdfjsLib.GlobalWorkerOptions.workerSrc = "https://cdn.jsdelivr.net/npm/pdfjs-dist@3.11.174/build/pdf.worker.min.js";
      var buf = await f.arrayBuffer();
      var pdf = await pdfjsLib.getDocument({ data: buf }).promise;
      var pages = [];
      for (var p = 1; p <= pdf.numPages; p++) {
        var page = await pdf.getPage(p);
        var tc = await page.getTextContent();
        var line = "";
        for (var i = 0; i < tc.items.length; i++) {
          var s = tc.items[i].str || "";
          if (tc.items[i].hasEOL) line += s + "\\n";
          else line += s;
        }
        pages.push(line);
      }
      text = pages.join("\\n\\n");
    } else if (lower.endsWith(".docx")) {
      if (typeof mammoth === "undefined") throw new Error("Word 解析库没加载上，请检查网络后刷新再试");
      var ab = await f.arrayBuffer();
      var res = await mammoth.extractRawText({ arrayBuffer: ab });
      text = res.value || "";
    } else if (lower.endsWith(".epub")) {
      if (typeof JSZip === "undefined") throw new Error("EPUB 解析库没加载上，请检查网络后刷新再试");
      text = await extractEpubText(await f.arrayBuffer());
      isEpub = true;
    } else if (lower.endsWith(".doc")) {
      throw new Error("老格式 .doc 提不出文字，请在 Word 里另存为 .docx 再传");
    } else {
      throw new Error("只支持 TXT、PDF、Word .docx、EPUB 四种文件");
    }
    text = String(text || "").replace(/\\r\\n/g, "\\n").replace(/\\n{3,}/g, "\\n\\n").trim();
    if (!text) throw new Error("没提取到文字：PDF 可能是扫描图片版，请用文字版 PDF；Word 请确认里面有文字；EPUB 请确认里面有文字章节");
    var truncated = false;
    if (text.length > 1000000) {
      if (isEpub) { text = text.slice(0, 1000000); truncated = true; }
      else throw new Error("这本书太长了（超过 100 万字），请按章节拆成多个文件分别导入");
    }
    document.getElementById("content").value = text;
    if (!document.getElementById("title").value.trim()) {
      document.getElementById("title").value = name.replace(/\\.[^.]+$/, "");
    }
    var note = "";
    if (encNote) note = "已按 " + encNote + " 编码读取。";
    if (truncated) note += "原书超过 100 万字，已截断到 100 万字，建议按章节拆开分多次导入。";
    msg.textContent = note + "已导入 " + text.length + " 字，检查一下正文，确认没问题再点保存。";
  } catch (e) {
    msg.textContent = "导入失败：" + (e && e.message ? e.message : "未知错误");
  }
}
// TXT 自动识别编码：先按 UTF-8 严格解码，失败就按 GB18030 解码（老中文书常是 GBK，
// GB18030 是 GBK 的超集，能读 GBK 也能读 GB18030），再失败才按 UTF-8 宽容解码。
// 返回 { text, label }，label 写进导入提示里，让用户知道按什么编码读的。
function decodeTextAuto(buf) {
  try {
    return { text: new TextDecoder("utf-8", { fatal: true }).decode(buf), label: "UTF-8" };
  } catch (e1) {}
  try {
    return { text: new TextDecoder("gb18030").decode(buf), label: "GB18030" };
  } catch (e2) {}
  return { text: new TextDecoder("utf-8").decode(buf), label: "UTF-8" };
}
// EPUB 解包提取正文：读 META-INF/container.xml 找到 OPF，再按 spine 顺序读各 XHTML 章节，
// 去标签取纯文本，章节之间空一行。EPUB2 / EPUB3 都能读，没有导航文件也不影响。
// 解析失败就抛错（调用方显示中文提示，正文框不会填进半截内容）。
async function extractEpubText(ab) {
  var zip;
  try {
    zip = await JSZip.loadAsync(ab);
  } catch (e) {
    throw new Error("这不是有效的 EPUB 文件（解包失败），请确认文件没有损坏");
  }
  var containerFile = zip.file("META-INF/container.xml");
  if (!containerFile) throw new Error("这不是有效的 EPUB：找不到 META-INF/container.xml");
  var containerDoc = parseXml(await containerFile.async("string"));
  var rootfiles = containerDoc.getElementsByTagName("rootfile");
  var opfPath = "";
  for (var i = 0; i < rootfiles.length; i++) {
    var mt = rootfiles[i].getAttribute("media-type") || "";
    if (mt === "application/oebps-package+xml" || !opfPath) opfPath = rootfiles[i].getAttribute("full-path") || "";
  }
  if (!opfPath) throw new Error("这不是有效的 EPUB：container.xml 里没有找到 OPF 文件");
  var opfFile = getZipFile(zip, opfPath);
  if (!opfFile) throw new Error("这不是有效的 EPUB：找不到 OPF 文件 " + opfPath);
  var opfDoc = parseXml(await opfFile.async("string"));
  var base = opfPath.indexOf("/") >= 0 ? opfPath.slice(0, opfPath.lastIndexOf("/") + 1) : "";
  var manifest = {};
  var mitems = opfDoc.getElementsByTagName("item");
  for (var j = 0; j < mitems.length; j++) {
    var id = mitems[j].getAttribute("id");
    var href = mitems[j].getAttribute("href");
    if (id && href) manifest[id] = href;
  }
  var spine = opfDoc.getElementsByTagName("itemref");
  var chapters = [];
  for (var k = 0; k < spine.length; k++) {
    var ref = spine[k].getAttribute("idref");
    if (!ref || !manifest[ref]) continue;
    if ((spine[k].getAttribute("linear") || "") === "no") continue;
    var chapterFile = getZipFile(zip, joinZipPath(base, manifest[ref]));
    if (!chapterFile) continue;
    var chapterText = htmlToText(await chapterFile.async("string"));
    if (chapterText) chapters.push(chapterText);
  }
  if (!chapters.length) throw new Error("EPUB 里没找到文字章节，请确认这本是文字版 EPUB");
  return chapters.join("\\n\\n");
}
// 用浏览器自带 DOMParser 解析 XML，解析失败时抛错（不要静默返回空文档）。
function parseXml(xmlText) {
  var doc = new DOMParser().parseFromString(xmlText, "text/xml");
  if (doc.getElementsByTagName("parsererror").length) throw new Error("EPUB 里的 XML 解析失败，文件可能损坏");
  return doc;
}
// zip 里按路径找文件：先按原样找，再试 URL 解码后的路径（href 里可能有 %20 这类转义）。
function getZipFile(zip, path) {
  var f = zip.file(path);
  if (f) return f;
  try {
    var dec = decodeURIComponent(path);
    if (dec !== path) return zip.file(dec);
  } catch (e) {}
  return null;
}
// 把 OPF 里 manifest 的 href（相对 OPF 所在目录）拼成 zip 里的完整路径，顺带处理 ./ 和 ../。
function joinZipPath(base, href) {
  var h = href.split("#")[0].split("?")[0];
  if (h.charAt(0) === "/") return normalizeZipPath(h.slice(1));
  return normalizeZipPath(base + h);
}
function normalizeZipPath(p) {
  var parts = p.split("/");
  var out = [];
  for (var i = 0; i < parts.length; i++) {
    var s = parts[i];
    if (!s || s === ".") continue;
    if (s === "..") { out.pop(); continue; }
    out.push(s);
  }
  return out.join("/");
}
// 把一章 XHTML 转纯文本：按块级标签断段，段与段之间空一行，跳过 script/style。
// 用浏览器自带的 HTML 解析器，比正则去标签稳（XHTML 写得不规范也能读）。
var HTML_BLOCK_TAGS = { P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, DIV: 1, SECTION: 1, ARTICLE: 1, BLOCKQUOTE: 1, LI: 1, UL: 1, OL: 1, TABLE: 1, TR: 1, PRE: 1, HR: 1, HEADER: 1, FOOTER: 1, NAV: 1, ASIDE: 1, FIGURE: 1, FIGCAPTION: 1, DT: 1, DD: 1, ADDRESS: 1 };
function htmlToText(xhtml) {
  var doc = new DOMParser().parseFromString(xhtml, "text/html");
  var body = doc.body;
  if (!body) return "";
  var out = "";
  function walk(node) {
    if (node.nodeType === 3) { out += node.nodeValue || ""; return; }
    if (node.nodeType !== 1) return;
    var tag = node.tagName || "";
    if (tag === "SCRIPT" || tag === "STYLE") return;
    if (tag === "BR") { out += "\\n"; return; }
    var kids = node.childNodes;
    for (var i = 0; i < kids.length; i++) walk(kids[i]);
    if (HTML_BLOCK_TAGS[tag]) out += "\\n\\n";
  }
  walk(body);
  return out.replace(/[ \\t]+\\n/g, "\\n").replace(/\\n[ \\t]+/g, "\\n").replace(/\\n{3,}/g, "\\n\\n").trim();
}
function esc(s) { return String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;"); }
function resetForm() {
  document.getElementById("editId").value = "";
  document.getElementById("formTitle").textContent = "上传新内容";
  document.getElementById("title").value = "";
  document.getElementById("description").value = "";
  document.getElementById("content").value = "";
  document.getElementById("duration").value = "";
  document.getElementById("mustRead").checked = false;
  document.getElementById("file").value = "";
  var docInput = document.getElementById("docFile");
  if (docInput) docInput.value = "";
  var docMsg = document.getElementById("docImportMsg");
  if (docMsg) docMsg.textContent = "";
  var slidesInput = document.getElementById("slidesFile");
  if (slidesInput) slidesInput.value = "";
  document.getElementById("slidesNow").textContent = "";
  document.getElementById("delSlidesBtn").style.display = "none";
  var songInput = document.getElementById("songFile");
  if (songInput) songInput.value = "";
  var sheetInput = document.getElementById("sheetFile");
  if (sheetInput) sheetInput.value = "";
  document.getElementById("sheetNow").textContent = "";
  document.getElementById("delSheetBtn").style.display = "none";
  var bookPdfInput = document.getElementById("bookPdfFile");
  if (bookPdfInput) bookPdfInput.value = "";
  document.getElementById("bookPdfNow").textContent = "";
  document.getElementById("delBookPdfBtn").style.display = "none";
  showMsg("", "msg2");
}
async function editItem(id) {
  var it = null;
  for (var i = 0; i < items.length; i++) if (items[i].id === id) it = items[i];
  if (!it) return;
  // 列表是轻量的、没有正文，编辑前按条取全文；取不到就不开表单，
  // 免得正文框是空的、一点保存就把原文盖掉
  try {
    var r = await fetch("/api/items/" + encodeURIComponent(id));
    var d = await r.json();
    if (!d.ok || !d.item) { alert(d.error || "正文加载失败，请检查网络后重试"); return; }
    it = d.item;
  } catch (e) {
    alert("正文加载失败，请检查网络后重试");
    return;
  }
  document.getElementById("editId").value = it.id;
  document.getElementById("formTitle").textContent = "编辑：" + it.title;
  document.getElementById("title").value = it.title;
  document.getElementById("category").value = it.category;
  document.getElementById("description").value = it.description || "";
  document.getElementById("mustRead").checked = !!it.mustRead;
  document.getElementById("content").value = it.content || "";
  document.getElementById("duration").value = it.duration || "";
  onCategory();
  if (it.pdfUrl) {
    document.getElementById("slidesNow").textContent = "已有讲章：" + it.pdfUrl.split("/").pop();
    document.getElementById("delSlidesBtn").style.display = "inline-block";
  } else {
    document.getElementById("slidesNow").textContent = "";
    document.getElementById("delSlidesBtn").style.display = "none";
  }
  if (it.imageUrl) {
    document.getElementById("sheetNow").textContent = "已有歌谱：" + it.imageUrl.split("/").pop();
    document.getElementById("delSheetBtn").style.display = "inline-block";
  } else {
    document.getElementById("sheetNow").textContent = "";
    document.getElementById("delSheetBtn").style.display = "none";
  }
  if (it.category === "属灵书籍") {
    if (it.pdfUrl) {
      document.getElementById("bookPdfNow").textContent = "已有原书 PDF：" + it.pdfUrl.split("/").pop();
      document.getElementById("delBookPdfBtn").style.display = "inline-block";
    } else {
      document.getElementById("bookPdfNow").textContent = "";
      document.getElementById("delBookPdfBtn").style.display = "none";
    }
  } else {
    document.getElementById("bookPdfNow").textContent = "";
    document.getElementById("delBookPdfBtn").style.display = "none";
  }
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
        mustRead: document.getElementById("mustRead").checked,
        content: document.getElementById("content").value }) });
    var d = await r.json();
    if (!d.ok) { showMsg(d.error || "保存失败", "msg2"); return; }
    var sf = document.getElementById("slidesFile").files[0];
    if (sf) {
      var errS = await uploadAttachment(editId, sf);
      if (errS) return;
    }
    var sh = document.getElementById("sheetFile").files[0];
    if (sh) {
      var sheetErr = await uploadSheet(editId, sh);
      if (sheetErr) { showMsg(sheetErr, "msg2"); return; }
    }
    var bf = document.getElementById("bookPdfFile").files[0];
    if (bf) {
      var errB = await uploadAttachment(editId, bf, "原书 PDF");
      if (errB) return;
    }
    showMsg("已保存", "msg2"); resetForm(); load(); return;
  }
  var f = document.getElementById("file").files[0];
  var cat = document.getElementById("category").value;
  var isMedia = (cat === "主日信息" || cat === "灵修信息" || cat === "专题讲座");
  // 音频/视频一律走分块上传：10MB 一块、4 块并行上传，音频上限 500MB、视频上限 2GB
  if (isMedia && f) {
    await uploadChunked(title, cat, f);
    return;
  }
  var fd = new FormData();
  fd.append("title", title);
  fd.append("category", cat);
  fd.append("description", document.getElementById("description").value);
  fd.append("mustRead", document.getElementById("mustRead").checked ? "true" : "false");
  fd.append("duration", document.getElementById("duration").value);
  fd.append("content", document.getElementById("content").value);
  if (f && cat !== "敬拜赞美") fd.append("file", f);
  if (cat === "敬拜赞美") {
    // 歌的 MP3 走 songFile（fileBlock 那个 file 口是给媒体栏目的，敬拜赞美不用它）
    var songF = document.getElementById("songFile").files[0];
    if (songF) fd.append("file", songF);
  }
  var bookPdfSent = false;
  if (cat === "属灵书籍") {
    // 扫描版书：原书 PDF 跟新建请求一起传——服务器校验要求正文或文件二选一，
    // 空正文只靠事后附件是建不出条目的；服务器存好后同时写 pdfUrl
    var bf0 = document.getElementById("bookPdfFile").files[0];
    if (bf0) { fd.append("file", bf0); bookPdfSent = true; }
  }
  showMsg("正在上传，请稍等…", "msg2");
  var r2 = await fetch("/api/upload", { method: "POST", body: fd });
  var d2 = await r2.json();
  if (!d2.ok) { showMsg(d2.error || "上传失败", "msg2"); return; }
  // 谱图等条目建好之后再传（要 itemId）；歌已保存，谱图没传上去也不丢条目，
  // 报错后提示用户点编辑再传
  var shf = document.getElementById("sheetFile").files[0];
  if (shf) {
    var sheetErr2 = await uploadSheet(d2.item.id, shf);
    if (sheetErr2) {
      resetForm(); load();
      showMsg("歌已保存，但歌谱没传上去：" + sheetErr2 + "。点编辑可以再传谱图。", "msg2");
      return;
    }
  }
  // 原书 PDF 等条目建好之后再传（要 itemId）；书已保存，PDF 没传上去也不丢条目，
  // 报错后提示用户点编辑再传
  var bf2 = document.getElementById("bookPdfFile").files[0];
  if (bf2 && !bookPdfSent) {
    var errB2 = await uploadAttachment(d2.item.id, bf2, "原书 PDF");
    if (errB2) {
      resetForm(); load();
      showMsg("书已保存，但原书 PDF 没传上去：" + errB2 + "。点编辑可以再传。", "msg2");
      return;
    }
  }
  showMsg("上传成功，App 打开就能看到", "msg2"); resetForm(); load();
}
// 分块上传：init -> 4 块并行 POST（每块 5 分钟超时、最多 3 次重试）-> complete；中途失败就 abort 并报错
async function uploadChunked(title, category, file) {
  var prog = document.getElementById("upProg");
  var progText = document.getElementById("upProgText");
  var totalParts = 0;
  function setProg(done, total) {
    var pct = total ? Math.round(done / total * 100) : 0;
    prog.style.display = "block";
    prog.value = pct;
    progText.textContent = "正在上传 " + pct + "%（第 " + done + "/" + total + " 块）";
  }
  function fail(t) {
    prog.style.display = "none";
    progText.textContent = "";
    showMsg(t, "msg2");
  }
  // 一点保存就先亮进度条，别让用户干等：创建上传（init）本身也要走网，
  // 慢网上它也可能卡很久，以前要等它回来才出现进度条，看起来就像保存卡死了。
  prog.style.display = "block";
  prog.value = 0;
  progText.textContent = "正在创建上传，请稍等…";
  var init = null;
  for (var initTry = 1; initTry <= 3; initTry++) {
    var initCtl = new AbortController();
    var initTimer = setTimeout(function () { initCtl.abort(); }, 30000);
    try {
      var initR = await fetch("/api/upload/init", { method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: title, category: category,
          description: document.getElementById("description").value,
          duration: document.getElementById("duration").value,
          filename: file.name, fileSize: file.size, contentType: file.type || "" }),
        signal: initCtl.signal });
      clearTimeout(initTimer);
      init = await initR.json();
      break;
    } catch (e) {
      clearTimeout(initTimer);
      if (initTry < 3) {
        progText.textContent = "创建上传没连上，正在重试（第 " + initTry + " 次）…";
      }
    }
  }
  if (!init || !init.ok) { fail((init && init.error) || "创建上传失败，请检查网络后重试"); return; }
  totalParts = Math.ceil(file.size / init.partSize);
  // 4 块并行传：以前是一块一块等，慢网下一块的来回等待占了不少时间；
  // 并行后同一时间有 4 块在传。服务端按 partNumber 排序合并，乱序也没事。
  var parts = new Array(totalParts);
  var doneCount = 0;
  var CONCURRENCY = 4;
  async function uploadOnePart(i) {
    var chunk = file.slice(i * init.partSize, (i + 1) * init.partSize);
    var lastErr = null;
    // 每块最多试 3 次：一次请求超过 5 分钟没回音就当它卡死了，取消重传。
    // 10MB 一块在 40KB/s 的慢网下也要约 4 分钟，5 分钟够走完，卡住不动的才会被掐掉。
    for (var attempt = 1; attempt <= 3; attempt++) {
      var controller = new AbortController();
      var timer = setTimeout(function () { controller.abort(); }, 300000);
      try {
        var r = await fetch("/api/upload/part?uploadId=" + encodeURIComponent(init.uploadId)
          + "&key=" + encodeURIComponent(init.key) + "&partNumber=" + (i + 1),
          { method: "POST", body: chunk, signal: controller.signal });
        clearTimeout(timer);
        var d = await r.json();
        if (!d.ok) throw new Error(d.error || "第 " + (i + 1) + " 块上传失败");
        parts[i] = { partNumber: d.partNumber, etag: d.etag };
        doneCount++;
        setProg(doneCount, totalParts);
        return;
      } catch (e) {
        clearTimeout(timer);
        lastErr = e;
        if (attempt < 3) {
          progText.textContent = "第 " + (i + 1) + " 块没传上去，正在重试（第 " + attempt + " 次）…";
        }
      }
    }
    throw lastErr || new Error("第 " + (i + 1) + " 块上传失败");
  }
  try {
    var next = 0;
    var workers = [];
    for (var w = 0; w < Math.min(CONCURRENCY, totalParts); w++) {
      workers.push((async function () {
        while (next < totalParts) {
          var i = next++;
          await uploadOnePart(i);
        }
      })());
    }
    await Promise.all(workers);
  } catch (e) {
    try { await fetch("/api/upload/abort", { method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ uploadId: init.uploadId, key: init.key }) }); } catch (e2) {}
    fail(e.message || "上传失败"); return;
  }
  progText.textContent = "正在合并，请稍等…";
  var cr = await fetch("/api/upload/complete", { method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ uploadId: init.uploadId, key: init.key, parts: parts,
      title: title, category: category,
      description: document.getElementById("description").value,
      duration: document.getElementById("duration").value,
      mustRead: document.getElementById("mustRead").checked, fileSize: file.size }) });
  var cd = await cr.json();
  if (!cd.ok) {
    try { await fetch("/api/upload/abort", { method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ uploadId: init.uploadId, key: init.key }) }); } catch (e3) {}
    fail(cd.error || "合并失败"); return;
  }
  prog.style.display = "none";
  progText.textContent = "";
  var sf2 = document.getElementById("slidesFile").files[0];
  if (sf2) {
    var errS2 = await uploadAttachment(cd.item.id, sf2);
    if (errS2) return;
  }
  showMsg("上传成功，App 打开就能看到", "msg2"); resetForm(); load();
}
// 附件上传（讲章 / 原书 PDF 共用）：成功返回 null，失败返回错误文字（已显示过，调用方别再盖掉）
async function uploadAttachment(itemId, file, label) {
  label = label || "讲章";
  var fd = new FormData();
  fd.append("itemId", itemId);
  fd.append("file", file);
  showMsg("正在上传" + label + "，请稍等…", "msg2");
  var d;
  try {
    var r = await fetch("/api/upload/attachment", { method: "POST", body: fd });
    d = await r.json();
  } catch (e) {
    var netErr = label + "上传失败：网络断开了，请检查网络后重试";
    showMsg(netErr, "msg2");
    return netErr;
  }
  if (!d.ok) { var errText = d.error || label + "上传失败"; showMsg(errText, "msg2"); return errText; }
  return null;
}
// 歌谱图片：条目建好之后再传（要 itemId）。成功返回 ""，失败返回中文错误（调用方显示出来，不静默丢掉）。
async function uploadSheet(itemId, file) {
  var fd = new FormData();
  fd.append("itemId", itemId);
  fd.append("file", file);
  showMsg("正在上传歌谱，请稍等…", "msg2");
  var r = await fetch("/api/upload/sheet", { method: "POST", body: fd });
  var d = await r.json();
  if (!d.ok) return d.error || "歌谱上传失败";
  return "";
}
async function delSheet() {
  var editId = document.getElementById("editId").value;
  if (!editId) return;
  if (!confirm("确定删除这首歌的歌谱吗？")) return;
  var r = await fetch("/api/items/" + encodeURIComponent(editId) + "/sheet", { method: "DELETE" });
  var d = await r.json();
  if (!d.ok) { alert(d.error || "删除失败"); return; }
  document.getElementById("sheetNow").textContent = "";
  document.getElementById("delSheetBtn").style.display = "none";
  load();
}
async function delSlides() {
  var editId = document.getElementById("editId").value;
  if (!editId) return;
  if (!confirm("确定删除这条内容的讲章吗？")) return;
  var r = await fetch("/api/items/" + encodeURIComponent(editId) + "/attachment", { method: "DELETE" });
  var d = await r.json();
  if (!d.ok) { alert(d.error || "删除失败"); return; }
  document.getElementById("slidesNow").textContent = "";
  document.getElementById("delSlidesBtn").style.display = "none";
  load();
}
async function delBookPdf() {
  var editId = document.getElementById("editId").value;
  if (!editId) return;
  if (!confirm("确定删除这本书的原书 PDF 吗？")) return;
  var r = await fetch("/api/items/" + encodeURIComponent(editId) + "/attachment", { method: "DELETE" });
  var d = await r.json();
  if (!d.ok) { alert(d.error || "删除失败"); return; }
  document.getElementById("bookPdfNow").textContent = "";
  document.getElementById("delBookPdfBtn").style.display = "none";
  load();
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
