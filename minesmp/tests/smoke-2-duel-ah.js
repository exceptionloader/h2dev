const mineflayer = require('mineflayer');
const { Rcon } = require('rcon-client');
const sleep = ms => new Promise(r => setTimeout(r, ms));
function mk(name){ return new Promise((res)=>{ const b=mineflayer.createBot({host:'127.0.0.1',port:+(process.env.MC_PORT||25565),username:name,version:'1.21.11'}); b.log=[]; b.on('messagestr',m=>{b.log.push(m)}); b.once('spawn',()=>res(b)); b.on('kicked',r=>console.log(name,'KICKED',JSON.stringify(r).slice(0,200))); b.on('error',e=>console.log(name,'ERR',e.message)); }); }
async function cmd(b, c, wait=1200){ b.log=[]; b.chat(c); await sleep(wait); console.log(`[${b.username}] ${c} => ${b.log.join(' | ').slice(0,300)}`); }
(async()=>{
  const rcon = await Rcon.connect({host:'127.0.0.1',port:+(process.env.RCON_PORT||25575),password:process.env.RCON_PASSWORD||''});
  const a = await mk('TesterAlpha'); const b = await mk('TesterBeta'); await sleep(2500);
  await rcon.send('unmute TesterBeta');
  await rcon.send('execute in minecraft:overworld run tp TesterAlpha 100 120 100');
  await rcon.send('execute in minecraft:overworld run tp TesterBeta 102 120 100');
  await rcon.send('execute in minecraft:overworld run fill 95 119 95 110 119 110 stone');
  await sleep(2000);
  await cmd(a,'/sethome base'); await cmd(a,'/homes');
  await cmd(a,'/duel setarena test 1'); await rcon.send('execute in minecraft:overworld run tp TesterAlpha 105 120 100'); await sleep(800); await cmd(a,'/duel setarena test 2');
  await rcon.send('gamemode survival TesterAlpha'); await rcon.send('gamemode survival TesterBeta');
  await rcon.send('give TesterBeta diamond 5');
  // Combat tag + Kill
  await rcon.send('execute in minecraft:overworld run tp TesterBeta 101 120 100'); await sleep(800);
  await rcon.send('attribute TesterBeta minecraft:max_health base set 2'); await rcon.send('effect clear TesterBeta');
  const target = Object.values(a.entities).find(e=>e.username==='TesterBeta');
  a.log=[]; b.log=[];
  for (let i=0;i<6 && b.health>0;i++){ a.attack(target); await sleep(700); }
  await sleep(1500);
  console.log('Alpha msgs after kill:', a.log.join(' | ').slice(0,400));
  await rcon.send('attribute TesterBeta minecraft:max_health base set 20');
  b.respawn && b.respawn(); await sleep(2500);
  await cmd(a,'/spawn',1000);
  await cmd(a,'/stats');
  await cmd(b,'/bounty list'); if (b.currentWindow) b.closeWindow(b.currentWindow);
  // Duel
  await rcon.send('execute in minecraft:overworld run tp TesterBeta 101 120 100'); await rcon.send('execute in minecraft:overworld run tp TesterAlpha 100 120 100'); await sleep(5000);
  await cmd(a,'/duel TesterBeta duel_classic'); await cmd(b,'/duel accept TesterAlpha',6000);
  console.log('Beta inv in duel:', b.inventory.items().map(i=>i.name).slice(0,6).join(','));
  await rcon.send('kill TesterBeta'); await sleep(1500); b.respawn && b.respawn(); await sleep(5000);
  console.log('Beta inv after duel:', b.inventory.items().map(i=>i.name+'x'+i.count).join(','));
  console.log('duel msgs', a.log.join(' | ').slice(-300));
  await cmd(a,'/duel stats');
  // AH kaufen durch Beta
  await cmd(b,'/ah'); if (b.currentWindow){ await b.clickWindow(0,0,0); await sleep(800); if (b.currentWindow) { await b.clickWindow(11,0,0); await sleep(1200);} } console.log('ah buy:', b.log.join(' | ').slice(0,300));
  await cmd(a,'/ah collect');
  // Sell
  await cmd(b,'/sellhand');
  console.log('RCON save-all & stop test');
  a.quit(); b.quit(); await rcon.end(); process.exit(0);
})().catch(e=>{console.log('FAIL',e); process.exit(1)});
