// Reporter plays on survival-1 through proxy-1, the moderator on lobby-1 through proxy-2.
const { PROXY_1, PROXY_2, TEMPLATE, sleep, connect, waitFor, nextWindow, itemAt, click, report, reportIds, checklist } = require('./lib');

const COOLDOWN = 3200;
const { check, finish } = checklist();

(async () => {
  const moderator = await connect('Moderator', PROXY_2);
  check('login reminder on proxy-2', !!await waitFor(moderator, /Du hast aktuell 0 offene Reports/));

  let reporter = await connect('Reporter', PROXY_1);
  await sleep(1500);

  let mark = reporter.lines.length;
  reporter.chat('/report a!b');
  check('invalid name is rejected', !!await waitFor(reporter, /kein gültiger Spielername/, { from: mark }));
  reporter.chat('/report zqx9nouser77');
  check('unknown name is looked up and rejected', !!await waitFor(reporter, /Es gibt keinen Spieler/, { from: mark, timeout: 10000 }));
  reporter.chat('/report Reporter');
  check('self report is blocked', !!await waitFor(reporter, /nicht selbst melden/, { from: mark }));

  // offline target, resolved through mc-api.io
  mark = reporter.lines.length;
  let staffMark = moderator.lines.length;
  check('template menu for an offline player', await report(reporter, 'Notch', TEMPLATE.CHEATING));
  check('reporter gets a confirmation', !!await waitFor(reporter, /Dein Report wurde aufgenommen/, { from: mark }));
  check('staff alert reaches the other proxy', !!await waitFor(moderator,
      /Notch wurde gemeldet von Reporter \(Grund: CHEATING\)/, { from: staffMark }));
  check('alert points to /reports', !!await waitFor(moderator, /Verwende \/reports, um offene Reports zu verwalten/, { from: staffMark }));
  const [resolvedId] = reportIds(moderator, staffMark);

  mark = reporter.lines.length;
  await report(reporter, 'jeb_', TEMPLATE.CHEATING);
  check('cooldown applies', !!await waitFor(reporter, /Bitte warte noch/, { from: mark }));
  if (reporter.currentWindow) reporter.closeWindow(reporter.currentWindow);

  // moderator resolves through the menus
  mark = reporter.lines.length;
  staffMark = moderator.lines.length;
  let menu = nextWindow(moderator);
  moderator.chat('/reports');
  check('report browser opens', !!await menu);
  check('browser lists the report', !!await itemAt(moderator, 0));
  menu = nextWindow(moderator);
  await click(moderator, 0);
  check('detail menu opens', !!await menu);
  menu = nextWindow(moderator);
  await click(moderator, 21);
  check('moderator gets feedback', !!await waitFor(moderator, /wurde als berechtigt abgeschlossen/, { from: staffMark }));
  check('browser opens again afterwards', !!await menu);
  check('reporter on the other proxy is notified', !!await waitFor(reporter,
      /Dein Report gegen Notch wurde bearbeitet und als berechtigt eingestuft/, { from: mark }));
  moderator.closeWindow(moderator.currentWindow);

  staffMark = moderator.lines.length;
  moderator.chat(`/report resolve ${resolvedId}`);
  check('a closed report cannot be closed again', !!await waitFor(moderator, /wurde bereits von Moderator bearbeitet/, { from: staffMark }));

  // free text, reporter offline while the report is rejected
  await sleep(COOLDOWN);
  mark = reporter.lines.length;
  staffMark = moderator.lines.length;
  await report(reporter, 'jeb_', TEMPLATE.OTHER);
  check('OTHER asks for a description', !!await waitFor(reporter, /Beschreibe im Chat, was jeb_ getan hat/, { from: mark }));
  reporter.chat('kurz');
  check('too short descriptions are refused', !!await waitFor(reporter, /Die Beschreibung ist zu kurz/, { from: mark }));
  await sleep(300);
  reporter.chat('Fliegt seit zehn Minuten über den Spawn');
  check('OTHER report is stored', !!await waitFor(reporter, /Dein Report wurde aufgenommen/, { from: mark }));
  check('alert shows the description', !!await waitFor(moderator,
      /jeb_ wurde gemeldet von Reporter \(Grund: Fliegt seit zehn Minuten über den Spawn\)/, { from: staffMark }));
  const [rejectedId] = reportIds(moderator, staffMark);

  reporter.quit();
  await sleep(1500);
  staffMark = moderator.lines.length;
  moderator.chat(`/report reject ${rejectedId} Kein Beweis vorhanden`);
  check('reject command', !!await waitFor(moderator, /wurde abgelehnt/, { from: staffMark }));
  moderator.chat(`/report details ${rejectedId}`);
  check('details command', !!await waitFor(moderator, /Notiz: Kein Beweis vorhanden/, { from: staffMark }));

  reporter = await connect('Reporter', PROXY_2);
  check('missed result is delivered on the next login', !!await waitFor(reporter,
      /Dein Report gegen jeb_ wurde geprüft und abgelehnt\. Grund: Kein Beweis vorhanden/, { timeout: 10000 }));
  await sleep(1500);
  const deliveries = reporter.lines.filter(line => /wurde geprüft und abgelehnt/.test(line)).length;
  check('result is delivered exactly once', deliveries === 1, `${deliveries}x`);

  // duplicates and reports against the moderator
  mark = reporter.lines.length;
  staffMark = moderator.lines.length;
  await report(reporter, 'Moderator', TEMPLATE.INSULT);
  check('online player can be reported', !!await waitFor(reporter, /Dein Report wurde aufgenommen/, { from: mark }));
  await waitFor(moderator, /Moderator wurde gemeldet/, { from: staffMark });
  const [ownId] = reportIds(moderator, staffMark);
  await sleep(COOLDOWN);
  mark = reporter.lines.length;
  await report(reporter, 'Moderator', TEMPLATE.BUGUSING);
  check('second open report against the same player is refused', !!await waitFor(reporter, /bereits gemeldet/, { from: mark }));

  staffMark = moderator.lines.length;
  moderator.chat(`/report resolve ${ownId}`);
  check('moderators cannot close reports against themselves', !!await waitFor(moderator, /gegen dich gerichtet/, { from: staffMark }));

  moderator.chat('/report stats');
  check('global statistics', !!await waitFor(moderator, /Gesamt: 3 \| Offen: 1 \| Berechtigt: 1 \| Abgelehnt: 1/, { from: staffMark }));
  moderator.chat('/report stats Reporter');
  check('player statistics', !!await waitFor(moderator, /Selbst gemeldet: 3/, { from: staffMark }));

  moderator.quit();
  await sleep(1500);
  const returning = await connect('Moderator', PROXY_1);
  check('login reminder counts open reports on proxy-1', !!await waitFor(returning, /Du hast aktuell 1 offene Reports/));

  returning.quit();
  reporter.quit();
  finish();
})().catch(error => {
  console.error(error);
  process.exit(2);
});
