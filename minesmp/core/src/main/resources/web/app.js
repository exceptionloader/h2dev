// MineSMP Website - eine Seite, Inhalte von /api (Plugin MineSMPCore). Alle Texte von Nutzern werden als Text gesetzt (kein HTML).
'use strict';
const app = document.getElementById('app');
let META = null, ME = null, STATUS = null;

// ---------- Helfer ----------
function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k instanceof Node ? k : document.createTextNode(String(k)));
  return e;
}
async function api(path, body) {
  const opt = body ? { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-MineSMP': '1' }, body: JSON.stringify(body) } : {};
  const r = await fetch('/api' + path, opt);
  let data = {};
  try { data = await r.json(); } catch (e) { /* leer */ }
  if (!r.ok) throw new Error(data.error || 'Fehler ' + r.status);
  return data;
}
function rankInfo(id) {
  return (META && META.ranks.find(r => r.id === id)) || { id: 'default', badge: 'rank_spieler', label: 'Spieler', color: '#94aab9' };
}
function badge(id) {
  const r = rankInfo(id);
  return el('img', { class: 'badge', src: 'img/' + r.badge + '.png', alt: r.label, title: r.label });
}
// Pixel-Kopf aus dem Namen (keine Bilder von fremden Seiten)
const avCache = {};
function avatar(name, rank) {
  const key = name + '|' + rank;
  if (!avCache[key]) {
    let h = 2166136261;
    for (const ch of name.toLowerCase()) { h ^= ch.charCodeAt(0); h = Math.imul(h, 16777619); }
    const c = document.createElement('canvas'); c.width = c.height = 8;
    const g = c.getContext('2d');
    const col = rankInfo(rank).color;
    g.fillStyle = '#0f1a23'; g.fillRect(0, 0, 8, 8);
    for (let y = 0; y < 8; y++) for (let x = 0; x < 4; x++) {
      h = Math.imul(h ^ (h >>> 13), 1274126177);
      if ((h >>> 28) & 1) { g.fillStyle = ((h >>> 20) & 3) ? col : '#e8f3f8'; g.fillRect(x, y, 1, 1); g.fillRect(7 - x, y, 1, 1); }
    }
    avCache[key] = c.toDataURL();
  }
  return el('img', { class: 'av', src: avCache[key], alt: '', width: 48, height: 48 });
}
function ago(t) {
  if (!t) return '';
  const s = Math.max(1, Math.round((Date.now() - t) / 1000));
  if (s < 60) return 'gerade eben';
  if (s < 3600) return 'vor ' + Math.round(s / 60) + ' Min.';
  if (s < 86400) return 'vor ' + Math.round(s / 3600) + ' Std.';
  if (s < 86400 * 30) return 'vor ' + Math.round(s / 86400) + ' Tagen';
  return new Date(t).toLocaleDateString('de-DE');
}
function duration(ms) {
  const m = Math.round(ms / 60000);
  if (m < 60) return m + ' Min.';
  const h = Math.floor(m / 60);
  return h < 24 ? h + ' Std. ' + (m % 60) + ' Min.' : Math.floor(h / 24) + ' Tage ' + (h % 24) + ' Std.';
}
function setTitle(t) { document.title = t ? t + ' · MineSMP' : 'MineSMP'; }
function copyBtn(text) {
  const b = el('button', { class: 'copy', type: 'button', text: 'Kopieren' });
  b.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(text); b.textContent = 'Kopiert ✓'; }
    catch (e) { const r = document.createRange(); r.selectNodeContents(b.previousSibling); const s = getSelection(); s.removeAllRanges(); s.addRange(r); b.textContent = 'Markiert'; }
    setTimeout(() => { b.textContent = 'Kopieren'; }, 1800);
  });
  return b;
}

// ---------- Kopf: Anmeldung ----------
function renderAccount() {
  const a = document.getElementById('account');
  a.replaceChildren();
  if (ME) {
    a.append(badge(ME.rank), el('b', { text: ME.name }),
      el('button', { class: 'btn ghost small', type: 'button', text: 'Abmelden', onclick: async () => { await api('/logout', {}); ME = null; renderAccount(); route(); } }));
  } else {
    a.append(el('a', { class: 'btn small', href: '#/anmelden', text: 'Anmelden' }));
  }
}

