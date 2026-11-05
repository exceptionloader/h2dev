const mineflayer = require('mineflayer');
const { Rcon } = require('rcon-client');
const sleep = ms => new Promise(r => setTimeout(r, ms));
function mk(name){ return new Promise((res)=>{ const b=mineflayer.createBot({host:'127.0.0.1',port:+(process.env.MC_PORT||25565),username:name,version:'1.21.11'}); b.log=[]; b.on('messagestr',m=>{b.log.push(m)}); b.once('spawn',()=>res(b)); }); }
async function cmd(b, c, wait=1200){ b.log=[]; b.chat(c); await sleep(wait); console.log(`[${b.username}] ${c} => ${b.log.join(' | ').slice(0,300)}`); }
(async()=>{
  const rcon = await Rcon.connect({host:'127.0.0.1',port:+(process.env.RCON_PORT||25575),password:process.env.RCON_PASSWORD||''});
  const a = await mk('TesterAlpha'); const b = await mk('TesterBeta'); await sleep(2000); a.chat('/clan leave'); await sleep(1000);
  console.log(await rcon.send('execute in minecraft:overworld run forceload add 290 290 310 310')); await sleep(3000);
  for (const [n,x] of [['TesterAlpha',300],['TesterBeta',301]]) console.log(await rcon.send(`execute in minecraft:overworld positioned ${x} 0 300 positioned over motion_blocking_no_leaves run tp ${n} ~ ~ ~`));
  await sleep(4000);
  await rcon.send('attribute TesterBeta minecraft:max_health base set 2');
  const t = Object.values(a.entities).find(e=>e.username==='TesterBeta');
  a.log=[]; b.log=[];
  console.log('t?', !!t, t && t.position, a.entity.position, a.game.gameMode); for (let i=0;i<8;i++){ await a.lookAt(t.position.offset(0,1.5,0)); a.attack(t); console.log('bhp',b.health); await sleep(600); if (b.health<=0) break; }
  await sleep(1500);
  console.log('Alpha:', a.log.join(' | ').slice(0,400));
  console.log('Beta:', b.log.join(' | ').slice(0,300));
  await rcon.send('attribute TesterBeta minecraft:max_health base set 20');
  await cmd(a,'/stats');
  // combat tag test: Beta hits Alpha then tries /spawn
  b.respawn(); await sleep(3000);
  await rcon.send('execute in minecraft:overworld positioned 301 0 300 positioned over motion_blocking_no_leaves run tp TesterBeta ~ ~ ~'); await sleep(2500);
  const ta = Object.values(b.entities).find(e=>e.username==='TesterAlpha'); b.attack(ta); await sleep(500);
  await cmd(b,'/spawn');
  b.quit(); await sleep(2000);
  console.log('after combat-log quit:', a.log.join(' | ').slice(-200));
  a.quit(); await rcon.end(); process.exit(0);
})();
