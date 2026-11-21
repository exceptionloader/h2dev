const mineflayer = require('mineflayer');

const VERSION = '1.21.3';
const PROXY_1 = 25577;
const PROXY_2 = 25578;

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function connect(username, port) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, version: VERSION, auth: 'offline' });
    bot.lines = [];
    bot.json = [];
    bot.on('message', message => {
      bot.lines.push(message.toString());
      bot.json.push(JSON.stringify(message.json));
      if (process.env.VERBOSE) console.log(`  [${username}] ${message.toString()}`);
    });
    bot.on('error', error => console.log(`  [${username}] ${error.message}`));
    const timer = setTimeout(() => reject(new Error(`${username} did not spawn`)), 30000);
    bot.once('spawn', () => {
      clearTimeout(timer);
      resolve(bot);
    });
  });
}

/** Resolves with the first chat line after `from` matching `pattern`, or null after `timeout`. */
async function waitFor(bot, pattern, { from = 0, timeout = 8000 } = {}) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    const line = bot.lines.slice(from).find(text => pattern.test(text));
    if (line) return line;
    await sleep(100);
  }
  return null;
}

function nextWindow(bot, timeout = 10000) {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(null), timeout);
    bot.once('windowOpen', window => {
      clearTimeout(timer);
      resolve(window);
    });
  });
}

async function itemAt(bot, slot, timeout = 6000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    const item = bot.currentWindow && bot.currentWindow.slots[slot];
    if (item) return item;
    await sleep(100);
  }
  return null;
}

async function click(bot, slot) {
  if (await itemAt(bot, slot)) {
    await bot.clickWindow(slot, 0, 0);
    return true;
  }
  return false;
}

/** Runs `/report <name>` and picks the template in `slot` of the template menu. */
async function report(bot, name, slot) {
  const menu = nextWindow(bot);
  bot.chat(`/report ${name}`);
  return (await menu) !== null && click(bot, slot);
}

function reportIds(bot, from = 0) {
  return bot.json.slice(from)
      .map(json => json.match(/\/report details ([0-9a-f]{24})/))
      .filter(Boolean)
      .map(match => match[1]);
}

function checklist() {
  const results = [];
  return {
    check(name, ok, detail = '') {
      results.push(ok);
      console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
    },
    finish() {
      const passed = results.filter(Boolean).length;
      console.log(`\n${passed}/${results.length} checks passed`);
      process.exit(passed === results.length ? 0 : 1);
    },
  };
}

// template slots in the default menu: one row, starting at slot 10
const TEMPLATE = { CHEATING: 10, INSULT: 11, BUGUSING: 12, GRIEFING: 13, SPAM: 14, OTHER: 15 };

module.exports = { PROXY_1, PROXY_2, TEMPLATE, sleep, connect, waitFor, nextWindow, itemAt, click, report, reportIds, checklist };
