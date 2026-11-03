/* 06 Kosten-Labor – what a Claude Code session costs when the main model decides and cheap subagents do the reading. */
(() => {
  const { $, $$, esc, rand, randi, pick, clamp, fmt, usd } = AC;

  // Anthropic API list prices in $ per 1M tokens (stand Okt 2026). Editable in the UI.
  const MODELS = {
    'claude-fable-5-1': { n: 'Fable 5.1', i: 10, o: 50 },
    'claude-opus-5-5': { n: 'Opus 5.5', i: 4, o: 20 },
    'claude-sonnet-5-5': { n: 'Sonnet 5.5', i: 2, o: 10 },
    'claude-haiku-5-5': { n: 'Haiku 5.5', i: 0.1, o: 0.5 },
  };
  const IDS = Object.keys(MODELS);
  const STEPS = [
    ['explore', 'grep src/**/*.ts'], ['plan', 'read 14 files'], ['general', 'test api/webhooks'], ['explore', 'grep billing'],
    ['read', 'docs/costs.md'], ['test', '47 passed, 2 failed'], ['main', 'review diff +212 −48'], ['fix', '#3 retry 1/2'],
    ['lint', '0 errors'], ['diff', 'api/sync.ts'], ['main', 'decide: ship it?'], ['read', 'package.json'], ['explore', 'find **/*.test.ts'],
  ];
  const LANES = ['explore', 'plan', 'general', 'read', 'test', 'fix', 'lint', 'diff', 'main'];
  const FACTS = [
    ['effort', 'Stufen: low · medium · high · xhigh · max'],
    ['opus 5.5', 'Standard-Effort ist medium'],
    ['opus 5.5', 'Thinking lässt sich nicht abschalten, Effort ist der Regler'],
    ['billing', 'Thinking-Tokens werden als Output abgerechnet'],
    ['env', 'CLAUDE_CODE_SUBAGENT_MODEL = Standardmodell für Subagenten'],
    ['env', 'CLAUDE_CODE_SUBAGENT_MODEL_FORCE=1 erzwingt es (ab v2.1.257)'],
    ['env', 'CLAUDE_CODE_EFFORT_LEVEL schlägt --effort, /effort und effortLevel'],
    ['cache', 'Jedes Modell hat eigenen Prompt-Cache: ein Wechsel liest den Verlauf neu'],
    ['memory', 'CLAUDE.md unter 200 Zeilen halten'],
    ['settings', 'settings.json ist strenges JSON: keine Kommentare, kein Komma am Ende'],
  ];

  let el, cv, log, F, sim, running = true, cloud = [];
  const price = (id, tin, tout) => (tin * MODELS[id].i + tout * MODELS[id].o) / 1e6;

  function init(root) {
    el = root;
    const opts = (sel) => IDS.map((id) => `<option value="${id}" ${id === sel ? 'selected' : ''}>${MODELS[id].n}</option>`).join('');
    el.innerHTML = `
      <div class="vhead">
        <div><h1>Claude Code · <em>Kosten-Labor</em></h1>
        <p>Das Hauptmodell plant und entscheidet, günstige Subagenten lesen und suchen. Rechne aus, was das spart, sieh es live und erzeuge die passende settings.json.</p></div>
        <div class="ctrls"><button class="btn pri" id="clRun">Pause</button><button class="btn" id="clReset">Neue Session</button></div>
      </div>
      <div class="grid">
        <div class="panel s4"><div class="ph">Rechner <span class="r">pro Session</span></div><div class="pb"><div class="cl-form">
          <label class="fld">Hauptmodell<select id="clMain">${opts('claude-opus-5-5')}</select></label>
          <label class="fld">Subagenten<select id="clSub">${opts('claude-haiku-5-5')}</select></label>
          <label class="fld wide">Anzahl Subagenten <b id="clNV">16</b><input type="range" id="clN" min="1" max="32" value="16"></label>
          <label class="fld">Subagent Input (k)<input type="number" id="clSi" value="40" min="0"></label>
          <label class="fld">Subagent Output (k)<input type="number" id="clSo" value="4" min="0"></label>
          <label class="fld">Haupt Input (k)<input type="number" id="clMi" value="180" min="0"></label>
          <label class="fld">Haupt Output (k)<input type="number" id="clMo" value="24" min="0"></label>
          <label class="fld wide">Sessions pro Tag <b id="clDV">8</b><input type="range" id="clD" min="1" max="60" value="8"></label>
        </div>
        <details style="margin-top:10px"><summary class="sub" style="cursor:pointer">Preise anpassen ($ / 1M Tokens, Listenpreis Okt 2026)</summary>
          <table class="tbl" style="margin-top:6px"><tr><th>Modell</th><th>Input</th><th>Output</th></tr>${IDS.map((id) => `<tr><td>${MODELS[id].n}</td><td><input type="number" step="0.01" data-p="${id}" data-k="i" value="${MODELS[id].i}" aria-label="${MODELS[id].n} Input-Preis"></td><td><input type="number" step="0.01" data-p="${id}" data-k="o" value="${MODELS[id].o}" aria-label="${MODELS[id].n} Output-Preis"></td></tr>`).join('')}</table>
        </details></div></div>
        <div class="panel s8"><div class="ph">Ergebnis <span class="r" id="clResSub"></span></div><div class="pb"><div class="cl-res">
          <div><div class="sub">Alles auf <span id="clMainN">Opus 5.5</span></div><div class="big" id="clAll">–</div><div class="sub" id="clAllM">–</div></div>
          <div><div class="sub">Gemischt</div><div class="big acc" id="clMix">–</div><div class="sub" id="clMixM">–</div></div>
          <div><div class="sub">Faktor</div><div class="big" id="clX">–</div><div class="sub" id="clSave">–</div></div>
        </div>
        <div class="lm" id="clMatrix" style="margin-top:14px"></div></div></div>
        <div class="panel s8 hero" style="min-height:clamp(300px, 46vh, 480px)"><canvas class="bg" id="clCv"></canvas>
          <div class="ov"><div class="ph"><span>~/.claude · <b id="clHdr">Opus entscheidet · Haiku sucht</b></span><span class="r" id="clLive">0 $ · 0 Schritte</span></div></div></div>
        <div class="panel s4"><div class="ph">Subagenten <span class="r" id="clLiveN">0 live</span></div><div class="pb sub-list" id="clAgents"></div></div>
        <div class="panel s6"><div class="ph">Log</div><div class="log tall" id="clLog"></div></div>
        <div class="panel s6"><div class="ph"><span>.claude/settings.json</span><span class="r">echte Schlüssel aus der Doku</span></div><div class="pb">
          <div class="ctrls" style="margin-bottom:8px">
            <label class="btn"><input type="checkbox" id="clSetMain" checked> Hauptmodell</label>
            <label class="btn"><input type="checkbox" id="clSetForce"> Subagent-Modell erzwingen</label>
            <label class="fld" style="width:150px">Effort<select id="clEffort"><option value="">nicht setzen</option><option>low</option><option selected>medium</option><option>high</option><option>xhigh</option><option>max</option></select></label>
          </div>
          <pre class="out" id="clJson"></pre>
          <div class="ctrls" style="margin-top:8px"><button class="btn pri" id="clCopy">Kopieren</button><button class="btn" id="clSaveBtn">settings.json speichern</button></div>
          <p class="note">Ablegen unter <code>.claude/settings.json</code> im Projekt (für alle) oder <code>~/.claude/settings.json</code> (nur für dich). Danach in Claude Code <code>/status</code> prüfen.</p>
        </div></div>
        <div class="panel s12"><div class="ph">Aus der Doku <span class="r">geprüft, Stand Nov 2026</span></div><div class="pb" id="clFacts">${FACTS.map(([k, v]) => `<div class="row"><span>[${k}]</span><span style="text-align:left;flex:1">${esc(v)}</span></div>`).join('')}</div></div>
      </div>`;
    log = new AC.Log($('#clLog', el), 40);
    cv = AC.canvas($('#clCv', el));
    $$('input, select', $('.cl-form', el).parentElement).forEach((i) => (i.oninput = calc));
    $$('[data-p]', el).forEach((i) => (i.oninput = () => { const v = Number(i.value); if (v >= 0) MODELS[i.dataset.p][i.dataset.k] = v; calc(); }));
    for (const id of ['clSetMain', 'clSetForce', 'clEffort']) $('#' + id, el).oninput = renderJson;
    $('#clCopy', el).onclick = () => AC.copy(json());
    $('#clSaveBtn', el).onclick = () => AC.save('settings.json', json());
    $('#clRun', el).onclick = () => { running = !running; $('#clRun', el).textContent = running ? 'Pause' : 'Weiter'; };
    $('#clReset', el).onclick = newSim;
    calc(); newSim();
  }

  function form() {
    const v = (id) => Math.max(0, Number($('#' + id, el).value) || 0);
    return { main: $('#clMain', el).value, sub: $('#clSub', el).value, n: v('clN'), si: v('clSi') * 1000, so: v('clSo') * 1000, mi: v('clMi') * 1000, mo: v('clMo') * 1000, d: v('clD') };
  }
  function calc() {
    F = form();
    $('#clNV', el).textContent = F.n; $('#clDV', el).textContent = F.d;
    const all = price(F.main, F.mi + F.n * F.si, F.mo + F.n * F.so);
    const mix = price(F.main, F.mi, F.mo) + F.n * price(F.sub, F.si, F.so);
    const m = 30 * F.d;
    $('#clMainN', el).textContent = MODELS[F.main].n;
    $('#clAll', el).textContent = usd(all); $('#clAllM', el).textContent = usd(all * m, 0) + ' / Monat';
    $('#clMix', el).textContent = usd(mix); $('#clMixM', el).textContent = usd(mix * m, 0) + ' / Monat';
    $('#clX', el).textContent = mix > 0 ? fmt(all / mix, 1) + '×' : '–';
    $('#clSave', el).textContent = usd((all - mix) * m, 0) + ' / Monat gespart';
    $('#clResSub', el).textContent = `${F.n} Subagenten · ${F.d} Sessions/Tag · 30 Tage`;
    $('#clHdr', el).textContent = `${MODELS[F.main].n.split(' ')[0]} entscheidet · ${MODELS[F.sub].n.split(' ')[0]} sucht`;
    // lane × model matrix
    const C = AC.col, cols = ['claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-5-5'];
    $('#clMatrix', el).innerHTML = `<span class="h">Bahn</span>${cols.map((c) => `<span class="h">${MODELS[c].n.split(' ')[0]}</span>`).join('')}<span class="h" style="text-align:right">pro Schritt</span>` +
      LANES.map((l) => { const id = l === 'main' ? F.main : F.sub; const c = l === 'main' ? price(id, 40000, 3000) : price(id, F.si / 4 || 10000, F.so / 4 || 1000); return `<span>${l}</span>${cols.map((m) => `<i style="background:${m === id ? (m === 'claude-haiku-5-5' ? C.accent : m === 'claude-sonnet-5-5' ? C.warn : C['accent-2']) : AC.alpha(C.fg, 0.06)}"></i>`).join('')}<span style="text-align:right">${usd(c, 4)}</span>`; }).join('');
    renderJson();
  }
  function json() {
    const env = { CLAUDE_CODE_SUBAGENT_MODEL: F.sub };
    if ($('#clSetForce', el).checked) env.CLAUDE_CODE_SUBAGENT_MODEL_FORCE = '1';
    const eff = $('#clEffort', el).value; if (eff) env.CLAUDE_CODE_EFFORT_LEVEL = eff;
    const o = { $schema: 'https://json.schemastore.org/claude-code-settings.json' };
    if ($('#clSetMain', el).checked) o.model = F.main;
    o.env = env;
    return JSON.stringify(o, null, 2) + '\n';
  }
  const renderJson = () => { if (F) $('#clJson', el).textContent = json(); };

  // ---------- live session ----------
  function newSim() {
    F = form();
    sim = { t: 0, steps: 0, cost: 0, all: 0, pts: [], ptsAll: [], agents: Array.from({ length: Math.min(16, F.n) }, (_, i) => ({ id: 'agt-' + String(i + 1).padStart(3, '0'), lane: pick(LANES.slice(0, -1)), p: rand(0.2), v: rand(0.08, 0.25), cost: 0 })) };
    cloud = []; log.clear();
    log.add('main', `Session gestartet · ${MODELS[F.main].n}`, 'plant', 'hi');
  }
  function stepSim(dt) {
    sim.t += dt;
    for (const a of sim.agents) {
      a.p += dt * a.v;
      if (a.p >= 1) {
        a.p = 0; a.lane = pick(LANES.slice(0, -1));
        const [lane, what] = pick(STEPS.filter((s) => s[0] !== 'main'));
        const ti = randi(6000, 30000), to = randi(400, 2200), c = price(F.sub, ti, to), ca = price(F.main, ti, to);
        a.cost += c; sim.cost += c; sim.all += ca; sim.steps++;
        AC.decision({ latency: randi(300, 1400), conf: rand(0.82, 0.97), cost: c });
        sim.pts.push([sim.t, sim.cost, 0]); sim.ptsAll.push([sim.t, sim.all]);
        if (Math.random() < 0.45) log.add(a.id, `${lane} ${what}`, MODELS[F.sub].n.split(' ')[0].toLowerCase());
        for (let k = 0; k < 18; k++) cloud.push({ t: sim.t + rand(-0.6, 0.6), c: sim.cost * (1 + AC.gauss() * 0.05), m: 0, a: 1 });
      }
    }
    if (Math.random() < dt * 0.25) {
      const [, what] = pick(STEPS.filter((s) => s[0] === 'main'));
      const ti = randi(20000, 60000), to = randi(1000, 4000), c = price(F.main, ti, to);
      sim.cost += c; sim.all += c; sim.steps++;
      AC.decision({ latency: randi(900, 2400), conf: rand(0.9, 0.99), cost: c });
      sim.pts.push([sim.t, sim.cost, 1]); sim.ptsAll.push([sim.t, sim.all]);
      log.add('main', what, MODELS[F.main].n.split(' ')[0].toLowerCase(), 'hi');
      for (let k = 0; k < 30; k++) cloud.push({ t: sim.t + rand(-0.5, 0.5), c: sim.cost * (1 + AC.gauss() * 0.06), m: 1, a: 1 });
    }
    if (cloud.length > 6000) cloud.splice(0, cloud.length - 6000);
    if (sim.pts.length > 800) { sim.pts.shift(); sim.ptsAll.shift(); }
  }
  function draw() {
    const { ctx, w, h } = cv, C = AC.col; ctx.clearRect(0, 0, w, h);
    if (!sim.ptsAll.length) return;
    const t0 = Math.max(0, sim.t - 120), maxC = Math.max(...sim.ptsAll.map((p) => p[1])) * 1.08 || 1;
    const X = (t) => 12 + ((t - t0) / Math.max(1, sim.t - t0)) * (w - 24), Y = (c) => h - 16 - (c / maxC) * (h - 50);
    ctx.strokeStyle = AC.alpha(C.fg, 0.06); for (let i = 1; i < 5; i++) { const y = 34 + ((h - 50) / 5) * i; ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); }
    // counterfactual: everything on the main model
    ctx.strokeStyle = AC.alpha(C['accent-2'], 0.75); ctx.lineWidth = 1.5; ctx.setLineDash([5, 4]); ctx.beginPath();
    sim.ptsAll.forEach(([t, c], i) => (i ? ctx.lineTo(X(t), Y(c)) : ctx.moveTo(X(t), Y(c)))); ctx.stroke(); ctx.setLineDash([]);
    for (const p of cloud) { if (p.t < t0) continue; ctx.fillStyle = p.m ? AC.alpha(C['accent-2'], 0.55) : AC.alpha(p.c > 0 && Math.random() < 0.3 ? C.fg : C.accent, 0.45); ctx.fillRect(X(p.t) + rand(-2, 2), Y(p.c) + rand(-2, 2), 1.4, 1.4); }
    ctx.strokeStyle = C.accent; ctx.lineWidth = 2; ctx.beginPath();
    sim.pts.forEach(([t, c], i) => (i ? ctx.lineTo(X(t), Y(c)) : ctx.moveTo(X(t), Y(c)))); ctx.stroke();
    const F2 = getComputedStyle(document.body).getPropertyValue('--f-mono');
    ctx.font = '11px ' + F2; ctx.textAlign = 'right';
    const last = sim.ptsAll[sim.ptsAll.length - 1], lp = sim.pts[sim.pts.length - 1];
    ctx.fillStyle = C['accent-2']; ctx.fillText(`alles ${MODELS[F.main].n}: ${usd(last[1], 3)}`, w - 14, Math.max(46, Y(last[1]) - 8));
    ctx.fillStyle = C.accent; ctx.fillText(`gemischt: ${usd(lp[1], 3)} · ${fmt(last[1] / Math.max(1e-9, lp[1]), 1)}× günstiger`, w - 14, Math.min(h - 22, Y(lp[1]) + 18));
  }
  function renderPanels() {
    $('#clLive', el).textContent = `${usd(sim.cost, 3)} · ${sim.steps} Schritte`;
    $('#clLiveN', el).textContent = `${sim.agents.length} live`;
    $('#clAgents', el).innerHTML = sim.agents.map((a) => `<div class="sa"><b>${a.id}</b><span>${a.lane}</span><span class="meter"><i style="width:${a.p * 100}%"></i></span><span class="t" style="text-align:right">${usd(a.cost, 4)}</span></div>`).join('');
  }

  let pAcc = 0;
  AC.register('costlab', {
    init,
    enter() { cv.fit(); },
    frame(t, dt) {
      if (running) stepSim(dt);
      draw();
      pAcc += dt; if (pAcc > 0.4) { pAcc = 0; renderPanels(); }
    },
  });
})();
