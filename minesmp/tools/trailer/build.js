const { Rcon } = require('rcon-client');
(async()=>{ const r=await Rcon.connect({host:'127.0.0.1',port:25604,password:'testpw'});
 const W='execute in minecraft:spawn run ';
 const cmds=[];
 const f=(a,b,blk)=>cmds.push(`${W}fill ${a} ${b} ${blk}`);
 const s=(p,blk)=>cmds.push(`${W}setblock ${p} ${blk}`);
 cmds.push('forceload add -40 -40 40 40');
 cmds.push(W+'forceload add -40 -40 40 40');
 f('-24 99 -24','24 99 24','polished_deepslate');
 // Kreisplattform im Schachbrett
 for (let x=-22;x<=22;x++) for (let z=-22;z<=22;z++){ const d=Math.hypot(x,z); if(d<=22){ const blk = d<=3?'sea_lantern': d>20.5?'cyan_concrete': ((x+z)&1?'white_concrete':'light_blue_concrete'); s(`${x} 99 ${z}`,blk);} }
 f('-24 100 -24','24 130 24','air');
 // Brunnen in der Mitte
 for (const [x,z] of [[-2,-2],[2,-2],[-2,2],[2,2]]) { f(`${x} 100 ${z}`,`${x} 103 ${z}`,'quartz_pillar'); s(`${x} 104 ${z}`,'sea_lantern'); }
 f('-2 105 -2','2 105 2','smooth_quartz_slab');
 s('0 100 0','beacon'); s('0 106 0','cyan_stained_glass');
 // Saeulen am Rand
 for (let i=0;i<8;i++){ const a=i*Math.PI/4; const x=Math.round(Math.cos(a)*19), z=Math.round(Math.sin(a)*19); f(`${x} 100 ${z}`,`${x} 106 ${z}`,'quartz_pillar'); s(`${x} 107 ${z}`,'sea_lantern'); s(`${x} 108 ${z}`,'cyan_stained_glass'); }
 // Crate-Reihe (Norden, z=-10)
 const crates=[['vote','lime_concrete'],['daily','yellow_concrete'],['rare','blue_concrete'],['epic','purple_concrete'],['legendary','orange_concrete']];
 crates.forEach(([id,c],i)=>{ const x=-8+i*4; s(`${x} 100 -10`,c); s(`${x} 101 -10`,'chest[facing=south]'); s(`${x} 100 -11`,'end_rod[facing=up]'); s(`${x} 99 -9`,'sea_lantern'); });
 // Schriftzug MINESMP (Pixel-Font 5 hoch) bei z=-20, steht aufrecht
 const font={M:['10001','11011','10101','10001','10001'],I:['111','010','010','010','111'],N:['10001','11001','10101','10011','10001'],E:['1111','1000','1110','1000','1111'],S:['0111','1000','0110','0001','1110'],P:['1110','1001','1110','1000','1000']};
 let x0=-22; const word='MINESMP';
 for (let k=0;k<word.length;k++){ const g=font[word[k]]; const col=k<4?'light_blue_concrete':'white_concrete';
   for (let row=0;row<5;row++) for (let c=0;c<g[row].length;c++) if(g[row][c]==='1') s(`${x0+c} ${112-row} -21`,col);
   x0+=g[0].length+1; }
 f('-23 106 -22','23 106 -22','polished_deepslate'); f('-23 114 -22','23 114 -22','polished_deepslate');
 f('-23 106 -22','23 114 -22','polished_deepslate hollow');
 let ok=0, bad=[];
 for (const c of cmds){ const o=(await r.send(c)).replace(/§./g,''); if(/Unknown|Incorrect|error|Expected/i.test(o)) bad.push(c+' => '+o); else ok++; }
 console.log('ok',ok,'bad',bad.length, bad.slice(0,3));
 await r.end(); })();
