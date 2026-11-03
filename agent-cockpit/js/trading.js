/* 02 Trading-Desk – paper trading with an agent chain: watcher → analyst → risk → gate → executor → reporter (→ human). */
(() => {
  const { $, $$, esc, rand, randi, clamp, fmt, usd, gauss } = AC;

  const ASSETS = [
    { s: 'BTC', p: 84212, vol: 0.0011 }, { s: 'ETH', p: 2713, vol: 0.0014 }, { s: 'SOL', p: 119.35, vol: 0.0019 },
    { s: 'HYPE', p: 38.8, vol: 0.0026 }, { s: 'AVAX', p: 21.4, vol: 0.0021 },
  ];
  const FEATS = ['Momentum', 'Rückkehr', 'Volatilität', 'Orderflow', 'Breite'];
  const AGENTS = [
    { id: 'watcher', n: 'Watcher', st: 'RUN' }, { id: 'analyst', n: 'Analyst', st: 'AKTIV' }, { id: 'risk', n: 'Risiko', st: 'PRÜFT' },
    { id: 'executor', n: 'Executor', st: 'FILL' }, { id: 'reporter', n: 'Reporter', st: 'SYNC' }, { id: 'human', n: 'Mensch', st: 'OPT' },
  ];
  const CODE = `from desk import Desk, Book, gate

desk = Desk(agents=6, perms="explicit")
book = Book(["BTC", "ETH", "SOL", "HYPE", "AVAX"])

@desk.on("tick")
def on_tick(tick):
    asset = watcher.pick(book)          # größte Bewegung
    ctx = analyst.evidence(asset)       # 5 Merkmale
    side, conf = analyst.choice(ctx)    # LONG / SHORT
    size = risk.size(conf, equity)      # max 5 % Risiko
    if not risk.ok(size):
        return reporter.skip(asset)     # Limit erreicht
    if conf < GATE:
        return reporter.hold(asset)     # Gate hält
    if HUMAN_CONFIRMS:
        return human.queue(side, size)  # du entscheidest
    fill = executor.paper(side, size)   # nur Papiergeld
    reporter.evidence(fill)`;

  let el, S, cv, cvs = {}, log, code, running = true, speed = 1, gate = 0.85, humanMode = false, sel = 0, parts = [], energy = 0;

  const fresh = () => ({
    t: 0, cycle: 0, step: -1, cash: 10000, start: 10000, peak: 10000, eqHist: [10000],
    decisions: 0, passes: 0, holds: 0, lat: [], flow: [], handoffs: Object.fromEntries(AGENTS.map((a) => [a.id, 0])),
    pend: [], cur: null, evid: 4400 + randi(0, 99),
    assets: ASSETS.map((a) => ({ ...a, p0: a.p, hist: Array(60).fill(a.p), rets: [], drift: 0, flow: 0, ef: a.p, es: a.p, ev: [0, 0, 0, 0, 0], pos: 0, entry: 0 })),
    conf: Object.fromEntries(AGENTS.map((a) => [a.id, Array(24).fill(0.9)])),
  });

  function init(root) {
    el = root;
    el.innerHTML = `
      <div class="vhead">
        <div><h1>Trading-Desk · <em>Orbital-Signal</em></h1>
        <p>Sechs Agenten geben ein Signal weiter. Nur wenn die Konfidenz über dem Gate liegt und das Risiko passt, wird gehandelt. Alles mit Papiergeld auf simulierten Kursen.</p></div>
        <div class="ctrls">
          <label class="fld" style="width:150px">Gate <b id="trGateV">0,85</b><input type="range" id="trGate" min="0.6" max="0.99" step="0.01" value="0.85"></label>
          <label class="btn"><input type="checkbox" id="trHuman"> Mensch bestätigt</label>
          <div class="seg" id="trSpeed" role="group" aria-label="Tempo"><button aria-pressed="true" data-s="1">1×</button><button aria-pressed="false" data-s="4">4×</button></div>
          <button class="btn pri" id="trRun">Pause</button><button class="btn" id="trReset">Neu</button>
        </div>
      </div>
      <div class="grid">
        <div class="panel s12 hero" id="trHero" style="min-height:clamp(460px, 60vh, 620px)">
          <canvas class="bg" id="trCv"></canvas>
          <div class="ov agents" id="trAgents">${AGENTS.map((a, i) => `<div class="ag" data-i="${i}"><div class="an"><span>0${i + 1} ${a.n}</span><span>${a.st}</span></div><canvas></canvas><div class="av t">0,90</div></div>`).join('')}</div>
          <div class="tag" id="trTagChoice" style="left:4%;top:34%"><small>CHOICE / SIDE</small><b>–</b></div>
          <div class="tag" id="trTagRisk" style="right:4%;top:24%"><small>RISIKO</small><b>ok</b></div>
          <div class="tag" id="trTagGate" style="right:8%;bottom:14%"><small>GATE / HOLD</small><b>–</b></div>
          <div class="tag" id="trTagCore" style="left:50%;bottom:6%;transform:translateX(-50%)"><small>SIMULIERT · KEINE ANLAGEBERATUNG</small>Signal-Kern</div>
        </div>
        <div class="panel s3"><div class="ph">Markt <span class="r">Sim</span></div><div class="pb" id="trMkt"></div></div>
        <div class="panel s3"><div class="ph">Orderbuch <span class="r" id="trBookSym">BTC-PERP</span></div><div class="pb"><canvas class="chart" id="trBook"></canvas><div class="sub" id="trSpread">Spread –</div></div></div>
        <div class="panel s3"><div class="ph">Volatilität <span class="r">letzte 40 Ticks</span></div><div class="pb"><div class="big" id="trVol">0 %</div><canvas class="chart short" id="trVolC"></canvas></div></div>
        <div class="panel s3"><div class="ph">Evidenz-Matrix <span class="r">−1 … +1</span></div><div class="pb"><canvas class="chart" id="trEv"></canvas><div class="sub">${FEATS.join(' · ')}</div></div></div>
        <div class="panel s4"><div class="ph">Depot <span class="r">Start 10.000 $</span></div><div class="pb"><div class="big acc" id="trEq">10.000 $</div><div class="sub" id="trPnl">±0 %</div><canvas class="chart short" id="trEqC"></canvas><div id="trPos"></div></div></div>
        <div class="panel s5"><div class="ph"><span>risk_gate.py</span><span class="r">LIVE</span></div><div class="code" id="trCode"></div></div>
        <div class="panel s3"><div class="ph">Risiko <span class="r">Exposure / Limit</span></div><div class="pb"><canvas class="chart" id="trGauge"></canvas><div class="row"><span>Drawdown</span><b id="trDD">0 %</b></div><div class="row"><span>Exposure</span><b id="trExp">0 %</b></div></div></div>
        <div class="panel s7"><div class="ph">Live-Log <span class="r">alle Agenten</span></div><div class="log tall" id="trLog"></div></div>
        <div class="panel s5"><div class="ph">Freigaben <span class="r" id="trPendN">0 offen</span></div><div class="pb" id="trPend"><p class="note">Schalte „Mensch bestätigt“ ein. Dann landen Trades hier und warten auf dich.</p></div>
          <div class="ph" style="border-top:1px solid var(--line)">Übergaben <span class="r">pro Agent</span></div><div class="pb" id="trHand"></div></div>
        <div class="panel s2"><div class="ph">Entscheidungen</div><div class="pb"><div class="big" id="trDec">0</div></div></div>
        <div class="panel s2"><div class="ph">Gate-Quote</div><div class="pb"><div class="big" id="trPass">0 %</div></div></div>
        <div class="panel s2"><div class="ph">Median-Latenz</div><div class="pb"><div class="big" id="trLat">– ms</div></div></div>
        <div class="panel s2"><div class="ph">Kosten / 10k</div><div class="pb"><div class="big acc">0,42 $</div><div class="sub">Engine generiert nichts</div></div></div>
        <div class="panel s4"><div class="ph">Signalfluss <span class="r">Konfidenz je Zyklus</span></div><div class="pb"><canvas class="chart short" id="trFlow"></canvas></div></div>
      </div>`;
    log = new AC.Log($('#trLog', el), 40);
    code = AC.code($('#trCode', el), CODE);
    cv = AC.canvas($('#trCv', el), seedParts);
    for (const id of ['trBook', 'trVolC', 'trEv', 'trEqC', 'trGauge', 'trFlow']) cvs[id] = AC.canvas($('#' + id, el));
    cvs.ag = $$('#trAgents canvas', el).map((c) => AC.canvas(c));
    $('#trMkt', el).innerHTML = ASSETS.map((a, i) => `<div class="mkt" data-i="${i}" role="button" tabindex="0" aria-label="${a.s} im Orderbuch zeigen"><b>${a.s}</b><canvas></canvas><span class="t"></span><span class="t"></span></div>`).join('');
    cvs.mkt = $$('#trMkt canvas', el).map((c) => AC.canvas(c));
    $('#trGate', el).oninput = (e) => { gate = +e.target.value; $('#trGateV', el).textContent = fmt(gate, 2); };
    $('#trHuman', el).onchange = (e) => { humanMode = e.target.checked; renderPend(); };
    $('#trRun', el).onclick = () => { running = !running; $('#trRun', el).textContent = running ? 'Pause' : 'Weiter'; };
    $$('#trSpeed button', el).forEach((b) => (b.onclick = () => { speed = +b.dataset.s; $$('#trSpeed button', el).forEach((x) => x.setAttribute('aria-pressed', x === b)); }));
    $('#trReset', el).onclick = reset;
    $('#trMkt', el).onclick = (e) => { const r = e.target.closest('.mkt'); if (r) { sel = +r.dataset.i; renderSide(); } };
    $('#trMkt', el).onkeydown = (e) => { const r = e.target.closest('.mkt'); if (r && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); sel = +r.dataset.i; renderSide(); } };
    $('#trPend', el).onclick = (e) => {
      const b = e.target.closest('button[data-k]'); if (!b) return;
      const i = +b.dataset.k, o = S.pend[i]; if (!o) return;
      S.pend.splice(i, 1);
      if (b.dataset.a === 'ok') { execute(o.a, o.side, o.size, o.conf, 'du'); } else log.add('human', `${o.a.s} ${o.side} abgelehnt`, 'verworfen', 'bad');
      renderPend();
    };
    reset();
  }

  function reset() { S = fresh(); log.clear(); log.add('system', 'Desk gestartet · Papiergeld 10.000 $', 'Sim'); renderPend(); }

  // ---------- market ----------
  function marketTick() {
    for (const a of S.assets) {
      if (Math.random() < 0.01) a.drift = gauss() * a.vol * 0.35;
      const r = a.drift + gauss() * a.vol;
      a.p *= Math.exp(r);
      a.flow = clamp(a.flow * 0.92 + (a.drift / a.vol) * 0.1 + gauss() * 0.12, -1, 1);
      AC.push(a.hist, a.p, 120); AC.push(a.rets, r, 60);
      a.ef += (a.p - a.ef) * 0.18; a.es += (a.p - a.es) * 0.04;
      if (a.pos) { const ch = (a.p / a.entry - 1) * Math.sign(a.pos); if (ch > 0.025 || ch < -0.015) close(a, ch > 0 ? 'Take-Profit' : 'Stop-Loss'); }
    }
  }
  const equity = () => S.cash + S.assets.reduce((s, a) => s + a.pos * a.p, 0);
  const exposure = () => S.assets.reduce((s, a) => s + Math.abs(a.pos * a.p), 0) / Math.max(1, equity());

  function evidence(a) {
    const n = a.hist.length, sma = a.hist.slice(-30).reduce((x, y) => x + y, 0) / Math.min(30, n);
    const sd = Math.sqrt(a.rets.reduce((x, r) => x + r * r, 0) / Math.max(1, a.rets.length)) || a.vol;
    const mom = Math.tanh(((a.ef - a.es) / a.es) / (a.vol * 6));
    const rev = -Math.tanh(((a.p - sma) / sma) / (a.vol * 8));
    const volr = Math.tanh(sd / a.vol - 1);
    const breadth = Math.tanh(S.assets.reduce((x, b) => x + Math.sign(b.ef - b.es), 0) / 3);
    a.ev = [mom, rev, volr, a.flow, breadth];
    return a.ev;
  }

  // ---------- one decision cycle, stepped agent by agent ----------
  const STEP_LINES = [7, 8, 10, 13, 17, 18];
  function cycleStep() {
    const C = S.cur;
    S.step = (S.step + 1) % 6;
    const ag = AGENTS[S.step];
    S.handoffs[ag.id]++;
    if (S.step === 0) {
      const a = S.assets.reduce((best, x) => (Math.abs(x.ef / x.es - 1) / x.vol > Math.abs(best.ef / best.es - 1) / best.vol ? x : best), S.assets[0]);
      S.cur = { a, t0: performance.now(), lat: randi(180, 320) };
      log.add('watcher', `${a.s} ${fmt(a.p, a.p > 1000 ? 0 : 2)}`, 'Bewegung');
      code.mark(STEP_LINES[0]);
    } else if (S.step === 1) {
      const ev = evidence(C.a);
      const s = 1.0 * ev[0] + 0.5 * ev[3] + 0.3 * ev[1] + 0.2 * ev[4] * Math.sign(ev[0] || 1);
      C.side = s >= 0 ? 'LONG' : 'SHORT';
      C.conf = clamp(0.5 + 0.5 * Math.tanh(Math.abs(s)), 0.5, 0.995);
      AC.push(S.conf.analyst, C.conf, 24);
      log.add('analyst', `ctx #${S.evid} p=${fmt(C.conf, 2)}`, C.side);
      code.mark(9);
    } else if (S.step === 2) {
      const eq = equity(), dd = 1 - eq / S.peak;
      C.size = eq * 0.05 * C.conf * 4;
      C.riskOk = exposure() + C.size / eq < 0.8 && dd < 0.06;
      AC.push(S.conf.risk, C.riskOk ? 0.95 : 0.4, 24);
      log.add('risk', C.riskOk ? `dd ${AC.pct(dd, 1)} · Limits ok` : 'Limit erreicht → skip', usd(C.size, 0), C.riskOk ? '' : 'bad');
      code.mark(C.riskOk ? STEP_LINES[2] : 12);
    } else if (S.step === 3) {
      S.decisions++;
      C.pass = C.riskOk && C.conf >= gate;
      if (C.pass) S.passes++; else S.holds++;
      AC.push(S.flow, C.conf, 48);
      AC.push(S.lat, C.lat, 80);
      AC.decision({ latency: C.lat, conf: C.conf, cost: 0.000042 });
      AC.push(S.conf.watcher, clamp(C.conf + rand(-0.05, 0.05), 0, 1), 24);
      log.add('gate', `${fmt(C.conf, 2)} ${C.conf >= gate ? '≥' : '<'} ${fmt(gate, 2)}`, C.pass ? 'PASS' : 'HOLD', C.pass ? 'hi' : '');
      code.mark(!C.riskOk ? 12 : C.pass ? 13 : 14);
    } else if (S.step === 4) {
      if (C.pass) {
        const a = C.a, want = C.side === 'LONG' ? 1 : -1;
        if (a.pos && Math.sign(a.pos) === want) { log.add('executor', `${a.s} schon ${C.side} – keine Order`, 'skip'); }
        else if (humanMode) { S.pend.push({ a, side: C.side, size: C.size, conf: C.conf }); if (S.pend.length > 6) S.pend.shift(); log.add('human', `${a.s} ${C.side} wartet auf Freigabe`, 'Queue', 'hi'); S.handoffs.human++; renderPend(); }
        else execute(a, C.side, C.size, C.conf, 'executor');
        AC.push(S.conf.executor, C.conf, 24);
      } else log.add('executor', 'kein Auftrag', '–');
      code.mark(C.pass ? (humanMode ? 16 : 17) : 14);
    } else {
      log.add('reporter', `Evidenz #${S.evid++}`, C.pass ? 'gespeichert' : 'hold');
      AC.push(S.conf.reporter, clamp(0.9 + rand(-0.05, 0.08), 0, 1), 24);
      code.mark(STEP_LINES[5]);
      S.cycle++;
    }
  }
  function execute(a, side, size, conf, who) {
    if (a.pos) close(a, 'Umkehr');
    const qty = (size / a.p) * (side === 'LONG' ? 1 : -1), px = a.p * (1 + 0.0004 * Math.sign(qty));
    a.pos = qty; a.entry = px; S.cash -= qty * px;
    energy = 1;
    log.add(who, `${side === 'LONG' ? 'KAUF' : 'VERKAUF'} ${fmt(Math.abs(qty), Math.abs(qty) < 10 ? 3 : 1)} ${a.s}-PERP`, `conf ${fmt(conf, 2)}`, 'ok');
  }
  function close(a, why) {
    const pnl = (a.p - a.entry) * a.pos;
    S.cash += a.pos * a.p; a.pos = 0;
    log.add('executor', `${a.s} schließen · ${why}`, (pnl >= 0 ? '+' : '') + usd(pnl), pnl >= 0 ? 'ok' : 'bad');
  }

  // ---------- render ----------
  function renderPend() {
    const box = $('#trPend', el); if (!box) return;
    $('#trPendN', el).textContent = S.pend.length + ' offen';
    if (!humanMode && !S.pend.length) { box.innerHTML = '<p class="note">Schalte „Mensch bestätigt“ ein. Dann landen Trades hier und warten auf dich.</p>'; return; }
    if (!S.pend.length) { box.innerHTML = '<p class="note">Keine offenen Trades. Neue erscheinen, sobald ein Signal das Gate passiert.</p>'; return; }
    box.innerHTML = S.pend.map((o, i) => `<div class="pend"><span><b>${o.a.s}</b> ${o.side} · ${usd(o.size, 0)} · conf ${fmt(o.conf, 2)}</span><span><button class="btn sm pri" data-k="${i}" data-a="ok">Ausführen</button> <button class="btn sm" data-k="${i}" data-a="no">Ablehnen</button></span></div>`).join('');
  }
  function renderSide() {
    const C = AC.col;
    $$('#trMkt .mkt', el).forEach((row, i) => {
      const a = S.assets[i], ch = a.p / a.p0 - 1, sp = row.children;
      row.classList.toggle('sel', i === sel);
      sp[2].textContent = fmt(a.p, a.p > 1000 ? 0 : 2);
      sp[3].textContent = (ch >= 0 ? '+' : '') + AC.pct(ch, 2); sp[3].className = 't ' + (ch >= 0 ? 'up' : 'dn');
      AC.spark(cvs.mkt[i], a.hist.slice(-50), { color: a.p >= a.p0 ? C.ok : C.bad, fill: false, dot: false, lw: 1 });
    });
    const a = S.assets[sel];
    $('#trBookSym', el).textContent = a.s + '-PERP';
    // order book depth
    const o = cvs.trBook, { ctx, w, h } = o; ctx.clearRect(0, 0, w, h);
    const lv = 24, step = a.p * 0.0006; let cb = 0, ca = 0; const bids = [], asks = [];
    for (let i = 0; i < lv; i++) { cb += rand(0.4, 1.4) * (1 + a.flow * 0.6); ca += rand(0.4, 1.4) * (1 - a.flow * 0.6); bids.push(cb); asks.push(ca); }
    const mx = Math.max(cb, ca);
    const draw = (arr, col, dir) => { ctx.beginPath(); ctx.moveTo(w / 2, h); arr.forEach((v, i) => ctx.lineTo(w / 2 + dir * ((i + 1) / lv) * (w / 2 - 2), h - (v / mx) * (h - 6))); ctx.lineTo(w / 2 + dir * (w / 2 - 2), h); ctx.closePath(); ctx.fillStyle = AC.alpha(col, 0.3); ctx.fill(); ctx.strokeStyle = col; ctx.stroke(); };
    draw(bids, C.ok, -1); draw(asks, C.accent, 1);
    $('#trSpread', el).textContent = `Spread ${fmt(step, a.p > 1000 ? 1 : 4)} · Bid ${fmt(a.p - step / 2, 2)} · Ask ${fmt(a.p + step / 2, 2)}`;
    const vol = Math.sqrt(a.rets.reduce((x, r) => x + r * r, 0) / Math.max(1, a.rets.length)) * Math.sqrt(24 * 60) ;
    $('#trVol', el).textContent = AC.pct(vol, 1);
    AC.bars(cvs.trVolC, a.rets.slice(-40).map(Math.abs), { color: (v, i) => (i === 39 ? C.fg : C.accent) });
    S.assets.forEach(evidence);
    AC.heat(cvs.trEv, S.assets.map((x) => x.ev), (v) => (v >= 0 ? AC.alpha(C.accent, 0.15 + 0.85 * Math.abs(v)) : AC.alpha(C['accent-2'], 0.15 + 0.85 * Math.abs(v))));
    const eq = equity(); S.peak = Math.max(S.peak, eq); AC.push(S.eqHist, eq, 120);
    $('#trEq', el).textContent = usd(eq, 0);
    const pnl = eq / S.start - 1;
    $('#trPnl', el).textContent = `${pnl >= 0 ? '+' : ''}${AC.pct(pnl, 2)} seit Start`;
    AC.spark(cvs.trEqC, S.eqHist, { color: pnl >= 0 ? C.ok : C.bad, grid: true, ref: S.start });
    const open = S.assets.filter((x) => x.pos);
    $('#trPos', el).innerHTML = open.length ? `<table class="tbl"><tr><th>Asset</th><th>Seite</th><th class="r">Einstieg</th><th class="r">PnL</th></tr>${open.map((x) => { const p = (x.p - x.entry) * x.pos; return `<tr><td>${x.s}</td><td>${x.pos > 0 ? 'LONG' : 'SHORT'}</td><td class="r">${fmt(x.entry, x.entry > 1000 ? 0 : 2)}</td><td class="r" style="color:${p >= 0 ? C.ok : C.bad}">${p >= 0 ? '+' : ''}${usd(p)}</td></tr>`; }).join('')}</table>` : '<p class="note">Keine offenen Positionen.</p>';
    const ex = exposure(), dd = 1 - eq / S.peak;
    AC.gauge(cvs.trGauge, ex / 0.8, ex < 0.27 ? 'NIEDRIG' : ex < 0.55 ? 'MITTEL' : 'HOCH');
    $('#trDD', el).textContent = AC.pct(dd, 2); $('#trExp', el).textContent = AC.pct(ex, 1);
    const hmax = Math.max(1, ...Object.values(S.handoffs));
    $('#trHand', el).innerHTML = AGENTS.map((g) => `<div class="row" style="align-items:center"><span>${g.n}</span><span class="meter" style="width:60%"><i style="width:${(S.handoffs[g.id] / hmax) * 100}%"></i></span></div>`).join('');
    $('#trDec', el).textContent = fmt(S.decisions);
    $('#trPass', el).textContent = S.decisions ? AC.pct(S.passes / S.decisions, 1) : '0 %';
    $('#trLat', el).textContent = S.lat.length ? fmt(AC.median(S.lat)) + ' ms' : '– ms';
    AC.bars(cvs.trFlow, S.flow, { color: (v) => (v >= gate ? C.accent : AC.alpha(C.fg, 0.25)), min: 0, max: 1, ref: gate });
    // agent strip
    $$('#trAgents .ag', el).forEach((n, i) => {
      n.classList.toggle('on', i === S.step);
      const d = S.conf[AGENTS[i].id]; AC.spark(cvs.ag[i], d, { color: i === S.step ? C.accent : AC.alpha(C.fg, 0.5), fill: false, dot: i === S.step, min: 0.3, max: 1 });
      $('.av', n).textContent = fmt(d[d.length - 1], 2);
    });
    const Cc = S.cur;
    if (Cc && Cc.conf) {
      $('#trTagChoice b', el).textContent = `${Cc.a.s} ${Cc.side || ''} · conf ${fmt(Cc.conf, 2)}`;
      $('#trTagGate b', el).textContent = Cc.pass === undefined ? '…' : `${Cc.pass ? 'PASS' : 'HOLD'} · ${fmt(Cc.conf, 2)} / ${fmt(gate, 2)}`;
      $('#trTagRisk b', el).textContent = Cc.riskOk === false ? 'Limit erreicht' : `ok · Exp ${AC.pct(ex, 0)}`;
    }
  }

  // ---------- orbital torus ----------
  function seedParts() {
    const n = AC.reduced ? 500 : Math.round(Math.min(2200, cv.w * cv.h / 260));
    parts = Array.from({ length: n }, () => ({ th: rand(Math.PI * 2), ph: rand(Math.PI * 2), v: rand(0.08, 0.3), dv: rand(-0.4, 0.4), j: rand(0.85, 1.15) }));
  }
  function drawTorus(dt) {
    const { ctx, w, h } = cv, C = AC.col;
    ctx.globalCompositeOperation = 'source-over';
    ctx.fillStyle = AC.alpha(C.panel.startsWith('#') ? C.panel : '#0c0e12', AC.reduced ? 1 : 0.16); ctx.fillRect(0, 0, w, h);
    ctx.globalCompositeOperation = 'lighter';
    const cx = w / 2, cy = h * 0.56, R = Math.min(w * 0.36, h * 0.62), r = R * 0.3, tilt = 1.12, ct = Math.cos(tilt), stt = Math.sin(tilt);
    energy = Math.max(0, energy - dt * 0.6);
    const conf = S.cur && S.cur.conf ? S.cur.conf : 0.7, boost = 1 + energy * 3 + (conf - 0.7) * 1.5;
    const acc = C.accent, a2 = C['accent-2'];
    for (const p of parts) {
      if (running) { p.th += dt * p.v * boost * speed * 0.6; p.ph += dt * p.dv * speed; }
      const rr = (R + r * Math.cos(p.ph)) * p.j, x = rr * Math.cos(p.th), y = rr * Math.sin(p.th), z = r * Math.sin(p.ph);
      const yy = y * ct - z * stt, zz = y * stt + z * ct;
      const depth = (zz / (R + r) + 1) / 2;
      ctx.fillStyle = AC.alpha(energy > 0.3 && p.v > 0.24 ? a2 : acc, 0.18 + depth * 0.5);
      const s = 0.9 + depth * 1.4;
      ctx.fillRect(cx + x, cy + yy * 0.9, s, s);
    }
    const glow = ctx.createRadialGradient(cx, cy, R * 0.1, cx, cy, R * 1.1);
    glow.addColorStop(0, AC.alpha(acc, 0.05 + energy * 0.08)); glow.addColorStop(1, AC.alpha(acc, 0));
    ctx.fillStyle = glow; ctx.fillRect(cx - R * 1.1, cy - R * 1.1, R * 2.2, R * 2.2);
    ctx.globalCompositeOperation = 'source-over';
    // core ellipse + labels
    ctx.strokeStyle = AC.alpha(C.fg, 0.35); ctx.lineWidth = 1;
    ctx.beginPath(); ctx.ellipse(cx, cy, R * 0.5, R * 0.5 * ct * 0.9, 0, 0, 7); ctx.stroke();
    ctx.fillStyle = AC.alpha(C.fg, 0.5); ctx.font = '10px ' + getComputedStyle(document.body).getPropertyValue('--f-mono'); ctx.textAlign = 'center';
    ctx.fillText('SIGNAL-KERN', cx, cy + 4);
    // leader lines from tags to the ring
    const hr = cv.cv.getBoundingClientRect();
    for (const [id, th] of [['trTagChoice', Math.PI], ['trTagRisk', -0.35], ['trTagGate', 0.45]]) {
      const t = $('#' + id, el); if (!t || !t.offsetWidth) continue;
      const tr = t.getBoundingClientRect(), tx = (th > 1.5 ? tr.right : tr.left) - hr.left, ty = tr.top + tr.height / 2 - hr.top;
      const rr = R * 0.92, px = cx + rr * Math.cos(th), py = cy + rr * Math.sin(th) * ct * 0.9;
      ctx.strokeStyle = AC.alpha(C.fg, 0.4); ctx.beginPath(); ctx.moveTo(tx, ty); ctx.lineTo(px, py); ctx.stroke();
      ctx.fillStyle = C.fg; ctx.beginPath(); ctx.arc(px, py, 2.5, 0, 7); ctx.fill();
    }
  }

  let mAcc = 0, cAcc = 0, rAcc = 0;
  AC.register('trading', {
    init,
    enter() { cv.fit(); if (!parts.length) seedParts(); renderSide(); },
    frame(t, dt) {
      if (running) {
        mAcc += dt * speed; cAcc += dt * speed;
        while (mAcc > 0.25) { mAcc -= 0.25; marketTick(); }
        while (cAcc > 0.32) { cAcc -= 0.32; cycleStep(); }
      }
      drawTorus(dt);
      rAcc += dt; if (rAcc > 0.35) { rAcc = 0; renderSide(); }
    },
  });
})();
