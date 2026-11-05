// MineSMP Trailer: Kamera-Bot (Kaito) + Mitspieler-Bots, 3D-Ansicht via prismarine-viewer, Aufnahme via Playwright.
const mineflayer = require('mineflayer');
const { mineflayer: viewer } = require('prismarine-viewer');
const { chromium } = require('playwright');
const { Rcon } = require('rcon-client');
const { Vec3 } = require('vec3');
const fs = require('fs');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = m => fs.appendFileSync('rec.log', m + '\n');
const HOST = '127.0.0.1', PORT = 25603;
const W = 1280, H = 720;

function mk(name) {
  return new Promise(res => {
    const b = mineflayer.createBot({ host: HOST, port: PORT, username: name, version: '1.21.11' });
    b.on('kicked', r => console.log(name, 'KICK', JSON.stringify(r).slice(0, 200)));
    b.on('error', e => console.log(name, 'ERR', e.message));
    b.once('spawn', () => res(b));
  });
}

(async () => {
  const rcon = await Rcon.connect({ host: HOST, port: 25604, password: 'testpw' });
  const rc = async c => { fs.appendFileSync('rec.log', 'RCON ' + c + '\n'); try { return (await Promise.race([rcon.send(c), sleep(3000).then(() => 'TIMEOUT')])).replace(/§./g, ''); } catch (e) { return 'ERR'; } };
  log('connect'); const cam = await mk('Kaito'); log('cam ok');
  const luna = await mk('LunaFox'); const steve = await mk('Steve22'); const nova = await mk('NovaPvP');
  await sleep(2500);
  for (const n of ['Kaito', 'LunaFox', 'Steve22', 'NovaPvP']) await rc(`lp user ${n} permission set grim.exempt true`);
  await rc('lp user Kaito parent set mvp'); await rc('lp user LunaFox parent set vip'); await rc('lp user Steve22 parent set legende');
  await rc('gamemode spectator Kaito');
  await rc('eco set Kaito 48250'); await rc('eco give Steve22 20000'); await rc('eco give LunaFox 15000');
  await rc('crate givekey Kaito legendary 1'); await rc('crate givekey Kaito epic 1');
  await rc('rg flag -w spawn __global__ pvp allow');
  await rc('effect give @a minecraft:resistance 1 0');
  // Clan vorbereiten
  cam.chat('/clan create Drachen DRA'); await sleep(900);
  cam.chat('/clan invite Steve22'); await sleep(900); steve.chat('/clan join Drachen'); await sleep(900);
  steve.chat('/clan bank deposit 5000'); await sleep(900);
  // Mitspieler aufstellen
  await rc('execute in minecraft:spawn run tp LunaFox -4.5 101 -7.5 180 0');
  await rc('execute in minecraft:spawn run tp Steve22 3.5 100 -6.5 160 0');
  await rc('execute in minecraft:spawn run tp NovaPvP 8.5 100 -6.5 180 0');
  await sleep(1500);

  // ---- Kamera ----
  cam.physicsEnabled = false;
  const camPos = new Vec3(0, 126, 42);
  let lookAt = new Vec3(0, 110, -21);
  function applyCam() {
    const d = lookAt.minus(camPos);
    const yaw = Math.atan2(-d.x, -d.z);
    const pitch = Math.atan2(d.y, Math.hypot(d.x, d.z));
    cam.entity.position.set(camPos.x, camPos.y, camPos.z);
    cam.entity.yaw = yaw; cam.entity.pitch = pitch;
    cam.emit('move');
    try {
      cam._client.write('position_look', { x: camPos.x, y: camPos.y, z: camPos.z,
        yaw: 180 - yaw * 180 / Math.PI, pitch: -pitch * 180 / Math.PI, flags: { onGround: false, hasHorizontalCollision: false } });
    } catch (e) {}
  }
  applyCam();
  const ease = t => t < .5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
  async function move(toPos, toLook, ms) {
    const p0 = camPos.clone(), l0 = lookAt.clone(), t0 = Date.now();
    while (true) {
      const t = Math.min(1, (Date.now() - t0) / ms), e = ease(t);
      camPos.set(p0.x + (toPos.x - p0.x) * e, p0.y + (toPos.y - p0.y) * e, p0.z + (toPos.z - p0.z) * e);
      lookAt = new Vec3(l0.x + (toLook.x - l0.x) * e, l0.y + (toLook.y - l0.y) * e, l0.z + (toLook.z - l0.z) * e);
      applyCam();
      if (t >= 1) break;
      await sleep(40);
    }
  }
  async function orbit(center, radius, y, fromDeg, toDeg, ms, lookY) {
    const t0 = Date.now();
    while (true) {
      const t = Math.min(1, (Date.now() - t0) / ms), a = (fromDeg + (toDeg - fromDeg) * ease(t)) * Math.PI / 180;
      camPos.set(center.x + Math.sin(a) * radius, y, center.z + Math.cos(a) * radius);
      lookAt = new Vec3(center.x, lookY, center.z); applyCam();
      if (t >= 1) break;
      await sleep(40);
    }
  }

  viewer(cam, { port: 3007, firstPerson: true, viewDistance: 5 });
  await sleep(3000);

  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const ctx = await browser.newContext({ viewport: { width: W, height: H }, recordVideo: { dir: 'video', size: { width: W, height: H } } });
  const page = await ctx.newPage();
  const tRec = Date.now();
  await page.goto('http://localhost:3007');
  await page.addStyleTag({ content: `
    @import url('https://fonts.googleapis.com/css2?family=Pixelify+Sans:wght@500;700&family=Figtree:wght@600;800&display=swap');
    #hud{position:fixed;inset:0;pointer-events:none;font-family:'Pixelify Sans',monospace;color:#fff;text-shadow:2px 2px 0 rgba(0,0,0,.55);z-index:9}
    #sb{position:absolute;right:10px;top:50%;transform:translateY(-50%);background:rgba(0,0,0,.4);padding:6px 12px;min-width:230px;font-size:19px;line-height:1.3}
    #sb .t{text-align:center;margin-bottom:3px}
    #chat{position:absolute;left:8px;bottom:70px;width:700px;font-size:18px;display:flex;flex-direction:column;gap:1px}
    #chat div{background:rgba(0,0,0,.45);padding:1px 6px;animation:in .25s ease-out}
    @keyframes in{from{transform:translateY(8px);opacity:.2}}
    #boss{position:absolute;top:12px;left:50%;transform:translateX(-50%);width:560px;text-align:center;font-size:18px}
    #boss .bar{height:10px;margin-top:4px;border:2px solid #000;background:#2a3a10}#boss .bar i{display:block;height:100%;background:linear-gradient(#8fff6a,#3ca82a)}
    #title{position:absolute;top:30%;left:0;right:0;text-align:center;font-size:96px;font-weight:700;text-shadow:5px 5px 0 rgba(0,0,0,.45);transition:opacity .5s}
    #sub{position:absolute;top:calc(30% + 112px);left:0;right:0;text-align:center;font-size:36px;transition:opacity .5s}
    #ab{position:absolute;bottom:40px;left:0;right:0;text-align:center;font-size:20px}
    #tab{position:absolute;top:70px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.62);padding:12px 26px;font-size:19px;text-align:center;min-width:560px}
    #tab .n{display:grid;grid-template-columns:1fr 1fr;gap:3px 22px;margin:10px 0;text-align:left}#tab .n div{background:rgba(255,255,255,.08);padding:1px 8px}
    #cap{position:absolute;left:24px;top:22px;font-family:Figtree,sans-serif;transition:opacity .4s}
    #cap b{display:block;font-size:13px;letter-spacing:.18em;text-transform:uppercase;color:#55FFFF}
    #cap span{font-size:26px;font-weight:800}
    #wm{position:absolute;right:16px;bottom:12px;font-size:16px;opacity:.85}
  ` });
  await page.evaluate(() => {
    const C = { 0:'#000',1:'#00A',2:'#0A0',3:'#0AA',4:'#A00',5:'#A0A',6:'#FA0',7:'#AAA',8:'#555',9:'#55F',a:'#5F5',b:'#5FF',c:'#F55',d:'#F5F',e:'#FF5',f:'#FFF' };
    window.mc = s => { let out = '', col = 'f', b = false, st = false; const parts = (s || '').split(/§([0-9a-fk-or])/i);
      for (let i = 0; i < parts.length; i++) { if (i % 2) { const c = parts[i].toLowerCase(); if (c === 'l') b = true; else if (c === 'm') st = true; else if (c === 'r') { col = 'f'; b = st = false } else if (C[c]) { col = c; b = st = false } continue }
        if (!parts[i]) continue; const t = parts[i].replace(/[&<>]/g, x => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[x]));
        out += `<span style="color:${C[col]}${b ? ';font-weight:700' : ''}${st ? ';text-decoration:line-through' : ''}">${t}</span>` } return out || '&nbsp;' };
    const h = document.createElement('div'); h.id = 'hud';
    h.innerHTML = '<div id="cap" style="opacity:0"><b></b><span></span></div><div id="boss" hidden><div class="l"></div><div class="bar"><i></i></div></div><div id="title" style="opacity:0"></div><div id="sub" style="opacity:0"></div><div id="tab" hidden></div><div id="sb"></div><div id="chat"></div><div id="ab"></div><div id="wm">' + mc('§b§lMINE§f§lSMP §7· §fminesmp.de') + '</div>';
    document.body.appendChild(h);
  });
  const ui = (fn, ...a) => page.evaluate(fn, ...a).catch(() => {});

  // ---- HUD aus echten Server-Paketen ----
  const ChatMessage = require('prismarine-chat')(cam.registry);
  const motd = n => { try { return ChatMessage.fromNotch(n).toMotd(); } catch (e) { return ''; } };
  const scores = {}; let sbTitle = '';
  cam._client.on('scoreboard_objective', p => { if (p.displayText) sbTitle = motd(p.displayText); });
  cam._client.on('scoreboard_score', p => { if (p.display_name) scores[p.itemName] = { v: p.value, t: motd(p.display_name) }; });
  cam._client.on('reset_score', p => { delete scores[p.entity_name || p.itemName]; });
  setInterval(() => {
    const lines = Object.values(scores).sort((a, b) => b.v - a.v).map(x => x.t);
    ui(([t, l]) => { document.getElementById('sb').innerHTML = `<div class="t">${mc(t)}</div>` + l.map(x => `<div>${mc(x)}</div>`).join(''); }, [sbTitle, lines]);
  }, 250);
  let chatLines = [], recording = false;
  cam.on('message', (m, pos) => {
    if (!recording || pos === 'game_info') return;
    const s = m.toMotd(); if (!s.trim()) return;
    chatLines.push(s); chatLines = chatLines.slice(-9);
    ui(l => { document.getElementById('chat').innerHTML = l.map(x => `<div>${mc(x)}</div>`).join(''); }, chatLines);
  });
  cam.on('actionBar', m => ui(s => document.getElementById('ab').innerHTML = mc(s), m.toMotd()));
  const bars = {};
  cam._client.on('boss_bar', p => {
    const id = p.entityUUID; if (p.action === 1) delete bars[id]; else { bars[id] = bars[id] || {}; if (p.title) bars[id].t = motd(p.title); if (p.health !== undefined) bars[id].h = p.health; }
    const b = Object.values(bars)[0];
    ui(b => { const e = document.getElementById('boss'); if (!b) { e.hidden = true; return } e.hidden = false; e.querySelector('.l').innerHTML = mc(b.t); e.querySelector('i').style.width = (b.h * 100) + '%'; }, b || null);
  });
  let tTitle;
  const onTitle = (text, type) => {
    let s = motd(text);
    ui(([s, type]) => { const e = document.getElementById(type === 'subtitle' ? 'sub' : 'title'); e.innerHTML = mc(s); e.style.opacity = 1; }, [s, type]);
    clearTimeout(tTitle); tTitle = setTimeout(() => ui(() => { document.getElementById('title').style.opacity = 0; document.getElementById('sub').style.opacity = 0; }), 3200);
  };
  cam._client.on('set_title_text', p => onTitle(p.text, 'title'));
  cam._client.on('set_title_subtitle', p => onTitle(p.text, 'subtitle'));
  const caption = (k, t) => ui(([k, t]) => { const c = document.getElementById('cap'); c.style.opacity = k ? 1 : 0; if (k) { c.querySelector('b').textContent = k; c.querySelector('span').textContent = t; } }, [k, t]);

  log('page ready'); await sleep(9000); // Welt laden
  const tStart = (Date.now() - tRec) / 1000;
  recording = true;
  console.log('Szene-Start bei', tStart.toFixed(1), 's');

  // ---- Szene 1: Intro ----
  await rc('title Kaito times 10 60 20');
  await rc('title Kaito subtitle {"text":"Deutscher Survival-Server · minesmp.de","color":"gray"}');
  await rc('title Kaito title [{"text":"MINE","color":"aqua","bold":true},{"text":"SMP","color":"white","bold":true}]');
  await move(new Vec3(0, 109, 4), new Vec3(0, 109, -21), 8000);

  // ---- Szene 2: Kisten ----
  caption('Spawn', 'Fünf Kisten mit Animation & Vorschau');
  luna.lookAt(new Vec3(0, 102, 6)); steve.lookAt(new Vec3(0, 102, 6));
  await move(new Vec3(0, 103.5, -1), new Vec3(0, 101, -10), 4000);
  luna.chat('Hey Kaito! Schon die Legendary-Crate probiert?'); await sleep(1800);
  cam.chat('Jetzt gleich :D'); await sleep(1200);
  cam.chat('/crate open legendary'); await sleep(5200);
  cam.chat('/keys'); await sleep(600); cam.closeWindow(cam.currentWindow || { id: 0 });
  await move(new Vec3(6, 103, -3), new Vec3(8, 101, -10), 3000);

  // ---- Szene 3: Economy & Clan ----
  caption('Economy & Clans', 'MineCoins, Clan-Bank, Rangliste');
  const orb = orbit(new Vec3(0, 0, 0), 10, 104.5, 30, 140, 13000, 102);
  cam.chat('/money'); await sleep(1800);
  cam.chat('/pay LunaFox 500'); await sleep(1800);
  cam.chat('/daily'); await sleep(2200);
  cam.chat('/clan info'); await sleep(4500);
  await orb;

  // ---- Szene 4: Event ----
  caption('Events', 'KOTH, Supply Drops, Boss, Turniere, Wettbewerbe');
  await rc('eventadmin start mining');
  const m4 = move(new Vec3(-12, 108, 10), new Vec3(0, 104, -10), 7000);
  await sleep(1500); cam.chat('/event'); await sleep(4000);
  steve.chat('Ich hol mir Platz 1 beim Mining!'); await m4;

  // ---- Szene 5: PvP ----
  caption('PvP', 'Combat-Tag, Killstreaks, Kopfgelder, Duelle');
  await rc('execute in minecraft:spawn run tp Steve22 12.5 100 6.5 90 0');
  await rc('execute in minecraft:spawn run tp NovaPvP 10.5 100 6.5 -90 0');
  await rc('give Steve22 minecraft:netherite_sword 1');
  await move(new Vec3(11.5, 102.5, 13), new Vec3(11.5, 100.8, 6.5), 3000);
  luna.chat('/bounty set NovaPvP 2000'); await sleep(700);
  if (luna.currentWindow) { await luna.clickWindow(10, 0, 0).catch(() => {}); }
  await sleep(1500);
  await rc('attribute NovaPvP minecraft:max_health base set 6');
  const target = Object.values(steve.entities).find(e => e.username === 'NovaPvP');
  for (let i = 0; i < 10 && target && nova.health > 0; i++) { await steve.lookAt(target.position.offset(0, 1.5, 0)); steve.attack(target); await sleep(650); }
  await sleep(1800);
  await rc('attribute NovaPvP minecraft:max_health base set 20');

  // ---- Szene 6: Tab & Outro ----
  caption('Community', 'Ränge, Clans und Team im Tab');
  const players = Object.values(cam.players).map(p => p.displayName ? p.displayName.toMotd() : p.username);
  ui(p => { const t = document.getElementById('tab'); t.hidden = false;
    t.innerHTML = mc('§b§lMINE§f§lSMP §8| §7Survival') + '<br>' + mc('§7Willkommen, §fKaito§7!') + '<div class="n">' + p.map(x => `<div>${mc(x)}</div>`).join('') + '</div>' + mc('§7Discord: §b/discord §8| §7Shop: §bstore.minesmp.de'); }, players);
  const mo = move(new Vec3(0, 122, 34), new Vec3(0, 108, -21), 6000);
  await sleep(2800); ui(() => document.getElementById('tab').hidden = true); caption(null);
  await rc('title Kaito subtitle {"text":"Jetzt joinen: minesmp.de","color":"aqua"}');
  await rc('title Kaito title [{"text":"MINE","color":"aqua","bold":true},{"text":"SMP","color":"white","bold":true}]');
  await mo; await sleep(3500);
  const tEnd = (Date.now() - tRec) / 1000;

  await rc('eventadmin stop'); await rc('rg flag -w spawn __global__ pvp deny');
  const vpath = await page.video().path();
  await ctx.close(); await browser.close();
  fs.writeFileSync('video/times.json', JSON.stringify({ vpath, tStart, tEnd }));
  console.log('fertig', vpath, tStart, tEnd);
  for (const b of [cam, luna, steve, nova]) b.quit();
  await rcon.end(); setTimeout(() => process.exit(0), 1500);
})().catch(e => { console.log('FAIL', e); process.exit(1); });
