#!/usr/bin/env node
// 안부 시연용 릴레이.
//
// 차에서 온 안부 요약을 받아 두었다가 가족 화면(family/index.html)에 내준다.
// 외부 패키지를 쓰지 않는다. 시제품 시연용이며 인증과 TLS가 없으므로 공개 서버로 쓰면 안 된다.
//
//   node relay/server.js            # http://127.0.0.1:8787
//   PORT=9000 HOST=0.0.0.0 node relay/server.js
//
// 릴레이는 정해진 필드만 받는다. 좌표나 속도처럼 보이는 값이 섞여 오면 저장하지 않고 거절한다.
// 앱이 실수로라도 위치를 내보내면 여기서 한 번 더 막기 위해서다.
//
// 가족이 차로 보내는 답장도 여기를 지난다. 받는 것은 정해 둔 답장의 이름 하나뿐이고 글은 받지 않는다.

"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || "127.0.0.1";
const RETENTION_MS = 30 * 24 * 3600 * 1000; // 가족 화면 보관 30일
const FAMILY_DIR = path.join(__dirname, "..", "family");
const DATA_FILE = process.env.RELAY_DATA || ""; // 지정하면 파일에도 저장한다

// 앱의 AnbuNote.WIRE_KEYS와 같은 목록이다.
const ALLOWED_KEYS = new Set([
  "v", "id", "familyCode", "type", "who", "at", "line",
  "placeLabel", "etaText", "durationNote", "level", "details",
]);
const TYPES = new Set(["DEPARTURE", "ARRIVAL", "WEEKLY", "CHECK_IN"]);
const LEVELS = new Set(["SIMILAR", "SLIGHTLY_DIFFERENT", "VERY_DIFFERENT"]);
const CODE_RE = /^[A-Z2-9]{6}$/;
const COORDINATE_RE = /-?\d{1,3}\.\d{4,}/; // 소수점 넷째 자리 이상이면 좌표로 본다
const FORBIDDEN_WORDS = /(latitude|longitude|\blat\b|\blng\b|\blon\b|coord|km\/h|m\/s)/i;

// 가족이 고를 수 있는 답장. 앱의 ReplyPreset과 같은 이름이다. 문장은 차와 가족 화면이 각자 갖고 있다.
const REPLY_CODES = new Set(["SAFE_TRIP", "DRIVE_SAFE", "CALL_ON_ARRIVAL", "THANKS"]);
const REPLY_KEYS = new Set(["v", "familyCode", "code"]);
const REPLY_KEEP_MS = 3 * 3600 * 1000;  // 차에 내주는 기간. 몇 시간 지난 답장은 읽어 줄 뜻이 없다
const REPLY_GAP_MS = 3000;              // 같은 가족 코드로 잇달아 보낼 때의 최소 간격
const REPLY_DAILY_MAX = 30;

/** familyCode -> Map(id -> note) */
const families = new Map();
/** familyCode -> [{ id, seq, code, atMs }] 메모리에만 둔다 */
const replies = new Map();
let replySeq = Date.now();

/** 가족 화면이 보낸 답장이 약속한 모양인지 본다. 문제가 있으면 이유를 돌려준다. */
function validateReply(body) {
  if (typeof body !== "object" || body === null || Array.isArray(body)) return "객체가 아닙니다";
  for (const key of Object.keys(body)) {
    if (!REPLY_KEYS.has(key)) return `받지 않는 항목입니다: ${key}`;
  }
  if (typeof body.familyCode !== "string" || !CODE_RE.test(body.familyCode)) return "가족 코드 형식이 아닙니다";
  if (typeof body.code !== "string" || !REPLY_CODES.has(body.code)) return "정해 둔 답장이 아닙니다";
  return null;
}

function pruneReplies(now = Date.now()) {
  for (const [code, list] of replies) {
    const kept = list.filter((r) => now - r.atMs <= 24 * 3600 * 1000);
    if (kept.length) replies.set(code, kept); else replies.delete(code);
  }
}

