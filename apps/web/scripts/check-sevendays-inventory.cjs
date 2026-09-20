// Synthetic fixtures only: tests the actual player list and inventory component, no game writes.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const repo = path.resolve(__dirname, '../../..');
const req = createRequire(path.join(repo, 'package.json'));
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  const catalog = JSON.parse(
    fs.readFileSync(path.join(repo, 'apps/web/src/i18n/catalogs/ru.json')),
  );
  const bundle = await req('esbuild').build({
    stdin: {
      contents: `import React from 'react';import{createRoot}from'react-dom/client';import{SevenDaysPlayersTab}from'./apps/web/src/modules/sevendays/tabs';const root=createRoot(document.getElementById('root'));window.allowed=false;let n=0;window.render=()=>root.render(<SevenDaysPlayersTab key={n++} serverId="fixture"/>);window.unmount=()=>root.unmount();window.render();`,
      loader: 'tsx',
      resolveDir: repo,
    },
    bundle: true,
    write: false,
    jsx: 'automatic',
    plugins: [
      {
        name: 'fixtures',
        setup(b) {
          b.onResolve({ filter: /\/i18n$/ }, () => ({ path: 'locale', namespace: 'mock' }));
          b.onResolve({ filter: /\/lib\/(api|auth)$/ }, (a) => ({
            path: a.path.endsWith('auth') ? 'auth' : 'api',
            namespace: 'mock',
          }));
          b.onLoad({ filter: /.*/, namespace: 'mock' }, ({ path: name }) => ({
            loader: 'js',
            contents:
              name === 'locale'
                ? `const c=${JSON.stringify(catalog)};export const useI18n=()=>({locale:'ru',t:k=>c[k]||k});export const useT=()=>useI18n().t;`
                : name === 'auth'
                  ? `export const useAuth=()=>({hasPermission:k=>((k==='sevendays.inventory.view'||k==='sevendays.events.view')&&window.allowed)||(k==='sevendays.inventory.give'&&window.giveAllowed)||(k==='sevendays.inventory.edit'&&window.editAllowed)});`
                  : `window.stats={inventory:0,aborted:0,searches:0,grants:[]};window.spawnedIds=new Set();window.mode='ready';window.grantMode='lost';export async function api(p,init){
          if(p.includes('/items?q=')){window.stats.searches++; if(window.searchFails)throw Error('old companion');return {sessionId:'ac67354a-2548-4dc5-8f23-a43a90b55d9d',ready:true,truncated:true,items:[{itemId:1,name:'gunPistol',hasQuality:true,maxCount:1},{itemId:2,name:'ammoLongName'.repeat(8),hasQuality:false,maxCount:1000},...Array.from({length:20},(_,i)=>({itemId:i+3,name:'ammoFixture'+i,hasQuality:false,maxCount:1000}))]};}
          if(p.endsWith('/item-drop')){const g=JSON.parse(init.body);window.stats.grants.push(g);await new Promise(r=>setTimeout(r,120));if(window.grantMode==='offline')return {requestId:g.requestId,status:'offline'};window.spawnedIds.add(g.requestId);if(window.grantMode==='lost')throw Error('reply lost after spawn');return {requestId:g.requestId,status:'spawned'};}
          if(p.includes('/saved-players?')){window.savedCalls=(window.savedCalls||0)+1;return window.savedOld ? {ready:false,reason:'mod_update',players:[],hasMore:false,truncated:false} : {ready:true,players:[{id:'Steam_456',name:'Offline Test Player'}],hasMore:p.includes('offset=0'),truncated:false};}
          if(p.includes('/events?')){window.eventQuery=p;window.eventCalls=(window.eventCalls||0)+1;return Array.from({length:20},(_,i)=>({id:String(i),kind:i%2?'leave':'join',playerName:'Event Player '+i,occurredAt:'2026-09-20T12:00:00Z'}));}
          if(p.endsWith('/saved-stack')){window.edits=(window.edits||[]);const dto=JSON.parse(init.body);window.edits.push(dto);await new Promise(r=>setTimeout(r,100));return {requestId:dto.requestId,status:'unknown'};}
          if(p.endsWith('/saved-inventory')){window.savedReads=(window.savedReads||0)+1;return window.savedBusy ? {available:false,source:'saved_file',reason:'save_busy',items:[]} : {available:true,source:'saved_file',revision:'a'.repeat(64),savedAt:'2026-09-19T08:00:00Z',fetchedAt:'2026-09-20T12:00:00Z',slotCounts:{belt:10,bag:45,equipment:4,cursor:1},truncated:false,items:[{section:'belt',slot:0,itemId:1,name:'gunPistol',count:1,quality:6}]};}
          if(p.endsWith('/players'))return {online:1,players:[{entityId:1,name:'Test Player',platformId:'Steam_123',crossId:null,level:5,ping:20,position:null}]};
          if(p.endsWith('/state'))return {available:false,reason:'Test server'};
          if(p.endsWith('/inventory')){window.stats.inventory++;await new Promise((ok,no)=>{const timer=setTimeout(ok,80);init.signal.addEventListener('abort',()=>{window.stats.aborted++;clearTimeout(timer);no(new DOMException('aborted','AbortError'));},{once:true});});
          if(window.mode==='error')throw Error('Test network error');if(window.mode!=='ready')return {available:false,reason:window.mode,items:[]};
          return {available:true,slotCounts:window.legacyCounts?null:{belt:10,bag:45,equipment:4,cursor:1},source:'client_snapshot',fetchedAt:'2026-09-18T12:00:00Z',truncated:false,items:[{section:'belt',slot:0,itemId:1,name:'gunPistol',count:1,quality:6},{section:'bag',slot:42,itemId:2,name:'<script>'+('LongModdedItemName'.repeat(5))+'</script>',count:2147483647,quality:0}]};}return [];}`,
          }));
        },
      },
    ],
  });
  const assets = path.join(repo, 'apps/web/dist/assets');
  const css = fs.readFileSync(
    path.join(
      assets,
      fs.readdirSync(assets).find((f) => f.endsWith('.css')),
    ),
  );
  const server = http.createServer((r, s) => {
    if (r.url === '/app.js') {
      s.setHeader('content-type', 'text/javascript');
      return s.end(bundle.outputFiles[0].contents);
    }
    if (r.url === '/style.css') {
      s.setHeader('content-type', 'text/css');
      return s.end(css);
    }
    s.setHeader('content-type', 'text/html');
    s.end(
      '<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/style.css"></head><body style="padding:16px"><div id="root"></div><script src="/app.js"></script></body></html>',
    );
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}),
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.getByText('Test Player', { exact: true }).first().waitFor();
    assert.equal(
      await page
        .getByRole('button', { name: catalog['sdtd.inventory.title'], exact: true })
        .count(),
      0,
    );
    assert.equal(await page.evaluate(() => window.stats.inventory), 0);
    await page.evaluate(() => {
      window.allowed = true;
      window.render();
    });
    const open = () =>
      page
        .getByRole('button', { name: catalog['sdtd.inventory.title'], exact: true })
        .filter({ visible: true })
        .click();
    await open();
    await page.getByText('gunPistol', { exact: false }).waitFor();
    assert.equal(
      await page.getByRole('button', { name: catalog['sdtd.give.open'], exact: true }).count(),
      0,
    );
    assert.equal(await page.evaluate(() => document.activeElement.id), 'sdtd-inventory-title');
    const count = await page.evaluate(() => window.stats.inventory);
    await page.getByRole('button', { name: 'Обновить', exact: true }).click();
    await page.waitForTimeout(200);
    assert.equal(
      await page.evaluate(() => window.stats.inventory),
      count,
      'player-list refresh must not poll inventory',
    );
    const refresh = page.getByRole('button', {
      name: catalog['sdtd.inventory.refresh'],
      exact: true,
    });
    assert.equal(await page.locator('[data-inventory-slot]').count(), 60);
    const pistol = page.locator('[data-inventory-slot="belt:0"]');
    await pistol.focus();
    await page.keyboard.press('Enter');
    assert.equal(
      await page
        .locator('[data-inventory-details] h5')
        .evaluate((el) => el === document.activeElement),
      true,
    );
    await page.getByRole('button', { name: catalog['sdtd.inventory.closeDetails'] }).click();
    assert.equal(await pistol.evaluate((el) => el === document.activeElement), true);
    await page.locator('[data-inventory-slot="bag:42"]').click();
    assert.ok((await page.locator('[data-inventory-details]').innerText()).includes('<script>'));
    assert.equal(await page.locator('[data-inventory-details] script').count(), 0);
    assert.equal(await page.evaluate(() => window.stats.inventory), count);
    for (const [label, width, height] of [
      ['desktop', 1200, 1000],
      ['mobile', 390, 844],
    ]) {
      await page.setViewportSize({ width, height });
      assert.equal(
        await page
          .locator('button[data-inventory-slot]')
          .evaluateAll((els) => els.some((el) => el.scrollWidth > el.clientWidth + 1)),
        false,
        'item contents stay within their slots',
      );
      assert.equal(
        await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1),
        false,
      );
      if (process.env.INVENTORY_SCREENSHOT_DIR)
        await page.screenshot({
          path: path.join(process.env.INVENTORY_SCREENSHOT_DIR, `7dtd-inventory-${label}.png`),
          fullPage: true,
        });
    }
    for (const mode of ['offline', 'snapshot_pending', 'mod_update', 'mod_unavailable', 'error']) {
      await page.evaluate((m) => (window.mode = m), mode);
      await refresh.click();
      await page
        .getByText(mode === 'error' ? 'Test network error' : catalog['sdtd.inventory.' + mode], {
          exact: true,
        })
        .waitFor();
      assert.equal(
        await page.getByText('gunPistol', { exact: false }).count(),
        0,
        'old items must not survive failed refresh',
      );
      assert.equal(await page.locator('[data-inventory-details]').count(), 0);
    }
    await page.evaluate(() => {
      window.mode = 'ready';
      window.legacyCounts = true;
    });
    await refresh.click();
    await page.getByText(catalog['sdtd.inventory.unknownCapacity'], { exact: true }).waitFor();
    assert.equal(
      await page.locator('[data-inventory-slot]').count(),
      2,
      'old mod must not invent empty slots',
    );
    await page.getByRole('button', { name: catalog['common.close'], exact: true }).click();
    assert.equal(
      await page.evaluate(() => document.activeElement.textContent),
      catalog['sdtd.inventory.title'],
    );
    await page.evaluate(() => {
      window.giveAllowed = true;
      window.render();
    });
    await open();
    await page.getByRole('button', { name: catalog['sdtd.give.open'], exact: true }).click();
    const searchInput = page.getByLabel(catalog['sdtd.give.search'], { exact: true });
    assert.equal(await searchInput.evaluate((el) => el === document.activeElement), true);
    assert.equal(await page.evaluate(() => window.stats.searches), 0);
    await searchInput.fill('a');
    await page.waitForTimeout(450);
    assert.equal(await page.evaluate(() => window.stats.searches), 0, 'requires two characters');
    await searchInput.fill('am');
    await page.waitForTimeout(120);
    await searchInput.fill('ammo');
    await page.waitForTimeout(120);
    assert.equal(await page.evaluate(() => window.stats.searches), 0, 'typing is debounced');
    await page.waitForFunction(() => document.querySelectorAll('[role="option"]').length === 21);
    assert.equal(await page.locator('datalist').count(), 0);
    const suggestions = page.getByRole('listbox');
    for (const [label, width, height] of [
      ['desktop', 1200, 1000],
      ['mobile', 390, 844],
    ]) {
      await page.setViewportSize({ width, height });
      await suggestions.scrollIntoViewIfNeeded();
      assert.equal(
        await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1),
        false,
      );
      if (process.env.INVENTORY_SCREENSHOT_DIR)
        await page.screenshot({
          path: path.join(process.env.INVENTORY_SCREENSHOT_DIR, `7dtd-give-list-${label}.png`),
          fullPage: true,
        });
    }
    await suggestions.hover();
    await page.mouse.wheel(0, 250);
    await page.waitForFunction(() => document.querySelector('[role="listbox"]').scrollTop > 0);
    assert.equal(
      await searchInput.getAttribute('aria-expanded'),
      'true',
      'wheel must not dismiss suggestions',
    );
    await searchInput.press('Escape');
    assert.equal(await suggestions.count(), 0);
    await searchInput.press('ArrowDown');
    await searchInput.press('Enter');
    assert.equal(await suggestions.count(), 0);
    assert.equal(await searchInput.inputValue(), 'ammoLongName'.repeat(8));
    assert.equal(
      await page.evaluate(() => window.stats.grants.length),
      0,
      'Enter selects, never grants',
    );
    await searchInput.fill('gunPistol');
    const qualitySelect = page.getByLabel(catalog['sdtd.inventory.quality'], { exact: true });
    await qualitySelect.selectOption('6');
    assert.deepEqual(
      await qualitySelect.locator('option').evaluateAll((els) => els.map((el) => el.value)),
      ['1', '2', '3', '4', '5', '6'],
    );
    await searchInput.fill('ammoLongName'.repeat(8));
    assert.equal(await qualitySelect.count(), 0, 'quality is hidden after switching to ammunition');
    await page.waitForTimeout(500);
    assert.equal(
      await page.evaluate(() => window.stats.searches),
      1,
      'selection reuses suggestions',
    );
    const amount = page.getByRole('spinbutton');
    const give = page.getByRole('button', { name: catalog['sdtd.give.submit'], exact: true });
    await amount.fill('1001');
    assert.equal(await page.getByLabel(catalog['sdtd.give.reason'], { exact: true }).count(), 0);
    assert.equal(
      await page.getByRole('button', { name: catalog['sdtd.give.review'], exact: true }).count(),
      0,
    );
    await give.click();
    assert.equal(
      await page.evaluate(() => window.stats.grants.length),
      0,
      'invalid count must not mutate',
    );
    await amount.fill('1000');
    for (const [label, width, height] of [
      ['desktop', 1200, 1000],
      ['mobile', 390, 844],
    ]) {
      await page.setViewportSize({ width, height });
      assert.equal(
        await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1),
        false,
      );
      if (process.env.INVENTORY_SCREENSHOT_DIR)
        await page.screenshot({
          path: path.join(process.env.INVENTORY_SCREENSHOT_DIR, `7dtd-give-${label}.png`),
          fullPage: true,
        });
    }
    await give.evaluate((el) => {
      el.click();
      el.click();
    });
    await page.getByText(catalog['sdtd.give.unknown'], { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.stats.grants.length), 1);
    assert.equal(
      await searchInput.isDisabled(),
      true,
      'uncertain request freezes the original payload',
    );
    await page.evaluate(() => {
      window.grantMode = 'ready';
    });
    await page.getByRole('button', { name: catalog['sdtd.give.retry'], exact: true }).click();
    await page.getByText(catalog['sdtd.give.spawned'], { exact: true }).waitFor();
    const grants = await page.evaluate(() => window.stats.grants);
    assert.deepEqual(grants[0], grants[1], 'retry retains the entire confirmed payload');
    assert.equal(await page.evaluate(() => window.spawnedIds.size), 1);
    assert.equal(grants[0].count, 1000);
    assert.equal(grants[0].quality, 0);
    assert.equal(grants[0].reason, 'Panel item grant');
    assert.equal(grants[0].confirmed, true);
    assert.equal(await amount.inputValue(), '1000', 'keep values for the next grant');
    await give.click();
    await page.getByText(catalog['sdtd.give.spawned'], { exact: true }).waitFor();
    assert.equal(
      await page.evaluate(() => window.spawnedIds.size),
      2,
      'explicit next Give creates another grant',
    );
    assert.notEqual(
      await page.evaluate(() => window.stats.grants[2].requestId),
      grants[0].requestId,
    );
    await page.evaluate(() => {
      window.searchFails = true;
    });
    await searchInput.fill('gun');
    await page
      .getByRole('button', { name: catalog['sdtd.give.retrySearch'], exact: true })
      .waitFor();
    await page.evaluate(() => {
      window.searchFails = false;
    });
    await page.getByRole('button', { name: catalog['sdtd.give.retrySearch'], exact: true }).click();
    await page.getByRole('option').filter({ hasText: 'gunPistol' }).click();
    assert.equal(
      await suggestions.count(),
      0,
      'mouse selection closes the list even after a retry',
    );
    assert.equal(await qualitySelect.inputValue(), '1', 'new item resets quality');
    await page.getByLabel(catalog['sdtd.inventory.quality'], { exact: true }).selectOption('6');
    assert.equal(await page.getByRole('spinbutton').getAttribute('max'), '1');
    await page.evaluate(() => {
      window.grantMode = 'offline';
    });
    await give.click();
    await page.getByText(catalog['sdtd.give.offline'], { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.stats.grants.at(-1).quality), 6);
    await page.getByRole('button', { name: catalog['sdtd.give.close'], exact: true }).click();
    assert.equal(
      await page
        .getByRole('button', { name: catalog['sdtd.give.open'], exact: true })
        .evaluate((el) => el === document.activeElement),
      true,
    );
    await page.getByRole('button', { name: catalog['sdtd.give.open'], exact: true }).click();
    await page.getByText(catalog['sdtd.give.offline'], { exact: true }).waitFor();
    assert.equal(
      await page.evaluate(() => window.stats.grants.length),
      4,
      'reopening preserves result without another write',
    );
    await page.getByRole('button', { name: catalog['common.close'], exact: true }).click();
    await open();
    await page.getByRole('button', { name: catalog['common.close'], exact: true }).click();
    await page.waitForTimeout(120);
    assert.equal(await page.evaluate(() => window.savedCalls || 0), 0, 'saved catalogue is lazy');
    await page
      .getByRole('button', { name: catalog['sdtd.inventory.savedPlayers'], exact: true })
      .click();
    await page.getByText('Offline Test Player', { exact: true }).waitFor();
    await page
      .getByRole('button', { name: catalog['sdtd.inventory.savedNext'], exact: true })
      .click();
    await page
      .getByRole('button', { name: catalog['sdtd.inventory.savedNext'], exact: true })
      .isDisabled();
    await page
      .getByRole('button', { name: catalog['sdtd.inventory.savedOpen'], exact: true })
      .click();
    await page.getByText(catalog['sdtd.inventory.savedNote'], { exact: true }).waitFor();
    await page.locator('[data-inventory-slot="belt:0"]').waitFor();
    assert.equal(
      await page.getByRole('button', { name: catalog['sdtd.give.open'], exact: true }).count(),
      0,
      'no grant control for saved inventory',
    );
    assert.match(await page.locator('#sdtd-inventory').innerText(), /19\.09\.2026/);
    for (const [label, width] of [
      ['desktop', 1200],
      ['mobile', 390],
    ]) {
      await page.setViewportSize({ width, height: 900 });
      assert.equal(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        true,
      );
      if (process.env.INVENTORY_SCREENSHOT_DIR)
        await page.screenshot({
          path: path.join(process.env.INVENTORY_SCREENSHOT_DIR, `7dtd-saved-${label}.png`),
          fullPage: true,
        });
    }
    await page.locator('[data-inventory-slot="belt:0"]').click();
    assert.equal(await page.getByText(catalog['sdtd.edit.note'], { exact: true }).count(), 0, 'view permission does not permit edits');
    await page.evaluate(() => { window.editAllowed = true; });
    await page.getByRole('button', { name: catalog['sdtd.inventory.closeDetails'], exact: true }).click();
    await page.locator('[data-inventory-slot="belt:0"]').click();
    const removeStack = page.getByRole('button', { name: catalog['sdtd.edit.remove'], exact: true });
    assert.equal(await removeStack.isDisabled(), true);
    await page.getByLabel(catalog['sdtd.edit.confirm'], { exact: true }).check();
    for (const [label, width] of [['desktop', 1200], ['mobile', 390]]) {
      await page.setViewportSize({ width, height: 900 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      if(process.env.INVENTORY_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.INVENTORY_SCREENSHOT_DIR, `7dtd-edit-${label}.png`), fullPage: true });
    }
    await removeStack.evaluate(el => {el.click(); el.click();});
    await page.getByText(catalog['sdtd.edit.unknown'], { exact: false }).waitFor();
    assert.equal(await page.evaluate(() => window.edits.length), 1);
    assert.equal(await removeStack.isDisabled(), true, 'unknown outcome locks the stale form');
    assert.equal(await page.evaluate(() => window.edits[0].revision), 'a'.repeat(64));
    await page.evaluate(() => { window.savedBusy = true; });
    await page
      .getByRole('button', { name: catalog['sdtd.inventory.refresh'], exact: true })
      .click();
    await page.getByText(catalog['sdtd.inventory.save_busy'], { exact: true }).waitFor();
    assert.equal(
      await page.locator('[data-inventory-slot]').count(),
      0,
      'busy save never leaves stale inventory',
    );
    await page.getByRole('button', { name: catalog['common.close'], exact: true }).click();
    await page.evaluate(() => {
      window.savedOld = true;
    });
    await page
      .getByRole('button', { name: catalog['sdtd.inventory.savedFind'], exact: true })
      .click();
    await page.getByText(catalog['sdtd.inventory.mod_update'], { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.eventCalls || 0), 0, 'collapsed log does not fetch');
    await page.getByRole('button', { name: catalog['sdtd.events.expand'], exact: true }).click();
    await page.getByText('Event Player 0', { exact: true }).waitFor();
    assert.equal(await page.locator('#sdtd-events-content li').count(), 5);
    assert.match(await page.evaluate(() => window.eventQuery), /limit=5/);
    if(process.env.INVENTORY_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.INVENTORY_SCREENSHOT_DIR, '7dtd-events-mobile.png'), fullPage: true });
    await page.getByRole('button', { name: catalog['sdtd.events.collapse'], exact: true }).click();
    assert.equal(await page.locator('#sdtd-events-content').count(), 0);
    const calls = await page.evaluate(() => window.stats.inventory);
    await page.evaluate(() => window.unmount());
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => window.stats.inventory), calls);
    assert.deepEqual(errors, []);
    console.log(
      'PASS: permission gating, manual inventory, panel list with wheel/keyboard selection, debounced search and retry, conditional quality 1–6, desktop/mobile layout; one-click grants without reason/review, bounds, double-click guard, stable retry ID, repeated grant, offline target and reopen without writes.',
    );
  } finally {
    await browser.close();
    await new Promise((r) => server.close(r));
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