// ---------- Seiten ----------
async function pageStart() {
  setTitle('');
  const [news] = await Promise.all([api('/forum/news').catch(() => [])]);
  const s = STATUS || { online: 0, max: 0, players: [] };
  const ipBox = el('div', { class: 'ip' }, el('code', { text: META.address }), copyBtn(META.address));
  const heads = el('div', { class: 'heads' }, (s.players || []).slice(0, 24).map(p => { const i = avatar(p.name, p.rank); i.title = p.name; i.alt = p.name; return i; }));
  const ev = s.nextEvent ? el('p', { class: 'chip' }, '⏱ Nächstes Event: ', el('b', { text: s.nextEvent.name }), ' in ' + duration(s.nextEvent.in)) : null;
  show(
    el('section', { class: 'hero' },
      el('div', null,
        el('img', { class: 'logo', src: 'img/logo.png', alt: 'MineSMP', width: 592, height: 228 }),
        el('p', { class: 'lead', text: META.tagline }),
        el('div', { class: 'actions' },
          el('a', { class: 'btn', href: '#/raenge', text: 'Ränge ansehen' }),
          META.discord ? el('a', { class: 'btn ghost', href: META.discord, target: '_blank', rel: 'noopener', text: 'Discord beitreten' }) : null)),
      el('div', { class: 'card join' },
        el('span', { class: 'label', text: 'Jetzt mitspielen' }), ipBox,
        el('p', { class: 'muted', text: 'Minecraft ' + META.version + ' → Mehrspieler → Server hinzufügen' }),
        el('div', { class: 'stat' }, el('span', { class: 'dot on' }), el('b', { text: String(s.online) }), el('span', { class: 'muted', text: 'von ' + s.max + ' Spielern online' })),
        heads, ev)),
    el('section', { class: 'section' },
      el('div', { class: 'head' }, el('h2', { text: 'Was dich erwartet' })),
      el('div', { class: 'grid' }, (META.features || []).map(f => el('div', { class: 'card feature' },
        el('img', { src: 'img/' + f.icon + '.png', alt: '' }), el('h3', { text: f.title }), el('p', { text: f.text }))))),
    el('section', { class: 'section' },
      el('div', { class: 'head' }, el('h2', { text: 'Neuigkeiten' }), el('a', { href: '#/forum/c/news', text: 'Alle Ankündigungen →' })),
      news.length ? el('div', { class: 'news' }, news.map(n => el('a', { class: 'card item', href: '#/forum/t/' + n.id },
        el('h3', { text: n.title }), el('div', { class: 'meta' }, badge(n.rank), n.author + ' · ' + ago(n.created)), el('p', { class: 'excerpt', text: n.excerpt }))))
        : el('p', { class: 'card note', text: 'Noch keine Ankündigungen. Das Team schreibt sie im Forum unter "Ankündigungen".' })),
    META.discord ? el('section', { class: 'section' }, el('div', { class: 'card discord' },
      el('div', null, el('h2', { text: 'Komm auf unseren Discord' }), el('p', { class: 'muted', text: 'Events, Support, Clan-Suche und alle Neuigkeiten zuerst.' })),
      el('a', { class: 'btn', href: META.discord, target: '_blank', rel: 'noopener', text: 'Discord öffnen' }))) : null);
}

function pageRaenge() {
  setTitle('Ränge');
  const shop = META.shop || {};
  const cards = (shop.ranks || []).map(r => {
    const url = r['buy-url'] || META.store;
    const card = el('article', { class: 'card rank' + (r.highlight ? ' hl' : '') },
      r.highlight ? el('span', { class: 'tag', text: 'BELIEBT' }) : null,
      el('img', { class: 'rbadge', src: 'img/' + r.badge + '.png', alt: r.name }),
      el('h3', { text: r.name }),
      el('div', { class: 'price' }, el('b', { text: r.price }), el('span', { class: 'muted', text: '/ ' + r.period })),
      el('ul', null, (r.perks || []).map(p => el('li', { text: p }))),
      url ? el('a', { class: 'btn', href: url, target: '_blank', rel: 'noopener', text: r.name + ' kaufen' })
          : el('span', { class: 'btn', 'aria-disabled': 'true', text: 'Bald verfügbar' }));
    card.style.setProperty('--c', r.color || '#22c3d6');
    return card;
  });
  show(
    el('div', { class: 'section', style: null }, el('h1', { text: 'Ränge' }), el('p', { class: 'muted', text: shop.intro || '' })),
    el('div', { class: 'ranks section' }, cards),
    shop.note ? el('p', { class: 'card note', text: shop.note }) : null,
    el('p', { class: 'card note' }, 'Fragen zu einem Kauf? ', META.discord ? el('a', { href: META.discord, target: '_blank', rel: 'noopener', text: 'Schreib uns auf Discord' }) : 'Schreib uns im Forum', '.'));
}

