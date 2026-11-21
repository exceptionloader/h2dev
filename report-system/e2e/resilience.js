// Outages of Redis and MongoDB, and template hot reload. Run on a fresh database.
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const { PROXY_1, PROXY_2, TEMPLATE, sleep, connect, waitFor, nextWindow, click, itemAt, report, checklist } = require('./lib');

const { check, finish } = checklist();
const sh = command => execSync(command, { stdio: 'pipe' }).toString().trim();
const log = server => fs.readFileSync(path.join(__dirname, '.work', server, 'output.log'), 'utf8');

(async () => {
  const moderator = await connect('Moderator', PROXY_2);
  const reporter = await connect('Reporter', PROXY_1);
  await sleep(2000);

  sh('docker restart report-redis');
  await sleep(9000);
  let mark = reporter.lines.length;
  let staffMark = moderator.lines.length;
  await report(reporter, 'Dinnerbone', TEMPLATE.BUGUSING);
  check('reports work after a Redis restart', !!await waitFor(reporter, /Dein Report wurde aufgenommen/, { from: mark }));
  check('subscription is restored after a Redis restart', !!await waitFor(moderator, /Dinnerbone wurde gemeldet/, { from: staffMark }));

  await sleep(3200);
  const menu = nextWindow(reporter);
  reporter.chat('/report Grumm');
  await menu;
  await itemAt(reporter, TEMPLATE.GRIEFING);
  sh('docker stop report-mongo');
  mark = reporter.lines.length;
  const started = Date.now();
  await click(reporter, TEMPLATE.GRIEFING);
  await sleep(500);
  const targets = nextWindow(reporter, 3000);
  reporter.chat('/report');
  check('server stays responsive while MongoDB hangs', !!await targets);
  if (reporter.currentWindow) reporter.closeWindow(reporter.currentWindow);
  const failure = await waitFor(reporter, /Es ist ein Fehler aufgetreten/, { from: mark, timeout: 20000 });
  check('player is told when MongoDB is down', !!failure, `${Date.now() - started} ms`);
  sh('docker start report-mongo');
  await sleep(8000);

  mark = reporter.lines.length;
  await report(reporter, 'Grumm', TEMPLATE.GRIEFING);
  check('reports work again once MongoDB is back', !!await waitFor(reporter, /Dein Report wurde aufgenommen/, { from: mark, timeout: 12000 }));

  sh(`docker exec report-mongo mongosh --quiet reports_e2e --eval 'db.templates.insertOne({_id: "teaming", name: "TEAMING", description: "Unerlaubtes Teaming", icon: "LEAD", sortOrder: 70})'`);
  staffMark = moderator.lines.length;
  moderator.chat('/report reload');
  check('reload command', !!await waitFor(moderator, /neu geladen/, { from: staffMark }));
  await sleep(1500);
  const reloaded = ['survival', 'lobby'].every(server => log(server).includes('Loaded 7 report templates'));
  check('reload reaches the other server through Redis', reloaded);

  await sleep(3200);
  const templates = nextWindow(reporter);
  reporter.chat('/report Jeb_');
  await templates;
  const added = await itemAt(reporter, 16);
  check('new template appears in the menu', !!added && added.name === 'lead');

  reporter.quit();
  moderator.quit();
  finish();
})().catch(error => {
  console.error(error);
  process.exit(2);
});
