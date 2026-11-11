#!/usr/bin/env node
// Pleos Connect Virtual Console을 터미널에서 조작하는 작은 도구.
//
// Virtual Console(http://127.0.0.1:8088)은 브라우저가 열려 있을 때만 값을 받는다.
// 이 스크립트는 브라우저가 하는 것과 같은 방식으로 세션 신호를 보내면서
// 주어진 단계를 순서대로 실행한다. 시연과 검증에서 같은 신호를 되풀이할 때 쓴다.
//
//   adb forward tcp:8088 tcp:8088
//   node tools/vc.mjs gear:Drive speed:40 wait:5 brake:100 speed:0 wait:2 gear:Park
//
// 단계
//   gear:Park|Reverse|Neutral|Drive      speed:<kph>        brake:<0-100>
//   accel:<0-100>                        odo:<km>           turn:Off|BlinkLeft|BlinkRight
//   wiper:Off|Auto|Low|High              lights:Off|Auto|Position|LowBeam
//   door:<front_left|...>:<true|false>   seat:<driver|...>:<true|false>
//   telltale:<SeatBelt|...>:<Off|On|Blink>
//   ramp:<fromKph>:<toKph>:<seconds>     wait:<seconds>     state

const BASE = process.env.VC_URL || "http://127.0.0.1:8088";
const client = "anbu-cli-" + Math.random().toString(36).slice(2, 10);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const controls = { speed: 0, accelerator: 0, brake: 0 };

async function put(path, params) {
  const q = new URLSearchParams(params).toString();
  const res = await fetch(`${BASE}${path}?${q}`, { method: "PUT" });
  const text = await res.text();
  if (!res.ok) throw new Error(`${path}?${q} -> HTTP ${res.status} ${text.slice(0, 120)}`);
  return text;
}

async function heartbeat(closing = false) {
  const url = `${BASE}/browser/session?client=${client}${closing ? "&closing=1" : ""}`;
  try { await fetch(url, { method: "POST" }); } catch { /* 에뮬레이터가 아직 안 떴을 수 있다 */ }
}

async function state() {
  const res = await fetch(`${BASE}/vehicle/state`, { cache: "no-store" });
  return res.json();
}

async function pushControls() {
  await put("/vehicle/controls", {
    speed: String(controls.speed), accelerator: String(controls.accelerator), brake: String(controls.brake),
  });
}

async function run(step) {
  const [name, a, b, c] = step.split(":");
  switch (name) {
    case "gear": return put("/vehicle/gear", { gear: a });
    case "speed": controls.speed = Number(a); return pushControls();
    case "brake": controls.brake = Number(a); return pushControls();
    case "accel": controls.accelerator = Number(a); return pushControls();
    case "odo": return put("/vehicle/odometer", { traveled_distance_km: a });
    case "turn": return put("/vehicle/turn-signal", { mode: a });
    case "wiper": return put("/vehicle/body", { front_wiper: a });
    case "lights": return put("/vehicle/lights", { feature: "headlamp_mode", value: a });
    case "door": {
      const field = { front_left: "door_fl", front_right: "door_fr", rear_left: "door_rl", rear_right: "door_rr" }[a];
      return put("/vehicle/body", { [field]: b });
    }
    case "seat": return put("/vehicle/seat", { kind: "occupied", value: b, area: a });
    case "telltale": return put("/vehicle/telltales/simulator", { color: "Red", state: b, type: a });
    case "ramp": {
      const from = Number(a), to = Number(b), seconds = Number(c);
      const steps = Math.max(1, Math.round(seconds * 5));
      for (let i = 1; i <= steps; i++) {
        controls.speed = Math.round(from + ((to - from) * i) / steps); // 콘솔은 정수 km/h만 받는다
        await pushControls();
        await sleep(200);
      }
      return;
    }
    case "wait": return sleep(Number(a) * 1000);
    case "state": {
      const s = await state();
      console.log(JSON.stringify({
        gear: s.gear?.actual?.value, speed_kph: s.controls?.speed_kph, brake_pct: s.controls?.brake_pct,
        wiper: s.body?.wipers?.front?.mode, headlamp: s.lights?.headlamp_mode, turn: s.lights?.turn_signal_mode,
        odometer_km: s.odometer?.traveled_distance_km, emulator_active: s.controls?.emulator_active,
      }));
      return;
    }
    default: throw new Error(`모르는 단계: ${step}`);
  }
}

const steps = process.argv.slice(2);
if (steps.length === 0) {
  console.error("사용법: node tools/vc.mjs gear:Drive speed:40 wait:5 speed:0 gear:Park");
  process.exit(2);
}

await heartbeat();
const timer = setInterval(heartbeat, 1000);
try {
  // 세션이 살아났다고 콘솔이 인정할 때까지 잠깐 기다린다.
  for (let i = 0; i < 30; i++) {
    const s = await state().catch(() => null);
    if (s?.controls?.emulator_active) break;
    await sleep(300);
  }
  for (const step of steps) {
    const t = new Date().toISOString().slice(11, 19);
    try {
      await run(step);
      if (!step.startsWith("wait") && step !== "state") console.log(`${t} ${step}`);
    } catch (e) {
      console.error(`${t} ${step} 실패: ${e.message}`);
      process.exitCode = 1;
    }
  }
} finally {
  clearInterval(timer);
  await heartbeat(true);
}
