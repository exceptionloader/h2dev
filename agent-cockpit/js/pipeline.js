/* 01 Pipeline – six workers walk a dependency graph; two gates need a human (price + send approval). */
(() => {
  const { $, $$, esc, rand, randi, pick, clamp, fmt, usd } = AC;

  const TASKS = [
    { id: 'schedule_14d', deps: [], work: 2, rows: (p) => [['T1–2', 'Prototyp'], ['T3–7', 'Wissen · Qualität'], ['T8–13', 'live · Demo'], ['T14', 'Abschluss']] },
    { id: 'kb.index', deps: ['schedule_14d'], work: 6, rows: (p) => [['Chunks', fmt(208 * p)], ['FAQs', fmt(48 * p)], ['Speicher', p < 1 ? 'indiziert…' : 'bereit'], ['Recall', (0.6 + 0.34 * p).toFixed(2)]] },
    { id: 'vector.db', deps: ['kb.index'], work: 5, rows: (p) => [['Dims', '1.536'], ['Chunks', fmt(208 * p)], ['Kosten', '25 $ / Mon'], ['Recall', p < 1 ? '…' : '0.94']] },
    { id: 'lead.json', deps: ['schedule_14d'], work: 4, rows: (p) => [['Felder', '6'], ['Schema', 'name · need · budget'], ['geschrieben', fmt(40 * p)], ['Status', p < 1 ? 'parst…' : 'typisiert']] },
    { id: 'webhook', deps: ['lead.json'], work: 3, rows: (p) => [['Route', '/lead'], ['Prüfung', p > 0.5 ? 'HMAC ok' : '…'], ['Latenz', p > 0.3 ? '118 ms' : '–'], ['Status', p < 1 ? 'verdrahtet…' : 'live']] },
    { id: 'deploy.live', deps: ['vector.db', 'webhook'], work: 5, rows: (p) => [['Host', '20 $ / Mon'], ['URL', p < 1 ? 'privat' : 'öffentlich'], ['Uptime', p < 1 ? '–' : '99,9 %'], ['Status', p < 1 ? 'baut…' : 'live']] },
    { id: 'price_gate', deps: ['lead.json'], work: 1, gate: 'price', rows: (p, S) => [['Wert', S.price ? usd(S.price, 0) : '[ fehlt ]'], ['wenn fehlt', 'STOP + FRAGEN'], ['Besitzer', 'du'], ['Status', S.price ? 'gesetzt' : 'wartet']] },
    { id: 'your_call', deps: ['deploy.live', 'price_gate'], work: 1, gate: 'approve', rows: (p, S) => [['Eingabe', S.approved ? 'bereit' : '[ offen ]'], ['Autorität', 'Mensch'], ['Angebot', S.approved ? 'freigegeben' : 'in Warteschlange'], ['Senden', S.approved ? 'erlaubt' : 'nicht freigegeben']] },
    { id: 'outreach', deps: ['your_call'], work: 6, rows: (p, S) => [['Kunden', `${fmt(25 * p)} / 25`], ['Preis', S.price ? usd(S.price, 0) : '–'], ['Potenzial', S.price ? usd(25 * p * S.price, 0) : '–'], ['gesendet', fmt(25 * p)]] },
  ];
  const WORKERS = ['maps.pull', 'kb.load', 'qualify', 'deploy', 'outreach', 'review'];
  const TAGS = ['ok', 'fit', 'draft', 'ctx', '$', 'dup', 'json', 'ack', '200'];

  let el, S, cards = {}, hero, cv, mesh, log, running = true, speed = 1, strands = [], parts = [], rebuildT = 0;

  const fresh = () => ({
    t: 0, price: 0, approved: false, chats: 0, qualified: 0, sent: 0, closed: 0, revenue: 0, unitsDone: 0,
    tasks: Object.fromEntries(TASKS.map((t) => [t.id, { p: 0, state: 'wait' }])),
    workers: WORKERS.map((n, i) => ({ id: 'w' + (i + 1), name: n, task: null, p: 0, lat: 0, done: 0 })),
  });
  const totalWork = TASKS.reduce((a, t) => a + t.work, 0);

  function init(root) {
    el = root;
    el.innerHTML = `
      <div class="vhead">
        <div><h1>Pipeline · <em>Prototyp in 14 Tagen</em></h1>
        <p>Sechs Worker arbeiten einen Abhängigkeits-Graphen ab. An zwei Toren entscheidest du: Preis festlegen und Angebot freigeben. Ohne dich wird nichts verschickt.</p></div>
        <div class="ctrls">
          <button class="btn pri" id="plRun">Pause</button>
          <div class="seg" id="plSpeed" role="group" aria-label="Tempo"><button aria-pressed="true" data-s="1">1×</button><button aria-pressed="false" data-s="3">3×</button><button aria-pressed="false" data-s="8">8×</button></div>
          <button class="btn" id="plReset">Neu starten</button>
        </div>
      </div>
      <div class="grid">
        <div class="panel s12 hero" id="plHero">
          <canvas class="bg" id="plCv"></canvas>
          <div class="ov">
            <div class="ph"><span>Tag <b id="plDay">1</b> / 14 · <span id="plPhase">Arbeitsprototyp</span></span><span class="r" id="plGoal">Ziel 2.000 $ / Mon · 14 Tage · 6 Worker · 1 Mensch</span></div>
            <div class="pl-cards" id="plCards"></div>
          </div>
        </div>
        <div class="panel s3"><div class="ph">Angefragt <span class="r" id="plRate">0 / s</span></div><div class="log pb flush" id="plLog"></div></div>
        <div class="panel s3 dispatch"><div class="ph">Dispatch <span class="r">6 Worker</span></div><div class="pb" id="plWorkers"></div></div>
        <div class="panel s3"><div class="ph">Formeln · live <span class="r">Bewertung pro Tick</span></div><div class="pb" id="plFormulas"></div></div>
        <div class="panel s3"><div class="ph">Output <span class="r" id="plOutState">wartet</span></div><div class="pb">
          <div class="big acc" id="plOut">0 $</div><div class="sub" id="plOutSub">0 von 25 × Preis</div>
          <div class="row" style="margin-top:10px"><span>abgeschlossen</span><b id="plClosed">0</b></div>
          <div class="row"><span>Umsatz / Mon</span><b id="plRev">0 $</b></div>
          <p class="note">Abschlüsse sind simuliert (≈ 20 % der gesendeten Angebote).</p>
        </div></div>
      </div>`;
    hero = $('#plHero', el);
    log = new AC.Log($('#plLog', el), 30);
    const box = $('#plCards', el);
    for (const t of TASKS) {
      const c = AC.h(`<div class="card" data-id="${t.id}"><div class="ch"><span>${t.id}</span></div><div class="cb"></div>${t.gate ? '<div class="gx" style="padding:0 9px"></div>' : ''}<div class="cf"><span>Tor ${t.deps.length ? Math.min(3, t.deps.length + (t.gate ? 1 : 0)) : 1}</span><span class="meter"><i style="width:0"></i></span></div></div>`);
      box.append(c); cards[t.id] = c;
    }
    cv = AC.canvas($('#plCv', el), () => { buildMesh(); buildStrands(); });
    $('#plRun', el).onclick = () => { running = !running; $('#plRun', el).textContent = running ? 'Pause' : 'Weiter'; };
    $$('#plSpeed button', el).forEach((b) => (b.onclick = () => { speed = +b.dataset.s; $$('#plSpeed button', el).forEach((x) => x.setAttribute('aria-pressed', x === b)); }));
    $('#plReset', el).onclick = reset;
    reset();
  }

  function reset() {
    S = fresh(); log.clear();
    for (const t of TASKS) { const gx = $('.gx', cards[t.id]); if (gx) gx.innerHTML = ''; }
    log.add('system', 'Neuer Lauf: 9 Aufgaben, 6 Worker', 'T1');
    render(true);
  }

  // ---------- simulation ----------
  const ready = (t) => t.deps.every((d) => S.tasks[d].state === 'done');
  function tick(dt) {
    S.t += dt;
    for (const t of TASKS) {
      const st = S.tasks[t.id];
      if (st.state === 'wait' && ready(t)) {
        if (t.gate === 'price' && !S.price) { st.state = 'gate'; log.add('gate', 'price_gate: Preis fehlt → STOP + FRAGEN', 'du', 'hi'); showGate(t); }
        else if (t.gate === 'approve' && !S.approved) { st.state = 'gate'; log.add('gate', 'your_call: Freigabe nötig, Senden gesperrt', 'du', 'hi'); showGate(t); }
        else st.state = 'ready';
      }
    }
    // assign idle workers (max 2 per task)
    for (const w of S.workers) {
      if (w.task) continue;
      const open = TASKS.filter((t) => ['ready', 'run'].includes(S.tasks[t.id].state) && S.workers.filter((x) => x.task === t.id).length < 2);
      if (!open.length) continue;
      const t = open[0];
      w.task = t.id; w.lat = randi(40, 380); S.tasks[t.id].state = 'run';
    }
    // progress
    for (const w of S.workers) {
      if (!w.task) continue;
      const t = TASKS.find((x) => x.id === w.task), st = S.tasks[t.id];
      const inc = dt / t.work;
      st.p = clamp(st.p + inc, 0, 1); w.p = st.p; S.unitsDone += dt;
      if (Math.random() < dt * 1.6) {
        AC.decision({ latency: w.lat + randi(-30, 60), conf: rand(0.86, 0.99), cost: 0.00018 });
        if (Math.random() < 0.35) log.add(w.id + ' ' + w.name, `${t.id} · Schritt ${Math.ceil(st.p * 10)}/10`, w.lat + ' ms');
      }
      if (st.p >= 1 && st.state !== 'done') {
        st.state = 'done';
        log.add(w.id, `${t.id} fertig`, 'ok', 'ok');
        if (t.id === 'outreach') { S.sent = 25; log.add('outreach', `25 Angebote à ${usd(S.price, 0)} gesendet`, 'gesendet', 'hi'); }
      }
      if (st.state === 'done') { w.task = null; w.done++; }
    }
    // live product after deploy: chats + qualification
    if (S.tasks['deploy.live'].state === 'done') {
      if (Math.random() < dt * 1.4) { S.chats++; if (Math.random() < 0.18) S.qualified++; }
    }
    if (S.sent && Math.random() < dt * 0.25 && S.closed < Math.round(S.sent * 0.2)) {
      S.closed++; S.revenue = S.closed * S.price; log.add('crm', 'Deal abgeschlossen', usd(S.price, 0), 'ok');
    }
  }

  function showGate(t) {
    const gx = $('.gx', cards[t.id]);
    if (t.gate === 'price') {
      gx.innerHTML = `<div class="gin"><input type="number" id="plPrice" min="1" step="10" placeholder="Preis in $" aria-label="Preis pro Monat in Dollar" value="80"><button class="btn sm pri" id="plPriceBtn">Setzen</button></div>`;
      $('#plPriceBtn', gx).onclick = () => {
        const v = Math.round(+$('#plPrice', gx).value);
        if (!(v > 0)) { AC.toast('Gib einen Preis über 0 ein.'); return; }
        S.price = v; S.tasks[t.id].state = 'ready'; gx.innerHTML = '';
        log.add('du', `Preis gesetzt: ${usd(v, 0)} / Mon`, 'Tor 2', 'hi');
      };
    } else {
      gx.innerHTML = `<div class="gin"><button class="btn sm pri" id="plApprove">Freigeben</button><button class="btn sm" id="plDeny">Ablehnen</button></div>`;
      $('#plApprove', gx).onclick = () => { S.approved = true; S.tasks[t.id].state = 'ready'; gx.innerHTML = ''; log.add('du', 'Angebot freigegeben', 'Autorität: Mensch', 'hi'); };
      $('#plDeny', gx).onclick = () => { log.add('du', 'Angebot abgelehnt, nichts gesendet', 'gestoppt', 'bad'); AC.toast('Abgelehnt. Starte neu, um es nochmal zu versuchen.'); };
    }
  }

  // ---------- render DOM ----------
  let lastDom = 0;
  function render(force) {
    const doneUnits = TASKS.reduce((a, t) => a + S.tasks[t.id].p * t.work, 0);
    const day = 1 + Math.round(13 * (doneUnits / totalWork));
    $('#plDay', el).textContent = day;
    $('#plPhase', el).textContent = day <= 2 ? 'Arbeitsprototyp' : day <= 7 ? 'Wissensbasis + Qualität' : day <= 13 ? 'live · Demo' : 'Abschluss';
    for (const t of TASKS) {
      const st = S.tasks[t.id], c = cards[t.id];
      c.className = 'card ' + (st.state === 'run' ? 'run' : st.state === 'gate' ? 'gate' : st.state === 'done' ? 'done' : '');
      $('.cb', c).innerHTML = t.rows(st.p, S).map(([k, v]) => `<div class="row"><span>${esc(k)}</span><span>${esc(v)}</span></div>`).join('');
      $('.cf .meter i', c).style.width = st.p * 100 + '%';
    }
    $('#plWorkers', el).innerHTML = S.workers.map((w) => `<div class="w"><b>${w.id}</b><span>${w.name}</span><span class="meter"><i style="width:${w.task ? w.p * 100 : 0}%"></i></span><span>${w.task ? w.lat + ' ms' : w.done ? 'fertig' : 'frei'}</span></div>`).join('');
    const infra = 25 + 20 + 96 + 25;
    $('#plFormulas', el).innerHTML = [
      ['Chats', 'Σ Inbox', fmt(S.chats)],
      ['Konversion', 'qual / chats', S.chats ? AC.pct(S.qualified / S.chats, 1) : '–'],
      ['Kosten / Chat', '96 $ / chats', S.chats ? usd(96 / S.chats) : '–'],
      ['Infra', '25+20+96+25', usd(infra, 0)],
      ['Gates offen', 'Mensch', String(TASKS.filter((t) => S.tasks[t.id].state === 'gate').length)],
    ].map(([a, b, c]) => `<div class="row"><span>${a} <span class="sub">= ${b}</span></span><b>${c}</b></div>`).join('');
    $('#plOut', el).textContent = usd(S.sent * S.price, 0);
    $('#plOutSub', el).textContent = `${S.sent} von 25 × ${S.price ? usd(S.price, 0) : 'Preis'}`;
    $('#plClosed', el).textContent = S.closed;
    $('#plRev', el).textContent = usd(S.revenue, 0);
    const gates = TASKS.filter((t) => S.tasks[t.id].state === 'gate');
    $('#plOutState', el).textContent = S.sent ? 'gesendet' : gates.length ? 'wartet auf dich' : 'läuft';
    $('#plRate', el).textContent = fmt(S.workers.filter((w) => w.task).length * 1.6 * speed, 1) + ' / s';
  }

  // ---------- canvas: mesh on top, strands into each card ----------
  function buildMesh() {
    const w = cv.w, h = 80;
    mesh = document.createElement('canvas'); mesh.width = w * cv.dpr; mesh.height = h * cv.dpr;
    const m = mesh.getContext('2d'); m.scale(cv.dpr, cv.dpr);
    const pts = Array.from({ length: Math.round(w / 9) }, () => [rand(w), rand(6, h - 8)]);
    m.strokeStyle = AC.alpha(AC.col.fg, 0.07); m.lineWidth = 0.6;
    for (let i = 0; i < pts.length; i++) for (let j = i + 1; j < Math.min(pts.length, i + 7); j++) { m.beginPath(); m.moveTo(...pts[i]); m.lineTo(...pts[(j * 7) % pts.length]); m.stroke(); }
    m.fillStyle = AC.alpha(AC.col.fg, 0.35); m.font = '9px ' + getComputedStyle(document.body).getPropertyValue('--f-mono');
    for (let i = 0; i < pts.length; i += 6) m.fillText(String(randi(100, 999)), pts[i][0], pts[i][1]);
  }
  function buildStrands() {
    if (!hero || !hero.offsetWidth) return;
    const hr = hero.getBoundingClientRect();
    strands = [];
    for (const t of TASKS) {
      const r = cards[t.id].getBoundingClientRect();
      const x1 = r.left - hr.left + r.width / 2, y1 = r.top - hr.top;
      const n = AC.reduced ? 5 : 14;
      for (let i = 0; i < n; i++) {
        const x0 = x1 + rand(-cv.w * 0.25, cv.w * 0.25), y0 = rand(30, 70);
        strands.push({ id: t.id, x0, y0, c1x: x0, c1y: y1 * 0.55, c2x: x1 + rand(-24, 24), c2y: y1 - 30, x1: x1 + rand(-14, 14), y1 });
      }
    }
  }
  const bz = (s, t) => {
    const u = 1 - t;
    return [u * u * u * s.x0 + 3 * u * u * t * s.c1x + 3 * u * t * t * s.c2x + t * t * t * s.x1, u * u * u * s.y0 + 3 * u * u * t * s.c1y + 3 * u * t * t * s.c2y + t * t * t * s.y1];
  };
  function draw(dt) {
    const { ctx, w, h } = cv; ctx.clearRect(0, 0, w, h);
    if (mesh) ctx.drawImage(mesh, 0, 0, w, 80);
    const C = AC.col;
    for (const s of strands) {
      const st = S.tasks[s.id].state;
      ctx.strokeStyle = st === 'run' ? AC.alpha(C.accent, 0.42) : st === 'gate' ? AC.alpha(C['accent-2'], 0.4) : st === 'done' ? AC.alpha(C.fg, 0.13) : AC.alpha(C.fg, 0.05);
      ctx.lineWidth = st === 'run' || st === 'gate' ? 0.9 : 0.6;
      ctx.beginPath(); ctx.moveTo(s.x0, s.y0); ctx.bezierCurveTo(s.c1x, s.c1y, s.c2x, s.c2y, s.x1, s.y1); ctx.stroke();
      if ((st === 'run' || st === 'gate') && running && Math.random() < dt * 2.2 * speed && parts.length < 260) parts.push({ s, t: 0, v: rand(0.35, 0.8), tag: Math.random() < 0.1 ? pick(TAGS) : null });
    }
    ctx.font = '9px ' + getComputedStyle(document.body).getPropertyValue('--f-mono');
    parts = parts.filter((p) => {
      if (running) p.t += dt * p.v * Math.min(speed, 3);
      if (p.t >= 1) return false;
      const [x, y] = bz(p.s, p.t), st = S.tasks[p.s.id].state, col = st === 'gate' ? C['accent-2'] : C.accent;
      ctx.fillStyle = col; ctx.beginPath(); ctx.arc(x, y, 1.6, 0, 7); ctx.fill();
      if (p.tag) { const tw = ctx.measureText(p.tag).width + 8; ctx.fillStyle = AC.alpha(col, 0.85); ctx.fillRect(x + 4, y - 7, tw, 12); ctx.fillStyle = '#111'; ctx.fillText(p.tag, x + 8, y + 2); }
      return true;
    });
  }

  AC.register('pipeline', {
    init,
    enter() { cv.fit(); buildMesh(); buildStrands(); },
    frame(t, dt) {
      if (running) for (let i = 0; i < speed; i++) tick(dt);
      draw(dt);
      lastDom += dt; rebuildT += dt;
      if (lastDom > 0.2) { lastDom = 0; render(); }
      if (rebuildT > 2) { rebuildT = 0; buildStrands(); }
    },
  });
})();
