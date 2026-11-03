/* 04 Entscheider – one input, several cheap "heads" score it, the engine picks one action, code acts, a human approves anything external. */
(() => {
  const { $, $$, esc, rand, clamp, fmt, usd } = AC;

  const low = (t) => t.toLowerCase();
  const cnt = (t, ws) => ws.reduce((a, w) => a + (t.includes(w) ? 1 : 0), 0);
  const hash = (s) => { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0) / 4294967295; };
  const jit = (t, k) => (hash(t + k) - 0.5) * 0.08;

  const HEADS = {
    urgency: { q: 'Wie dringend?', f: (t) => 0.12 + 0.2 * cnt(t, ['heute', 'sofort', 'dringend', 'asap', 'deadline', 'frist', 'morgen', 'freitag', 'jetzt', 'schnell']) },
    value: { q: 'Wie wertvoll?', f: (t) => 0.15 + 0.17 * cnt(t, ['kunde', 'umsatz', 'budget', '€', '$', 'partner', 'angebot', 'auftrag', 'lizenz', 'team', 'abo']) + (/\d{3,}/.test(t) ? 0.15 : 0) },
    lead_quality: { q: 'Wie gut ist der Lead?', f: (t) => 0.1 + 0.16 * cnt(t, ['budget', 'mitarbeiter', 'unternehmen', 'gmbh', 'demo', 'starten', 'entscheider', 'geschäftsführ', 'angebot']) + (/\d{2,}\s?(€|eur|mitarbeiter)/.test(t) ? 0.2 : 0) },
    viral_fit: { q: 'Passt es viral?', f: (t) => { const L = t.length; return 0.12 + (/^\s*(\d|ich habe|niemand|so |warum|wie )/.test(t) ? 0.22 : 0) + (L > 50 && L < 260 ? 0.18 : 0) + 0.12 * cnt(t, ['fehler', 'geheim', 'niemand', 'krass', 'unterschätzt', 'gelernt', 'tage', 'tool', 'ki', 'ai']) + (/\d/.test(t) ? 0.1 : 0); } },
    safe_to_run: { q: 'Sicher automatisch?', f: (t) => 1 - 0.22 * cnt(t, ['löschen', 'zahlen', 'überweis', 'kündig', 'vertrag', 'anwalt', 'passwort', 'alle kunden', 'veröffentlich', 'posten', 'produktion', 'geld']) },
    sentiment: { q: 'Stimmung positiv?', f: (t) => 0.5 + 0.12 * cnt(t, ['danke', 'super', 'toll', 'liebe', 'genial', 'freu', 'hilfreich']) - 0.14 * cnt(t, ['schlecht', 'ärger', 'enttäusch', 'kaputt', 'geht nicht', 'nervt', 'unzufrieden']) },
    effort: { q: 'Wie viel Aufwand?', f: (t) => 0.15 + Math.min(0.35, t.length / 1200) + 0.13 * cnt(t, ['integration', 'migration', 'komplex', 'datenbank', 'mehrere', 'refactor', 'umbau', 'schnittstelle']) },
    clarity: { q: 'Klar formuliert?', f: (t) => { const s = t.split(/[.!?\n]+/).filter((x) => x.trim()); const avg = s.length ? t.length / s.length : 200; return 0.95 - Math.min(0.6, Math.max(0, avg - 60) / 160) - (s.length > 12 ? 0.15 : 0); } },
    novelty: { q: 'Ist es neu?', f: (t) => 0.25 + 0.15 * cnt(t, ['neu', 'erstmals', 'idee', 'prototyp', 'experiment', 'noch nie', 'test']) },
    risk: { q: 'Wie riskant?', f: (t) => 0.08 + 0.2 * cnt(t, ['haftung', 'anwalt', 'vertrag', 'kündig', 'strafe', 'dsgvo', 'exklusiv', 'unbegrenzt', 'automatisch verlänger', 'hebel']) },
  };
  const step = (who, what) => ({ who, what });
  const UC = [
    { id: 'email', n: 'E-Mail', heads: ['urgency', 'value', 'sentiment', 'safe_to_run'], w: { urgency: 1, value: 1.4, sentiment: 0.3 },
      ch: [[0.5, 'Antworten', [step('LLM', 'schreibt Antwort-Entwurf'), step('Mensch', 'liest und sendet')]], [0.32, 'Heute erledigen', [step('Code', 'setzt Erinnerung 16:00')]], [0, 'Archivieren', [step('Code', 'mail.archive()')]]],
      text: 'Hi, wir würden gern noch diese Woche starten. Budget 3.000 € pro Monat, 12 Mitarbeiter. Kannst du mir bis Freitag ein Angebot schicken?' },
    { id: 'youtube', n: 'YouTube', heads: ['viral_fit', 'novelty', 'effort'], w: { viral_fit: 1.4, novelty: 1, effort: -0.8 },
      ch: [[0.34, 'Video produzieren', [step('LLM', 'schreibt Skript-Gliederung'), step('Code', 'legt Notion-Seite an')]], [0.18, 'Als Short testen', [step('LLM', 'schreibt 3 Hooks'), step('Code', 'plant Upload')]], [-9, 'Parken', [step('Code', 'speichert in ideen.md')]]],
      text: 'Ich habe 30 Tage lang jeden Tag einen KI-Agenten gebaut. Der größte Fehler: zu viel LLM, zu wenig Code.' },
    { id: 'skool', n: 'Community', heads: ['sentiment', 'urgency', 'value'], w: { sentiment: -0.6, urgency: 1, value: 0.8 },
      ch: [[0.25, 'Persönlich antworten', [step('LLM', 'schreibt Antwort'), step('Mensch', 'postet')]], [0.05, 'In die FAQ', [step('Code', 'verlinkt FAQ-Eintrag')]], [-9, 'Liken', [step('Code', 'reagiert mit 👍')]]],
      text: 'Das Modul 3 geht bei mir nicht, das Video ist kaputt und ich bin echt enttäuscht. Kann mir heute jemand helfen?' },
    { id: 'feed', n: 'X-Post', heads: ['viral_fit', 'clarity', 'safe_to_run'], w: { viral_fit: 1.5, clarity: 0.8 }, guard: 'Nie automatisch auf X posten',
      ch: [[0.62, 'Entwurf posten', [step('LLM', 'schreibt 2 Varianten'), step('Mensch', 'wählt und postet')]], [0.42, 'Umschreiben', [step('LLM', 'kürzt auf 1 Hook')]], [-9, 'Verwerfen', [step('Code', 'archiviert Entwurf')]]],
      text: 'Niemand redet darüber: 90 % deiner Agenten brauchen kein LLM für Entscheidungen. 3 Regeln, die mir 12x Kosten gespart haben.' },
    { id: 'meetings', n: 'Meetings', heads: ['value', 'urgency', 'effort'], w: { value: 1.3, urgency: 0.7, effort: -0.5 },
      ch: [[0.32, 'Termin anbieten', [step('Code', 'schickt Kalender-Link'), step('Code', 'blockt 30 min')]], [0.15, 'Async per Mail', [step('LLM', 'schreibt Loom-Skript')]], [-9, 'Freundlich absagen', [step('LLM', 'schreibt Absage'), step('Mensch', 'sendet')]]],
      text: 'Hast du nächste Woche 30 Minuten? Wir wollen mit unserem Team (25 Leute) über ein Partner-Projekt sprechen.' },
    { id: 'clips', n: 'Clips', heads: ['viral_fit', 'clarity', 'novelty'], w: { viral_fit: 1.4, clarity: 0.7, novelty: 0.6 },
      ch: [[0.45, 'Clip schneiden', [step('Code', 'schneidet 00:42–01:10'), step('LLM', 'schreibt Untertitel')]], [-9, 'Parken', [step('Code', 'markiert Zeitstempel')]]],
      text: 'So habe ich in 14 Tagen einen Prototyp gebaut, der 2.000 € im Monat bringt. Schritt 1: Daten zuerst.' },
    { id: 'contracts', n: 'Verträge', heads: ['risk', 'value', 'urgency'], w: { risk: -1.6, value: 0.8, urgency: 0.4 }, guard: 'Verträge unterschreibt nur ein Mensch',
      ch: [[0.2, 'Ablegen + Frist eintragen', [step('Code', 'legt PDF ab'), step('Code', 'setzt Frist im Kalender')]], [-9, 'Mensch prüft', [step('LLM', 'fasst Klauseln zusammen'), step('Mensch', 'entscheidet')]]],
      text: 'Anbei der Vertrag. Laufzeit 24 Monate, automatische Verlängerung, unbegrenzte Haftung. Bitte bis morgen unterschreiben.' },
    { id: 'leads', n: 'Leads', heads: ['lead_quality', 'urgency', 'value'], w: { lead_quality: 1.6, urgency: 0.6, value: 0.8 },
      ch: [[0.42, 'Call buchen', [step('Code', 'schickt Buchungslink'), step('Code', 'crm.stage = "call"')]], [0.22, 'Nurture-Sequenz', [step('Code', 'startet 3-Mail-Sequenz')]], [-9, 'Disqualifizieren', [step('Code', 'crm.stage = "lost"')]]],
      text: 'Wir sind ein Unternehmen mit 40 Mitarbeitern, Budget 5.000 € für eine Demo und den Start im Januar. Geschäftsführer ist dabei.' },
    { id: 'brain', n: 'Brain-Dump', heads: ['clarity', 'value', 'effort'], w: { clarity: 0.8, value: 1, effort: -0.4 },
      ch: [[0.3, 'In Aufgaben zerlegen', [step('LLM', 'zerlegt in 5 Aufgaben'), step('Code', 'legt Tasks an')]], [-9, 'Als Notiz ablegen', [step('Code', 'schreibt inbox.md')]]],
      text: 'Idee: Landingpage neu, Preise testen, Kunde Nordlicht anrufen wegen Angebot, Newsletter schreiben, Steuer bis Freitag.' },
    { id: 'support', n: 'Support', heads: ['urgency', 'sentiment', 'effort'], w: { urgency: 0.8, sentiment: -0.9, effort: 0.6 },
      ch: [[0.32, 'Ticket an Mensch', [step('Code', 'erstellt Ticket P1'), step('Mensch', 'übernimmt')]], [-9, 'Antwort aus FAQ', [step('Code', 'sucht FAQ-Treffer'), step('LLM', 'formuliert kurz')]]],
      text: 'Wie kann ich mein Passwort ändern? Danke!' },
    { id: 'trading', n: 'Trading', heads: ['risk', 'value', 'urgency'], w: { risk: -1.4, value: 1, urgency: 0.5 }, guard: 'Nur Papiergeld',
      ch: [[0.25, 'Paper-Trade', [step('Code', 'executor.paper(order)')]], [-9, 'Halten', [step('Code', 'reporter.hold()')]]],
      text: 'BTC bricht über 85.000 aus, Volumen hoch. Einstieg jetzt mit 10x Hebel?' },
    { id: 'agents', n: 'Agenten', heads: ['effort', 'safe_to_run', 'clarity'], w: { effort: 1.2, clarity: -0.5 },
      ch: [[0.2, 'Opus-Hauptagent', [step('Engine', 'plant mit Opus 5.5'), step('Code', 'verteilt an Subagenten')]], [-9, 'Haiku-Subagent', [step('Code', 'startet Haiku 5.5 Subagent')]]],
      text: 'Finde alle Stellen im Code, an denen wir die alte Billing-API aufrufen, und liste die Dateien auf.' },
  ];
  const ACT = ['LLM', 'Engine', 'Code', 'Mensch'];

  let el, cv, cvs = {}, code, uc = UC[3], demo = true, lastRes = null, hist = [], split = { LLM: 0, Engine: 0, Code: 0, Mensch: 0 }, spend = 0, actions = 0, actLog = [], log, threads = [];

  function evaluate(u, text, override) {
    const t = low(text);
    const v = {};
    for (const h of u.heads) v[h] = clamp(override && override[h] != null ? Number(override[h]) : HEADS[h].f(t) + jit(t, h), 0, 1);
    let num = 0, den = 0;
    for (const [h, w] of Object.entries(u.w)) { num += w * v[h]; den += Math.abs(w); }
    const score = num / den;
    let pick = u.ch[u.ch.length - 1];
    for (const c of u.ch) if (score >= c[0]) { pick = c; break; }
    const ths = u.ch.map((c) => c[0]).filter((x) => x > -5);
    const margin = ths.length ? Math.min(...ths.map((x) => Math.abs(score - x))) : 0.3;
    let conf = clamp(0.6 + margin * 1.6, 0.5, 0.99);
    let choice = pick[1], steps = pick[2].slice(), guarded = null;
    const unsafe = (v.safe_to_run != null && v.safe_to_run < 0.55) || (v.risk != null && v.risk > 0.5);
    if (unsafe) { guarded = 'Sicherheitskopf: nicht automatisch'; choice = 'Mensch entscheidet'; steps = [step('LLM', 'fasst zusammen'), step('Mensch', 'entscheidet')]; conf = Math.max(conf, 0.9); }
    if (!steps.some((s) => s.who === 'Mensch') && u.guard && /post|Trade/i.test(choice)) steps.push(step('Mensch', 'gibt frei'));
    return { v, score, conf, choice, steps: [step('Engine', `wählt: ${choice}`), ...steps], guarded };
  }

  function init(root) {
    el = root;
    el.innerHTML = `
      <div class="vhead">
        <div><h1>Entscheider · <em>12 Anwendungsfälle</em></h1>
        <p>Ein Zustand, mehrere billige Fragen, eine Entscheidung. Jeder Kopf beantwortet eine Frage mit einer Zahl. Die Engine wählt die Aktion. Das LLM schreibt nur, wenn Text gebraucht wird.</p></div>
        <div class="ctrls"><label class="btn"><input type="checkbox" id="dzDemo" checked> Demo-Modus</label></div>
      </div>
      <div class="grid">
        <div class="panel s4"><div class="ph">Anwendungsfall <span class="r" id="dzUcN">04 / 12</span></div><div class="pb">
          <div class="uc" id="dzUc">${UC.map((u, i) => `<button data-i="${i}" aria-pressed="${u === uc}"><span>${String(i + 1).padStart(2, '0')}</span>${u.n}</button>`).join('')}</div>
          <label class="fld" for="dzText" style="margin-top:10px">Eingabe</label>
          <textarea id="dzText" rows="5"></textarea>
          <div class="ctrls" style="margin-top:8px"><button class="btn pri" id="dzRun">Entscheiden</button><button class="btn ai ai-only" id="dzAi">Mit Claude bewerten</button></div>
          <p class="note" id="dzNote">Lokal: Stichwort-Köpfe, 0 Tokens. Mit Claude: echte Bewertung und echter Entwurf.</p>
        </div></div>
        <div class="panel s8 hero" id="dzHero"><canvas class="bg" id="dzCv"></canvas>
          <div class="ov"><div class="ph"><span>Fall <b id="dzCase">X-Post</b> · Score <b id="dzScore">–</b></span><span class="r">Zustand → Fragen → Entscheidung → Aktion</span></div>
          <div class="dz-cols" id="dzCols"><div class="dz-col"><h3>Köpfe</h3><div id="dzHeads"></div></div><div class="dz-col"><h3>Entscheidung</h3><div id="dzChoice"></div></div><div class="dz-col"><h3>Aktionen</h3><div id="dzSteps"></div></div></div></div>
        </div>
        <div class="panel s4"><div class="ph">Parallele Fragen <span class="r">ein Zustand</span></div><div class="pb" id="dzQa"></div></div>
        <div class="panel s4"><div class="ph">Konfidenz-Stream <span class="r">letzte 30 Entscheidungen</span></div><div class="pb"><canvas class="chart" id="dzStream"></canvas><div class="sub">Linie = Konfidenz · Balken = Score</div></div></div>
        <div class="panel s4 split"><div class="ph">Die Aufteilung <span class="r">wer macht was</span></div><div class="pb" id="dzSplit"></div></div>
        <div class="panel s6"><div class="ph"><span>chief.py</span><span class="r">aus dem Fall erzeugt</span></div><div class="code" id="dzCode"></div></div>
        <div class="panel s3"><div class="ph">Leitplanken <span class="r">immer an</span></div><div class="pb">
          <div class="row"><span>Budget</span><b id="dzSpend">0 $ / 1,00 $</b></div><div class="meter a2"><i id="dzSpendBar" style="width:0"></i></div>
          <div class="row" style="margin-top:6px"><span>Code-Aktionen / Min</span><b id="dzAct">0 / 10</b></div><div class="meter"><i id="dzActBar" style="width:0"></i></div>
          <ul class="audit" style="margin-top:8px"><li>Nie automatisch auf X posten</li><li>Externes nur mit Freigabe</li><li>Fortschritt gespeichert</li></ul></div></div>
        <div class="panel s3"><div class="ph">Log</div><div class="log" id="dzLog"></div></div>
      </div>`;
    log = new AC.Log($('#dzLog', el), 30);
    code = AC.code($('#dzCode', el), '');
    cv = AC.canvas($('#dzCv', el), () => buildThreads());
    cvs.stream = AC.canvas($('#dzStream', el));
    $('#dzUc', el).onclick = (e) => { const b = e.target.closest('button'); if (!b) return; stopDemo(); select(UC[+b.dataset.i]); run(); };
    $('#dzText', el).oninput = stopDemo;
    $('#dzDemo', el).onchange = (e) => (demo = e.target.checked);
    $('#dzRun', el).onclick = () => { stopDemo(); run(); };
    $('#dzAi', el).onclick = () => { stopDemo(); askClaude(); };
    select(uc); run();
  }
  const stopDemo = () => { demo = false; $('#dzDemo', el).checked = false; };
  function select(u) {
    uc = u;
    $$('#dzUc button', el).forEach((b) => b.setAttribute('aria-pressed', UC[+b.dataset.i] === u));
    $('#dzUcN', el).textContent = `${String(UC.indexOf(u) + 1).padStart(2, '0')} / 12`;
    $('#dzText', el).value = u.text;
    code.set(genCode(u));
  }
  function genCode(u) {
    return [`# ${u.n}: ${u.heads.length} Köpfe, 1 Entscheidung`, 'from engine import heads, choose', '', `def decide(state):`,
      ...u.heads.map((h) => `    ${h} = heads.${h}(state)` + ' '.repeat(Math.max(1, 16 - h.length)) + `# ${HEADS[h].q}`),
      `    score = ${Object.entries(u.w).map(([h, w]) => `${w}*${h}`).join(' + ')}`,
      ...u.ch.map((c, i) => (c[0] > -5 ? `    ${i ? 'elif' : 'if'} score >= ${c[0]}:\n        return "${c[1]}"` : `    return "${c[1]}"`)).join('\n').split('\n'),
      '', `# Leitplanke: ${u.guard || 'Externes nur mit Freigabe'}`].join('\n');
  }

  function run(override, draft) {
    const text = $('#dzText', el).value.trim() || uc.text;
    const r = evaluate(uc, text, override);
    lastRes = r;
    const lat = override ? 0 : Math.round(rand(2, 9));
    AC.decision({ latency: lat, conf: r.conf, cost: 0 });
    hist.push({ conf: r.conf, score: r.score }); if (hist.length > 30) hist.shift();
    for (const s of r.steps) split[s.who] = (split[s.who] || 0) + 1;
    const nowS = performance.now() / 1000;
    actLog = actLog.filter((x) => nowS - x < 60);
    r.steps.filter((s) => s.who === 'Code').forEach(() => actLog.push(nowS));
    actions = Math.min(10, actLog.length);
    $('#dzCase', el).textContent = uc.n; $('#dzScore', el).textContent = fmt(r.score, 2);
    $('#dzHeads', el).innerHTML = uc.heads.map((h) => `<div class="card run" data-k="${h}"><div class="ch"><span>${h}</span><span>${fmt(r.v[h], 2)}</span></div><div class="cb">${HEADS[h].q}</div><div class="cf"><span>Kopf</span><span class="meter"><i style="width:${r.v[h] * 100}%"></i></span></div></div>`).join('');
    $('#dzChoice', el).innerHTML = `<div class="card ${r.guarded ? 'gate' : 'run'}" data-k="choice"><div class="ch"><span>choice · action</span><span>${fmt(r.conf, 2)}</span></div><div class="cb"><div class="big" style="font-size:20px">${esc(r.choice)}</div>${r.guarded ? `<p class="note">${esc(r.guarded)}</p>` : ''}${uc.guard ? `<p class="note">Leitplanke: ${esc(uc.guard)}</p>` : ''}</div></div>`;
    $('#dzSteps', el).innerHTML = r.steps.map((s, i) => `<div class="card ${s.who === 'Mensch' ? 'gate' : ''}" data-k="s${i}"><div class="ch"><span>${s.who}</span><span class="pill ${s.who === 'Mensch' ? 'acc2' : s.who === 'LLM' ? 'acc' : ''}">${s.who === 'LLM' ? 'schreibt' : s.who === 'Engine' ? 'entscheidet' : s.who === 'Code' ? 'handelt' : 'gibt frei'}</span></div><div class="cb">${esc(s.what)}${s.who === 'LLM' ? `<p class="note">${draft ? esc(draft) : 'Vorlage. Mit „Mit Claude bewerten“ entsteht ein echter Text.'}</p>` : ''}</div></div>`).join('');
    $('#dzQa', el).innerHTML = uc.heads.map((h) => `<div class="qa"><span>${HEADS[h].q}</span><span class="meter ${h === 'safe_to_run' || h === 'risk' ? 'a2' : ''}"><i style="width:${r.v[h] * 100}%"></i></span><span class="t">${fmt(r.v[h], 2)}</span></div>`).join('') + `<p class="note">${uc.heads.length} Antworten → 1 billige Entscheidung · ${override ? 'Werte von Claude' : 'lokal, 0 Tokens'}</p>`;
    const tot = Object.values(split).reduce((a, b) => a + b, 0) || 1;
    $('#dzSplit', el).innerHTML = ACT.map((a) => `<div class="row"><span>${a === 'LLM' ? 'LLM schreibt' : a === 'Engine' ? 'Engine entscheidet' : a === 'Code' ? 'Code handelt' : 'Mensch gibt frei'}</span><span class="meter ${a === 'Mensch' ? 'a2' : ''}"><i style="width:${(split[a] / tot) * 100}%"></i></span><b class="t">${split[a]}</b></div>`).join('');
    $('#dzSpend', el).textContent = `${usd(spend, 3)} / 1,00 $`; $('#dzSpendBar', el).style.width = Math.min(100, spend * 100) + '%';
    $('#dzAct', el).textContent = `${actions} / 10`; $('#dzActBar', el).style.width = actions * 10 + '%';
    log.add(uc.id, r.choice, fmt(r.conf, 2), r.guarded ? 'hi' : '');
    const ln = code.el.children.length; code.mark(Math.min(ln - 1, 4 + uc.heads.length + 1 + 2 * Math.max(0, uc.ch.findIndex((c) => c[1] === r.choice))));
    requestAnimationFrame(buildThreads);
  }

  async function askClaude() {
    const text = $('#dzText', el).value.trim() || uc.text, b = $('#dzAi', el);
    b.disabled = true; $('#dzNote', el).textContent = 'Claude bewertet…';
    try {
      const needs = evaluate(uc, text).steps.some((s) => s.who === 'LLM');
      const res = await AC.ai.json(`Du bist ein Bewertungs-Kopf in einer Entscheidungs-Engine. Fall: ${uc.n}.
Beantworte jede Frage mit einer Zahl von 0 bis 1:
${uc.heads.map((h) => `- ${h}: ${HEADS[h].q}`).join('\n')}
${needs ? 'Schreib außerdem einen kurzen deutschen Entwurf (max. 280 Zeichen), der zur Eingabe passt.' : ''}
Antworte nur mit JSON: {"heads": {${uc.heads.map((h) => `"${h}": 0.0`).join(', ')}}, "draft": "<Text oder leer>"}

Eingabe:
"""
${text.slice(0, 4000)}
"""`);
      const hv = res && typeof res.heads === 'object' ? res.heads : null;
      if (!hv) throw { code: 'invalid_json' };
      spend += 0.0002; // grobe Schätzung pro kurzer Bewertung
      run(hv, typeof res.draft === 'string' && res.draft.trim() ? res.draft.trim() : null);
      $('#dzNote', el).textContent = 'Werte und Entwurf kommen von Claude. Die Entscheidung trifft weiter die Engine.';
    } catch (e) { $('#dzNote', el).textContent = AC.ai.msg(e); }
    finally { b.disabled = false; }
  }

  // ---------- glowing threads between cards ----------
  function buildThreads() {
    const hero = $('#dzHero', el); if (!hero || !hero.offsetWidth) return;
    const hr = hero.getBoundingClientRect(), pos = (n) => { const r = n.getBoundingClientRect(); return { l: r.left - hr.left, r: r.right - hr.left, y: r.top - hr.top + r.height / 2 }; };
    const heads = $$('#dzHeads .card', el).map(pos), ch = $('#dzChoice .card', el), steps = $$('#dzSteps .card', el).map(pos);
    threads = [];
    if (!ch) return;
    const c = pos(ch);
    const wide = hero.offsetWidth > 700;
    for (const h of heads) for (let k = 0; k < 6; k++) threads.push(wide ? { a: [h.r, h.y + rand(-8, 8)], b: [c.l, c.y + rand(-14, 14)] } : { a: [h.l + 30, h.y], b: [c.l + 30, c.y] });
    for (const s of steps) for (let k = 0; k < 6; k++) threads.push(wide ? { a: [c.r, c.y + rand(-14, 14)], b: [s.l, s.y + rand(-8, 8)] } : { a: [c.l + 40, c.y], b: [s.l + 40, s.y] });
    threads.forEach((t) => (t.o = rand(1)));
  }
  function draw(t, dt) {
    const { ctx, w, h } = cv, C = AC.col; ctx.clearRect(0, 0, w, h);
    ctx.globalCompositeOperation = 'lighter';
    for (const th of threads) {
      const [ax, ay] = th.a, [bx, by] = th.b, mx = (ax + bx) / 2;
      ctx.strokeStyle = AC.alpha(C.accent, 0.16); ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(ax, ay); ctx.bezierCurveTo(mx, ay + rand(-1, 1), mx, by, bx, by); ctx.stroke();
      th.o = (th.o + dt * 0.35) % 1; const u = th.o, iu = 1 - u;
      const x = iu * iu * iu * ax + 3 * iu * iu * u * mx + 3 * iu * u * u * mx + u * u * u * bx, y = iu * iu * iu * ay + 3 * iu * iu * u * ay + 3 * iu * u * u * by + u * u * u * by;
      const g = ctx.createRadialGradient(x, y, 0, x, y, 7); g.addColorStop(0, AC.alpha(C.accent, 0.9)); g.addColorStop(1, AC.alpha(C.accent, 0));
      ctx.fillStyle = g; ctx.fillRect(x - 7, y - 7, 14, 14);
    }
    ctx.globalCompositeOperation = 'source-over';
    // stream chart
    const o = cvs.stream; if (o && hist.length) {
      const c2 = o.ctx; c2.clearRect(0, 0, o.w, o.h);
      const bw = o.w / 30;
      hist.forEach((p, i) => { const bh = clamp((p.score + 0.5) / 1.5, 0, 1) * o.h * 0.6; c2.fillStyle = AC.alpha(C['accent-2'], 0.35); c2.fillRect(i * bw + 1, o.h - bh, bw - 2, bh); });
      c2.strokeStyle = C.accent; c2.lineWidth = 1.5; c2.beginPath();
      hist.forEach((p, i) => { const x = i * bw + bw / 2, y = o.h - ((p.conf - 0.4) / 0.6) * o.h; i ? c2.lineTo(x, y) : c2.moveTo(x, y); }); c2.stroke();
    }
  }

  let dAcc = 0, bAcc = 0;
  AC.register('decide', {
    init,
    enter() { cv.fit(); buildThreads(); },
    frame(t, dt) {
      if (demo) { dAcc += dt; if (dAcc > 4.5) { dAcc = 0; select(UC[(UC.indexOf(uc) + 1) % UC.length]); run(); } }
      bAcc += dt; if (bAcc > 1.5) { bAcc = 0; buildThreads(); }
      draw(t, dt);
    },
  });
})();
