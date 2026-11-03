/* 07 Second Brain – edit CLAUDE.md files, simulate what Claude Code loads at session start (rules from the official docs), audit and export. */
(() => {
  const { $, $$, esc, rand, clamp, fmt } = AC;

  const DEFAULT = [
    { path: 'CLAUDE.md', text: `# Second Brain

Karte meines Gehirns. Unter 200 Zeilen.

## Wo was liegt
- people/ – Menschen, mit denen ich arbeite
- projects/ – laufende Projekte
- drafts/ – Texte in Arbeit
- decisions.md – getroffene Entscheidungen

## Immer
- Antworte auf Deutsch, kurz und direkt.
- Frag nach, bevor du etwas verschickst oder löschst.
- Nutze pnpm.
- Sei hilfreich.

## Fokus jetzt
@now.md
` },
    { path: 'CLAUDE.local.md', text: `# Nur für mich (nicht committen)
- Sandbox: http://localhost:5173
- Test-Kunde: demo@nova.local
- Nutze npm für schnelle Tests.
` },
    { path: 'now.md', text: `# Jetzt
- NOVA-Beta: Landingpage bis Freitag
- 3 Kunden-Calls diese Woche
- Preise testen: 49 $ vs 79 $
` },
    { path: '.claude/rules/people.md', text: `---
paths:
  - "people/**"
---

# Menschen
- Schreib Namen immer aus.
- Keine privaten Daten in Entwürfe übernehmen.
- Notiere nach jedem Call: Datum, Thema, nächster Schritt.
` },
    { path: '.claude/rules/writing.md', text: `---
paths:
  - "drafts/**"
---

# Schreiben
- Kurze Sätze. Aktiv statt passiv.
- Erst der Punkt, dann die Begründung.
- Keine Emojis in Kunden-Mails.
` },
    { path: '.claude/rules/code.md', text: `---
paths:
  - "src/**/*.{ts,tsx}"
  - "**/*.py"
---

# Code
- Tests vor dem Commit: pnpm test
- Keine neuen Abhängigkeiten ohne Rückfrage.
` },
    { path: '.claude/rules/naming.md', text: `# Benennung
- Dateien in kebab-case.
- Datum immer als JJJJ-MM-TT.
` },
    { path: 'MEMORY.md', memory: true, text: `# Memory (schreibt Claude selbst)
- Nutzer will Antworten ohne Einleitung.
- Deploy läuft über docker compose.
- Kunde Nordlicht: Entscheider ist Lena.
` },
    { path: 'decisions.md', text: `# Entscheidungen
- 2026-10-02: Preise in USD
- 2026-10-14: Beta nur mit Einladung
` },
    { path: 'people/lena.md', text: `# Lena (Nordlicht GmbH)
- Entscheiderin, mag kurze Mails
- Letzter Call: 2026-10-28, nächster Schritt: Angebot
` },
    { path: 'drafts/launch-post.md', text: `# Launch-Post
Ich habe 14 Tage lang einen Agenten gebaut. Das habe ich gelernt.
` },
  ];
  const SAMPLE_PATHS = ['people/lena.md', 'drafts/launch-post.md', 'src/app.ts', 'scripts/sync.py', 'notes/steuer.md', 'README.md'];

  let el, cv, files, sel = 0, session = null, queueT = 0, anim = 0;

  // ---------- docs-based loading rules ----------
  const front = (t) => {
    const m = t.match(/^---\s*\n([\s\S]*?)\n---/);
    if (!m) return null;
    const paths = []; let inPaths = false;
    for (const line of m[1].split('\n')) {
      if (/^paths:\s*$/.test(line)) { inPaths = true; continue; }
      const it = line.match(/^\s*-\s*["']?([^"']+)["']?\s*$/);
      if (inPaths && it) paths.push(it[1].trim()); else if (!/^\s/.test(line)) inPaths = false;
    }
    return { paths };
  };
  function globRe(g) {
    let p = g.trim();
    if (p.startsWith('/')) p = p.slice(1); else if (!p.includes('/')) p = '**/' + p;
    let re = '';
    for (let i = 0; i < p.length; i++) {
      const c = p[i];
      if (c === '*') {
        if (p[i + 1] === '*') { if (p[i + 2] === '/') { re += '(?:.*/)?'; i += 2; } else { re += '.*'; i++; } }
        else re += '[^/]*';
      } else if (c === '?') re += '[^/]';
      else if (c === '{') { const j = p.indexOf('}', i); if (j < 0) { re += '\\{'; continue; } re += '(?:' + p.slice(i + 1, j).split(',').map((s) => s.replace(/[.+^$()|[\]\\]/g, '\\$&')).join('|') + ')'; i = j; }
      else re += c.replace(/[.+^$()|[\]\\]/g, '\\$&');
    }
    return new RegExp('^' + re + '$');
  }
  const matches = (globs, path) => globs.some((g) => { try { return globRe(g).test(path); } catch { return false; } });
  function kind(f) {
    if (f.memory) return { k: 'memory', t: 'Auto-Memory · erste 200 Zeilen' };
    if (f.path === 'CLAUDE.md' || f.path === 'CLAUDE.local.md' || f.path === '.claude/CLAUDE.md') return { k: 'always', t: 'bei jedem Start' };
    if (f.path.startsWith('.claude/rules/')) { const fm = front(f.text); return fm && fm.paths.length ? { k: 'match', t: 'bei Treffer: ' + fm.paths.join(', '), paths: fm.paths } : { k: 'always', t: 'bei jedem Start (Regel ohne paths)' }; }
    const imp = files.some((o) => o !== f && imports(o).includes(f.path));
    return imp ? { k: 'import', t: 'per @import' } : { k: 'demand', t: 'bei Bedarf (wenn Claude sie liest)' };
  }
  const imports = (f) => (f.text.match(/^@[^\s]+[ \t]*$/gm) || []).map((s) => s.trim().slice(1).replace(/^\.\//, ''));
  const lines = (t) => t.replace(/\n$/, '').split('\n').length;

  function simulate(prompt, touched) {
    const out = [], seen = new Set();
    const add = (f, why, cut) => { if (seen.has(f.path)) return; seen.add(f.path); out.push({ f, why, cut }); for (const p of imports(f)) { const g = files.find((x) => x.path === p); if (g) add(g, '@import aus ' + f.path); else out.push({ f: { path: p, text: '' }, why: 'fehlt! (@import aus ' + f.path + ')', missing: true }); } };
    const by = (p) => files.find((f) => f.path === p);
    if (by('CLAUDE.md')) add(by('CLAUDE.md'), 'Start: Projekt-Anweisungen');
    if (by('CLAUDE.local.md')) add(by('CLAUDE.local.md'), 'Start: nach CLAUDE.md angehängt');
    files.filter((f) => kind(f).k === 'always' && f.path.startsWith('.claude/rules/')).forEach((f) => add(f, 'Start: Regel ohne paths'));
    const mem = files.find((f) => f.memory); if (mem) add(mem, 'Start: Auto-Memory', lines(mem.text) > 200 || mem.text.length > 25600);
    const lazyFrom = out.length;
    for (const tp of touched) {
      const tf = by(tp); if (tf && !seen.has(tf.path)) add(tf, 'Claude liest ' + tp);
      files.filter((f) => kind(f).k === 'match' && matches(kind(f).paths, tp)).forEach((f) => add(f, 'Treffer: ' + tp));
    }
    const pl = prompt.toLowerCase();
    files.filter((f) => kind(f).k === 'demand' && pl.includes(f.path.replace(/\.md$/, '').split('/').pop().toLowerCase())).forEach((f) => add(f, 'im Prompt erwähnt'));
    return out.map((x, i) => ({ ...x, lazy: i >= lazyFrom }));
  }

  // ---------- audit ----------
  function audit() {
    const res = [];
    const C = files.find((f) => f.path === 'CLAUDE.md');
    if (C) { const n = lines(C.text); res.push(n > 200 ? ['bad', `CLAUDE.md hat ${n} Zeilen. Ziel laut Doku: unter 200. Verschiebe Bereichs-Wissen in .claude/rules/ mit paths.`] : ['ok', `CLAUDE.md: ${n} Zeilen (unter 200).`]); }
    else res.push(['warn', 'Keine CLAUDE.md im Projekt-Wurzelordner.']);
    const mem = files.find((f) => f.memory);
    if (mem && (lines(mem.text) > 200 || mem.text.length > 25600)) res.push(['bad', 'MEMORY.md ist über 200 Zeilen oder 25 KB. Alles danach wird beim Start nicht geladen.']);
    const launch = files.filter((f) => ['always', 'memory', 'import'].includes(kind(f).k));
    const pm = new Map(), lang = new Map();
    for (const f of launch) for (const l of f.text.split('\n')) {
      const a = l.toLowerCase().match(/\b(nutze|verwende|use|immer)\s+(npm|pnpm|yarn|bun)\b/); if (a) pm.set(a[2], f.path);
      const b = l.toLowerCase().match(/antworte\s+(?:auf|in)?\s*(deutsch|englisch|english|german)/); if (b) lang.set(b[1], f.path);
    }
    if (pm.size > 1) res.push(['bad', `Widerspruch: ${[...pm].map(([k, p]) => `${k} (${p})`).join(' vs. ')}. Claude wählt dann zufällig.`]);
    if (lang.size > 1) res.push(['bad', `Widerspruch bei der Sprache: ${[...lang.keys()].join(' vs. ')}.`]);
    const seenL = new Map();
    for (const f of files) for (const l of f.text.split('\n')) {
      const s = l.trim(), low = s.toLowerCase().replace(/^[-*]\s*/, '');
      if (/^(sei hilfreich|be helpful|gib dein bestes|schreib(e)? guten code|write good code|sei genau)\.?$/.test(low)) res.push(['warn', `„${s}“ in ${f.path} ist zu vage. Claude tut das ohnehin. Streichen oder konkret machen.`]);
      if (/(sk-ant-|api[_-]?key\s*[:=]|passwort\s*[:=]|password\s*[:=])/i.test(s)) res.push(['bad', `Möglicher Schlüssel oder Passwort in ${f.path}. Geheimnisse gehören nicht in Anweisungen.`]);
      if (s.length > 200) res.push(['warn', `Sehr lange Zeile in ${f.path}. Kürzere Regeln werden besser befolgt.`]);
      if (low.length > 14 && !low.startsWith('#') && !low.startsWith('---')) { if (seenL.has(low) && seenL.get(low) !== f.path) res.push(['warn', `Doppelt: „${s}“ in ${seenL.get(low)} und ${f.path}.`]); else seenL.set(low, f.path); }
    }
    for (const f of files) for (const p of imports(f)) if (!files.some((x) => x.path === p)) res.push(['bad', `@${p} in ${f.path} zeigt auf eine Datei, die es nicht gibt.`]);
    files.filter((f) => f.path.startsWith('.claude/rules/') && kind(f).k === 'always').forEach((f) => res.push(['warn', `${f.path} hat kein paths: und lädt bei jedem Start. Gilt die Regel nur für einen Bereich, setz paths.`]));
    files.filter((f) => kind(f).k === 'match').forEach((f) => { if (!SAMPLE_PATHS.concat(files.map((x) => x.path)).some((p) => matches(kind(f).paths, p))) res.push(['warn', `${f.path}: paths trifft keine bekannte Datei. Muster prüfen.`]); });
    const tok = Math.round(launch.reduce((a, f) => a + f.text.length, 0) / 4);
    res.push(['ok', `Kontext beim Start: ca. ${fmt(tok)} Tokens aus ${launch.length} Dateien.`]);
    return res;
  }

  // ---------- UI ----------
  function init(root) {
    el = root;
    files = AC.store.get('brain.files', null) || DEFAULT.map((f) => ({ ...f }));
    el.innerHTML = `
      <div class="vhead">
        <div><h1>Claude Code · <em>Second Brain</em></h1>
        <p>Bau deine CLAUDE.md-Dateien und sieh, was Claude Code beim Start lädt: CLAUDE.md immer, Regeln mit paths erst bei passenden Dateien, Auto-Memory nur die ersten 200 Zeilen. Entwürfe bleiben in diesem Browser.</p></div>
        <div class="ctrls"><button class="btn" id="brResetAll">Vorlage laden</button></div>
      </div>
      <div class="grid">
        <div class="panel s12 hero" style="min-height:clamp(360px, 52vh, 560px)"><canvas class="bg" id="brCv"></canvas>
          <div class="ov"><div class="ph"><span>Second Brain · <b id="brStat">0 Dateien</b></span><span class="r" id="brSess">claude  # neue Session</span></div></div></div>
        <div class="panel s3"><div class="ph">Dateien <span class="r" id="brCount"></span></div><div class="pb">
          <div class="br-files" id="brFiles"></div>
          <div class="gin" style="display:flex;gap:6px;margin-top:8px"><input type="text" id="brNew" placeholder=".claude/rules/neu.md" aria-label="Pfad der neuen Datei"><button class="btn sm" id="brAdd">Anlegen</button></div>
        </div></div>
        <div class="panel s5 br-edit"><div class="ph"><span id="brPath">CLAUDE.md</span><span class="r" id="brLines"></span></div><div class="pb">
          <textarea id="brText" spellcheck="false" aria-label="Dateiinhalt"></textarea>
          <div class="row" style="margin-top:6px"><span>Lädt</span><span id="brKind"></span></div>
          <div class="ctrls" style="margin-top:6px"><button class="btn sm" id="brSave1">Datei speichern</button><button class="btn sm" id="brDel">Datei entfernen</button></div>
        </div></div>
        <div class="panel s4"><div class="ph">Neue Session <span class="r">Simulation</span></div><div class="pb">
          <label class="fld" for="brPrompt">Prompt</label><input type="text" id="brPrompt" value="Bereite den Call mit Lena vor und prüf die decisions">
          <label class="fld" for="brTouch" style="margin-top:8px">Dateien, die Claude öffnet (Komma)</label><input type="text" id="brTouch" value="people/lena.md">
          <div class="ctrls" style="margin-top:8px"><button class="btn pri" id="brGo">Session starten</button></div>
          <div class="queue" id="brQueue" style="margin-top:10px"></div>
        </div></div>
        <div class="panel s6"><div class="ph">Prompt-Audit <span class="r" id="brAuditN"></span></div><div class="pb"><ul class="audit" id="brAudit"></ul>
          <div class="ctrls" style="margin-top:10px"><button class="btn ai ai-only" id="brAi">Mit Claude prüfen</button></div><div id="brAiOut"></div></div></div>
        <div class="panel s3"><div class="ph">Regel-Matrix <span class="r">Regel × Datei</span></div><div class="pb"><div class="matrix" id="brMatrix"></div></div></div>
        <div class="panel s3"><div class="ph">Export</div><div class="pb">
          <div class="ctrls"><button class="btn pri" id="brScript">Setup-Skript kopieren</button><button class="btn" id="brSaveMain">CLAUDE.md speichern</button></div>
          <p class="note">Das Skript legt alle Dateien per Bash in deinem Projekt an. Auto-Memory lässt es weg, die schreibt Claude selbst.</p></div></div>
      </div>`;
    cv = AC.canvas($('#brCv', el));
    $('#brFiles', el).onclick = (e) => { const b = e.target.closest('button[data-i]'); if (b) { sel = +b.dataset.i; renderEditor(); renderFiles(); } };
    $('#brText', el).oninput = (e) => { files[sel].text = e.target.value; persist(); renderMeta(); renderFiles(); renderAudit(); renderMatrix(); };
    $('#brAdd', el).onclick = () => {
      const p = $('#brNew', el).value.trim().replace(/^\.\//, '');
      if (!p || !/\.md$/.test(p)) { AC.toast('Gib einen Pfad an, der auf .md endet.'); return; }
      if (files.some((f) => f.path === p)) { AC.toast('Die Datei gibt es schon.'); return; }
      files.push({ path: p, text: p.startsWith('.claude/rules/') ? '---\npaths:\n  - "src/**"\n---\n\n# Neue Regel\n- \n' : '# ' + p + '\n' });
      sel = files.length - 1; $('#brNew', el).value = ''; persist(); renderAll();
    };
    $('#brDel', el).onclick = () => { if (files.length < 2) return; const p = files[sel].path; files.splice(sel, 1); sel = 0; persist(); renderAll(); AC.toast(p + ' entfernt.'); };
    $('#brResetAll', el).onclick = () => { files = DEFAULT.map((f) => ({ ...f })); sel = 0; persist(); renderAll(); AC.toast('Vorlage geladen.'); };
    $('#brGo', el).onclick = run;
    $('#brSave1', el).onclick = () => AC.save(files[sel].path.split('/').pop(), files[sel].text);
    $('#brSaveMain', el).onclick = () => { const f = files.find((x) => x.path === 'CLAUDE.md'); if (f) AC.save('CLAUDE.md', f.text); };
    $('#brScript', el).onclick = () => AC.copy(script());
    $('#brAi', el).onclick = askClaude;
    renderAll(); run();
  }
  const persist = () => AC.store.set('brain.files', files);
  function renderAll() { renderFiles(); renderEditor(); renderAudit(); renderMatrix(); }
  function renderFiles() {
    $('#brCount', el).textContent = files.length;
    $('#brStat', el).textContent = `${files.length} Dateien · ${files.filter((f) => kind(f).k === 'match').length} Pfad-Regeln`;
    const pill = { always: ['acc', 'IMMER'], memory: ['acc2', 'MEMORY'], match: ['', 'TREFFER'], import: ['acc', '@IMPORT'], demand: ['', 'BEDARF'] };
    $('#brFiles', el).innerHTML = files.map((f, i) => { const k = kind(f).k; return `<button data-i="${i}" aria-pressed="${i === sel}"><span>${esc(f.path)}</span><span class="pill ${pill[k][0]}">${pill[k][1]}</span></button>`; }).join('');
  }
  function renderEditor() { const f = files[sel]; $('#brPath', el).textContent = f.memory ? '~/.claude/projects/…/memory/MEMORY.md' : f.path; $('#brText', el).value = f.text; renderMeta(); }
  function renderMeta() { const f = files[sel], n = lines(f.text); $('#brLines', el).textContent = `${n} Zeilen${(f.path === 'CLAUDE.md' || f.memory) ? ' / 200' : ''}`; $('#brKind', el).textContent = kind(f).t; }
  function renderAudit() {
    const r = audit(), bad = r.filter((x) => x[0] !== 'ok').length;
    $('#brAuditN', el).textContent = bad ? `${bad} Hinweise` : 'alles gut';
    $('#brAudit', el).innerHTML = r.map(([k, t]) => `<li><span class="pill ${k}">${k === 'ok' ? 'OK' : k === 'warn' ? 'HINWEIS' : 'PROBLEM'}</span> ${esc(t)}</li>`).join('');
  }
  function renderMatrix() {
    const rules = files.filter((f) => kind(f).k === 'match'), C = AC.col;
    const cols = `120px repeat(${SAMPLE_PATHS.length}, minmax(0, 1fr))`;
    $('#brMatrix', el).style.gridTemplateColumns = cols;
    $('#brMatrix', el).innerHTML = `<span></span>${SAMPLE_PATHS.map((p) => `<span title="${esc(p)}" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--dim)">${esc(p.split('/')[0])}</span>`).join('')}` +
      (rules.length ? rules.map((r) => `<span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(r.path.replace('.claude/rules/', ''))}</span>${SAMPLE_PATHS.map((p) => `<i style="background:${matches(kind(r).paths, p) ? C.accent : AC.alpha(C.fg, 0.07)}" title="${esc(r.path)} × ${esc(p)}"></i>`).join('')}`).join('') : '<span style="grid-column:1/-1" class="note">Keine Regeln mit paths.</span>');
    $('#brMatrix', el).insertAdjacentHTML('beforeend', `<span style="grid-column:1/-1" class="sub">${SAMPLE_PATHS.join(' · ')}</span>`);
  }
  function run() {
    const touched = $('#brTouch', el).value.split(',').map((s) => s.trim()).filter(Boolean);
    session = { list: simulate($('#brPrompt', el).value, touched), shown: 0, t: 0, touched };
    $('#brSess', el).textContent = 'claude  # neue Session · ' + session.list.length + ' Dateien';
    queueT = 0; anim = 0; renderQueue();
    AC.decision({ latency: Math.round(rand(8, 30)), conf: 1, cost: 0 });
  }
  function renderQueue() {
    if (!session) return;
    $('#brQueue', el).innerHTML = session.list.map((x, i) => `<div class="q" title="${esc(x.why)}"><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(x.f.memory ? 'MEMORY.md' : x.f.path)} <span class="sub">${esc(x.why)}</span></span><span class="meter ${x.lazy ? 'a2' : ''}"><i style="width:${i < session.shown ? 100 : 0}%"></i></span><span class="pill ${x.missing ? 'bad' : x.cut ? 'warn' : x.lazy ? 'acc2' : 'acc'}">${x.missing ? 'FEHLT' : x.cut ? 'GEKÜRZT' : x.lazy ? 'LAZY' : 'IN'}</span></div>`).join('') +
      `<p class="note">${session.list.filter((x) => !x.lazy).length} beim Start, ${session.list.filter((x) => x.lazy).length} nachgeladen. Nicht geladen: ${files.filter((f) => !session.list.some((x) => x.f.path === f.path)).map((f) => f.path).join(', ') || '–'}.</p>`;
  }
  function script() {
    const fs = files.filter((f) => !f.memory);
    const dirs = [...new Set(fs.map((f) => f.path.split('/').slice(0, -1).join('/')).filter(Boolean))];
    return ['#!/usr/bin/env bash', '# Second Brain anlegen – im Projektordner ausführen', 'set -e', dirs.length ? `mkdir -p ${dirs.map((d) => `'${d}'`).join(' ')}` : '',
      ...fs.map((f) => `cat > '${f.path}' <<'H2EOF'\n${f.text.replace(/\n$/, '')}\nH2EOF`), "grep -qxF 'CLAUDE.local.md' .gitignore 2>/dev/null || echo 'CLAUDE.local.md' >> .gitignore", 'echo "Fertig. Starte claude und prüfe mit /context, was geladen wurde."'].filter(Boolean).join('\n') + '\n';
  }
  async function askClaude() {
    const b = $('#brAi', el); b.disabled = true; $('#brAiOut', el).innerHTML = '<p class="note">Claude liest deine Dateien…</p>';
    try {
      const bundle = files.map((f) => `=== ${f.memory ? 'MEMORY.md (auto memory)' : f.path} ===\n${f.text}`).join('\n\n').slice(0, 30000);
      const r = await AC.ai.json(`Prüfe diese Claude-Code-Anweisungsdateien (CLAUDE.md, CLAUDE.local.md, .claude/rules/*.md mit paths-Frontmatter, Auto-Memory). Suche: Widersprüche, vage Regeln, Doppeltes, Dinge, die besser als Pfad-Regel ausgelagert wären, und was fehlt.
Antworte nur mit JSON: {"findings": [{"file": "<Pfad>", "problem": "<kurz, deutsch>", "fix": "<konkreter Vorschlag, deutsch>"}]} mit höchstens 6 Einträgen.

${bundle}`, { modelTier: 'default' });
      const list = Array.isArray(r && r.findings) ? r.findings.slice(0, 6) : [];
      $('#brAiOut', el).innerHTML = list.length ? `<div class="panel" style="margin-top:10px"><div class="ph"><span class="ai-chip">Claude</span><span class="r">${list.length} Vorschläge</span></div><div class="pb"><ul class="audit">${list.map((x) => `<li><b>${esc(String(x.file || ''))}</b>: ${esc(String(x.problem || ''))}<br><span class="sub">→ ${esc(String(x.fix || ''))}</span></li>`).join('')}</ul></div></div>` : '<p class="note">Claude hat nichts gefunden.</p>';
    } catch (e) { $('#brAiOut', el).innerHTML = `<p class="note">${esc(AC.ai.msg(e))}</p>`; }
    finally { b.disabled = false; }
  }

  // ---------- canvas: inputs converge into one context, path rules fan out ----------
  function draw(t, dt) {
    const { ctx, w, h } = cv, C = AC.col; ctx.clearRect(0, 0, w, h);
    if (!session) return;
    const F = getComputedStyle(document.body).getPropertyValue('--f-mono');
    const cx = w / 2, cy = h * 0.56, left = files.filter((f) => kind(f).k !== 'match'), right = files.filter((f) => kind(f).k === 'match');
    const isOn = (f) => { const i = session.list.findIndex((x) => x.f === f); return i > -1 && i < session.shown; };
    const ins = [{ path: 'prompt', on: true }, ...left.map((f) => ({ path: f.memory ? 'MEMORY.md' : f.path, on: isOn(f) }))];
    const outs = right.map((f) => ({ path: f.path.replace('.claude/rules/', 'rules/'), on: isOn(f) }));
    // spine
    ctx.strokeStyle = AC.alpha(C.fg, 0.18); ctx.beginPath(); ctx.moveTo(cx, 44); ctx.lineTo(cx, h - 18); ctx.stroke();
    for (let y = 56; y < h - 20; y += 16) { ctx.fillStyle = AC.alpha(C.fg, 0.5); ctx.fillRect(cx - 1.5, y, 3, 3); }
    const lx = Math.max(110, w * 0.16), rx = w - Math.max(110, w * 0.16), y0 = 60, y1 = h - 40;
    const strand = (x0, y, x1, yy, on, n) => {
      for (let k = 0; k < n; k++) {
        const off = (k - n / 2) * 2.2;
        ctx.strokeStyle = on ? AC.alpha(C.accent, 0.5 - k * 0.04) : AC.alpha(C.fg, 0.05);
        ctx.beginPath(); ctx.moveTo(x0, y); ctx.bezierCurveTo((x0 + x1) / 2, y + off * 2, (x0 + x1) / 2 + off * 6, yy, x1, yy); ctx.stroke();
      }
    };
    ctx.font = '11px ' + F; ctx.lineWidth = 0.8;
    ins.forEach((p, i) => {
      const y = y0 + ((y1 - y0) * (i + 0.5)) / ins.length;
      strand(lx + 6, y, cx, cy, p.on, 7);
      let label = p.path; while (label.length > 4 && ctx.measureText(label).width > lx - 10) label = '…' + label.slice(2);
      ctx.fillStyle = p.on ? C.fg : C.dim; ctx.textAlign = 'right'; ctx.fillText(label, lx, y + 4);
      for (let k = 0; k < 4; k++) { ctx.strokeStyle = p.on ? AC.alpha(C.accent, 0.6) : AC.alpha(C.fg, 0.15); ctx.beginPath(); ctx.moveTo(lx + 6 + k * 5, y + Math.sin(t * 3 + i + k) * 2); ctx.lineTo(lx + 9 + k * 5, y - Math.sin(t * 3 + i + k) * 2); ctx.stroke(); }
    });
    outs.forEach((p, i) => {
      const y = y0 + ((y1 - y0) * (i + 0.5)) / Math.max(1, outs.length);
      strand(cx, cy, rx - 6, y, p.on, 9);
      let label = p.path; while (label.length > 4 && ctx.measureText(label).width > w - rx - 8) label = '…' + label.slice(2);
      ctx.fillStyle = p.on ? C.accent : C.dim; ctx.textAlign = 'left'; ctx.fillText(label, rx, y + 4);
      ctx.fillStyle = C.dim; ctx.font = '10px ' + F; ctx.fillText(p.on ? 'geladen: Treffer' : 'wartet auf Treffer', rx, y + 17); ctx.font = '11px ' + F;
    });
    // dust
    for (let k = 0; k < 60; k++) { const a = rand(Math.PI * 2), r = rand(20, Math.min(w, h) * 0.45); ctx.fillStyle = AC.alpha(C['accent-2'], rand(0.05, 0.3)); ctx.fillRect(cx + Math.cos(a) * r * 1.4, cy + Math.sin(a) * r * 0.8, 1.2, 1.2); }
    // node
    const pulse = 6 + Math.sin(t * 3) * 1.5;
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, 30); g.addColorStop(0, AC.alpha(C.accent, 0.7)); g.addColorStop(1, AC.alpha(C.accent, 0));
    ctx.fillStyle = g; ctx.fillRect(cx - 30, cy - 30, 60, 60); ctx.fillStyle = C.accent; ctx.beginPath(); ctx.arc(cx, cy, pulse, 0, 7); ctx.fill();
    // caption
    const done = session.shown >= session.list.length;
    const cap = done ? 'CLAUDE.md / GELADEN' : `LADE ${session.shown}/${session.list.length}`;
    ctx.font = '600 13px ' + F; ctx.textAlign = 'center'; const tw = ctx.measureText(cap).width + 22;
    ctx.fillStyle = AC.alpha(C.panel, 0.9); ctx.strokeStyle = C.accent; ctx.fillRect(cx - tw / 2, h - 46, tw, 26); ctx.strokeRect(cx - tw / 2, h - 46, tw, 26);
    ctx.fillStyle = C.accent; ctx.fillText(cap, cx, h - 28);
    ctx.font = '10px ' + F; ctx.textAlign = 'left'; ctx.fillStyle = C.muted;
    ctx.fillText('INPUTS', 12, 58); ctx.textAlign = 'right'; ctx.fillText('PFAD-REGELN', w - 12, 58);
  }

  AC.register('brain', {
    init,
    enter() { cv.fit(); },
    frame(t, dt) {
      if (session && session.shown < session.list.length) { queueT += dt; if (queueT > 0.45) { queueT = 0; session.shown++; renderQueue(); } }
      draw(t, dt);
    },
  });
})();
