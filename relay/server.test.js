// 릴레이 점검: node --test relay/
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { validate, validateReply, addReply, repliesFor, replies, REPLY_CODES } = require("./server.js");

const note = (extra = {}) => ({
  v: 1, id: "a-1", familyCode: "ABC234", type: "ARRIVAL", who: "아버지",
  at: "2026-11-11T20:47+09:00", line: "아버지께서 병원에 잘 도착하셨어요.", placeLabel: "병원", ...extra,
});

test("약속한 항목만 있는 안부는 받는다", () => {
  assert.strictEqual(validate(note()), null);
});

test("목록에 없는 항목, 좌표나 속도로 보이는 값은 거절한다", () => {
  assert.ok(validate(note({ latitude: "37.5" })));
  assert.ok(validate(note({ line: "37.57961, 126.99901 근처" })));
  assert.ok(validate(note({ line: "시속 80 km/h" })));
  assert.ok(validate(note({ type: "LOCATION" })));
});

test("답장은 정해 둔 네 가지 이름만 받는다", () => {
  assert.deepStrictEqual([...REPLY_CODES], ["SAFE_TRIP", "DRIVE_SAFE", "CALL_ON_ARRIVAL", "THANKS"]);
  for (const code of REPLY_CODES) assert.strictEqual(validateReply({ v: 1, familyCode: "ABC234", code }), null);
  assert.ok(validateReply({ v: 1, familyCode: "ABC234", code: "아무 글이나" }));
  assert.ok(validateReply({ v: 1, familyCode: "ABC234", code: "SAFE_TRIP", text: "글을 덧붙임" }));
  assert.ok(validateReply({ v: 1, familyCode: "ABC234", text: "글만 보냄" }));
  assert.ok(validateReply({ v: 1, familyCode: "abc", code: "SAFE_TRIP" }));
});

test("답장은 차에 이름과 시각만 내주고, 받은 것 뒤의 것만 준다", () => {
  replies.clear();
  const t0 = Date.now();
  const first = addReply("ABC234", "SAFE_TRIP", t0).reply;
  assert.deepStrictEqual(addReply("ABC234", "THANKS", t0 + 1000), { error: "too_fast" });
  const second = addReply("ABC234", "THANKS", t0 + 4000).reply;

  const all = repliesFor("ABC234", null, t0 + 5000);
  assert.deepStrictEqual(all.map((r) => r.code), ["SAFE_TRIP", "THANKS"]);
  assert.deepStrictEqual(Object.keys(all[0]).sort(), ["atMs", "code", "id"]);
  assert.deepStrictEqual(repliesFor("ABC234", first.id, t0 + 5000).map((r) => r.id), [second.id]);
  assert.deepStrictEqual(repliesFor("ABC234", second.id, t0 + 5000), []);
  assert.deepStrictEqual(repliesFor("ZZZ999", null, t0 + 5000), []);
  assert.deepStrictEqual(repliesFor("ABC234", null, t0 + 4 * 3600 * 1000), [], "몇 시간 지난 답장은 내주지 않는다");
});