/** 답장을 받아 둔다. 너무 자주 오면 받지 않는다. */
function addReply(familyCode, code, now = Date.now()) {
  pruneReplies(now);
  const list = replies.get(familyCode) || [];
  const last = list[list.length - 1];
  if (last && now - last.atMs < REPLY_GAP_MS) return { error: "too_fast" };
  if (list.length >= REPLY_DAILY_MAX) return { error: "too_many" };
  const seq = ++replySeq;
  const reply = { id: "r" + seq.toString(36), seq, code, atMs: now };
  list.push(reply);
  replies.set(familyCode, list);
  return { reply };
}

/** 차에 내줄 답장. [afterId] 뒤에 온 것 가운데 최근 것만 준다. */
function repliesFor(familyCode, afterId, now = Date.now()) {
  const list = replies.get(familyCode) || [];
  const afterSeq = afterId ? (list.find((r) => r.id === afterId)?.seq ?? parseInt(String(afterId).slice(1), 36)) : 0;
  return list
    .filter((r) => r.seq > (Number.isFinite(afterSeq) ? afterSeq : 0) && now - r.atMs <= REPLY_KEEP_MS)
    .map(({ id, code, atMs }) => ({ id, code, atMs }));
}

function load() {
  if (!DATA_FILE || !fs.existsSync(DATA_FILE)) return;
  try {
    const raw = JSON.parse(fs.readFileSync(DATA_FILE, "utf8"));
    for (const [code, notes] of Object.entries(raw)) {
      families.set(code, new Map(notes.map((n) => [n.id, n])));
    }
  } catch (e) {
    console.error("저장 파일을 읽지 못했습니다:", e.message);
  }
}

function save() {
  if (!DATA_FILE) return;
  const out = {};
  for (const [code, notes] of families) out[code] = [...notes.values()];
  fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
  fs.writeFileSync(DATA_FILE, JSON.stringify(out));
}

function prune() {
  const cutoff = Date.now() - RETENTION_MS;
  for (const [code, notes] of families) {
    for (const [id, n] of notes) if (n.receivedAt < cutoff) notes.delete(id);
    if (notes.size === 0) families.delete(code);
  }
}

/** 받은 안부가 약속한 모양인지 본다. 문제가 있으면 이유를 돌려준다. */
function validate(note) {
  if (typeof note !== "object" || note === null || Array.isArray(note)) return "객체가 아닙니다";
  for (const key of Object.keys(note)) {
    if (!ALLOWED_KEYS.has(key)) return `받지 않는 항목입니다: ${key}`;
  }
  for (const key of ["id", "familyCode", "type", "who", "at", "line"]) {
    if (typeof note[key] !== "string" || note[key].length === 0) return `빠진 항목입니다: ${key}`;
  }
  if (!CODE_RE.test(note.familyCode)) return "가족 코드 형식이 아닙니다";
  if (!TYPES.has(note.type)) return "모르는 종류입니다";
  if (note.level !== undefined && !LEVELS.has(note.level)) return "모르는 단계입니다";
  if (Number.isNaN(Date.parse(note.at))) return "시각을 읽을 수 없습니다";
  if (note.details !== undefined) {
    if (typeof note.details !== "object" || note.details === null || Array.isArray(note.details)) {
      return "세부 수치 형식이 아닙니다";
    }
    if (Object.keys(note.details).length > 12) return "세부 수치가 너무 많습니다";
  }
  const texts = [];
  for (const [key, value] of Object.entries(note)) {
    if (key === "details") {
      for (const [k, v] of Object.entries(value)) {
        if (typeof v !== "string") return "세부 수치는 글자여야 합니다";
        texts.push(k, v);
      }
    } else if (key !== "v") {
      if (typeof value !== "string") return `글자가 아닙니다: ${key}`;
      if (key !== "at") texts.push(value);
    }
  }
  for (const t of texts) {
    if (t.length > 200) return "너무 긴 값이 있습니다";
    if (COORDINATE_RE.test(t) || FORBIDDEN_WORDS.test(t)) return "위치나 속도로 보이는 값은 받지 않습니다";
  }
  return null;
}

