/* 05 Schwarm – a goal is split by a decision layer into lanes, three leads dispatch up to 300 agents, results merge into one deliverable. */
(() => {
  const { $, $$, esc, rand, randi, pick, clamp, fmt, usd } = AC;

  const LANES = [
    { id: 'research', n: 'Recherche', lead: 0 }, { id: 'browse', n: 'Browsen', lead: 0 }, { id: 'code', n: 'Code', lead: 1 },
    { id: 'analyze', n: 'Analyse', lead: 2 }, { id: 'test', n: 'Test', lead: 2 }, { id: 'deploy', n: 'Deploy', lead: 1 },
  ];
  const LEADS = ['Planer', 'Bauer', 'Prüfer'];
  // $ per 1M tokens (Anthropic list prices, Okt 2026)
  const P = { opus: [4, 20], sonnet: [2, 10], haiku: [0.1, 0.5] };
  const cost = (m, i, o) => (i * P[m][0] + o * P[m][1]) / 1e6;
  const JOB = cost('haiku', 6000, 800), LEADC = cost('sonnet', 4000, 600), PLAN = cost('opus', 3000, 1500), JOB_OPUS = cost('opus', 6000, 800);

  const template = (goal) => ({
    research: [`Zielgruppe für „${goal}“ verstehen`, 'Wettbewerber sammeln', 'Preise vergleichen'],
    browse: ['Top-10 Seiten auslesen', 'Bewertungen sammeln', 'Screenshots ablegen'],
    code: ['Grundgerüst anlegen', 'Kernfunktion bauen', 'API anbinden', 'UI verdrahten'],
    analyze: ['Daten auswerten', 'Risiken bewerten'],
    test: ['Unit-Tests', 'End-to-End-Test', 'Lasttest'],
    deploy: ['Staging ausrollen', 'Monitoring an', 'Live schalten'],
  });

  let el, cv, cvs = {}, log, S, speed = 1, goal = 'Landingpage für die NOVA-Beta bauen', nAgents = 120, plan = null, glow = [];

  const fresh = () => ({ phase: 'idle', t: 0, jobs: [], agents: [], done: 0, fails: 0, decisions: 0, spend: 0, opusOnly: 0, thr: Array(40).fill(0), thrAcc: 0, laneDone: {}, laneTot: {}, merged: false });

  function init(root) {
    el = root;
    el.innerHTML = `
      <div class="vhead">
        <div><h1>Engineering · <em>für Schwärme</em></h1>
        <p>Ein Ziel geht an den Entscheidungs-Layer. Drei Leads verteilen die Arbeit auf viele kleine Agenten. Am Ende wird alles zu einem Ergebnis zusammengeführt.</p></div>
      </div>
      <div class="grid">
        <div class="panel s4"><div class="ph">Auftrag</div><div class="pb">
          <label class="fld" for="swGoal">Ziel</label><input type="text" id="swGoal" value="${esc(goal)}">
          <label class="fld" for="swN" style="margin-top:10px">Agenten <b id="swNV">120</b><input type="range" id="swN" min="24" max="300" step="6" value="120"></label>
          <div class="ctrls" style="margin-top:10px"><button class="btn pri" id="swGo">Schwarm starten</button><button class="btn ai ai-only" id="swAi">Plan mit Claude</button>
          <div class="seg" id="swSpeed" role="group" aria-label="Tempo"><button aria-pressed="true" data-s="1">1×</button><button aria-pressed="false" data-s="3">3×</button></div></div>
          <p class="note" id="swNote">Ohne Claude nutzt der Schwarm einen Standard-Plan. Mit Claude wird dein Ziel echt in Aufgaben zerlegt.</p>
          <div id="swPlan" style="margin-top:8px"></div>
        </div></div>
        <div class="panel s8 hero" id="swHero" style="min-height:clamp(420px, 62vh, 640px)"><canvas class="bg" id="swCv"></canvas>
          <div class="ov"><div class="ph"><span><b id="swA">0</b> Agenten · <b id="swDec">0</b> Entscheidungen · <b id="swJobs">0</b> Jobs · <b id="swErr">0</b> Fehler</span><span class="r" id="swPhase">bereit</span></div></div>
        </div>
        <div class="panel s4"><div class="ph">Entscheidungs-Stream</div><div class="log" id="swLog"></div></div>
        <div class="panel s4 lanes"><div class="ph">Aufgaben-Split <span class="r">fertig / gesamt</span></div><div class="pb" id="swLanes"></div></div>
        <div class="panel s4"><div class="ph">Dispatch-Kosten <span class="r">Opus plant · Sonnet führt · Haiku arbeitet</span></div><div class="pb">
          <div class="big acc" id="swCost">0 $</div><div class="sub" id="swCostSub">für 0 Jobs</div>
          <div class="row" style="margin-top:8px"><span>alles auf Opus 5.5</span><b id="swOpus">0 $</b></div>
          <div class="row"><span>Ersparnis</span><b id="swSave">–</b></div></div></div>
        <div class="panel s4"><div class="ph">Durchsatz <span class="r">Jobs / s</span></div><div class="pb"><canvas class="chart short" id="swThr"></canvas></div></div>
        <div class="panel s4"><div class="ph">Agenten-Heatmap <span class="r">ein Feld = ein Agent</span></div><div class="pb"><canvas class="chart" id="swHeat"></canvas></div></div>
        <div class="panel s4"><div class="ph">Ergebnis <span class="r" id="swDelivState">offen</span></div><div class="pb deliv" id="swDeliv"><p class="note">Nach dem Merge steht hier das Ergebnis.</p></div></div>
      </div>`;
    log = new AC.Log($('#swLog', el), 30);
    cv = AC.canvas($('#swCv', el), layout);
    cvs.thr = AC.canvas($('#swThr', el)); cvs.heat = AC.canvas($('#swHeat', el));
    $('#swN', el).oninput = (e) => { nAgents = +e.target.value; $('#swNV', el).textContent = nAgents; };
    $('#swGoal', el).onchange = (e) => { goal = e.target.value.trim() || goal; plan = null; renderPlan(); };
    $('#swGo', el).onclick = () => start();
    $('#swAi', el).onclick = askClaude;
    $$('#swSpeed button', el).forEach((b) => (b.onclick = () => { speed = +b.dataset.s; $$('#swSpeed button', el).forEach((x) => x.setAttribute('aria-pressed', x === b)); }));
    S = fresh(); renderPlan(); start();
  }
  const curPlan = () => plan || template(goal);
  function renderPlan() {
    const p = curPlan();
    $('#swPlan', el).innerHTML = `<table class="tbl">${LANES.map((l) => `<tr><td>${l.n}</td><td style="white-space:normal">${esc((p[l.id] || []).join(' · '))}</td></tr>`).join('')}</table>`;
  }
  async function askClaude() {
    const b = $('#swAi', el); b.disabled = true; $('#swNote', el).textContent = 'Claude zerlegt das Ziel…';
    goal = $('#swGoal', el).value.trim() || goal;
    try {
      const r = await AC.ai.json(`Zerlege dieses Ziel in kleine Aufgaben für einen Agenten-Schwarm. Ziel: "${goal.slice(0, 300)}"
Es gibt genau diese Bahnen: research, browse, code, analyze, test, deploy. Pro Bahn 1 bis 4 kurze deutsche Aufgaben (max. 6 Wörter je Aufgabe). Lass eine Bahn leer ([]), wenn sie nicht passt.
Antworte nur mit JSON: {"research": [], "browse": [], "code": [], "analyze": [], "test": [], "deploy": []}`, { modelTier: 'default' });
      const p = {}; let n = 0;
      for (const l of LANES) { p[l.id] = Array.isArray(r && r[l.id]) ? r[l.id].slice(0, 4).map((x) => String(x).slice(0, 60)) : []; n += p[l.id].length; }
      if (!n) throw { code: 'invalid_json' };
      plan = p; renderPlan(); $('#swNote', el).textContent = `Plan von Claude: ${n} Aufgaben. Starte den Schwarm.`;
      start();
    } catch (e) { $('#swNote', el).textContent = AC.ai.msg(e); }
    finally { b.disabled = false; }
  }

  // ---------- simulation ----------
  function start() {
    goal = $('#swGoal', el).value.trim() || goal;
    S = fresh(); S.phase = 'plan'; glow = [];
    const p = curPlan();
    const tasks = LANES.flatMap((l) => (p[l.id] || []).map((t) => ({ lane: l.id, t })));
    const perTask = Math.max(2, Math.round((nAgents * 2.2) / Math.max(1, tasks.length)));
    for (const tk of tasks) for (let k = 0; k < perTask; k++) S.jobs.push({ lane: tk.lane, task: tk.t, state: 'queued', agent: null, tries: 0 });
    for (const l of LANES) { S.laneTot[l.id] = S.jobs.filter((j) => j.lane === l.id).length; S.laneDone[l.id] = 0; }
    // agents per lane proportional to jobs
    const active = LANES.filter((l) => S.laneTot[l.id]);
    let left = nAgents;
    active.forEach((l, i) => {
      const k = i === active.length - 1 ? left : Math.max(2, Math.round((nAgents * S.laneTot[l.id]) / S.jobs.length));
      left -= k;
      for (let j = 0; j < k; j++) S.agents.push({ lane: l.id, state: 'idle', job: null, t: 0, d: 0, flash: 0 });
    });
    S.spend += PLAN; S.opusOnly += PLAN;
    log.clear(); log.add('decision', `Ziel: ${goal}`, 'Opus 5.5', 'hi');
    log.add('decision', `${tasks.length} Aufgaben → ${S.jobs.length} Jobs`, `${S.agents.length} Agenten`);
    layout();
    $('#swDeliv', el).innerHTML = '<p class="note">Läuft. Nach dem Merge steht hier das Ergebnis.</p>'; $('#swDelivState', el).textContent = 'läuft';
  }
  function tick(dt) {
    S.t += dt;
    if (S.phase === 'plan' && S.t > 1.2) { S.phase = 'run'; LEADS.forEach((n) => { log.add(n.toLowerCase(), 'übernimmt seine Bahnen', 'Sonnet 5.5'); S.spend += LEADC; S.opusOnly += cost('opus', 4000, 600); }); }
    if (S.phase !== 'run') return;
    for (const a of S.agents) {
      a.flash = Math.max(0, a.flash - dt * 2);
      if (a.state === 'idle') {
        const j = S.jobs.find((x) => x.state === 'queued' && x.lane === a.lane);
        if (j) { j.state = 'run'; j.agent = a; a.job = j; a.state = 'work'; a.t = 0; a.d = rand(1.2, 4.5); S.decisions++; }
      } else if (a.state === 'work') {
        a.t += dt;
        if (a.t >= a.d) {
          const j = a.job; S.spend += JOB; S.opusOnly += JOB_OPUS;
          AC.decision({ latency: randi(220, 900), conf: rand(0.8, 0.98), cost: JOB });
          if (Math.random() < 0.06 && j.tries < 2) { j.tries++; j.state = 'queued'; S.fails++; a.flash = 1; a.state = 'idle'; a.job = null; if (Math.random() < 0.3) log.add(LEADS[LANES.find((l) => l.id === j.lane).lead].toLowerCase(), `Retry: ${j.task}`, 'Fehler', 'bad'); }
          else {
            j.state = 'done'; S.done++; S.laneDone[j.lane]++; S.thrAcc++; a.state = 'idle'; a.job = null;
            glow.push({ x: a.x, y: a.y, t: 0 });
            if (S.laneDone[j.lane] === S.laneTot[j.lane]) log.add('lead', `${LANES.find((l) => l.id === j.lane).n} fertig`, `${S.laneTot[j.lane]} Jobs`, 'ok');
          }
        }
      }
    }
    if (S.done === S.jobs.length && !S.merged) { S.merged = true; S.phase = 'merge'; const run = S; setTimeout(() => { if (S === run) finish(); }, 900 / speed); log.add('merge', 'Alle Jobs fertig → Merge', '1 Ergebnis', 'hi'); }
  }
  function finish() {
    S.phase = 'done';
    const p = curPlan();
    $('#swDelivState', el).textContent = 'fertig';
    $('#swDeliv', el).innerHTML = `<b>${esc(goal)}</b><ul>${LANES.filter((l) => (p[l.id] || []).length).map((l) => `<li><span class="pill ok">${l.n}</span> ${esc(p[l.id].join(', '))}</li>`).join('')}</ul><p class="note">${S.jobs.length} Jobs, ${S.fails} Retries, ${usd(S.spend, 3)}. Die Arbeit selbst ist simuliert, der Plan ${plan ? 'kommt von Claude' : 'ist eine Vorlage'}.</p>`;
  }

  // ---------- layout + drawing ----------
  let G = {};
  function layout() {
    const w = cv.w, h = cv.h; if (w < 10) return;
    G = { top: [w / 2, 58], leads: LEADS.map((_, i) => [w * (0.22 + 0.28 * i), 120]), merge: [w / 2, h - 30], cx: w / 2, cy: 140 };
    const R0 = Math.min(w, h * 1.4) * 0.14, R1 = Math.max(R0 + 40, Math.min(w * 0.46, h - 230));
    const lanesActive = LANES.filter((l) => S.agents.some((a) => a.lane === l.id));
    const a0 = Math.PI * 0.06, a1 = Math.PI * 0.94, span = (a1 - a0) / Math.max(1, lanesActive.length);
    G.labels = [];
    lanesActive.forEach((l, li) => {
      const ag = S.agents.filter((a) => a.lane === l.id), cols = Math.ceil(Math.sqrt(ag.length * 1.6));
      ag.forEach((a, k) => {
        const ring = Math.floor(k / cols), pos = k % cols, rings = Math.ceil(ag.length / cols);
        const ang = a0 + span * li + span * ((pos + 0.5) / cols) * 0.9 + span * 0.05;
        const rr = R0 + (R1 - R0) * ((ring + 0.5) / Math.max(1, rings));
        a.x = G.cx + Math.cos(Math.PI - ang) * rr; a.y = G.cy + Math.sin(ang) * rr;
      });
      const mid = a0 + span * (li + 0.5); G.labels.push({ n: l.n, x: G.cx + Math.cos(Math.PI - mid) * (R1 + 18), y: G.cy + Math.sin(mid) * (R1 + 18) });
    });
  }
  function draw(dt) {
    const { ctx, w, h } = cv, C = AC.col; ctx.clearRect(0, 0, w, h);
    if (!G.top) return;
    const F = getComputedStyle(document.body).getPropertyValue('--f-mono');
    const work = S.phase === 'run' || S.phase === 'plan';
    // top → leads
    ctx.lineWidth = 2;
    for (const L of G.leads) { ctx.strokeStyle = AC.alpha(C.accent, work ? 0.7 : 0.25); ctx.beginPath(); ctx.moveTo(...G.top); ctx.bezierCurveTo(G.top[0], 100, L[0], 70, L[0], L[1]); ctx.stroke(); }
    ctx.lineWidth = 0.7;
    // leads → working agents
    for (const a of S.agents) {
      if (a.x == null) continue;
      const L = G.leads[LANES.find((l) => l.id === a.lane).lead];
      if (a.state === 'work') { ctx.strokeStyle = AC.alpha(C.accent, 0.28); ctx.beginPath(); ctx.moveTo(...L); ctx.lineTo(a.x, a.y); ctx.stroke(); }
    }
    // agents
    for (const a of S.agents) {
      if (a.x == null) continue;
      const done = a.state === 'idle' && S.phase !== 'plan';
      ctx.fillStyle = a.flash > 0 ? AC.alpha(C['accent-2'], 0.5 + a.flash / 2) : a.state === 'work' ? C.accent : done ? AC.alpha(C.fg, 0.55) : AC.alpha(C.fg, 0.2);
      ctx.beginPath(); ctx.arc(a.x, a.y, a.state === 'work' ? 3.6 : 2.8, 0, 7); ctx.fill();
      if (a.state === 'work') { ctx.fillStyle = C.bg; ctx.fillRect(a.x - 1.6, a.y - 1, 1.1, 1.1); ctx.fillRect(a.x + 0.6, a.y - 1, 1.1, 1.1); }
    }
    // done → merge particles
    ctx.fillStyle = C['accent-2'];
    glow = glow.filter((g) => { g.t += dt * 1.1 * speed; if (g.t >= 1) return false; const x = AC.lerp(g.x, G.merge[0], g.t), y = AC.lerp(g.y, G.merge[1], g.t * g.t); ctx.globalAlpha = 1 - g.t; ctx.fillRect(x - 1, y - 1, 2, 2); ctx.globalAlpha = 1; return true; });
    // nodes + labels
    const box = (x, y, t, sub, hot) => {
      ctx.font = '600 11px ' + F; const tw = Math.max(ctx.measureText(t).width, sub ? ctx.measureText(sub).width : 0) + 16;
      ctx.fillStyle = C.panel; ctx.strokeStyle = hot ? C.accent : C['line-2']; ctx.lineWidth = 1;
      ctx.fillRect(x - tw / 2, y - 14, tw, sub ? 30 : 22); ctx.strokeRect(x - tw / 2, y - 14, tw, sub ? 30 : 22);
      ctx.fillStyle = hot ? C.accent : C.fg; ctx.textAlign = 'center'; ctx.fillText(t, x, y + 1);
      if (sub) { ctx.font = '10px ' + F; ctx.fillStyle = C.muted; ctx.fillText(sub, x, y + 12); }
    };
    box(G.top[0], G.top[1], 'ENTSCHEIDUNGS-LAYER', 'Opus 5.5 plant', S.phase === 'plan');
    G.leads.forEach((L, i) => box(L[0], L[1], LEADS[i].toUpperCase(), 'Sonnet 5.5', work));
    ctx.font = '10px ' + F; ctx.fillStyle = C.muted; ctx.textAlign = 'center';
    for (const l of G.labels || []) ctx.fillText(l.n.toUpperCase(), clamp(l.x, 30, w - 30), clamp(l.y, 160, h - 56));
    box(G.merge[0], G.merge[1], S.phase === 'done' ? 'MERGE → 1 ERGEBNIS ✓' : 'MERGE → 1 ERGEBNIS', null, S.phase === 'merge' || S.phase === 'done');
    // progress bar
    const pw = Math.min(260, w * 0.5), px = w / 2 - pw / 2, py = h - 12, pr = S.jobs.length ? S.done / S.jobs.length : 0;
    ctx.strokeStyle = C['line-2']; ctx.strokeRect(px, py, pw, 6); ctx.fillStyle = C.accent; ctx.fillRect(px + 1, py + 1, (pw - 2) * pr, 4);
  }
  function renderPanels() {
    const C = AC.col;
    $('#swA', el).textContent = S.agents.length; $('#swDec', el).textContent = fmt(S.decisions); $('#swJobs', el).textContent = `${S.done}/${S.jobs.length}`; $('#swErr', el).textContent = S.fails;
    $('#swPhase', el).textContent = { idle: 'bereit', plan: 'Opus plant…', run: 'Agenten arbeiten', merge: 'Merge…', done: 'fertig' }[S.phase];
    $('#swLanes', el).innerHTML = LANES.map((l) => `<div class="row"><span>${l.n}</span><span class="meter"><i style="width:${S.laneTot[l.id] ? (S.laneDone[l.id] / S.laneTot[l.id]) * 100 : 0}%"></i></span><b class="t">${S.laneDone[l.id] || 0}/${S.laneTot[l.id] || 0}</b></div>`).join('');
    $('#swCost', el).textContent = usd(S.spend, 3); $('#swCostSub', el).textContent = `für ${S.done} Jobs + Planung`;
    $('#swOpus', el).textContent = usd(S.opusOnly, 3); $('#swSave', el).textContent = S.spend ? fmt(S.opusOnly / S.spend, 1) + '× günstiger' : '–';
    AC.bars(cvs.thr, S.thr, { color: C.accent });
    const n = S.agents.length || 1, cols = Math.ceil(Math.sqrt(n * 3)), rows = Math.ceil(n / cols);
    const grid = Array.from({ length: rows }, (_, r) => Array.from({ length: cols }, (_, c) => S.agents[r * cols + c]));
    AC.heat(cvs.heat, grid, (a) => (!a ? 'rgba(0,0,0,0)' : a.flash > 0 ? C['accent-2'] : a.state === 'work' ? C.accent : AC.alpha(C.fg, S.phase === 'plan' ? 0.08 : 0.25)));
  }

  let pAcc = 0, tAcc = 0;
  AC.register('swarm', {
    init,
    enter() { cv.fit(); layout(); },
    frame(t, dt) {
      for (let i = 0; i < speed; i++) tick(dt);
      tAcc += dt; if (tAcc >= 1) { tAcc = 0; AC.push(S.thr, S.thrAcc, 40); S.thrAcc = 0; }
      draw(dt);
      pAcc += dt; if (pAcc > 0.3) { pAcc = 0; renderPanels(); }
    },
  });
})();