async function pageForum() {
  setTitle('Forum');
  const cats = await api('/forum');
  show(
    el('h1', { text: 'Forum' }),
    el('p', { class: 'muted', text: ME ? 'Angemeldet als ' + ME.name + '.' : 'Lesen kann jeder. Zum Schreiben im Spiel /web eingeben und hier anmelden.' }),
    el('div', { class: 'list section' }, cats.map(c => el('a', { class: 'row', href: '#/forum/c/' + c.id },
      el('img', { class: 'ic', src: 'img/' + (c.icon || 'dot') + '.png', alt: '' }),
      el('div', { class: 't' }, el('b', { text: c.name }), el('span', { text: c.text || '' })),
      el('div', { class: 'n' }, c.threads + ' Themen · ' + c.posts + ' Beiträge', el('br'), c.last ? ago(c.last) : '')))));
}

async function pageCategory(id) {
  const d = await api('/forum/c/' + encodeURIComponent(id));
  const c = d.category;
  setTitle(c.name);
  const canWrite = ME && (!c['team-only'] || ME.moderator);
  const form = canWrite ? newThreadForm(c) : null;
  show(
    el('div', { class: 'crumbs' }, el('a', { href: '#/forum', text: 'Forum' }), '›', el('span', { text: c.name })),
    el('div', { class: 'section', style: null }, el('h1', { text: c.name }), el('p', { class: 'muted', text: c.text || '' })),
    d.threads.length ? el('div', { class: 'list section' }, d.threads.map(t => el('a', { class: 'row', href: '#/forum/t/' + t.id },
      el('img', { class: 'ic av', src: avatar(t.author, t.rank).src, alt: '' }),
      el('div', { class: 't' }, el('b', null, t.pinned ? el('span', { class: 'pill', text: 'ANGEHEFTET' }) : null, t.locked ? el('span', { class: 'pill', text: 'ZU' }) : null, t.title),
        el('span', null, badge(t.rank), ' ' + t.author + ' · ' + ago(t.created))),
      el('div', { class: 'n', text: t.replies + ' Antworten · ' + ago(t.last) }))))
      : el('p', { class: 'card note section', text: 'Noch keine Themen in diesem Bereich.' }),
    form || (!ME ? el('p', { class: 'card note' }, 'Zum Schreiben ', el('a', { href: '#/anmelden', text: 'anmelden' }), '.')
      : c['team-only'] ? el('p', { class: 'card note', text: 'Neue Themen schreibt hier nur das Team.' }) : null));
}

function newThreadForm(c) {
  const msg = el('p', { class: 'err', role: 'alert' });
  const title = el('input', { id: 'nt-title', maxlength: 80, required: true, placeholder: 'Worum geht es?' });
  const body = el('textarea', { id: 'nt-body', maxlength: 4000, required: true });
  return el('form', { class: 'card box', onsubmit: async e => {
    e.preventDefault(); msg.textContent = '';
    try { const r = await api('/forum/thread', { category: c.id, title: title.value, body: body.value }); location.hash = '#/forum/t/' + r.id; }
    catch (err) { msg.textContent = err.message; }
  } }, el('h3', { text: 'Neues Thema' }), el('label', null, 'Titel', title), el('label', null, 'Text', body), msg,
     el('div', null, el('button', { class: 'btn', type: 'submit', text: 'Thema erstellen' })));
}