function send(res, status, body, type = "application/json; charset=utf-8") {
  const data = typeof body === "string" || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": type,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  res.end(data);
}

function readBody(req, limit = 16 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > limit) {
        reject(new Error("too large"));
        req.destroy();
      } else chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

const STATIC_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
};

function serveFamily(res, urlPath) {
  const rel = urlPath === "/" || urlPath === "/family" || urlPath === "/family/"
    ? "index.html"
    : urlPath.replace(/^\/family\//, "");
  const file = path.normalize(path.join(FAMILY_DIR, rel));
  if (!file.startsWith(FAMILY_DIR) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    return send(res, 404, { error: "not_found" });
  }
  send(res, 200, fs.readFileSync(file), STATIC_TYPES[path.extname(file)] || "application/octet-stream");
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://relay.local");
  const now = new Date().toISOString().slice(11, 19);
  try {
    if (url.pathname === "/healthz") return send(res, 200, { ok: true });

    if (url.pathname === "/api/notes") {
      if (req.method === "POST") {
        let note;
        try {
          note = JSON.parse(await readBody(req));
        } catch {
          return send(res, 400, { error: "bad_json" });
        }
        const problem = validate(note);
        if (problem) {
          console.log(`${now} 거절: ${problem}`);
          return send(res, 422, { error: "rejected", reason: problem });
        }
        prune();
        const { familyCode, ...rest } = note;
        if (!families.has(familyCode)) families.set(familyCode, new Map());
        families.get(familyCode).set(note.id, { ...rest, receivedAt: Date.now() });
        save();
        console.log(`${now} 받음: ${note.type} "${note.line}"`);
        return send(res, 201, { ok: true });
      }

      const code = (url.searchParams.get("code") || "").toUpperCase();
      if (!CODE_RE.test(code)) return send(res, 400, { error: "bad_code" });

      if (req.method === "GET") {
        prune();
        const notes = [...(families.get(code)?.values() || [])].sort((a, b) => b.receivedAt - a.receivedAt);
        return send(res, 200, { notes });
      }
      if (req.method === "DELETE") {
        const had = families.delete(code);
        replies.delete(code);
        save();
        console.log(`${now} 삭제 요청: ${had ? "지움" : "지울 것 없음"}`);
        return send(res, 200, { ok: true, deleted: had });
      }
      return send(res, 405, { error: "method_not_allowed" });
    }

    if (url.pathname === "/api/replies") {
      if (req.method === "POST") {
        let body;
        try {
          body = JSON.parse(await readBody(req, 1024));
        } catch {
          return send(res, 400, { error: "bad_json" });
        }
        const problem = validateReply(body);
        if (problem) {
          console.log(`${now} 답장 거절: ${problem}`);
          return send(res, 422, { error: "rejected", reason: problem });
        }
        const result = addReply(body.familyCode, body.code);
        if (result.error) return send(res, 429, { error: result.error });
        console.log(`${now} 답장 받음: ${body.code}`);
        return send(res, 201, { ok: true, id: result.reply.id, atMs: result.reply.atMs });
      }
      if (req.method === "GET") {
        const code = (url.searchParams.get("code") || "").toUpperCase();
        if (!CODE_RE.test(code)) return send(res, 400, { error: "bad_code" });
        return send(res, 200, { replies: repliesFor(code, url.searchParams.get("after")) });
      }
      return send(res, 405, { error: "method_not_allowed" });
    }

    if (req.method === "GET") return serveFamily(res, url.pathname);
    send(res, 404, { error: "not_found" });
  } catch (e) {
    console.error(`${now} 오류:`, e.message);
    if (!res.headersSent) send(res, 500, { error: "server_error" });
  }
});

if (require.main === module) {
  load();
  server.listen(PORT, HOST, () => {
    console.log(`안부 시연용 릴레이: http://${HOST}:${PORT}/  (가족 화면도 같은 주소)`);
    console.log("에뮬레이터 안의 앱은 http://10.0.2.2:" + PORT + " 로 보냅니다.");
  });
}

module.exports = { validate, validateReply, addReply, repliesFor, replies, REPLY_CODES, server };
