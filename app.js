/* 안부 웹 시연
 *
 * 차량용 앱 안부(app/ 폴더의 Kotlin 소스)의 동작을 브라우저에서 따라 한다.
 * 문구는 앱의 문자열과 같고, 기다리는 시간 가운데 일부만 시연에 맞게 줄였다(화면에 표시한다).
 * 바깥으로 나가는 요청은 "QR 만들기"를 누른 뒤의 짝 연결(ntfy.sh)뿐이다.
 */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ------------------------------------------------------------ 앱과 같은 값
  const WHO = '아버지';
  const PLACES = [
    { id: 'home', label: '집' }, { id: 'hospital', label: '병원' },
    { id: 'daughter', label: '딸네' }, { id: 'mart', label: '마트' },
  ];
  // 앱의 "예시 기록 넣기"가 만드는 최근 7일과 같은 값(core/ExampleHistory.kt)
  const WEEK = [
    ['운전한 횟수', '5번'], ['운전한 거리', '55km'], ['밤 운전', '20%', true], ['비 오는 날 운전', '10%'],
    ['급하게 멈춤', '1번'], ['안전띠 늦게 맴', '0번'], ['길 다시 찾음', '1번'], ['보호구역·역주행 주의', '5번'],
  ];
  const WEEK_DETAILS = { '주행': '5번', '거리': '55km', '밤 운전': '20%', '비 오는 날 운전': '10%', '급하게 멈춤': '1번', '안전띠 늦게 맴': '0번', '길 다시 찾음': '1번', '주의 구간 알림': '5번' };
  const WEEKLY_LINE = '이번 주는 평소와 조금 달랐어요. 밤 운전이 평소보다 많았어요.';
  const CHECK_IN_LINE = `${WHO}께 "괜찮으세요?" 하고 여쭸는데 30초 동안 답이 없었어요. 전화해 보세요.`;

  // 실제 앱의 시간과 시연에서 쓰는 시간(ms)
  const REAL = { parkHold: 20000, cancel: 10000, stopHold: 60000, answer: 30000 };
  const DEMO = { parkHold: 3000, cancel: 10000, stopHold: 4000, answer: 10000 };
  const LISTEN_MS = 6000;               // "말로 하기"를 누른 뒤 듣는 시간(앱과 같다)
  const REPLY_QUIET_MS = 5000;          // 차가 다른 안내를 한 뒤 답장을 읽기까지 기다리는 시간(앱과 같다)
  // 가족이 보낼 수 있는 답장. 앱의 ReplyPreset과 같다. 글을 써서 보내는 길은 없다.
  const REPLIES = [['SAFE_TRIP', '잘 다녀오세요'], ['DRIVE_SAFE', '조심히 오세요'], ['CALL_ON_ARRIVAL', '도착하면 전화 주세요'], ['THANKS', '고마워요']];
  const REPLY_TEXT = Object.fromEntries(REPLIES);

  // why: 실제 앱에서 그 장면을 만드는 차량 신호와 SDK
  const SCENES = [
    { id: 'hospital', name: '① 병원 가는 날',
      why: '실제 앱에서는 기어가 P로 바뀐 신호(AAOS Car API)와 저장한 장소에 들어선 신호(FusedLocation SDK)가 "도착"을 만듭니다. 이름을 붙인 곳에 섰을 때만 알리고, 주유소처럼 이름 없는 곳은 알리지 않습니다. 도착 예정 시각은 내비게이션의 소요 시간(NaviHelper SDK)에서 옵니다. 운전자는 도착해서 아무것도 누르지 않습니다.' },
    { id: 'diary', name: '② 주간 운전 일기',
      why: '실제 앱에서는 브레이크, 와이퍼, 전조등, 안전띠, 주행거리 신호(Vehicle SDK)와 속도(AAOS Car API)를 주행이 끝날 때 요약해 지난 8주의 본인 기록과 견줍니다. 본인 기록과의 비교이고 건강이나 운전 능력을 판정하지 않습니다.' },
    { id: 'checkin', name: '③ 괜찮으세요',
      why: '실제 앱에서는 브레이크와 속도 신호로 급하게 멈춘 것을 알고, 기어가 D인 채 서 있으면 TextToSpeech SDK로 묻고 SpeechToText SDK로 듣기를 시작합니다. 가족이 받는 알림에는 위치가 없습니다.' },
    { id: 'pause', name: '④ 오늘은 쉬기',
      why: '쉬는 동안 앱은 차량 신호를 기록하지 않고, 도착해도 보내지 않습니다. 멈추는 것도 되돌리는 것도 운전자 본인이 합니다.' },
  ];

  // ------------------------------------------------------------ 글 도우미
  function particle(word) {            // "로/으로"
    const c = word.charCodeAt(word.length - 1);
    if (c < 0xAC00 || c > 0xD7A3) return word + '로';
    const jong = (c - 0xAC00) % 28;
    return word + (jong === 0 || jong === 8 ? '로' : '으로');
  }
  function objParticle(word) {         // "을/를"
    const c = word.charCodeAt(word.length - 1);
    if (c < 0xAC00 || c > 0xD7A3) return word + '를';
    return word + ((c - 0xAC00) % 28 === 0 ? '를' : '을');
  }
  function clock12(d) {
    const h = d.getHours();
    return `${h < 12 ? '오전' : '오후'} ${h % 12 === 0 ? 12 : h % 12}:${String(d.getMinutes()).padStart(2, '0')}`;
  }
  function etaText(d) {
    const five = 5 * 60000;
    const r = new Date(Math.round(d.getTime() / five) * five);
    return `${r.getHours()}:${String(r.getMinutes()).padStart(2, '0')}쯤 도착 예정`;
  }
  function todayText() {
    const d = new Date();
    return `${d.getMonth() + 1}월 ${d.getDate()}일 ${'일월화수목금토'[d.getDay()]}요일`;
  }
  function weekRange() {
    const to = new Date(); const from = new Date(to.getTime() - 6 * 86400000);
    return `${from.getMonth() + 1}월 ${from.getDate()}일부터 ${to.getMonth() + 1}월 ${to.getDate()}일까지`;
  }

  // ------------------------------------------------------------ 소리
  let soundOn = false;
  let audioCtx = null;
  function speak(text) {
    if (!soundOn || !('speechSynthesis' in window)) return;
    try {
      const synth = window.speechSynthesis;
      const u = new SpeechSynthesisUtterance(text);
      u.lang = 'ko-KR';
      const v = synth.getVoices().find((x) => x.lang && x.lang.toLowerCase().startsWith('ko'));
      if (v) u.voice = v;
      // 말하는 중이면 끊고 새 문장을 말한다. 끊은 직후 바로 말하면 묻히는 브라우저가 있어 잠깐 띄운다.
      if (synth.speaking || synth.pending) { synth.cancel(); setTimeout(() => synth.speak(u), 80); } else synth.speak(u);
    } catch (e) { /* 음성 합성이 없는 브라우저 */ }
  }
  function unlockAudio() {
    try {
      audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
    } catch (e) { audioCtx = null; }
  }
  function chime() {                    // 가족 휴대폰 알림음: 짧은 두 음
    if (!audioCtx) return;
    const t0 = audioCtx.currentTime;
    [[880, 0], [1174.7, 0.16]].forEach(([freq, at]) => {
      const o = audioCtx.createOscillator(); const g = audioCtx.createGain();
      o.type = 'sine'; o.frequency.value = freq;
      g.gain.setValueAtTime(0.0001, t0 + at);
      g.gain.exponentialRampToValueAtTime(0.25, t0 + at + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + at + 0.32);
      o.connect(g); g.connect(audioCtx.destination);
      o.start(t0 + at); o.stop(t0 + at + 0.36);
    });
  }

  // ------------------------------------------------------------ 가족 휴대폰(화면 오른쪽, 또는 QR로 연 휴대폰)
  const LEVEL_MARK = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9.2" fill="none" stroke="currentColor" stroke-width="2.8"/><path d="M12 2.8a9.2 9.2 0 0 0 0 18.4z" fill="currentColor"/></svg>';
  let notes = [];                        // 최신이 앞
  let freshId = null;
  const REPLY_HINT = '운전 중에는 읽지 않고, 차를 세운 뒤에 읽어 드려요.';
  let replyStatus = { text: REPLY_HINT, ok: false };
  let carReplies = [];                   // 차가 받은 답장. { code, text, time, read }

  function familyHtml(extraTop) {
    const cards = notes.map((n) => {
      const fresh = n.id === freshId ? ' fresh' : '';
      if (n.type === 'WEEKLY') {
        const table = n.details ? '<table>' + Object.entries(n.details).map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`).join('') + '</table>' : '';
        return `<article class="fnote weekly${fresh}"><div class="time">${esc(n.time)}에 나눠 주신 이번 주 운전 일기</div>
          <div class="flevel SLIGHTLY_DIFFERENT">${LEVEL_MARK}<span>조금 다름</span></div>
          <div class="line">${esc(n.line)}</div>${table}
          <p class="fhint">${WHO}께서 먼저 보시고 나누기로 하신 내용이에요. 평소의 본인 운전과 견준 것이고, 건강을 판단한 것이 아니에요.</p>
          <span class="fcall">전화로 안부 묻기</span></article>`;
      }
      if (n.type === 'CHECK_IN') {
        return `<article class="fnote checkin${fresh}"><div class="time">${esc(n.time)}</div><div class="line">${esc(n.line)}</div>
          ${n.more ? `<div class="more">${esc(n.more)}</div>` : ''}<span class="fcall">지금 전화 걸기</span>
          <p class="fhint">차가 119에 자동으로 신고하지는 않습니다. 이 알림에는 위치가 없습니다.</p>
          <p class="fhint">연락이 닿지 않으면 119에 신고하세요. 배우자와 2촌 이내 친족은 긴급구조기관에 위치 확인을 요청할 수 있습니다(위치정보법 제29조 제1항).</p></article>`;
      }
      const short = n.line.replace(`${WHO}께서 `, '').replace(/^지금 /, '');
      return `<article class="fnote${fresh}"><div class="time">${esc(n.time)}</div><div class="line">${esc(short)}</div>
        ${n.more ? `<div class="more">${esc(n.more)}</div>` : ''}<div class="stamp">${n.type === 'ARRIVAL' ? '도착' : '출발'}</div></article>`;
    }).join('');
    const reply = notes.length ? `<div class="freply"><b>답장 보내기</b><div class="fgrid">${REPLIES.map(([code, text]) => `<button type="button" data-reply="${code}">${text}</button>`).join('')}</div>
      <p class="fhint${replyStatus.ok ? ' ok' : ''}">${esc(replyStatus.text)}</p></div>` : '';
    return `<h2>${WHO}의 안부</h2><p class="lede">차가 도착하면 대신 알려 드려요. 위치는 오지 않아요.</p>
      ${extraTop || ''}
      ${notes.length ? '<div class="day">오늘</div>' + cards + reply : '<div class="fempty">아직 온 안부가 없어요. 다음에 도착하시면 여기에 보여요.</div>'}
      <div class="ffoot">이 화면에는 지도가 없습니다. 차는 도착 시각과 본인이 붙인 장소 이름만 보냅니다.</div>`;
  }

  function addNote(n, opts) {
    notes.unshift(n);
    freshId = n.id;
    if (opts && opts.buzz && navigator.vibrate) { try { navigator.vibrate([140, 70, 140]); } catch (e) { /* 진동 없는 기기 */ } }
    if (soundOn || (opts && opts.buzz)) chime();
  }

  // ------------------------------------------------------------ 짝 연결 (ntfy.sh)
  const NTFY = 'https://ntfy.sh/';
  // 파일로 열었거나 이 컴퓨터 안에서만 보이는 주소로 열었으면 휴대폰이 닿을 수 없으므로, QR에는 공개 주소를 넣는다.
  const PUBLIC_URL = 'https://taronsung.github.io/anbu/';
  const pageUrl = () => (location.protocol === 'file:' || /^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname)
    ? PUBLIC_URL : location.href.split(/[?#]/)[0]);
  const pair = { topic: null, es: null, status: 'idle', phoneSeen: false };

  function randomTopic() {
    const a = new Uint8Array(20); crypto.getRandomValues(a);
    return 'anbu' + Array.from(a, (b) => 'abcdefghijklmnopqrstuvwxyz0123456789'[b % 36]).join('');
  }
  function publish(topic, msg) {
    // Cache: no 는 서버에 남기지 않고 지금 연결된 쪽에만 전하라는 뜻이다.
    return fetch(NTFY + topic, { method: 'POST', headers: { 'Cache': 'no', 'Firebase': 'no' }, body: JSON.stringify(msg) })
      .then((r) => r.ok);
  }
  function subscribe(topic, onMsg, onState) {
    const es = new EventSource(NTFY + topic + '/sse');
    es.addEventListener('open', () => onState('open'));
    es.addEventListener('error', () => onState('error'));
    es.addEventListener('message', (ev) => {
      try {
        const env = JSON.parse(ev.data);
        if (env.event !== 'message') return;
        const m = JSON.parse(env.message);
        if (m && m.v === 1) onMsg(m);
      } catch (e) { /* 다른 형식의 글은 넘긴다 */ }
    });
    return es;
  }
  function qrSvg(text) {
    const qr = qrcode(0, 'M'); qr.addData(text); qr.make();
    const n = qr.getModuleCount(); const m = 3; const size = n + m * 2;
    let d = '';
    for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) d += `M${c + m},${r + m}h1v1h-1z`;
    return `<svg viewBox="0 0 ${size} ${size}" role="img" aria-label="가족 휴대폰 연결용 QR"><rect width="${size}" height="${size}" fill="#fff"/><path d="${d}" fill="#14263A"/></svg>`;
  }

  // ------------------------------------------------------------ QR로 들어온 휴대폰: 가족 화면만 보여 준다
  const familyTopic = (() => {
    const t = new URLSearchParams(location.search).get('f') || '';
    return /^[a-z0-9]{16,40}$/.test(t) ? t : null;
  })();

  // 알림: QR로 들어온 휴대전화 화면이 열려 있는 동안 새 안부가 오면 브라우저 알림을 띄운다(서버가 밀어 보내는 알림은 아니다).
  const canNotify = 'Notification' in window && window.isSecureContext;
  let swReady = null;
  async function notifyPhone(note) {
    if (!canNotify || Notification.permission !== 'granted') return;
    const title = note.type === 'CHECK_IN' ? '안부: 답이 없으셨어요' : `${WHO}의 안부`;
    const options = { body: note.line, tag: note.id, lang: 'ko' };
    try {
      const reg = swReady && (await swReady);
      if (reg && reg.showNotification) await reg.showNotification(title, options);   // 안드로이드 크롬은 이 길만 된다
      else new Notification(title, options);
    } catch (e) { /* 알림을 띄우지 못하는 브라우저 */ }
  }

  function runFamilyOnly() {
    document.body.classList.add('family-only');
    if (canNotify && 'serviceWorker' in navigator) {
      swReady = navigator.serviceWorker.register('sw.js').then(() => navigator.serviceWorker.ready).catch(() => null);
    }
    $('desk').hidden = true; $('familyOnly').hidden = false;
    document.title = `${WHO}의 안부`;
    let state = 'ready';               // ready → waiting → linked / lost
    const draw = () => {
      const bar = state === 'ready'
        ? '<div class="linkbar">컴퓨터의 차 화면과 이어집니다.<button class="startbtn" id="startFamily" type="button">안부 받기 시작</button></div>'
        : `<div class="linkbar"><b class="${state === 'lost' ? 'bad' : 'ok'}">${state === 'linked' ? '차 화면과 이어졌어요.' : state === 'lost' ? '연결이 끊겼어요. 다시 잇는 중이에요.' : '차 화면에 잇는 중이에요.'}</b> 컴퓨터의 차 화면에서 안부를 보내면 여기에 옵니다.
          ${!canNotify ? '' : Notification.permission === 'granted' ? '<br>알림이 켜져 있어요. 이 화면을 열어 둔 동안 알려 드려요.'
            : Notification.permission === 'denied' ? '' : '<button class="alarmbtn" id="alarmOn" type="button">알림 켜기</button>'}</div>`;
      $('phoneFull').innerHTML = familyHtml(bar);
      const al = $('alarmOn');
      if (al) al.addEventListener('click', () => Notification.requestPermission().then(draw));
      const b = $('startFamily');
      if (b) b.addEventListener('click', () => {
        unlockAudio();
        state = 'waiting'; draw();
        subscribe(familyTopic, (m) => {
          if (m.from !== 'car') return;
          if (m.type === 'NOTE' && m.note) { addNote(m.note, { buzz: true }); notifyPhone(m.note); draw(); }
          if (m.type === 'RESET') { notes = []; freshId = null; replyStatus = { text: REPLY_HINT, ok: false }; draw(); }
        }, (s) => {
          if (s === 'open') {
            state = 'linked'; draw();
            publish(familyTopic, { v: 1, from: 'phone', type: 'HELLO' }).catch(() => {});
          } else if (state !== 'ready') { state = 'lost'; draw(); }
        });
      });
    };
    $('phoneFull').addEventListener('click', (ev) => {
      const b = ev.target.closest('[data-reply]'); if (!b || state === 'ready') return;
      const code = b.dataset.reply;
      publish(familyTopic, { v: 1, from: 'phone', type: 'REPLY', code })
        .then((ok) => { replyStatus = ok ? { text: `"${REPLY_TEXT[code]}"를 보냈어요. 차를 세운 뒤에 읽어 드려요.`, ok: true } : { text: '보내지 못했어요.', ok: false }; draw(); })
        .catch(() => { replyStatus = { text: '보내지 못했어요. 연결을 확인해 주세요.', ok: false }; draw(); });
    });
    draw();
  }

  // ------------------------------------------------------------ 차 화면 상태
  let st;
  let userScreen = 'home';
  let dirty = true;
  const now = () => Date.now();

  function freshState(scene) {
    return {
      scene, gear: scene === 'checkin' ? 'D' : 'P', drove: scene === 'checkin',
      paused: false, dest: null, etaAt: null, departureSent: false, arrivedPlace: null,
      parkSince: null, pendingArrival: null, hardStopAt: null, checkIn: null,
      lastNote: null, banner: null, bannerUntil: 0, diaryDecision: null,
      listenUntil: 0, lastPromptAt: 0, skipTrip: false,
      // 괜찮으세요 장면은 병원을 떠나 달리는 중에서 시작한다.
      leftPlace: scene === 'checkin' ? '병원' : null,
      done: {},
      set: { shareArrival: true, shareUnnamedArrival: false, receiveReplies: true, shareTripDuration: false, shareWeeklyDetails: false, useProximity: true, checkInAlert: true, checkInPlaceLabel: false },
    };
  }
  function banner(text) { st.banner = text; st.bannerUntil = now() + 6000; dirty = true; }
  function screen() {
    if (st.checkIn) return 'checkin';
    if (st.pendingArrival) return 'arrival';
    if (st.gear === 'D') return 'driving';
    return userScreen;
  }

  function send(note, summary) {         // 가족에게 안부 한 건. summary는 차 화면의 도장 옆에 적는 말
    note.id = 'n' + now() + Math.random().toString(36).slice(2, 6);
    note.time = clock12(new Date());
    st.lastNote = { summary, time: note.time };
    st.lastPromptAt = now();
    addNote(note);
    renderPhone();
    toast(note.line);
    if (pair.topic) publish(pair.topic, { v: 1, from: 'car', type: 'NOTE', note }).then((ok) => { if (!ok) pairFail(); }).catch(pairFail);
    dirty = true;
  }

  // 가족의 답장: 정해 둔 네 가지 이름만 받는다. 읽어 주는 것은 차가 서 있을 때뿐이다(앱의 AnbuEngine.deliverReplies).
  function receiveReply(code) {
    if (!REPLY_TEXT[code] || !st.set.receiveReplies || st.paused) return false;
    carReplies.push({ code, text: REPLY_TEXT[code], time: clock12(new Date()), read: false });
    st.done.reply = true; dirty = true;
    return true;
  }
  function deliverReplies(t) {
    const unread = carReplies.filter((r) => !r.read);
    if (!unread.length || st.paused || !st.set.receiveReplies) return;
    if (st.gear !== 'P' || st.drove || st.pendingArrival || st.checkIn) return;   // 주행이 끝나고 다른 안내도 끝난 뒤
    if (t - st.lastPromptAt < REPLY_QUIET_MS) return;
    unread.forEach((r) => { r.read = true; });
    st.lastPromptAt = t; st.done.replyRead = true;
    const texts = unread.map((r) => r.text);
    speak(texts.length === 1 ? `가족이 답장을 보냈어요. ${texts[0]}.` : `가족이 답장을 ${texts.length}개 보냈어요. ${texts.join('. ')}.`);
    banner(`가족의 답장: ${texts[texts.length - 1]}`);
  }
  const listeningNow = () => {           // 지금 차가 듣고 있는가(SpeechToText를 켠 구간)
    const t = now();
    if (st.pendingArrival) return t >= st.pendingArrival.listenAt;
    if (st.checkIn) return t >= st.checkIn.listenAt;
    return st.listenUntil > t;
  };

  function toast(line) {                 // 좁은 화면 전용(넓은 화면에서는 CSS가 숨긴다)
    const el = $('toast');
    el.innerHTML = `<b>딸의 휴대폰에 도착</b>${esc(line)}`;
    el.classList.add('on');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => el.classList.remove('on'), 5000);
  }

  // ------------------------------------------------------------ 차 화면에서 일어나는 일(앱의 AnbuEngine과 같은 순서)
  const act = {
    place(id) {
      const p = PLACES.find((x) => x.id === id);
      st.dest = p; st.etaAt = new Date(now() + 23 * 60000); st.done.place = true;
      speak(`${particle(p.label)} 안내할게요.`);
      banner(`${particle(p.label)} 길안내를 요청했어요.`);
    },
    departure() {
      if (st.paused) { banner('오늘은 쉬는 중이라 보내지 않았어요.'); return; }
      if (!st.set.shareArrival) { banner('안부 보내기가 꺼져 있어요. 설정에서 켤 수 있어요.'); return; }
      const line = st.dest ? `${WHO}께서 지금 ${particle(st.dest.label)} 출발하셨어요.` : `${WHO}께서 지금 출발하셨어요.`;
      send({ type: 'DEPARTURE', line, more: st.etaAt ? etaText(st.etaAt) : '' },
        st.dest ? `${particle(st.dest.label)} 출발한다고 전했어요` : '출발한다고 전했어요');
      st.departureSent = true; st.done.departure = true;
      speak('출발한다고 전했어요.');
      if (st.gear !== 'D') banner('가족에게 출발한다고 전했어요.');
    },
    gear(g) {
      if (st.gear === g) return;
      st.gear = g;
      if (g === 'D') { st.drove = true; st.parkSince = null; st.done.drive = true; }
      else {
        st.hardStopAt = null;
        if (st.drove) st.parkSince = now();
        userScreen = 'home';
      }
      dirty = true;
    },
    near() { st.arrivedPlace = 'hospital'; st.done.near = true; dirty = true; },
    hardStop() { st.hardStopAt = now(); st.done.hardStop = true; dirty = true; },
    tripEnded() {
      st.parkSince = null; st.drove = false;
      const label = st.arrivedPlace ? PLACES.find((p) => p.id === st.arrivedPlace).label : '외출지';
      st.arrivedPlace = null; st.dest = null; st.etaAt = null; st.departureSent = false; st.hardStopAt = null;
      if (st.paused) {
        // "이번 운전만 쉬기"는 그 주행이 끝나 주차하면 저절로 풀린다.
        if (st.skipTrip) { st.paused = false; st.skipTrip = false; st.done.skipEnded = true; banner('쉬었던 운전이 끝났어요. 다시 시작했어요.'); }
        return;
      }
      st.leftPlace = label === '외출지' ? null : label;
      if (!st.set.shareArrival) { banner('주행을 기록했어요. 안부는 보내지 않았어요.'); return; }
      // 이름을 붙인 곳에 섰을 때만 알린다. 이름 없는 곳은 본인이 켠 경우에만.
      if (label === '외출지' && !st.set.shareUnnamedArrival) { st.done.unnamed = true; banner('주행을 기록했어요. 이름 붙인 곳이 아니라서 안부는 보내지 않았어요.'); return; }
      st.lastPromptAt = now();
      st.pendingArrival = { label, line: `${WHO}께서 ${label}에 잘 도착하셨어요.`, deadline: now() + DEMO.cancel, listenAt: now() + 4500 };
      speak('가족에게 도착 안부를 보낼게요. 보내지 않으려면, 보내지 마, 라고 말씀하세요.');
      dirty = true;
    },
    cancelArrival() {
      if (!st.pendingArrival) return;
      st.pendingArrival = null; st.done.cancelled = true; st.lastPromptAt = now();
      speak('보내지 않았어요.'); banner('도착 안부를 보내지 않았어요.');
    },
    sendArrival() {
      const p = st.pendingArrival; if (!p) return;
      st.pendingArrival = null; st.done.arrival = true;
      send({ type: 'ARRIVAL', line: p.line }, `${p.label} 도착을 전했어요`);
      speak('가족에게 도착 안부를 보냈어요.'); banner('가족에게 도착 안부를 보냈어요.');
    },
    diary() {
      if (st.gear !== 'P') { banner('운전 일기는 차를 세우고 P에 두었을 때 볼 수 있어요.'); return; }
      userScreen = 'diary'; st.done.diaryOpen = true; dirty = true;
    },
    readDiary() {
      st.done.diaryRead = true;
      speak(`${WEEKLY_LINE} 주행 5번, 거리 55km, 밤 운전 20%, 비 오는 날 운전 10%, 급하게 멈춤 1번.`);
    },
    shareDiary() {
      if (st.paused) { banner('오늘은 쉬는 중이라 보내지 않았어요.'); return; }
      st.diaryDecision = 'SHARED'; st.done.diaryShared = true;
      send({ type: 'WEEKLY', line: WEEKLY_LINE, level: 'SLIGHTLY_DIFFERENT', details: st.set.shareWeeklyDetails ? WEEK_DETAILS : null }, '이번 주 운전 일기를 나눴어요');
      speak('이번 주 운전 일기를 가족과 나눴어요.');
    },
    keepDiary() { st.diaryDecision = 'PRIVATE'; st.done.diaryKept = true; banner('이번 주 일기는 나만 보기로 했어요.'); },
    askCheckIn() {
      st.hardStopAt = null;
      if (!st.set.checkInAlert || st.paused) return;
      st.lastPromptAt = now();
      st.checkIn = { deadline: now() + DEMO.answer, listenAt: now() + 3500 };
      speak('괜찮으세요? 괜찮으시면, 괜찮아, 라고 말씀해 주세요.');
      dirty = true;
    },
    okCheckIn() {
      if (!st.checkIn) return;
      st.checkIn = null; st.done.answered = true; st.lastPromptAt = now();
      speak('네, 알겠습니다.'); banner('괜찮다고 답하셨어요. 가족에게는 알리지 않았어요.');
    },
    escalate() {
      st.checkIn = null; st.done.escalated = true;
      // 붙이는 이름은 지금 있는 곳이 아니라 떠나온 곳이다(본인이 켠 경우에만).
      const where = st.set.checkInPlaceLabel && st.leftPlace ? `${objParticle(st.leftPlace)} 떠난 뒤` : '';
      send({ type: 'CHECK_IN', line: CHECK_IN_LINE, more: where }, '답이 없어 가족에게 알렸어요');
      speak('답이 없으셔서 가족에게 알렸어요.'); banner('답이 없어서 가족에게 알렸어요.');
    },
    call119() { banner('실제 앱에서는 119 전화 화면이 열립니다.'); },
    settings() { userScreen = 'settings'; st.done.settingsOpen = true; dirty = true; },
    home() { userScreen = 'home'; dirty = true; },
    pause() { st.paused = true; st.pendingArrival = null; st.checkIn = null; st.done.paused = true; banner('오늘은 쉬어요. 자정까지 기록도 전송도 하지 않아요.'); },
    // 다른 사람이 운전할 때. 지금부터 그 주행이 끝나 주차할 때까지 기록도 전송도 하지 않는다.
    pauseTrip() { st.paused = true; st.skipTrip = true; st.pendingArrival = null; st.checkIn = null; st.done.pausedTripOnly = true; banner('이번 운전은 쉬어요. 주차하면 다시 시작해요.'); },
    resume() { st.paused = false; st.skipTrip = false; st.done.resumed = true; banner('다시 시작했어요.'); },
    toggle(key) {
      st.set[key] = !st.set[key]; dirty = true;
      if (key === 'receiveReplies' && !st.set[key]) carReplies = [];
    },
    // "말로 하기": 실제 앱은 SpeechToText SDK로 6초 동안 듣는다. 여기서는 아래 "말하기" 단추가 그 말을 대신한다.
    listen() {
      if (st.listenUntil > now()) { st.listenUntil = 0; return; }
      st.listenUntil = now() + LISTEN_MS; st.done.listen = true;
      if (st.gear !== 'D') banner('듣고 있어요. "출발한다고 전해 줘"처럼 말씀하세요.');
    },
    sayDeparture() { if (st.listenUntil > now()) { st.listenUntil = 0; act.departure(); } },
    sayDiary() { if (st.listenUntil > now()) { st.listenUntil = 0; act.diary(); if (userScreen === 'diary') act.readDiary(); } },
  };

  function tick() {
    const t = now();
    if (st.parkSince && t - st.parkSince >= DEMO.parkHold) act.tripEnded();
    if (st.pendingArrival && t >= st.pendingArrival.deadline) act.sendArrival();
    if (st.hardStopAt && st.gear === 'D' && t - st.hardStopAt >= DEMO.stopHold) act.askCheckIn();
    if (st.checkIn && t >= st.checkIn.deadline) act.escalate();
    if (st.listenUntil && t > st.listenUntil) { st.listenUntil = 0; banner('잘 듣지 못했어요. 화면 버튼을 눌러 주세요.'); }
    deliverReplies(t);
    const hearing = listeningNow();
    if (hearing !== tick.lastHearing) { tick.lastHearing = hearing; dirty = true; }
    if (st.banner && t > st.bannerUntil) { st.banner = null; dirty = true; }
    const secs = st.pendingArrival ? Math.ceil((st.pendingArrival.deadline - t) / 1000) : st.checkIn ? Math.ceil((st.checkIn.deadline - t) / 1000) : -1;
    if (secs !== tick.lastSecs) { tick.lastSecs = secs; dirty = true; }
    if (dirty) render();
  }

  // ------------------------------------------------------------ 차 화면 그리기
  const GLYPH = {
    home: '<path d="M10,50 50,14 90,50M22,44V88H78V44"/>',
    hospital: '<rect x="12" y="12" width="76" height="76" rx="16"/><path d="M50,30V70M30,50H70"/>',
    daughter: '<path d="M10,50 50,14 90,50M22,44V88H78V44"/><path d="M50,78C30,64 36,48 50,58C64,48 70,64 50,78Z" fill="currentColor" stroke="none"/>',
    mart: '<path d="M14,42H86L76,84H24ZM34,42 44,16M66,42 56,16"/>',
  };
  const glyph = (id) => `<svg viewBox="0 0 100 100" fill="none" stroke="currentColor" stroke-width="8.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${GLYPH[id]}</svg>`;
  const HALF = '<svg class="mark" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9.2" fill="none" stroke="currentColor" stroke-width="2.8"/><path d="M12 2.8a9.2 9.2 0 0 0 0 18.4z" fill="currentColor"/></svg>';
  const gap = (px) => `<div style="height:${px}px;flex:none"></div>`;
  const btn = (act, label, cls = '', sub = '', extra = '') => `<button type="button" class="btn ${cls}" data-act="${act}" ${extra}>${esc(label)}${sub ? `<small>${esc(sub)}</small>` : ''}</button>`;

  function homeHtml(tall) {
    const status = st.skipTrip ? '이번 운전은 쉬는 중' : st.paused ? '오늘은 쉬는 중' : '주차 중';
    const header = `<div class="row" style="gap:24px"><div class="t-title">안부</div>
      <div class="t-body soft grow" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${todayText()}</div>
      <div class="t-strong ${st.paused ? '' : 'soft'}" style="${st.paused ? 'color:var(--slightly)' : ''};white-space:nowrap">${status}</div>
      ${btn('listen', listeningNow() ? '듣는 중' : '말로 하기', listeningNow() ? 'h84' : 'second h84')}
      ${btn('settings', '설정', 'second h84')}</div>`;
    const lastReply = carReplies[carReplies.length - 1];
    const replyCard = lastReply && !st.paused ? `<div class="cardbox replycard"><div class="t-caption soft">가족의 답장 · ${esc(lastReply.time)}</div><div class="t-headline">${esc(lastReply.text)}</div></div>` : '';
    const n = st.lastNote;
    const card = st.paused
      ? `<div class="cardbox"><div class="t-headline">${st.skipTrip ? '이번 운전은 쉬는 중이에요.' : '오늘은 쉬는 중이에요.'}</div><div class="t-body soft">${st.skipTrip ? '주차할 때까지' : '자정까지'} 기록도 전송도 하지 않아요.</div>${gap(14)}${btn('resume', '다시 시작', 'second h88')}</div>`
      : !n ? '<div class="cardbox t-body soft">아직 전한 안부가 없어요. 이름 붙인 곳에 도착하면 가족에게 대신 전해 드려요.</div>'
        : `<div class="cardbox row" style="gap:26px"><div class="stamp">전함</div><div><div class="t-strong">${esc(n.summary)}</div><div class="t-caption soft">${esc(n.time)}에 전했어요</div></div></div>`;
    const actions = `<div class="col" style="gap:18px">${btn('departure', '가족에게 한마디', 'wide-btn', '지금 출발한다고 전해요', st.paused ? 'disabled' : '')}${btn('diary', '이번 주 운전 일기', 'second wide-btn', '나에게 먼저 보여 줘요')}</div>`;
    const tiles = `<div class="tiles">${PLACES.map((p) => {
      const sel = st.dest && st.dest.id === p.id;
      return `<button type="button" class="tile${sel ? ' sel' : ''}" data-act="place" data-arg="${p.id}">${glyph(p.id)}<span><span class="name">${p.label}</span>${sel ? `<br><span class="eta">${esc(etaText(st.etaAt))}</span>` : ''}</span></button>`;
    }).join('')}</div>`;
    if (tall) return `<div class="scr">${header}${gap(20)}<div class="cols"><div class="t-title">어디로 가세요?</div>${tiles}${actions}${card}${replyCard}</div></div>`;
    return `<div class="scr">${header}${gap(26)}<div class="cols">
      <div class="col between" style="flex:0.4"><div class="col" style="gap:16px">${card}${replyCard}</div>${actions}</div>
      <div class="col" style="flex:0.6"><div class="t-title">어디로 가세요?</div>${gap(14)}${tiles}</div></div></div>`;
  }

  function drivingHtml() {
    const top = '<div class="row"><div class="t-headline soft grow">주행 중</div></div>';
    const mid = `<div><div class="t-display">${st.dest ? `${particle(st.dest.label)} 가는 중` : '안전하게 다녀오세요'}</div>${st.etaAt ? `<div class="t-title soft" style="margin-top:10px">${esc(etaText(st.etaAt))}</div>` : ''}</div>`;
    const bottom = st.paused
      ? `<div class="t-headline" style="color:var(--slightly)">${st.skipTrip ? '이번 운전은 쉬는 중이에요.' : '오늘은 쉬는 중이에요.'} 기록도 전송도 하지 않아요.</div>`
      : st.departureSent
        ? '<div class="row" style="gap:28px"><div class="stamp big on-paper">전함</div><div class="t-headline">가족에게 출발한다고 전했어요.</div></div>'
        : `<div>${btn('departure', '출발한다고 전하기', 'wide-btn h150')}${gap(16)}${listeningNow()
          ? btn('listen', '듣고 있어요', 'wide-btn h120', '"출발한다고 전해 줘"라고 말씀하세요')
          : btn('listen', '말로 하기', 'second wide-btn h120', '누른 뒤 "출발한다고 전해 줘"')}</div>`;
    return `<div class="scr between">${top}${mid}${bottom}</div>`;
  }

  function ringHtml(left, total) {
    const c = 2 * Math.PI * 46.5; const on = c * Math.max(0, Math.min(total, left)) / total;
    return `<div class="ring"><svg viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46.5" fill="none" stroke="#E6E0D2" stroke-width="7"/><circle cx="50" cy="50" r="46.5" fill="none" stroke="#14263A" stroke-width="7" stroke-dasharray="${on} ${c}"/></svg><div class="num">${left}</div></div>`;
  }
  function arrivalHtml(tall) {
    const p = st.pendingArrival; const left = Math.ceil((p.deadline - now()) / 1000);
    const text = `<div><div class="t-title">가족에게 도착 안부를 보낼게요</div>${gap(18)}
      <div class="cardbox" style="border-radius:24px;padding:22px 28px"><div class="t-headline">${esc(p.line)}</div><div class="t-caption soft">도착 시각과 장소 이름만 보냅니다. 위치는 보내지 않아요.</div></div>${gap(26)}
      ${btn('cancelArrival', '보내지 않기', 'wide-btn h150')}${gap(18)}${btn('sendArrival', '지금 보내기', 'second wide-btn')}${gap(14)}
      <div class="t-body soft">${listeningNow() ? '듣고 있어요. "보내지 마"라고 말씀하세요' : '안내가 끝나면 "보내지 마"를 듣습니다'}</div></div>`;
    if (tall) return `<div class="scr" style="align-items:center">${ringHtml(left, 10)}${gap(24)}${text}</div>`;
    return `<div class="scr" style="flex-direction:row;align-items:center;gap:40px"><div style="flex:0.4;display:grid;place-items:center">${ringHtml(left, 10)}</div><div style="flex:0.6">${text}</div></div>`;
  }

  function diaryHtml(tall) {
    const header = `<div class="row" style="gap:12px"><div class="grow"><div class="t-title screen" style="white-space:nowrap">이번 주 운전 일기</div>
      <div class="row" style="gap:16px;flex-wrap:wrap"><span class="t-body soft">${weekRange()}</span><span class="tag">예시 기록 포함</span></div></div>
      ${btn('home', '돌아가기', 'second h84')}</div>`;
    const verdict = `<div><div class="level-big"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9.2" fill="none" stroke="currentColor" stroke-width="2.8"/><path d="M12 2.8a9.2 9.2 0 0 0 0 18.4z" fill="currentColor"/></svg>조금 다름</div>${gap(12)}
      <div class="t-headline">${WEEKLY_LINE}</div>${gap(8)}
      <div class="t-caption soft">지난 8주의 내 운전과 견준 결과예요. 건강이나 운전 능력을 판단하는 것이 아니에요.</div></div>`;
    const decision = st.diaryDecision === 'SHARED'
      ? '<div class="row" style="gap:24px"><div class="stamp on-paper">전함</div><div class="t-headline">가족과 나눴어요.</div></div>'
      : st.diaryDecision === 'PRIVATE' ? '<div class="t-headline">이번 주는 나만 보기로 했어요.</div>'
        : `<div class="t-body soft">${st.set.shareWeeklyDetails ? '나누면 단계와 한 줄, 아래 수치가 가족에게 갑니다.' : '나누면 "조금 다름"과 위 한 줄만 가족에게 갑니다. 수치는 가지 않아요.'}</div>`;
    const actions = `<div>${decision}${gap(18)}<div class="row" style="gap:18px">${btn('shareDiary', '가족과 나누기', 'grow', '', st.diaryDecision === 'SHARED' || st.paused ? 'disabled' : '')}${btn('keepDiary', '나만 보기', 'second grow')}</div>${gap(18)}${btn('readDiary', '읽어 주기', 'second wide-btn h96')}</div>`;
    const metrics = `<div class="metrics">${WEEK.map(([k, v, flag]) => `<div class="metric"><span class="t-body grow">${k}</span>${flag ? `<span style="color:var(--slightly);display:flex;align-items:center;gap:10px;margin-right:18px" class="t-caption">${HALF}조금 다름</span>` : ''}<span class="v">${v}</span></div>`).join('')}</div>`;
    if (tall) return `<div class="scr">${header}${gap(18)}<div class="cols">${verdict}${actions}${metrics}</div></div>`;
    return `<div class="scr">${header}${gap(22)}<div class="cols"><div class="col between" style="flex:0.5">${verdict}${actions}</div><div class="col" style="flex:0.5">${metrics}</div></div></div>`;
  }

  function checkInHtml() {
    const left = Math.ceil((st.checkIn.deadline - now()) / 1000);
    return `<div class="scr between"><div class="row"><div class="t-headline soft grow">급하게 멈춘 뒤 서 있어요</div></div>
      <div><div class="t-display" style="font-size:109px;line-height:125px">괜찮으세요?</div>${gap(8)}<div class="t-headline soft">${left}초 안에 답이 없으면 가족에게 알려요.</div>
      <div class="t-body soft">${listeningNow() ? '듣고 있어요. "괜찮아"라고 말씀하세요' : '물음이 끝나면 "괜찮아"를 듣습니다'}</div></div>
      <div><div class="row" style="gap:22px">${btn('okCheckIn', '괜찮아요', 'h170', '', 'style="flex:0.64"')}${btn('call119', '119 전화', 'danger h170', '', 'style="flex:0.36"')}</div>${gap(14)}
      <div class="t-caption soft">119에 자동으로 신고하지는 않아요. 필요하면 직접 눌러 주세요.</div></div></div>`;
  }

  function settingsHtml(tall) {
    const s = st.set;
    const tg = (key, title, sub) => `<button type="button" class="toggle" role="switch" aria-checked="${s[key]}" data-act="toggle" data-arg="${key}"><span class="txt"><span class="t-strong" style="display:block">${title}</span><span class="t-caption soft">${sub}</span></span><span class="t-strong ${s[key] ? '' : 'soft'}">${s[key] ? '켬' : '끔'}</span><span class="switch"><i></i></span></button>`;
    const header = `<div class="row"><div class="t-title screen grow">공유 설정</div>${btn('home', '돌아가기', 'second h84')}</div>`;
    const side = `<div class="col" style="gap:18px"><div class="cardbox" style="border-radius:26px;padding:20px 28px"><div class="t-caption soft">가족 초대 코드</div><div class="t-title">K7M 2QX</div><div class="t-caption soft">가족에게는 "${WHO}"로 보입니다</div></div>
      ${st.paused ? btn('resume', '다시 시작', 'wide-btn', '지금은 쉬는 중이에요') : btn('pause', '오늘은 쉬기', 'wide-btn', '자정까지 기록도 전송도 하지 않아요')
        + btn('pauseTrip', '이번 운전만 쉬기', 'second wide-btn', '다른 사람이 운전할 때. 주차하면 다시 시작해요')}</div>`;
    const toggles = `<div class="col" style="gap:14px;overflow-y:auto">
      ${tg('shareArrival', '도착·출발 안부 보내기', '도착 시각과 장소 이름만 보냅니다')}
      ${tg('shareUnnamedArrival', '이름 없는 곳도 알리기', '끄면 집, 병원처럼 이름을 붙인 곳에 도착했을 때만 알립니다')}
      ${tg('receiveReplies', '가족의 답장 받기', '정해 둔 답장 네 가지만 받고, 차가 서 있을 때 읽어 드립니다')}
      ${tg('shareTripDuration', '평소보다 오래 걸렸는지도 알리기', '도착 안부에 한 줄을 붙입니다')}
      ${tg('shareWeeklyDetails', '운전 일기를 나눌 때 수치도 보내기', '끄면 단계와 한 줄만 갑니다')}
      ${tg('useProximity', '저장한 장소 도착 알아채기', '좌표는 차 밖으로 나가지 않습니다')}
      ${tg('checkInAlert', '괜찮으세요 확인', '30초 동안 답이 없으면 가족에게 시각을 알립니다')}
      ${tg('checkInPlaceLabel', '알림에 떠나온 곳 이름 붙이기', '좌표가 아니라 "병원을 떠난 뒤" 같은 글입니다')}</div>`;
    if (tall) return `<div class="scr">${header}${gap(18)}<div class="cols">${side}${toggles}</div></div>`;
    return `<div class="scr">${header}${gap(20)}<div class="cols"><div style="flex:0.4">${side}</div><div class="col" style="flex:0.6">${toggles}</div></div></div>`;
  }

  // ------------------------------------------------------------ 안내 문장과 차량 신호 단추
  function guide() {
    const s = screen(); const d = st.done;
    let next = ''; let note = '';
    if (st.scene === 'hospital') {
      if (s === 'arrival') { next = '10초 안에 "보내지 않기"를 누르면 취소됩니다. 그대로 두면 가족 휴대폰에 도착 안부가 갑니다.'; note = '실제 앱은 안내 음성이 끝나면 SpeechToText SDK로 "보내지 마"를 듣기 시작합니다. 여기서는 아래 "말하기" 단추가 그 말을 대신합니다.'; }
      else if (d.arrival && d.replyRead) next = '차가 서 있어서 답장을 읽어 주고 홈에 남겼습니다. 다음 장면을 골라 보세요.';
      else if (d.arrival && d.reply) next = '답장이 차에 왔습니다. 도착 안부 안내가 끝나면 읽어 줍니다.';
      else if (d.arrival) next = '가족 휴대폰에 "병원에 잘 도착하셨어요"가 왔습니다. 가족 휴대폰의 "답장 보내기"에서 하나를 눌러 보세요.';
      else if (d.cancelled) next = '도착 안부를 보내지 않았습니다. 가족 휴대폰에는 아무것도 가지 않았어요.';
      else if (d.unnamed && st.gear === 'P') next = '이름 붙인 곳이 아니어서 알리지 않았습니다. 설정의 "이름 없는 곳도 알리기"를 켜면 "외출지"로 알립니다.';
      else if (st.parkSince) { next = 'P에 두었습니다. 잠시 뒤 도착 안부 카운트다운이 뜹니다.'; note = `실제 앱은 P에 두고 ${REAL.parkHold / 1000}초를 기다립니다. 여기서는 ${DEMO.parkHold / 1000}초로 줄였습니다.`; }
      else if (st.gear === 'D') next = st.arrivedPlace ? '아래 차량 신호에서 기어를 P로 바꾸세요.' : '아래 차량 신호에서 "병원 근처에 도착"을 누른 뒤 기어를 P로 바꾸세요.';
      else if (!d.place) next = '차 화면에서 "병원"을 누르세요.';
      else if (!d.departure) next = '"가족에게 한마디"를 누르세요. 가족 휴대폰에 출발 한마디가 갑니다.';
      else next = '아래 차량 신호에서 기어를 D로 바꾸세요.';
    } else if (st.scene === 'diary') {
      if (d.diaryShared) next = '가족 휴대폰에는 단계와 한 줄, 두 문장만 갔습니다. 수치는 가지 않았어요.';
      else if (d.diaryKept) next = '나만 보기로 했습니다. 가족 휴대폰에는 아무것도 가지 않았어요.';
      else if (s === 'diary') next = '본인이 먼저 봅니다. "가족과 나누기"나 "나만 보기"를 누르세요.';
      else next = '차 화면에서 "이번 주 운전 일기"를 누르세요.';
      note = '화면의 수치는 앱의 예시 기록과 같은 값입니다.';
    } else if (st.scene === 'checkin') {
      if (s === 'checkin') { next = '"괜찮아요"를 누르면 가족에게 알리지 않습니다. 그대로 두면 가족 휴대폰에 알림이 갑니다.'; }
      else if (d.escalated) next = '답이 없어 가족 휴대폰에 알림이 갔습니다.';
      else if (d.answered) next = '괜찮다고 답했습니다. 가족 휴대폰에는 아무것도 가지 않았어요.';
      else if (st.hardStopAt) next = '급하게 멈춘 뒤 D에 둔 채 서 있습니다. 잠시 뒤 차가 묻습니다.';
      else next = '주행 중입니다. 아래 차량 신호에서 "급하게 멈춤"을 누르세요.';
      note = `실제 앱은 멈춘 뒤 ${REAL.stopHold / 1000}초가 지나면 묻고 ${REAL.answer / 1000}초 동안 답을 기다립니다. 여기서는 ${DEMO.stopHold / 1000}초와 ${DEMO.answer / 1000}초로 줄였습니다.`;
    } else {
      if (st.paused && st.gear === 'D') next = '쉬는 중에는 주행 화면에도 기록하지 않는다고 나옵니다. 기어를 P로 바꿔 보세요.';
      else if (st.paused && d.pausedTrip) next = '도착했지만 카운트다운이 뜨지 않았고 가족 휴대폰에도 아무것도 가지 않았습니다. "다시 시작"으로 되돌릴 수 있어요.';
      else if (st.paused) next = '쉬는 중입니다. 차량 신호에서 기어를 D로 바꿨다가 P로 돌려 한 번 다녀와 보세요.';
      else if (d.skipEnded) next = '쉬었던 운전이 끝나 주차하자 저절로 다시 시작했습니다. 그 주행은 기록도 전송도 하지 않았습니다.';
      else if (d.resumed) next = '다시 시작했습니다.';
      else if (s === 'settings') next = '"오늘은 쉬기"를 누르세요. 다른 사람이 운전할 때는 "이번 운전만 쉬기"가 있습니다.';
      else next = '차 화면 오른쪽 위의 "설정"을 누르세요.';
    }
    const why = SCENES.find((x) => x.id === st.scene).why;
    if (carReplies.some((r) => !r.read) && (st.gear === 'D' || st.drove)) note = '답장이 차에 와 있습니다. 주행 중에는 읽지 않고, 차를 세워 도착 안내가 끝난 뒤에 읽어 줍니다.';
    if (st.listenUntil > now()) note = '실제 앱은 SpeechToText SDK로 6초 동안 듣습니다. 여기서는 아래 "말하기" 단추가 그 말을 대신합니다.';
    return `<b>${esc(next)}</b>${note ? `<span class="note">${esc(note)}</span>` : ''}<span class="why">${esc(why)}</span>`;
  }

  function signals() {
    const s = screen();
    const g = (v, label) => `<button type="button" class="sig" data-act="gear" data-arg="${v}" aria-pressed="${st.gear === v}">${label}</button>`;
    let say = '';
    const off = listeningNow() ? '' : 'disabled';
    if (s === 'arrival') say = `<button type="button" class="sig say" data-act="cancelArrival" ${off}>말하기: "보내지 마"</button>`;
    else if (s === 'checkin') say = `<button type="button" class="sig say" data-act="okCheckIn" ${off}>말하기: "괜찮아"</button>`;
    else if (st.listenUntil > now()) say = '<button type="button" class="sig say" data-act="sayDeparture">말하기: "출발한다고 전해 줘"</button>'
      + (st.gear === 'P' ? '<button type="button" class="sig say" data-act="sayDiary">말하기: "운전 일기 읽어 줘"</button>' : '');
    return `<span class="label">차량 신호</span>${g('P', '기어 P')}${g('D', '기어 D')}
      <button type="button" class="sig" data-act="near" ${st.gear === 'D' && !st.arrivedPlace ? '' : 'disabled'}>병원 근처에 도착</button>
      <button type="button" class="sig" data-act="hardStop" ${st.gear === 'D' && !st.hardStopAt ? '' : 'disabled'}>급하게 멈춤</button>${say}`;
  }

  // ------------------------------------------------------------ 그리기
  let tall = false;
  function layout() {
    tall = window.innerWidth < 700;
    const car = $('car'); const view = $('carView');
    const w = tall ? 700 : 1400; const h = tall ? 1000 : 900;
    const k = view.clientWidth / w;
    car.classList.toggle('tall', tall);
    car.style.transform = `scale(${k})`;
    view.style.height = `${h * k}px`;
    layout.k = k;
  }
  // 세로 창은 안에서 스크롤하지 않고 내용만큼 길어진다.
  function fit() { if (tall) $('carView').style.height = `${$('car').offsetHeight * layout.k}px`; }
  function render() {
    dirty = false;
    const s = screen();
    const html = s === 'driving' ? drivingHtml() : s === 'arrival' ? arrivalHtml(tall) : s === 'checkin' ? checkInHtml()
      : s === 'diary' ? diaryHtml(tall) : s === 'settings' ? settingsHtml(tall) : homeHtml(tall);
    $('car').innerHTML = html + (st.banner && s !== 'driving' ? `<div class="banner" role="status">${esc(st.banner)}</div>` : '');
    $('car').dataset.screen = s;
    fit();
    $('guide').innerHTML = guide();
    $('signals').innerHTML = signals();
  }
  function renderPhone() { $('phone').innerHTML = familyHtml(''); }
  function renderScenes() {
    $('scenes').innerHTML = SCENES.map((s) => `<button type="button" class="scene" role="tab" aria-selected="${st.scene === s.id}" data-scene="${s.id}">${s.name}</button>`).join('')
      + '<button type="button" class="scene" data-scene="reset">처음부터</button>';
  }
  function startScene(id) {
    if ('speechSynthesis' in window) window.speechSynthesis.cancel();
    $('toast').classList.remove('on');
    st = freshState(id); userScreen = 'home'; tick.lastSecs = -1; dirty = true;
    renderScenes(); render();
  }

  // ------------------------------------------------------------ 짝 연결 패널(컴퓨터 쪽)
  function pairFail() { pair.status = 'bad'; renderPair(); }
  function renderPair() {
    const el = $('pair');
    if (!pair.topic) {
      el.innerHTML = `<h3>내 휴대폰으로 받아 보기</h3><p>QR을 찍으면 그 휴대폰이 가족 휴대폰이 됩니다. 여기 차 화면에서 보낸 안부가 그 휴대폰에 옵니다.</p><button type="button" class="go" id="pairGo">QR 만들기</button>`;
      $('pairGo').addEventListener('click', startPair);
      return;
    }
    const url = pageUrl() + '?f=' + pair.topic;
    const status = pair.status === 'bad' ? '<div class="status bad">연결 서비스에 닿지 않았어요. 한 화면 시연은 그대로 됩니다.</div>'
      : pair.phoneSeen ? '<div class="status ok">휴대폰이 이어졌어요. 차 화면에서 안부를 보내 보세요.</div>'
        : '<div class="status">휴대폰 카메라로 QR을 찍고 "안부 받기 시작"을 누르세요.</div>';
    el.innerHTML = `<h3>내 휴대폰으로 받아 보기</h3><div class="qr">${qrSvg(url)}</div><div class="url">${esc(url)}</div>${status}
      <p style="margin:8px 0 0">연결에는 공개 알림 서비스 ntfy.sh를 씁니다. 오가는 것은 시연 문장뿐이고, 주소는 QR을 만들 때마다 새로 정합니다.</p>`;
  }
  function startPair() {
    pair.topic = randomTopic(); pair.status = 'waiting'; pair.phoneSeen = false;
    renderPair();
    try {
      pair.es = subscribe(pair.topic, (m) => {
        if (m.from === 'phone' && m.type === 'HELLO') { pair.phoneSeen = true; pair.status = 'ok'; renderPair(); }
        if (m.from === 'phone' && m.type === 'REPLY') { receiveReply(m.code); render(); }
      }, (s) => { if (s === 'error' && !pair.phoneSeen) pairFail(); else if (s === 'open' && pair.status === 'bad') { pair.status = 'waiting'; renderPair(); } });
    } catch (e) { pairFail(); }
  }

  // ------------------------------------------------------------ 시작
  if (familyTopic) { runFamilyOnly(); return; }

  st = freshState('hospital');
  $('car').addEventListener('click', (ev) => {
    const b = ev.target.closest('[data-act]'); if (!b || b.disabled) return;
    act[b.dataset.act](b.dataset.arg); dirty = true; render();
  });
  $('signals').addEventListener('click', (ev) => {
    const b = ev.target.closest('[data-act]'); if (!b || b.disabled) return;
    if (st.scene === 'pause' && st.paused && b.dataset.act === 'gear' && b.dataset.arg === 'P' && st.gear === 'D') st.done.pausedTrip = true;
    act[b.dataset.act](b.dataset.arg); dirty = true; render();
  });
  $('scenes').addEventListener('click', (ev) => {
    const b = ev.target.closest('[data-scene]'); if (!b) return;
    if (b.dataset.scene === 'reset') {
      notes = []; freshId = null; carReplies = []; replyStatus = { text: REPLY_HINT, ok: false }; renderPhone();
      if (pair.topic) publish(pair.topic, { v: 1, from: 'car', type: 'RESET' }).catch(() => {});
      startScene('hospital');
    } else startScene(b.dataset.scene);
  });
  $('phone').addEventListener('click', (ev) => {
    const b = ev.target.closest('[data-reply]'); if (!b) return;
    const code = b.dataset.reply;
    replyStatus = receiveReply(code)
      ? { text: `"${REPLY_TEXT[code]}"를 보냈어요. 차를 세운 뒤에 읽어 드려요.`, ok: true }
      : { text: '차가 답장을 받지 않는 중이에요(쉬는 중이거나 답장 받기를 껐어요).', ok: false };
    renderPhone(); render();
  });
  $('sound').addEventListener('click', () => {
    soundOn = !soundOn; unlockAudio();
    $('sound').setAttribute('aria-pressed', String(soundOn));
    $('sound').textContent = soundOn ? '소리 끄기' : '소리 켜기';
    if (soundOn) speak('소리를 켰어요.'); else if ('speechSynthesis' in window) window.speechSynthesis.cancel();
  });
  $('toast').addEventListener('click', () => {
    $('toast').classList.remove('on');
    $('phone').scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
  window.addEventListener('resize', () => { layout(); render(); });

  layout(); renderScenes(); renderPhone(); renderPair(); render();
  setInterval(tick, 250);

  // 자동 검증에서 상태를 읽을 수 있게 둔다.
  window.anbuDemo = { state: () => ({ screen: screen(), scene: st.scene, gear: st.gear, paused: st.paused, notes: notes.map((n) => ({ type: n.type, line: n.line, more: n.more || '', details: !!n.details })), pair: { topic: pair.topic, status: pair.status, phoneSeen: pair.phoneSeen }, replies: carReplies.map((r) => ({ code: r.code, read: r.read })), listening: listeningNow() }) };
})();
