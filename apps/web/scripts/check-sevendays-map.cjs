// Browser regression check with synthetic terrain; no live server or credentials.
// PLAYWRIGHT_MODULE may point to an existing Playwright installation; CHROME_PATH is optional.
// MAP_BASELINE=1 measures the original implementation for the same interaction sequence.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { createRequire } = require('node:module');
const repo = path.resolve(__dirname, '../../..');
const req = createRequire(path.join(repo, 'package.json'));
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const baseline = process.env.MAP_BASELINE === '1';

(async () => {
  const catalog = JSON.parse(
    fs.readFileSync(path.join(repo, 'apps/web/src/i18n/catalogs/en.json')),
  );
  const bundle = await req('esbuild').build({
    stdin: {
      contents: `import React from 'react';import{createRoot}from'react-dom/client';import{SevenDaysMapTab}from'./apps/web/src/modules/sevendays/MapTab';const root=createRoot(document.getElementById('root'));window.unmount=()=>root.unmount();root.render(<SevenDaysMapTab serverId="fixture"/>);`,
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
          if (baseline)
            b.onLoad({ filter: /sevendays[\\/]MapTab\.tsx$/ }, () => ({
              contents: execFileSync(
                'git',
                ['show', 'fb135c2:apps/web/src/modules/sevendays/MapTab.tsx'],
                { cwd: repo, encoding: 'utf8' },
              ),
              loader: 'tsx',
            }));
          b.onResolve({ filter: /\/i18n$/ }, () => ({ path: 'locale', namespace: 'mock' }));
          b.onResolve({ filter: /\/lib\/api$/ }, () => ({ path: 'api', namespace: 'mock' }));
          b.onLoad({ filter: /.*/, namespace: 'mock' }, ({ path: name }) => ({
            loader: 'js',
            contents:
              name === 'locale'
                ? `const c=${JSON.stringify(catalog)};export const useI18n=()=>({t:k=>c[k]||k});export const useT=()=>useI18n().t;`
                : `window.stats={calls:0,snapshots:0,active:0,max:0};const canvas=document.createElement('canvas');canvas.width=canvas.height=128;const ctx=canvas.getContext('2d');ctx.fillStyle='#344e38';ctx.fillRect(0,0,128,128);const png=canvas.toDataURL().split(',')[1];export async function api(p,init){window.stats.calls++;if(!p.includes('/tiles/')){window.stats.snapshots++;return {available:true,info:{blockSize:128,maxZoom:4},players:[{id:'p',name:'Test Player',x:-320,z:200}],claims:[{ownerId:'p',owner:'Test Owner',x:-180,z:150,size:41}],truncated:false}}window.stats.active++;window.stats.max=Math.max(window.stats.max,window.stats.active);try{await new Promise((resolve,reject)=>{const id=setTimeout(resolve,60);init.signal.addEventListener('abort',()=>{clearTimeout(id);reject(new DOMException('aborted','AbortError'))},{once:true})});if(window.failTiles)throw Error('map_busy');return {png:window.emptyTiles?null:png}}finally{window.stats.active--}}`,
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
    const page = await browser.newPage({ viewport: { width: 1200, height: 900 }, hasTouch: true });
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.waitForFunction(() => document.querySelectorAll('svg image').length >= 28);
    await page.evaluate(() => {
      window.blankFrames = 0;
      window.monitor = true;
      const tick = () => {
        if (!window.monitor) return;
        if (!document.querySelector('svg image')) window.blankFrames++;
        requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    });
    await page.getByLabel(catalog['sdtd.map.zoomIn'], { exact: true }).click();
    await page.waitForTimeout(1700);
    const zoomBlankFrames = await page.evaluate(() => {
      const n = window.blankFrames;
      window.blankFrames = 0;
      return n;
    });
    await page.waitForFunction(() => window.stats.snapshots >= 2, null, { timeout: 15000 });
    await page.waitForTimeout(1000);
    const refreshBlankFrames = await page.evaluate(() => {
      window.monitor = false;
      return window.blankFrames;
    });
    console.log(JSON.stringify({ baseline, zoomBlankFrames, refreshBlankFrames }));
    if (baseline) return;
    assert.equal(zoomBlankFrames, 0, 'zoom must keep the previous terrain visible');
    assert.equal(refreshBlankFrames, 0, 'polling must not clear terrain');
    const claim = page.getByRole('button', { name: /Test Owner/ });
    await claim.click();
    await page.getByRole('status').filter({ hasText: 'Test Owner' }).waitFor();
    await claim.focus();
    await page.keyboard.press('Enter');
    const before = await page.getByRole('status').innerText();
    // Drag starts on a marker too: must pan, not select text or fire a click.
    for (const locator of [
      claim,
      page.locator('svg text').filter({ hasText: 'N ↑' }),
      page.locator('svg text').filter({ hasText: 'Test Player' }),
    ]) {
      const box = await locator.boundingBox();
      assert.ok(box);
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
      await page.mouse.move(box.x + box.width / 2 + 18, box.y + box.height / 2 + 12, { steps: 6 });
      await page.mouse.up();
      assert.equal(
        await page.evaluate(() => String(getSelection())),
        '',
        'drag must not select labels',
      );
      assert.equal(
        await page.getByRole('status').innerText(),
        before,
        'drag must not activate marker',
      );
    }
    await page.setViewportSize({ width: 390, height: 700 });
    const hit = await claim.locator('[data-map-hit]').boundingBox();
    assert.ok(hit.width >= 23 && hit.height >= 23, 'small claim needs usable hit area');
    await page.touchscreen.tap(hit.x + hit.width / 2, hit.y + hit.height / 2);
    await page.getByRole('status').filter({ hasText: 'Test Owner' }).waitFor();
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1),
      false,
    );
    if (process.env.MAP_SCREENSHOT_DIR) {
      await page.screenshot({
        path: path.join(process.env.MAP_SCREENSHOT_DIR, '7dtd-map-fixed-mobile.png'),
        fullPage: true,
      });
      await page.setViewportSize({ width: 1200, height: 900 });
      await page.screenshot({
        path: path.join(process.env.MAP_SCREENSHOT_DIR, '7dtd-map-fixed-desktop.png'),
        fullPage: true,
      });
    }
    assert.ok(await page.evaluate(() => window.stats.max <= 2), 'at most two tile requests');
    assert.ok((await page.locator('svg image').count()) <= 96, 'bounded terrain memory/DOM');
    await page.evaluate(() => {
      window.failTiles = true;
    });
    await page.getByLabel(catalog['sdtd.map.zoomIn'], { exact: true }).click();
    await page.getByText(catalog['sdtd.map.tileError'], { exact: true }).waitFor();
    assert.ok(
      (await page.locator('svg image').count()) > 0,
      'network errors must not erase old terrain',
    );
    await page.evaluate(() => window.unmount());
    const calls = await page.evaluate(() => window.stats.calls);
    await page.waitForTimeout(400);
    assert.equal(await page.evaluate(() => window.stats.calls), calls);
    assert.deepEqual(errors, []);
    console.log(
      'PASS: no blank refresh/zoom frames, click/keyboard/touch claims, marker/compass drag without text selection, bounded requests/tiles, failure fallback, unmount cleanup.',
    );
  } finally {
    await browser.close();
    await new Promise((r) => server.close(r));
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