async function pageThread(id) {
  const t = await api('/forum/t/' + encodeURIComponent(id));
  setTitle(t.title);
  const cat = t.categoryInfo || { id: t.category, name: t.category };
  const modTools = ME && ME.moderator ? el('div', { class: 'tools' },
    el('button', { class: 'btn ghost small', type: 'button', text: t.pinned ? 'Lösen' : 'Anheften', onclick: async () => { await api('/forum/flag', { thread: String(t.id), flag: 'pinned' }); route(); } }),
    el('button', { class: 'btn ghost small', type: 'button', text: t.locked ? 'Öffnen' : 'Schließen', onclick: async () => { await api('/forum/flag', { thread: String(t.id), flag: 'locked' }); route(); } })) : null;
  const posts = t.posts.map((p, i) => el('article', { class: 'card post' },
    el('div', { class: 'who' }, avatar(p.author, p.rank), el('div', null, el('b', { text: p.author }), el('div', null, badge(p.rank)))),
    el('div', { class: 'body' },
      el('div', { class: 'meta', text: (i === 0 ? 'Erstellt ' : 'Antwort ') + ago(p.created) }),
      el('div', { class: 'text', text: p.body }),
      ME && (ME.moderator || ME.name === p.author) ? el('div', { class: 'tools' },
        el('button', { class: 'btn ghost small', type: 'button', text: i === 0 ? 'Thema löschen' : 'Löschen', onclick: async () => {
          if (!delConfirm(i === 0)) return;
          const r = await api('/forum/delete', { post: String(p.id) });
          if (r.deleted === 'thread') location.hash = '#/forum/c/' + cat.id; else route();
        } })) : null)));
  let reply = null;
  if (ME && (!t.locked || ME.moderator)) {
    const msg = el('p', { class: 'err', role: 'alert' });
    const body = el('textarea', { id: 'reply-body', maxlength: 4000, required: true });
    reply = el('form', { class: 'card box', onsubmit: async e => {
      e.preventDefault(); msg.textContent = '';
      try { await api('/forum/reply', { thread: String(t.id), body: body.value }); route(); }
      catch (err) { msg.textContent = err.message; }
    } }, el('label', null, 'Antworten', body), msg, el('div', null, el('button', { class: 'btn', type: 'submit', text: 'Antwort senden' })));
  } else if (!ME) reply = el('p', { class: 'card note' }, 'Zum Antworten ', el('a', { href: '#/anmelden', text: 'anmelden' }), '.');
  else reply = el('p', { class: 'card note', text: 'Dieses Thema ist geschlossen.' });
  show(
    el('div', { class: 'crumbs' }, el('a', { href: '#/forum', text: 'Forum' }), '›', el('a', { href: '#/forum/c/' + cat.id, text: cat.name }), '›', el('span', { text: t.title })),
    el('h1', null, t.pinned ? el('span', { class: 'pill', text: 'ANGEHEFTET' }) : null, t.locked ? el('span', { class: 'pill', text: 'ZU' }) : null, t.title),
    modTools, el('div', { class: 'posts section' }, posts), reply);
}
// Sicherheitsabfrage ohne Browser-Dialog: zweimal klicken
let armed = 0;
function delConfirm() { const now = Date.now(); if (now - armed < 4000) { armed = 0; return true; } armed = now; alertBar('Zum Löschen noch einmal klicken.'); return false; }
function alertBar(t) { const p = el('p', { class: 'card note', role: 'status', text: t }); app.prepend(p); setTimeout(() => p.remove(), 3500); }

function pageLogin() {
  setTitle('Anmelden');
  if (ME) { location.hash = '#/forum'; return; }
  const msg = el('p', { class: 'err', role: 'alert' });
  const name = el('input', { id: 'login-name', autocomplete: 'username', maxlength: 16, required: true });
  const code = el('input', { id: 'login-code', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 6, required: true, pattern: '[0-9]{6}' });
  show(el('div', { class: 'login' },
    el('h1', { text: 'Anmelden' }),
    el('ol', { class: 'steps' }, el('li', null, 'Auf ', el('b', { text: META.address }), ' einloggen.'), el('li', null, 'Im Chat ', el('b', { text: '/web' }), ' eingeben.'),
      el('li', { text: 'Den 6-stelligen Code hier eintragen (5 Minuten gültig).' })),
    el('form', { class: 'card box', onsubmit: async e => {
      e.preventDefault(); msg.textContent = '';
      try { ME = await api('/login', { name: name.value, code: code.value }); renderAccount(); location.hash = '#/forum'; }
      catch (err) { msg.textContent = err.message; }
    } }, el('label', null, 'Minecraft-Name', name), el('label', null, 'Code aus dem Spiel', code), msg,
      el('div', null, el('button', { class: 'btn', type: 'submit', text: 'Anmelden' })))));
}

