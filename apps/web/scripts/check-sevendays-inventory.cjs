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
                  ? `export const useAuth=()=>({hasPermission:k=>k==='sevendays.inventory.view'&&window.allowed});`
                  : `window.stats={inventory:0,aborted:0};window.mode='ready';export async function api(p,init){
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
    await open();
    await page.getByRole('button', { name: catalog['common.close'], exact: true }).click();
    await page.waitForTimeout(120);
    const calls = await page.evaluate(() => window.stats.inventory);
    await page.evaluate(() => window.unmount());
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => window.stats.inventory), calls);
    assert.deepEqual(errors, []);
    console.log(
      'PASS: permission gating, manual-only fetch, safe text, desktop/mobile layout, unavailable/error states, focus restoration and cancellation.',
    );
  } finally {
    await browser.close();
    await new Promise((r) => server.close(r));
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
