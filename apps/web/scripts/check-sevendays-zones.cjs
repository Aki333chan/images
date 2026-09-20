// Synthetic map/editor regression. Never connects to a game server.
const fs = require('node:fs'), path = require('node:path'), http = require('node:http'), assert = require('node:assert/strict');
const repo = path.resolve(__dirname, '../../..');
const req = require('node:module').createRequire(path.join(repo, 'package.json'));
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  const catalog = JSON.parse(fs.readFileSync(path.join(repo, 'apps/web/src/i18n/catalogs/ru.json')));
  const bundle = await req('esbuild').build({ stdin: {
    contents: `import React from 'react';import{createRoot}from'react-dom/client';import{SevenDaysMapTab}from'./apps/web/src/modules/sevendays/MapTab';const root=createRoot(document.getElementById('root'));let n=0;window.allowed=true;window.render=()=>root.render(<SevenDaysMapTab key={n++} serverId="fixture"/>);window.render();`,
    loader: 'tsx', resolveDir: repo }, bundle: true, write: false, jsx: 'automatic', plugins: [{ name: 'fixtures', setup(b) {
      b.onResolve({ filter: /\/i18n$/ }, () => ({ path: 'i18n', namespace: 'mock' }));
      b.onResolve({ filter: /\/lib\/(api|auth)$/ }, a => ({ path: a.path.endsWith('auth') ? 'auth' : 'api', namespace: 'mock' }));
      b.onLoad({ filter: /.*/, namespace: 'mock' }, ({ path: name }) => ({ loader: 'js', contents: name === 'i18n'
        ? `const c=${JSON.stringify(catalog)};export const useI18n=()=>({t:k=>c[k]||k});`
        : name === 'auth' ? `export const useAuth=()=>({hasPermission:()=>window.allowed});`
        : `window.calls=[];window.zones={revision:0,worldId:'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',zones:[]};export async function api(url,init){let body=init?.body?JSON.parse(init.body):null;window.calls.push({url,body});if(url.endsWith('/zones')){if(body){if(window.conflict)throw Error('zones_revision_conflict');window.zones={...body,revision:body.revision+1};}return structuredClone(window.zones);}if(url.endsWith('/map'))return {available:true,reason:'native_map_missing',info:null,players:[{id:'p',name:'Test player',x:0,z:0}],claims:[],truncated:false};throw Error('Unexpected fixture '+url);}` }));
    }}] });
  const assets = path.join(repo, 'apps/web/dist/assets');
  const css = fs.readFileSync(path.join(assets, fs.readdirSync(assets).find(f => f.endsWith('.css'))));
  const server = http.createServer((r, s) => {
    if (r.url === '/app.js') { s.setHeader('content-type', 'text/javascript'); return s.end(bundle.outputFiles[0].contents); }
    if (r.url === '/style.css') { s.setHeader('content-type', 'text/css'); return s.end(css); }
    if (r.url?.startsWith('/assets/')) { const f = path.join(assets, path.basename(r.url)); if (fs.existsSync(f)) return s.end(fs.readFileSync(f)); s.statusCode = 404; return s.end(); }
    s.setHeader('content-type', 'text/html');
    s.end('<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/style.css"></head><body style="padding:16px"><p>Synthetic zone editor test — not a live game</p><div id="root"></div><script src="/app.js"></script></body></html>');
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', dialog => dialog.accept());
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    const button = key => page.getByRole('button', { name: catalog[key], exact: true });
    await button('sdtd.zones.title').click();
    await button('sdtd.zones.add').click();
    const map = page.locator('svg[role="group"]');
    await map.click({ position: { x: 150, y: 80 } });
    await map.click({ position: { x: 350, y: 250 } });
    await page.getByLabel(catalog['sdtd.zones.name'], { exact: true }).fill('Безопасная площадь');
    assert.ok(Number(await page.getByLabel('X1', { exact: true }).inputValue()) < Number(await page.getByLabel('X2', { exact: true }).inputValue()));
    assert.equal(await page.getByLabel(catalog['sdtd.zones.noPvp'], { exact: true }).isChecked(), true);
    assert.equal(await page.getByLabel(catalog['sdtd.zones.noDamage'], { exact: true }).isChecked(), false);
    await button('sdtd.zones.save').dblclick();
    await page.getByText(catalog['sdtd.zones.saved'], { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.calls.filter(c => c.body).length), 1);
    assert.equal(await map.locator('[data-zone-id]').count(), 1);
    // Normal editing updates the same rectangle, not a second zone.
    await page.getByLabel(catalog['sdtd.zones.type'], { exact: true }).selectOption('sanctuary');
    await button('sdtd.zones.save').click();
    await page.waitForFunction(() => window.zones.revision === 2);
    assert.equal(await page.evaluate(() => window.zones.zones.length), 1);
    assert.equal(await page.evaluate(() => window.zones.zones[0].blockSpawn), 5);
    assert.equal(await page.evaluate(() => window.zones.zones[0].despawn), 0);
    // Keyboard selection from the drawn rectangle.
    await button('sdtd.zones.close').click();
    await map.locator('[data-zone-id]').focus(); await page.keyboard.press('Enter');
    await page.getByLabel(catalog['sdtd.zones.name'], { exact: true }).waitFor();
    for (const [name, width] of [['desktop', 1200], ['mobile', 390]]) {
      await page.setViewportSize({ width, height: 900 });
      await page.evaluate(() => document.fonts.ready); await page.evaluate(() => scrollTo(0, 0));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      if (process.env.ZONES_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.ZONES_SCREENSHOT_DIR, `zones-${name}.png`), fullPage: true });
    }
    await page.evaluate(() => window.conflict = true);
    await page.getByLabel(catalog['sdtd.zones.name'], { exact: true }).fill('Unsaved edit');
    await button('sdtd.zones.save').click();
    await page.getByText('zones_revision_conflict', { exact: false }).waitFor();
    assert.equal(await page.getByLabel(catalog['sdtd.zones.name'], { exact: true }).inputValue(), 'Unsaved edit');
    await page.evaluate(() => { window.allowed = false; window.render(); });
    await button('sdtd.zones.title').click();
    assert.equal(await button('sdtd.zones.add').count(), 0);
    assert.deepEqual(errors, []);
    console.log('PASS: two corners, default rules, one save per double click, stable edit, keyboard selection, conflict retains draft, read-only controls, desktop/mobile overflow.');
  } finally { await browser.close(); await new Promise(r => server.close(r)); }
})().catch(e => { console.error(e); process.exitCode = 1; });