async function pageTeam() {
  setTitle('Team');
  const groups = await api('/team');
  const filled = groups.filter(g => g.members.length);
  show(
    el('h1', { text: 'Das Team' }),
    el('p', { class: 'muted', text: 'Bei Fragen hilft dir jeder aus dem Team - im Spiel, im Forum oder auf Discord.' }),
    el('div', { class: 'team section' }, filled.length ? filled.map(g => el('section', null,
      el('h2', { text: g.name }), g.text ? el('p', { class: 'muted', text: g.text }) : null,
      el('div', { class: 'members' }, g.members.map(m => el('div', { class: 'card member' }, avatar(m.name, m.rank),
        el('div', null, el('b', { text: m.name }), badge(m.rank), el('div', { class: 'chip' }, el('span', { class: 'dot' + (m.online ? ' on' : '') }), m.online ? 'online' : 'zuletzt ' + ago(m.lastSeen))))))))
      : el('p', { class: 'card note', text: 'Das Team erscheint hier, sobald jemand mit Team-Rang auf dem Server war.' })));
}

function pageRegeln() {
  setTitle('Regeln');
  show(el('h1', { text: 'Regeln' }), el('p', { class: 'muted', text: 'Mit dem Spielen auf MineSMP akzeptierst du diese Regeln. Verstöße meldest du im Spiel mit /report.' }),
    el('ol', { class: 'rules' }, (META.rules || []).map(r => el('li', { class: 'card', text: r }))));
}

function pageLegal(which) {
  const t = which === 'impressum' ? 'Impressum' : 'Datenschutz';
  setTitle(t);
  show(el('h1', { text: t }), el('div', { class: 'card legal', text: (META.legal && META.legal[which]) || '' }));
}

// ---------- Navigation ----------
async function route() {
  const h = location.hash.replace(/^#\/?/, '');
  const parts = h.split('/');
  const key = parts[0] || 'start';
  for (const a of document.querySelectorAll('[data-nav]')) a.classList.toggle('on', a.dataset.nav === key);
  document.getElementById('nav').classList.remove('open');
  document.getElementById('menuBtn').setAttribute('aria-expanded', 'false');
  try {
    if (key === 'start') await pageStart();
    else if (key === 'raenge') pageRaenge();
    else if (key === 'forum' && parts[1] === 'c') await pageCategory(parts[2]);
    else if (key === 'forum' && parts[1] === 't') await pageThread(parts[2]);
    else if (key === 'forum') await pageForum();
    else if (key === 'team') await pageTeam();
    else if (key === 'regeln') pageRegeln();
    else if (key === 'anmelden') pageLogin();
    else if (key === 'impressum' || key === 'datenschutz') pageLegal(key);
    else await pageStart();
  } catch (e) {
    show(el('h1', { text: 'Das hat nicht geklappt' }), el('p', { class: 'muted', text: e.message }));
  }
  window.scrollTo(0, 0);
}

function show(...kids) { app.replaceChildren(...kids.flat().filter(k => k != null && k !== false)); }

async function boot() {
  document.getElementById('menuBtn').addEventListener('click', e => {
    const n = document.getElementById('nav'); n.classList.toggle('open');
    e.currentTarget.setAttribute('aria-expanded', String(n.classList.contains('open')));
  });
  try { [META, STATUS] = await Promise.all([api('/meta'), api('/status')]); }
  catch (e) { show(el('h1', { text: 'Server nicht erreichbar' }), el('p', { class: 'muted', text: 'Die Website läuft im Minecraft-Server. Versuch es gleich noch einmal.' })); return; }
  try { const m = await api('/me'); ME = m && m.loggedIn ? m : null; } catch (e) { ME = null; }
  for (const id of ['navDiscord', 'footDiscord']) { const a = document.getElementById(id); if (META.discord) a.href = META.discord; else a.remove(); }
  renderAccount();
  window.addEventListener('hashchange', route);
  await route();
  setInterval(async () => { try { STATUS = await api('/status'); } catch (e) { /* weiter */ } }, 30000);
}
boot();
