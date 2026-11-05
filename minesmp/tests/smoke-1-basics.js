const mineflayer = require('mineflayer');
const { Rcon } = require('rcon-client');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
function mk(name){ return new Promise((res,rej)=>{ const b=mineflayer.createBot({host:'127.0.0.1',port:+(process.env.MC_PORT||25565),username:name,version:'1.21.11'}); b.log=[]; b.on('messagestr',m=>{b.log.push(m)}); b.once('spawn',()=>res(b)); b.on('kicked',r=>console.log(name,'KICKED',JSON.stringify(r))); b.on('error',e=>console.log(name,'ERR',e.message)); }); }
async function cmd(b, c, wait=1200){ b.log=[]; b.chat(c); await sleep(wait); const out=b.log.join(' | '); console.log(`[${b.username}] ${c} => ${out.slice(0,300)}`); return out; }
(async()=>{
  const rcon = await Rcon.connect({host:'127.0.0.1',port:+(process.env.RCON_PORT||25575),password:process.env.RCON_PASSWORD||''});
  const a = await mk('TesterAlpha'); await sleep(1500);
  const b = await mk('TesterBeta'); await sleep(2500);
  console.log('RCON op:', await rcon.send('op TesterAlpha'));
  console.log('RCON eco:', await rcon.send('eco give TesterBeta 50000'));
  await cmd(a,'/money'); await cmd(b,'/bal');
  await cmd(b,'/pay TesterAlpha 1234'); await cmd(a,'/money');
  await cmd(a,'/baltop',2000);
  await cmd(a,'/sethome base'); await cmd(a,'/home base',4500);
  await cmd(a,'/spawn',4500);
  await cmd(a,'/tpa TesterBeta'); await cmd(b,'/tpaccept',4500);
  await cmd(b,'/clan create Drachen DRA',1500); await cmd(b,'/clan invite TesterAlpha'); await cmd(a,'/clan join Drachen'); await cmd(a,'/clan info',1500);
  await cmd(b,'/clan bank deposit 5000'); await cmd(a,'/cc Hallo Clan');
  await cmd(a,'Hallo Welt von Alpha'); await cmd(a,'Hallo Welt von Alpha');
  await cmd(a,'/daily'); await cmd(a,'/daily');
  await cmd(a,'/quests'); console.log('window:', a.currentWindow && a.currentWindow.title); a.closeWindow(a.currentWindow||{id:0});
  await cmd(a,'/shop'); console.log('shop window:', a.currentWindow ? JSON.stringify(a.currentWindow.title).slice(0,120) : 'none');
  if (a.currentWindow) { await a.clickWindow(10,0,0); await sleep(800); console.log('cat window:', a.currentWindow && JSON.stringify(a.currentWindow.title).slice(0,100));
     await a.clickWindow(0,0,0); await sleep(800); a.closeWindow(a.currentWindow); }
  await cmd(a,'/money');
  console.log('RCON key:', await rcon.send('crate givekey TesterAlpha vote 2'));
  await cmd(a,'/keys'); if (a.currentWindow) a.closeWindow(a.currentWindow);
  await cmd(a,'/crate open vote',6000); if (a.currentWindow) a.closeWindow(a.currentWindow);
  await cmd(a,'/bounty set TesterBeta 1000'); if (a.currentWindow){ await a.clickWindow(10,0,0); await sleep(1000);} console.log(a.log.join('|').slice(0,200));
  await cmd(a,'/rtp',8000);
  await cmd(a,'/stats'); await cmd(a,'/playtime'); await cmd(a,'/msg TesterBeta Hi'); await cmd(b,'/r Hallo zurueck');
  await cmd(a,'/report TesterBeta Test');
  await cmd(a,'/history TesterBeta',1500);
  await cmd(a,'/eventadmin start mining'); await sleep(2000); await cmd(a,'/event'); await cmd(a,'/eventadmin stop');
  await cmd(a,'/ah sell 100'); 
  await cmd(a,'/help'); await cmd(a,'/rules');
  await cmd(b,'/pl'); await cmd(b,'/minecraft:op TesterBeta');
  console.log('RCON tempmute:', await rcon.send('tempmute TesterBeta 10m Spam'));
  await sleep(1500); await cmd(b,'darf ich reden?');
  await cmd(a,'/skills'); if (a.currentWindow) a.closeWindow(a.currentWindow);
  console.log('sidebar:', JSON.stringify(Object.values(a.scoreboards||{}).map(s=>s.itemsMap? Object.values(s.itemsMap).map(i=>i.displayName&&i.displayName.toString()):[])).slice(0,600));
  a.quit(); b.quit(); await rcon.end(); process.exit(0);
})().catch(e=>{console.log('FAIL',e); process.exit(1)});
