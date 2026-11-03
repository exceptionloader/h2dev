/* 03 Inbox-Router – a local classifier routes mail; above the gate code acts, below it a human decides and the engine learns. */
(() => {
  const { $, $$, esc, rand, randi, pick, clamp, fmt, usd } = AC;

  const LABELS = {
    phishing: { n: 'Phishing', who: 'Code', act: 'mail.move("quarantine")', kw: { passwort: 2, password: 2, 'konto gesperrt': 3, 'konto wird': 1.5, verifizieren: 2, verify: 2, 'klicken sie': 1.5, 'click here': 1.5, login: 1, gewinn: 1.5, wallet: 1.2, sicherheitswarnung: 2, 'ungewöhnliche aktivität': 2.5, '24 stunden': 1.5, 'gift card': 2, gutschein: 1, seed: 1.5, 'zugangsdaten': 2 } },
    lead: { n: 'Lead', who: 'Code', act: 'crm.nurture(mail)', kw: { angebot: 1.5, preis: 1.5, budget: 2, interesse: 1.5, termin: 1, demo: 1.8, mitarbeiter: 1, unternehmen: 1, kosten: 1, pricing: 1.5, zusammenarbeit: 1.5, projekt: 1, agentur: 1, lizenz: 1.2, 'pro monat': 1.2 } },
    worth_reply: { n: 'Antwort lohnt', who: 'LLM', act: 'llm.write_reply(mail)', kw: { frage: 1.5, 'kurze frage': 2, feedback: 1.5, idee: 1, meinung: 1.2, hilfe: 1, 'könntest du': 1.4, 'kannst du': 1.2, 'hast du': 1, 'würdest du': 1.2, podcast: 1.2, interview: 1.2 } },
    billing: { n: 'Rechnung', who: 'Code', act: 'accounting.forward(mail)', kw: { rechnung: 2.5, invoice: 2.5, zahlung: 1.5, 'fällig': 1.5, mahnung: 2, betrag: 1, 'überweisung': 1.5, iban: 1.2, beleg: 1.5, gutschrift: 1.5 } },
    newsletter: { n: 'Newsletter', who: 'Code', act: 'mail.archive()', kw: { newsletter: 3, abmelden: 2.5, unsubscribe: 2.5, 'wöchentlich': 1, neuigkeiten: 1.5, 'diese woche': 1.2, rabatt: 1.2, webinar: 1 } },
    risk: { n: 'Risiko', who: 'Mensch', act: 'human.review(reason="irreversibel")', kw: { anwalt: 3, 'kündigung': 2.5, beschwerde: 2, rechtlich: 2.5, frist: 1.5, vertrag: 1.2, dsgvo: 2.5, 'löschung': 1.5, schadenersatz: 3, abmahnung: 3, chargeback: 2.5 } },
  };
  const KEYS = Object.keys(LABELS);
  const NAMES = ['Lena', 'Jonas', 'Mia', 'Paul', 'Sara', 'Ali', 'Nina', 'Tim', 'Emre', 'Lea'];
  const CO = ['Nordlicht GmbH', 'Pixelwerk', 'Haus & Hof AG', 'Brotzeit Studio', 'Kraftwerk Media', 'Lumen Labs'];
  const T = [
    ['phishing', 'sicherheit@paypa1-support.xyz', 'Sicherheitswarnung: Konto gesperrt', 'Wir haben ungewöhnliche Aktivität festgestellt. Klicken Sie hier und verifizieren Sie Ihr Passwort innerhalb von 24 Stunden: http://paypa1-login.xyz'],
    ['phishing', 'wallet@metamask-help.top', 'Ihre Wallet wird deaktiviert', 'Bitte bestätigen Sie Ihre Seed Phrase, sonst wird Ihr Konto gesperrt. Login: http://mm-verify.top'],
    ['phishing', 'ceo@firma-mail.ru', 'Dringend: Gift Cards kaufen', 'Ich sitze im Meeting. Kauf bitte 5 Gift Cards à 100 € und schick mir die Codes. Dringend.'],
    ['phishing', 'it-team@micros0ft365.com', 'Passwort läuft heute ab', 'Ihr Passwort läuft in 24 Stunden ab. Zugangsdaten hier erneuern: http://ms365-reset.com/login'],
    ['lead', '{n}@{d}.de', 'Anfrage: Chatbot für unseren Kundenservice', 'Hallo, wir sind ein Unternehmen mit 40 Mitarbeitern und haben Interesse an einem Chatbot. Budget liegt bei ca. {a} € pro Monat. Wann passt ein Termin für eine Demo?'],
    ['lead', '{n}@{d}.com', 'Preis für 3 Lizenzen?', 'Hi, was kostet euer Tool für ein Team von 3 Leuten? Wir würden gern nächste Woche starten. Gibt es ein Angebot?'],
    ['lead', 'info@{d}.de', 'Zusammenarbeit Agentur', 'Wir sind eine Agentur und suchen einen Partner für ein Projekt im Q1. Budget {a} €. Habt ihr Kapazität?'],
    ['lead', '{n}@{d}.io', 'Demo anfragen', 'Können wir eine Demo bekommen? Wir vergleichen gerade drei Anbieter, Pricing ist wichtig.'],
    ['worth_reply', '{n}@gmail.com', 'Kurze Frage zu deinem Video', 'Hey, kurze Frage: Welches Mikro nutzt du in deinem letzten Video? Und hast du Feedback zu meinem Setup?'],
    ['worth_reply', '{n}@{d}.de', 'Podcast-Interview?', 'Hi! Würdest du als Gast in unseren Podcast kommen? Thema Automatisierung. Wir hätten im Dezember Zeit.'],
    ['worth_reply', '{n}@web.de', 'Idee für ein Feature', 'Ich hätte eine Idee: Könntest du einen Export als CSV einbauen? Wäre super hilfreich. Was ist deine Meinung?'],
    ['billing', 'buchhaltung@{d}.de', 'Rechnung Nr. 2026-{a}', 'Anbei unsere Rechnung über {a},00 €. Zahlung fällig in 14 Tagen. IBAN DE12 3456 …'],
    ['billing', 'billing@cloudhost.com', 'Your invoice for October', 'Your invoice is ready. Amount due: {a} USD. Thank you for your payment.'],
    ['billing', 'mahnung@{d}.de', '1. Mahnung', 'Leider konnten wir zu Rechnung 2026-{a} noch keine Überweisung feststellen. Bitte begleichen Sie den Betrag.'],
    ['newsletter', 'news@{d}.de', 'Neuigkeiten diese Woche', 'Unser wöchentlicher Newsletter: 5 Tools, 2 Webinare, 20 % Rabatt. Abmelden jederzeit möglich.'],
    ['newsletter', 'digest@devweekly.io', 'Dev Weekly #{a}', 'Top stories this week. Unsubscribe here. Newsletter preferences.'],
    ['risk', 'kanzlei@{d}-recht.de', 'Abmahnung wegen Bildrechten', 'Im Auftrag unserer Mandantin mahnen wir Sie wegen der Nutzung eines Bildes ab. Frist: 7 Tage. Schadenersatz {a} €.'],
    ['risk', '{n}@{d}.de', 'Kündigung und DSGVO-Löschung', 'Hiermit kündige ich meinen Vertrag und verlange die Löschung meiner Daten nach DSGVO. Bitte bestätigen.'],
    ['risk', 'support@{d}.com', 'Beschwerde + Chargeback', 'Ich bin sehr unzufrieden und habe einen Chargeback bei meiner Bank eingeleitet. Bitte rechtlich prüfen lassen.'],
    ['worth_reply', '{n}@{d}.de', 'Hilfe beim Setup', 'Hey, kannst du mir kurz helfen? Ich bekomme den Webhook nicht zum Laufen. Frage: muss ich HMAC prüfen?'],
    ['lead', '{n}@{d}.ch', 'Interesse an Automatisierung', 'Wir haben Interesse, unsere Angebotsprozesse zu automatisieren. Kosten? Wir sind 12 Mitarbeiter.'],
  ];
  const CODE = `def route(mail):
    label, conf = engine.classify(mail)    # 0 Tokens
    if label == "risk":
        return human.review(mail, reason="irreversibel")
    if conf < GATE:
        return human.review(mail)          # unsicher: Mensch
    if label == "phishing":
        return mail.move("quarantine")     # kein LLM nötig
    if label == "worth_reply":
        draft = llm.write_reply(mail)      # nur wenn es sich lohnt
        return human.send(draft)
    if label == "lead":
        return crm.nurture(mail)
    if label == "billing":
        return accounting.forward(mail)
    return mail.archive()`;
  const LINE = { risk: 3, human: 5, phishing: 7, worth_reply: 10, lead: 12, billing: 14, newsletter: 15 };
  const DRAFT_COST = (800 * 0.1 + 300 * 0.5) / 1e6; // Haiku 5.5: 800 in, 300 out

  let el, cv, cvs = {}, log, code, S, running = true, gate = 0.85, field = [], learned = AC.store.get('inbox.learned', {});

  const fresh = () => ({ n: 0, auto: 0, human: 0, correct: 0, judged: 0, drafts: 0, tokensOut: 0, spend: 0, lat: [], pts: [], queue: [], counts: Object.fromEntries(KEYS.map((k) => [k, { n: 0, c: 0 }])), cur: null, actions: [] });

  // ---------- classifier (local, no tokens) ----------
  const tokens = (s) => s.toLowerCase().match(/[a-zäöüß0-9]{4,}/g) || [];
  function classify(mail) {
    const txt = (mail.from + ' ' + mail.subj + ' ' + mail.body).toLowerCase();
    const sc = {}, hits = {};
    for (const k of KEYS) {
      sc[k] = 0.2; hits[k] = [];
      for (const [w, v] of Object.entries(LABELS[k].kw)) if (txt.includes(w)) { sc[k] += v; hits[k].push(w); }
    }
    const link = /https?:\/\//.test(txt), badTld = /\.(xyz|top|ru|click)\b/.test(mail.from) || /[0-9]/.test(mail.from.split('@')[1] || '');
    if (link && (sc.phishing > 1)) { sc.phishing += 1.5; hits.phishing.push('Link + Druck'); }
    if (badTld) { sc.phishing += 2; hits.phishing.push('verdächtige Domain'); }
    for (const t of tokens(txt)) for (const k of KEYS) if (learned[k] && learned[k][t]) { sc[k] += Math.min(1.2, learned[k][t] * 0.6); }
    const mx = Math.max(...KEYS.map((k) => sc[k]));
    const ex = KEYS.map((k) => Math.exp((sc[k] - mx) / 1.6)), sum = ex.reduce((a, b) => a + b, 0);
    const probs = Object.fromEntries(KEYS.map((k, i) => [k, ex[i] / sum]));
    const label = KEYS.reduce((a, b) => (probs[b] > probs[a] ? b : a));
    // confidence = how clearly the winner beats the runner-up
    const sorted = KEYS.map((k) => sc[k]).sort((a, b) => b - a);
    const conf = 0.5 + 0.49 * Math.tanh((sorted[0] - sorted[1]) / 3.2);
    return { label, conf, probs, hits: hits[label] };
  }
  function learn(mail, label) {
    for (const t of new Set(tokens(mail.subj + ' ' + mail.body))) { learned[label] = learned[label] || {}; learned[label][t] = (learned[label][t] || 0) + 1; }
    AC.store.set('inbox.learned', learned);
  }

  const fill = (s) => s.replace(/\{n\}/g, pick(NAMES).toLowerCase()).replace(/\{d\}/g, pick(CO).toLowerCase().replace(/[^a-z]/g, '').slice(0, 10)).replace(/\{a\}/g, () => String(randi(120, 4800)));
  const makeMail = () => { const [truth, from, subj, body] = pick(T); return { id: ++S.n, truth, from: fill(from), subj: fill(subj), body: fill(body) }; };

  function process(mail) {
    const r = classify(mail), lat = randi(4, 38) + (r.label === 'worth_reply' ? 0 : 0);
    mail.r = r; S.cur = mail;
    const now = S.t || 0;
    S.actions = S.actions.filter((x) => now - x < 60);
    let who, line, cls = '';
    const guard = S.actions.length >= 30;
    if (r.label === 'risk') { who = 'human'; line = LINE.risk; }
    else if (r.conf < gate || guard) { who = 'human'; line = LINE.human; }
    else { who = LABELS[r.label].who === 'LLM' ? 'llm' : 'code'; line = LINE[r.label]; }
    code.mark(line);
    S.counts[r.label].n++; S.counts[r.label].c += r.conf;
    AC.push(S.lat, lat, 80); AC.push(S.pts, { c: r.conf, h: who === 'human' }, 90);
    let cost = 0;
    if (who === 'human') {
      S.human++; S.queue.unshift(mail); if (S.queue.length > 8) S.queue.pop();
      log.add('human', `${mail.subj}`, `${LABELS[r.label].n} ${fmt(r.conf, 2)}${guard ? ' · Guard' : ''}`, 'hi');
    } else {
      S.auto++; S.actions.push(now); S.judged++; if (r.label === mail.truth) S.correct++;
      if (who === 'llm') { S.drafts++; S.tokensOut += 300; cost = DRAFT_COST; S.spend += cost; cls = 'ok'; }
      log.add(who === 'llm' ? 'llm' : 'code', `${LABELS[r.label].act}`, `${LABELS[r.label].n} ${fmt(r.conf, 2)}`, cls);
    }
    AC.decision({ latency: lat, conf: r.conf, cost });
    field.push({ x: 0, y: r.conf, label: r.label, h: who === 'human', subj: mail.subj });
    if (field.length > 24) field.shift();
    renderQueue(); renderCur();
  }

  // ---------- UI ----------
  function init(root) {
    el = root;
    el.innerHTML = `
      <div class="vhead">
        <div><h1>Inbox-Router · <em>Konfidenzfeld</em></h1>
        <p>Jede Mail bekommt ein Label und eine Konfidenz, ganz ohne LLM. Über dem Gate handelt Code, darunter entscheidest du. Deine Korrekturen bringen dem Router neue Wörter bei.</p></div>
        <div class="ctrls">
          <label class="fld" style="width:150px">Gate <b id="ibGateV">0,85</b><input type="range" id="ibGate" min="0.5" max="0.99" step="0.01" value="0.85"></label>
          <button class="btn pri" id="ibRun">Pause</button><button class="btn" id="ibReset">Neu</button><button class="btn" id="ibForget">Gelerntes löschen</button>
        </div>
      </div>
      <div class="grid">
        <div class="panel s8 hero"><canvas class="bg" id="ibCv"></canvas></div>
        <div class="panel s4"><div class="ph">Aktuelle Mail <span class="r" id="ibCurN">#0</span></div><div class="pb" id="ibCur"></div>
          <div class="ph" style="border-top:1px solid var(--line)">Router <span class="r">Label · Anzahl · Ø conf</span></div><div class="pb flush" id="ibRouter"></div></div>
        <div class="panel s5"><div class="ph"><span>router.py</span><span class="r">markiert = aktuelle Entscheidung</span></div><div class="code" id="ibCode"></div></div>
        <div class="panel s3"><div class="ph">Latenz <span class="r" id="ibP50">p50 –</span></div><div class="pb"><canvas class="chart" id="ibLat"></canvas><div class="sub">lokal, ohne Netzwerk</div></div></div>
        <div class="panel s4"><div class="ph">Konfidenz <span class="r">Gate-Linie</span></div><div class="pb"><canvas class="chart" id="ibConf"></canvas><div class="sub">gefüllt = Code · hohl = Mensch</div></div></div>
        <div class="panel s6"><div class="ph">Wartet auf dich <span class="r" id="ibQn">0</span></div><div class="pb" id="ibQueue"></div></div>
        <div class="panel s6"><div class="ph">Eigene Mail testen <span class="r">bleibt im Browser</span></div><div class="pb">
          <label class="fld" for="ibText">Betreff und Text einfügen</label>
          <textarea id="ibText" rows="6">Betreff: Ihr Konto wird gesperrt
Sehr geehrter Kunde, wir haben ungewöhnliche Aktivität festgestellt. Bitte verifizieren Sie Ihr Passwort innerhalb von 24 Stunden: http://secure-bank-login.xyz</textarea>
          <div class="ctrls" style="margin-top:8px"><button class="btn pri" id="ibTest">Lokal klassifizieren</button><button class="btn ai ai-only" id="ibAi">Mit Claude prüfen</button><button class="btn" id="ibFeed">In den Stream</button></div>
          <div id="ibTestOut" style="margin-top:10px"></div>
        </div></div>
        <div class="panel s3"><div class="ph">Tokens out</div><div class="pb"><div class="big" id="ibTok">0</div><div class="sub">Engine generiert nie. Nur Antwort-Entwürfe kosten Tokens.</div></div></div>
        <div class="panel s3"><div class="ph">Guard</div><div class="pb"><div class="row"><span>Budget</span><b id="ibSpend">0 $ / 1,00 $</b></div><div class="row"><span>Aktionen / Min</span><b id="ibAct">0 / 30</b></div><div class="row"><span>Senden</span><b>nur durch Mensch</b></div></div></div>
        <div class="panel s3"><div class="ph">Treffer <span class="r">automatisch erledigt</span></div><div class="pb"><div class="big acc" id="ibAcc">–</div><div class="sub" id="ibAccSub">0 Mails automatisch</div></div></div>
        <div class="panel s3"><div class="ph">Gelernt</div><div class="pb"><div class="big" id="ibLearn">0</div><div class="sub">Wörter aus deinen Korrekturen</div></div></div>
      </div>`;
    log = { add: () => {} };
    code = AC.code($('#ibCode', el), CODE);
    cv = AC.canvas($('#ibCv', el), seedField);
    cvs.lat = AC.canvas($('#ibLat', el)); cvs.conf = AC.canvas($('#ibConf', el));
    $('#ibGate', el).oninput = (e) => { gate = +e.target.value; $('#ibGateV', el).textContent = fmt(gate, 2); };
    $('#ibRun', el).onclick = () => { running = !running; $('#ibRun', el).textContent = running ? 'Pause' : 'Weiter'; };
    $('#ibReset', el).onclick = reset;
    $('#ibForget', el).onclick = () => { learned = {}; AC.store.set('inbox.learned', learned); renderStats(); AC.toast('Gelernte Wörter gelöscht.'); };
    $('#ibQueue', el).onclick = (e) => {
      const b = e.target.closest('button[data-l]'); if (!b) return;
      const m = S.queue.find((x) => x.id === +b.dataset.m); if (!m) return;
      const lab = b.dataset.l; learn(m, lab); S.queue = S.queue.filter((x) => x !== m);
      AC.toast(`Als ${LABELS[lab].n} markiert. Der Router hat dazugelernt.`);
      renderQueue(); renderStats();
    };
    $('#ibTest', el).onclick = () => showTest(classify(parseText()), null);
    $('#ibFeed', el).onclick = () => { const m = parseText(); m.id = ++S.n; m.truth = classify(m).label; process(m); AC.toast('Mail in den Stream gelegt.'); };
    $('#ibAi', el).onclick = askClaude;
    reset();
  }
  const parseText = () => {
    const raw = $('#ibText', el).value.trim(); const m = raw.match(/^betreff:\s*(.*)$/im);
    return { from: 'du@test.local', subj: m ? m[1] : raw.split('\n')[0].slice(0, 80), body: raw };
  };
  function showTest(r, ai) {
    const L = LABELS[r.label];
    $('#ibTestOut', el).innerHTML = `
      <div class="row"><span>Lokal</span><b>${L.n} · ${fmt(r.conf, 2)}</b></div>
      <div class="row"><span>Aktion</span><span>${r.label === 'risk' || r.conf < gate ? 'human.review(mail)' : esc(L.act)}</span></div>
      <div class="row"><span>Signale</span><span>${r.hits.length ? esc(r.hits.join(', ')) : 'keine Stichwörter'}</span></div>
      ${KEYS.map((k) => `<div class="qa"><span>${LABELS[k].n}</span><span class="meter"><i style="width:${r.probs[k] * 100}%"></i></span><span class="t">${fmt(r.probs[k], 2)}</span></div>`).join('')}
      <div id="ibAiOut">${ai || ''}</div>`;
  }
  async function askClaude() {
    const m = parseText(), local = classify(m);
    showTest(local, '<p class="note">Claude denkt nach…</p>');
    const b = $('#ibAi', el); b.disabled = true;
    try {
      const res = await AC.ai.json(`Du bist ein E-Mail-Router. Ordne die E-Mail genau einem Label zu: ${KEYS.join(', ')}.
Bedeutung: phishing = Betrug/Datenklau, lead = Kaufinteresse, worth_reply = persönliche Antwort lohnt sich, billing = Rechnung/Zahlung, newsletter = Massenmail, risk = rechtlich/irreversibel, Mensch muss ran.
Antworte nur mit JSON: {"label": "<eins der Labels>", "confidence": <0 bis 1>, "reason": "<ein kurzer deutscher Satz>"}

E-Mail:
"""
${m.body.slice(0, 6000)}
"""`);
      const lab = KEYS.includes(res && res.label) ? res.label : null;
      const conf = clamp(Number(res && res.confidence) || 0, 0, 1);
      const html = lab ? `<div class="panel" style="margin-top:10px"><div class="ph"><span class="ai-chip">Claude</span><b>${LABELS[lab].n} · ${fmt(conf, 2)}</b></div><div class="pb">${esc(String(res.reason || ''))}<p class="note">${lab === local.label ? 'Gleiches Ergebnis wie der lokale Router.' : 'Anders als lokal. Klick unten auf das richtige Label, damit der Router lernt.'}</p><div class="mail mx" style="border:0;padding:0">${KEYS.map((k) => `<button class="btn sm" data-learn="${k}">${LABELS[k].n}</button>`).join('')}</div></div></div>` : '<p class="note">Claude hat kein gültiges Label geliefert.</p>';
      showTest(local, html);
      $$('#ibAiOut [data-learn]', el).forEach((x) => (x.onclick = () => { learn(m, x.dataset.learn); renderStats(); AC.toast(`Gelernt: ${LABELS[x.dataset.learn].n}.`); }));
    } catch (e) {
      showTest(local, `<p class="note">${esc(AC.ai.msg(e))}</p>`);
    } finally { b.disabled = false; }
  }
  function reset() { S = fresh(); S.t = 0; const bg = field.bg; field = []; field.bg = bg; renderQueue(); renderCur(); renderStats(); }

  function renderCur() {
    const m = S.cur;
    if (!m) { $('#ibCur', el).innerHTML = '<p class="note">Gleich kommt die erste Mail.</p>'; return; }
    const r = m.r;
    $('#ibCurN', el).textContent = '#' + m.id;
    $('#ibCur', el).innerHTML = `<div class="mail"><div class="mh"><span>${esc(m.from)}</span></div><div class="ms">${esc(m.subj)}</div><div>${esc(m.body.slice(0, 160))}${m.body.length > 160 ? '…' : ''}</div>
      <div class="mx"><span class="pill acc">${LABELS[r.label].n} ${fmt(r.conf, 2)}</span>${r.label === 'risk' || r.conf < gate ? '<span class="pill warn">→ Mensch</span>' : `<span class="pill ok">→ ${LABELS[r.label].who}</span>`}${r.hits.slice(0, 3).map((h) => `<span class="pill">${esc(h)}</span>`).join('')}</div></div>`;
  }
  function renderQueue() {
    $('#ibQn', el).textContent = S.queue.length;
    $('#ibQueue', el).innerHTML = S.queue.length ? S.queue.map((m) => `<div class="mail"><div class="mh"><span>${esc(m.from)}</span><span>${LABELS[m.r.label].n} ${fmt(m.r.conf, 2)}</span></div><div class="ms">${esc(m.subj)}</div>
      <div class="mx">${KEYS.map((k) => `<button class="btn sm" data-m="${m.id}" data-l="${k}">${LABELS[k].n}</button>`).join('')}</div></div>`).join('') : '<p class="note">Nichts offen. Unsichere Mails und alles mit Risiko landen hier.</p>';
  }
  function renderStats() {
    const C = AC.col;
    $('#ibRouter', el).innerHTML = `<table class="tbl">${KEYS.map((k) => { const c = S.counts[k]; return `<tr><td>${LABELS[k].n}</td><td>${LABELS[k].who}</td><td class="r">${c.n}</td><td class="r">${c.n ? fmt(c.c / c.n, 2) : '–'}</td></tr>`; }).join('')}</table>`;
    const hist = Array(14).fill(0); S.lat.forEach((l) => hist[Math.min(13, Math.floor(l / 3))]++);
    AC.bars(cvs.lat, hist, { color: C.accent });
    $('#ibP50', el).textContent = 'p50 ' + (S.lat.length ? fmt(AC.median(S.lat)) + ' ms' : '–');
    const o = cvs.conf, { ctx, w, h } = o; ctx.clearRect(0, 0, w, h);
    const Y = (c) => h - 4 - ((c - 0.3) / 0.7) * (h - 8);
    ctx.strokeStyle = AC.alpha(C.fg, 0.4); ctx.setLineDash([3, 3]); ctx.beginPath(); ctx.moveTo(0, Y(gate)); ctx.lineTo(w, Y(gate)); ctx.stroke(); ctx.setLineDash([]);
    S.pts.forEach((p, i) => { const x = 4 + (i / 89) * (w - 8), y = Y(clamp(p.c, 0.3, 1)); ctx.beginPath(); ctx.arc(x, y, 2.4, 0, 7); if (p.h) { ctx.strokeStyle = C.accent; ctx.stroke(); } else { ctx.fillStyle = C.accent; ctx.fill(); } });
    $('#ibTok', el).textContent = fmt(S.tokensOut);
    $('#ibSpend', el).textContent = `${usd(S.spend, 4)} / 1,00 $`;
    $('#ibAct', el).textContent = `${S.actions.length} / 30`;
    $('#ibAcc', el).textContent = S.judged ? AC.pct(S.correct / S.judged, 0) : '–';
    $('#ibAccSub', el).textContent = `${S.auto} automatisch · ${S.human} an dich`;
    $('#ibLearn', el).textContent = fmt(Object.values(learned).reduce((a, o) => a + Object.keys(o).length, 0));
  }

  // ---------- confidence field ----------
  function seedField() {
    const n = AC.reduced ? 700 : Math.round(Math.min(3200, (cv.w * cv.h) / 120));
    field.bg = Array.from({ length: n }, () => { const j = AC.gauss() * 0.035; return { x: Math.random(), o: (randi(0, 4) + 0.5) / 5 + j, j: Math.abs(j), v: rand(0.02, 0.07), s: Math.random() }; });
  }
  function drawField(t, dt) {
    const { ctx, w, h } = cv, C = AC.col;
    ctx.clearRect(0, 0, w, h);
    const pad = 28, Y = (c) => pad + (1 - (c - 0.4) / 0.6) * (h - pad * 2);
    const bg = field.bg || [];
    for (const p of bg) {
      if (running) p.x += dt * p.v; if (p.x > 1) { p.x = 0; }
      const spread = 0.95 - 0.7 * p.x, center = 0.72 + 0.12 * Math.sin(p.x * 2.4 + t * 0.15) + 0.14 * p.x;
      const c = center + (p.o - 0.5) * spread * 0.55;
      const x = p.x * w, y = Y(c) + Math.sin(p.x * 14 + p.s * 9 + t) * 3;
      ctx.fillStyle = AC.alpha(C.fg, (0.1 + 0.75 * Math.exp(-(p.j * p.j) / 0.0004)) * (0.35 + p.x * 0.65));
      ctx.fillRect(x, y, 1.5, 1.5);
    }
    // axis
    ctx.fillStyle = AC.alpha(C.fg, 0.45); ctx.font = '10px ' + getComputedStyle(document.body).getPropertyValue('--f-mono'); ctx.textAlign = 'right';
    for (let c = 0.4; c <= 1.001; c += 0.1) { ctx.fillText(c.toFixed(1), w - 6, Y(c) + 3); ctx.fillRect(w - 30, Y(c), 6, 1); }
    // gate line
    const gy = Y(gate);
    ctx.strokeStyle = AC.alpha(C.fg, 0.75); ctx.setLineDash([6, 4]); ctx.beginPath(); ctx.moveTo(0, gy); ctx.lineTo(w - 36, gy); ctx.stroke(); ctx.setLineDash([]);
    ctx.textAlign = 'left';
    const lbl = `GATE ${fmt(gate, 2)}`; ctx.font = '600 11px ' + getComputedStyle(document.body).getPropertyValue('--f-mono');
    const lw = ctx.measureText(lbl).width + 14; ctx.fillStyle = C.fg; ctx.fillRect(w - 48 - lw, gy - 10, lw, 20); ctx.fillStyle = C.bg; ctx.fillText(lbl, w - 41 - lw, gy + 4);
    ctx.font = '10px ' + getComputedStyle(document.body).getPropertyValue('--f-mono'); ctx.fillStyle = AC.alpha(C.fg, 0.6);
    ctx.fillText('darüber: Code handelt', 10, gy - 8); ctx.fillText('darunter: Mensch prüft', 10, gy + 16);
    ctx.fillText('KONFIDENZFELD · MONO', 10, h - 10);
    // mails
    field.forEach((m, i) => {
      if (running) m.x = Math.min(1, m.x + dt * 0.09);
      const x = 20 + m.x * (w - 90), y = Y(clamp(m.y, 0.4, 1));
      ctx.strokeStyle = AC.alpha(C.fg, 0.3); ctx.beginPath(); ctx.moveTo(Math.max(0, x - 40), y); ctx.lineTo(x, y); ctx.stroke();
      ctx.fillStyle = m.h ? C.bg : C.fg; ctx.strokeStyle = C.fg; ctx.lineWidth = 1.4;
      ctx.beginPath(); ctx.rect(x - 4, y - 4, 8, 8); ctx.fill(); ctx.stroke(); ctx.lineWidth = 1;
      if (i === field.length - 1) {
        const txt = `→ ${LABELS[m.label].n} · ${fmt(m.y, 2)}`; ctx.font = '600 11px ' + getComputedStyle(document.body).getPropertyValue('--f-mono');
        const tw = ctx.measureText(txt).width + 14, bx = Math.max(4, Math.min(w - tw - 40, x + 18)), by = y - 34 < 18 ? y + 16 : y - 34;
        ctx.strokeStyle = C.fg; ctx.beginPath(); ctx.moveTo(x + 4, y - 4); ctx.lineTo(bx, by + 10); ctx.stroke();
        ctx.fillStyle = C.fg; ctx.fillRect(bx, by, tw, 20); ctx.fillStyle = C.bg; ctx.fillText(txt, bx + 7, by + 14);
        ctx.fillStyle = AC.alpha(C.fg, 0.7); ctx.font = '10px ' + getComputedStyle(document.body).getPropertyValue('--f-mono');
        ctx.fillText(m.subj.slice(0, 42), bx, by - 6);
      }
    });
  }

  let mAcc = 0.8, sAcc = 0;
  AC.register('inbox', {
    init,
    enter() { cv.fit(); if (!field.bg) seedField(); renderStats(); },
    frame(t, dt) {
      if (running) { S.t += dt; mAcc += dt; if (mAcc > 1.7) { mAcc = 0; process(makeMail()); } }
      drawField(t, dt);
      sAcc += dt; if (sAcc > 0.4) { sAcc = 0; renderStats(); }
    },
  });
})();
