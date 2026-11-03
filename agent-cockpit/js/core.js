/* H2 Agent Cockpit – core: helpers, routing, render loop, global KPIs, Claude + download bridges. */
(() => {
  'use strict';
  const AC = (window.AC = {});

  // ---------- small helpers ----------
  AC.$ = (s, r = document) => r.querySelector(s);
  AC.$$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  AC.esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  AC.h = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };
  AC.rand = (a = 1, b) => (b === undefined ? Math.random() * a : a + Math.random() * (b - a));
  AC.randi = (a, b) => Math.floor(AC.rand(a, b + 1));
  AC.pick = (a) => a[Math.floor(Math.random() * a.length)];
  AC.clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  AC.lerp = (a, b, t) => a + (b - a) * t;
  AC.sigmoid = (x) => 1 / (1 + Math.exp(-x));
  AC.gauss = () => { let u = 0, v = 0; while (!u) u = Math.random(); while (!v) v = Math.random(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); };
  AC.fmt = (n, d = 0) => Number(n).toLocaleString('de-DE', { minimumFractionDigits: d, maximumFractionDigits: d });
  AC.usd = (n, d = 2) => AC.fmt(n, d) + ' $';
  AC.pct = (n, d = 0) => AC.fmt(n * 100, d) + ' %';
  AC.now = () => { const d = new Date(); return d.toLocaleTimeString('de-DE', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0'); };
  AC.median = (a) => { if (!a.length) return 0; const s = [...a].sort((x, y) => x - y); const m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
  AC.push = (arr, v, max) => { arr.push(v); if (arr.length > max) arr.shift(); return arr; };
  AC.reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  AC.store = {
    get(k, d) { try { const v = localStorage.getItem('h2ac.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('h2ac.' + k, JSON.stringify(v)); } catch { /* storage blocked: ignore */ } },
  };

  // colors from the active module's tokens
  AC.col = {};
  AC.readColors = () => {
    const cs = getComputedStyle(document.body);
    for (const k of ['accent', 'accent-2', 'fg', 'muted', 'dim', 'line', 'line-2', 'panel', 'panel-2', 'bg', 'ok', 'warn', 'bad']) AC.col[k] = cs.getPropertyValue('--' + k).trim();
  };
  AC.alpha = (hex, a) => {
    const h = hex.replace('#', '');
    const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16);
    return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
  };

  // ---------- canvas helper (DPR aware, follows its CSS size) ----------
  AC.canvas = (cv, onresize) => {
    const o = { cv, ctx: cv.getContext('2d'), w: 1, h: 1, dpr: 1, onresize: null };
    o.fit = () => {
      const r = cv.getBoundingClientRect();
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      const w = Math.max(1, Math.round(r.width)), h = Math.max(1, Math.round(r.height));
      if (w !== o.w || h !== o.h || dpr !== o.dpr) {
        o.w = w; o.h = h; o.dpr = dpr;
        cv.width = w * dpr; cv.height = h * dpr;
        o.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        if (o.onresize) o.onresize(o);
      }
    };
    o.fit();
    // set after the first fit so callers can reference the returned object inside onresize
    o.onresize = onresize;
    if ('ResizeObserver' in window) new ResizeObserver(() => o.fit()).observe(cv);
    return o;
  };

  // ---------- mini charts ----------
  AC.spark = (o, data, opt = {}) => {
    const { ctx, w, h } = o; ctx.clearRect(0, 0, w, h);
    if (data.length < 2) return;
    const col = opt.color || AC.col.accent;
    let mn = opt.min ?? Math.min(...data), mx = opt.max ?? Math.max(...data);
    if (mx - mn < 1e-9) { mx += 1; mn -= 1; }
    const p = 2, X = (i) => p + (i / (data.length - 1)) * (w - p * 2), Y = (v) => h - p - ((v - mn) / (mx - mn)) * (h - p * 2);
    if (opt.grid) { ctx.strokeStyle = AC.alpha(AC.col.fg, 0.06); ctx.lineWidth = 1; for (let i = 1; i < 4; i++) { const y = (h / 4) * i; ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); } }
    if (opt.ref != null) { ctx.strokeStyle = AC.alpha(AC.col.fg, 0.25); ctx.setLineDash([3, 3]); ctx.beginPath(); ctx.moveTo(0, Y(opt.ref)); ctx.lineTo(w, Y(opt.ref)); ctx.stroke(); ctx.setLineDash([]); }
    ctx.beginPath(); data.forEach((v, i) => (i ? ctx.lineTo(X(i), Y(v)) : ctx.moveTo(X(i), Y(v))));
    if (opt.fill !== false && col.startsWith('#')) {
      ctx.save(); ctx.lineTo(X(data.length - 1), h); ctx.lineTo(X(0), h); ctx.closePath();
      const g = ctx.createLinearGradient(0, 0, 0, h); g.addColorStop(0, AC.alpha(col, 0.28)); g.addColorStop(1, AC.alpha(col, 0));
      ctx.fillStyle = g; ctx.fill(); ctx.restore();
      ctx.beginPath(); data.forEach((v, i) => (i ? ctx.lineTo(X(i), Y(v)) : ctx.moveTo(X(i), Y(v))));
    }
    ctx.strokeStyle = col; ctx.lineWidth = opt.lw || 1.3; ctx.stroke();
    if (opt.dot !== false) { ctx.fillStyle = col; ctx.beginPath(); ctx.arc(X(data.length - 1), Y(data[data.length - 1]), 2.2, 0, 7); ctx.fill(); }
  };
  AC.bars = (o, data, opt = {}) => {
    const { ctx, w, h } = o; ctx.clearRect(0, 0, w, h);
    if (!data.length) return;
    const mx = opt.max ?? Math.max(1e-9, ...data), n = data.length, gap = opt.gap ?? 1, bw = Math.max(1, (w - gap * (n - 1)) / n);
    data.forEach((v, i) => {
      const bh = Math.max(1, (v / mx) * (h - 2));
      ctx.fillStyle = typeof opt.color === 'function' ? opt.color(v, i) : opt.color || AC.col.accent;
      ctx.fillRect(i * (bw + gap), h - bh, bw, bh);
    });
    if (opt.ref != null) { const y = h - (opt.ref / mx) * (h - 2); ctx.strokeStyle = AC.alpha(AC.col.fg, 0.35); ctx.setLineDash([3, 3]); ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); ctx.setLineDash([]); }
  };
  AC.heat = (o, rows, colorFn) => {
    const { ctx, w, h } = o; ctx.clearRect(0, 0, w, h);
    if (!rows.length) return;
    const R = rows.length, C = rows[0].length, cw = w / C, chh = h / R;
    rows.forEach((r, y) => r.forEach((v, x) => { ctx.fillStyle = colorFn(v, x, y); ctx.fillRect(x * cw + 1, y * chh + 1, cw - 2, chh - 2); }));
  };
  AC.gauge = (o, v, label) => {
    const { ctx, w, h } = o; ctx.clearRect(0, 0, w, h);
    const cx = w / 2, cy = h * 0.78, r = Math.min(w * 0.42, h * 0.68), a0 = Math.PI * 0.9, a1 = Math.PI * 2.1;
    ctx.lineCap = 'round';
    ctx.strokeStyle = AC.col['line-2']; ctx.lineWidth = 8; ctx.beginPath(); ctx.arc(cx, cy, r, a0, a1); ctx.stroke();
    ctx.strokeStyle = v > 0.66 ? AC.col.bad : v > 0.33 ? AC.col.warn : AC.col.accent;
    ctx.beginPath(); ctx.arc(cx, cy, r, a0, a0 + (a1 - a0) * AC.clamp(v, 0, 1)); ctx.stroke();
    const a = a0 + (a1 - a0) * AC.clamp(v, 0, 1);
    ctx.strokeStyle = AC.col.fg; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + Math.cos(a) * r * 0.78, cy + Math.sin(a) * r * 0.78); ctx.stroke();
    ctx.fillStyle = AC.col.fg; ctx.font = '600 16px ' + getComputedStyle(document.body).getPropertyValue('--f-display'); ctx.textAlign = 'center';
    ctx.fillText(label, cx, cy + 4 - r * 0.32);
  };

  // ---------- log list ----------
  AC.Log = class {
    constructor(el, max = 40) { this.el = el; this.max = max; }
    add(who, msg, val = '', cls = '') {
      const r = document.createElement('div');
      r.className = 'lr new ' + cls;
      r.innerHTML = `<span class="t">${AC.now().slice(0, 12)}</span><span class="w">${AC.esc(who)}</span><span class="m">${AC.esc(msg)}</span><span class="v">${AC.esc(val)}</span>`;
      this.el.prepend(r);
      while (this.el.childElementCount > this.max) this.el.lastElementChild.remove();
    }
    clear() { this.el.innerHTML = ''; }
  };

  // ---------- code view with a moving highlight ----------
  const TOK = /(#.*$|\/\/.*$)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|\b(def|if|elif|else|return|for|in|not|and|or|async|await|import|from|class|assert|with|as|True|False|None|while|lambda|const|let|function)\b|\b(\d+(?:\.\d+)?)\b/g;
  const hl = (line) => {
    let out = '', last = 0;
    line.replace(TOK, (m, c, s, k, n, off) => {
      out += AC.esc(line.slice(last, off)) + `<i class="${c ? 'tc' : s ? 'ts' : k ? 'tk' : 'tn'}">${AC.esc(m)}</i>`;
      last = off + m.length; return m;
    });
    return out + AC.esc(line.slice(last));
  };
  AC.code = (el, src) => {
    const api = {
      set(s) { el.innerHTML = s.split('\n').map((l, i) => `<div class="cl"><span class="ln">${i + 1}</span><span>${hl(l) || ' '}</span></div>`).join(''); api.cur = -1; },
      mark(i) {
        if (i === api.cur) return;
        const rows = el.children; if (rows[api.cur]) rows[api.cur].classList.remove('on');
        api.cur = i; const r = rows[i]; if (!r) return;
        r.classList.add('on');
        const top = r.offsetTop - el.clientHeight / 2;
        el.scrollTop = Math.max(0, top);
      },
      cur: -1,
      el,
    };
    api.set(src);
    return api;
  };

  // ---------- toast ----------
  let toastT;
  AC.toast = (msg) => { const t = AC.$('#toast'); t.textContent = msg; t.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => (t.hidden = true), 3200); };

  // ---------- global KPIs (fed by every module) ----------
  const G = (AC.stats = { decisions: 0, lat: [], cost: 0, conf: [] });
  AC.decision = ({ latency = null, conf = null, cost = 0 } = {}) => {
    G.decisions++;
    if (latency != null) AC.push(G.lat, latency, 120);
    if (conf != null) AC.push(G.conf, conf, 60);
    G.cost += cost;
  };
  let kSpark;
  const renderKpis = () => {
    AC.$('#kDec').textContent = AC.fmt(G.decisions);
    AC.$('#kLat').textContent = G.lat.length ? '~' + AC.fmt(AC.median(G.lat)) + ' ms' : '– ms';
    AC.$('#kCost').textContent = AC.usd(G.cost, G.cost < 10 ? 3 : 2);
    const c = G.conf.length ? G.conf.reduce((a, b) => a + b, 0) / G.conf.length : null;
    AC.$('#kConf').textContent = c == null ? '–' : AC.fmt(c, 2);
    AC.$('#kConfBar').style.width = c == null ? '0' : c * 100 + '%';
    if (kSpark) AC.spark(kSpark, G.lat.slice(-40), { color: AC.col['accent-2'], fill: false, dot: true });
  };

  // ---------- Claude (artifact "sample" capability) ----------
  AC.ai = { sample: null };
  const aiCodes = {
    not_granted: 'Claude ist für diese Seite nicht erlaubt.',
    sampling_disabled: 'Claude ist für dieses Konto nicht verfügbar.',
    rate_limited: 'Zu viele Anfragen. Warte kurz und versuch es dann nochmal.',
    session_expired: 'Bitte melde dich bei claude.ai neu an.',
    refused: 'Claude hat diese Eingabe abgelehnt. Formuliere sie anders.',
    invalid_json: 'Die Antwort war unvollständig. Bitte nochmal versuchen.',
    prompt_too_large: 'Der Text ist zu lang. Kürze ihn bitte.',
    cancelled: 'Abgebrochen.',
  };
  AC.ai.msg = (e) => aiCodes[e && e.code] || 'Claude hat gerade nicht geantwortet. Bitte nochmal versuchen.';
  AC.ai.json = async (prompt, opts = {}) => {
    if (!AC.ai.sample) throw { code: 'sampling_disabled' };
    try { return await AC.ai.sample.json(prompt, { modelTier: 'quick', ...opts }); }
    catch (e) {
      if (e && ['not_granted', 'sampling_disabled', 'not_declared', 'capability_disabled', 'capability_removed'].includes(e.code)) document.documentElement.classList.remove('has-ai');
      throw e;
    }
  };
  const initAI = async () => {
    if (!window.claude || typeof window.claude.use !== 'function') return;
    try {
      const s = await window.claude.use('sample');
      if (s) { AC.ai.sample = s; document.documentElement.classList.add('has-ai'); }
    } catch { /* stays hidden */ }
  };

  // ---------- copy + save ----------
  AC.copy = async (text) => {
    try { await navigator.clipboard.writeText(text); AC.toast('In die Zwischenablage kopiert.'); return true; }
    catch { AC.toast('Kopieren ging nicht. Markiere den Text und kopiere ihn von Hand.'); return false; }
  };
  AC.save = async (filename, text) => {
    if (window.claude && typeof window.claude.use === 'function') {
      try {
        const d = await window.claude.use('downloads');
        if (d) { await d.save({ filename, data: text }); AC.toast(filename + ' gespeichert.'); return; }
      } catch (e) {
        if (e && e.code === 'declined') return;
      }
      await AC.copy(text); return;
    }
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    a.download = filename; document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
    AC.toast(filename + ' gespeichert.');
  };

  // ---------- views + routing ----------
  AC.views = {}; AC.order = []; AC.cur = null;
  AC.register = (id, def) => { AC.views[id] = def; AC.order.push(id); };
  AC.show = (id) => {
    if (!AC.views[id]) id = AC.store.get('tab', AC.order[0]);
    if (!AC.views[id]) id = AC.order[0];
    if (AC.cur === id) return;
    const prev = AC.views[AC.cur];
    if (prev && prev.leave) prev.leave();
    AC.$$('.view').forEach((s) => (s.hidden = s.id !== 'v-' + id));
    document.body.dataset.view = id;
    AC.readColors();
    AC.$$('#tabs a').forEach((a) => (a.getAttribute('data-v') === id ? a.setAttribute('aria-current', 'page') : a.removeAttribute('aria-current')));
    const cur = AC.$('#tabs a[aria-current]'); if (cur) cur.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    const v = AC.views[id], el = AC.$('#v-' + id);
    AC.cur = id; AC.store.set('tab', id);
    if (!v._ready) { v.init(el); v._ready = true; }
    if (v.enter) v.enter();
  };

  // ---------- main loop: only the visible module animates ----------
  let last = performance.now(), acc = 0, kAcc = 0;
  const loop = (t) => {
    let dt = Math.min(0.1, (t - last) / 1000); last = t;
    if (!document.hidden) {
      kAcc += dt; if (kAcc > 0.5) { kAcc = 0; renderKpis(); }
      const v = AC.views[AC.cur];
      if (v && v.frame) {
        if (AC.reduced) { acc += dt; if (acc >= 0.25) { v.frame(t / 1000, acc); acc = 0; } }
        else v.frame(t / 1000, dt);
      }
    }
    requestAnimationFrame(loop);
  };

  AC.start = () => {
    AC.readColors();
    kSpark = AC.canvas(AC.$('#kLatSpark'));
    addEventListener('hashchange', () => AC.show(location.hash.slice(1)));
    AC.show(location.hash.slice(1));
    initAI();
    requestAnimationFrame(loop);
  };
})();
