// Synthetic UI regression only: no game writes or real schedules.
const fs = require('node:fs'),
  path = require('node:path'),
  http = require('node:http'),
  assert = require('node:assert/strict');
const repo = path.resolve(__dirname, '../../..');
const { createRequire } = require('node:module'),
  req = createRequire(path.join(repo, 'package.json'));
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  const catalog = JSON.parse(
    fs.readFileSync(path.join(repo, 'apps/web/src/i18n/catalogs/ru.json')),
  );
  const bundle = await req('esbuild').build({
    stdin: {
      contents: `import React from 'react';import{createRoot}from'react-dom/client';import{SevenDaysToolsPanel}from'./apps/web/src/modules/sevendays/ToolsPanel';const root=createRoot(document.getElementById('root'));let n=0;window.allowed=false;function Fixture(){const[x,setX]=React.useState(15);return <SevenDaysToolsPanel serverId="fixture" onRefreshPlayers={async()=>{window.playerRefreshes=(window.playerRefreshes||0)+1;setX(25)}} players={[{platformId:'Steam_1',crossId:null,name:'Test Player',position:{x,y:64,z:-20}},{platformId:'Steam_2',crossId:null,name:'Other Player',position:{x:0,y:64,z:0}}]}/>};window.render=()=>root.render(<Fixture key={n++}/>);window.render();`,
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
          b.onResolve({ filter: /\/i18n$/ }, () => ({ path: 'i18n', namespace: 'mock' }));
          b.onResolve({ filter: /\/lib\/(api|auth)$/ }, (a) => ({
            path: a.path.endsWith('auth') ? 'auth' : 'api',
            namespace: 'mock',
          }));
          b.onLoad({ filter: /.*/, namespace: 'mock' }, ({ path: name }) => ({
            loader: 'js',
            contents:
              name === 'i18n'
                ? `const c=${JSON.stringify(catalog)};export const useI18n=()=>({t:k=>c[k]||k});`
                : name === 'auth'
                  ? `export const useAuth=()=>({hasPermission:k=>window.allowed&&(window.perms?window.perms.includes(k):true)});`
                  : `
  window.calls=[];window.tools={revision:0,points:[],kits:[]};export async function api(url,init){const body=init?.body?JSON.parse(init.body):null;window.calls.push({url,body,method:init?.method||'GET'});
  if(url.endsWith('/tools')){if(body){if(window.conflict)throw Error('revision conflict');window.tools={...body,revision:body.revision+1};}return structuredClone(window.tools);}
  if(url.includes('/items?q='))return {sessionId:'550e8400-e29b-41d4-a716-446655440000',ready:true,truncated:false,items:[{itemId:1,name:'gunPistol',maxCount:1,hasQuality:true},{itemId:2,name:'resourceRock',maxCount:1000,hasQuality:false}]};
  if(url.endsWith('/teleport')){await new Promise(r=>setTimeout(r,50));if(window.lost)throw Error('lost reply');return {status:'sent'};}
  if(url.endsWith('/kit'))return {status:'partial',items:[{name:'gunPistol',status:'spawned'},{name:'resourceRock',status:'offline'}]};
  if(url.endsWith('/schedules'))return {id:77};if(url.endsWith('/tasks')){if(window.stepFailed)throw Error('lost step reply');return {id:9}};throw Error('Unexpected fixture '+url);}`,
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
    if (r.url?.startsWith('/assets/')) {
      const f = path.join(assets, path.basename(r.url));
      if (fs.existsSync(f)) return s.end(fs.readFileSync(f));
      s.statusCode = 404;
      return s.end();
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
    const button = (key) => page.getByRole('button', { name: catalog[key], exact: true });
    assert.equal(await button('sdtd.tools.title').count(), 0);
    await page.evaluate(() => {
      window.allowed = true;
      window.render();
    });
    await button('sdtd.tools.title').click();
    await page.locator('#sdtd-tools-player').selectOption('Steam_1');
    await button('sdtd.tools.usePosition').click();
    assert.equal(await page.getByLabel('X', { exact: true }).inputValue(), '15');
    await button('common.refresh').click();
    await button('sdtd.tools.usePosition').click();
    assert.equal(await page.getByLabel('X', { exact: true }).inputValue(), '25');
    assert.equal(await page.evaluate(() => window.playerRefreshes), 1);
    await page.getByLabel(catalog['sdtd.tools.pointName'], { exact: true }).fill('Home');
    await button('sdtd.tools.savePoint').click();
    await page.waitForFunction(() => window.tools.points.length === 1);
    await button('sdtd.tools.savePoint').click();
    await page.waitForTimeout(80);
    assert.equal(
      await page.evaluate(() => window.tools.points.length),
      1,
      'save point updates instead of duplicating',
    );
    await page.getByLabel(catalog['sdtd.tools.confirmTeleport'], { exact: true }).check();
    await button('sdtd.tools.executeTeleport').dblclick();
    await page.getByText(catalog['sdtd.tools.result.sent'], { exact: true }).waitFor();
    assert.equal(
      await page.evaluate(() => window.calls.filter((c) => c.url.endsWith('/teleport')).length),
      1,
    );
    const capture = async (suffix) => {
      for (const [name, width] of [
        ['desktop', 1200],
        ['mobile', 390],
      ]) {
        await page.setViewportSize({ width, height: 900 });
        await page.evaluate(() => document.fonts.ready);
        await page.evaluate(() => scrollTo(0, 0));
        await page.evaluate(() => window.getSelection()?.removeAllRanges());
        await page.waitForTimeout(250); // settle incumbent 200ms control transitions
        assert.equal(
          await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
          true,
        );
        if (process.env.TOOLS_SCREENSHOT_DIR)
          await page.screenshot({
            path: path.join(process.env.TOOLS_SCREENSHOT_DIR, `7dtd-tools-${suffix}-${name}.png`),
            fullPage: true,
          });
      }
    };
    await capture('teleport');
    await button('sdtd.tools.kits').click();
    await page.getByLabel(catalog['sdtd.tools.name'], { exact: true }).fill('Starter');
    await button('sdtd.tools.addItem').click();
    const search = page.getByRole('combobox', { name: catalog['sdtd.give.search'] });
    await search.fill('gun');
    await page.getByRole('option').filter({ hasText: 'gunPistol' }).click();
    await page.getByLabel(catalog['sdtd.inventory.quality'], { exact: true }).selectOption('6');
    await button('sdtd.tools.addItem').and(page.locator('button:enabled')).focus();
    await page.keyboard.press('Enter');
    await page.waitForFunction(
      (label) => document.activeElement?.textContent === label,
      catalog['sdtd.tools.addItem'],
    );
    await button('sdtd.tools.addItem').click();
    await search.fill('rock');
    await page.getByRole('option').filter({ hasText: 'resourceRock' }).click();
    assert.equal(
      await page.getByLabel(catalog['sdtd.inventory.quality'], { exact: true }).count(),
      0,
    );
    await button('sdtd.tools.addItem').and(page.locator('button:enabled')).click();
    assert.equal(
      await page.evaluate(() => window.calls.some((c) => c.url.endsWith('/item-drop'))),
      false,
      'kit editor never grants items',
    );
    await button('sdtd.tools.saveKit').click();
    await page.waitForFunction(() => window.tools.kits.length === 1);
    await page.getByLabel(catalog['sdtd.tools.confirmKit'], { exact: true }).check();
    await button('sdtd.tools.executeKit').click();
    await page.getByText(catalog['sdtd.tools.result.partial'], { exact: true }).waitFor();
    await capture('kit');
    // Fill a fresh kit to its limit and verify the disappearing picker hands focus to its name.
    await page.locator('#sdtd-tools-kit').selectOption('');
    await page.getByLabel(catalog['sdtd.tools.name'], { exact: true }).fill('Limit test');
    for (let i = 0; i < 16; i++) {
      await button('sdtd.tools.addItem').click();
      await search.fill('rock');
      await page.getByRole('option').filter({ hasText: 'resourceRock' }).click();
      await button('sdtd.tools.addItem').and(page.locator('button:enabled')).focus();
      await page.keyboard.press('Enter');
    }
    assert.equal(await button('sdtd.tools.addItem').count(), 0);
    assert.equal(
      await page
        .getByLabel(catalog['sdtd.tools.name'], { exact: true })
        .evaluate((el) => el === document.activeElement),
      true,
    );
    await button('sdtd.tools.automation').click();
    await page.getByLabel(catalog['sdtd.tools.name'], { exact: true }).fill('World save');
    await button('sdtd.tools.createSchedule').click();
    await page.getByText(catalog['sdtd.tools.scheduleCreated'], { exact: false }).waitFor();
    const schedules = await page.evaluate(() =>
      window.calls.filter((c) => c.url.endsWith('/schedules')),
    );
    assert.equal(schedules.length, 1);
    assert.equal(schedules[0].body.isActive, false);
    assert.equal(schedules[0].body.onlyWhenOnline, true);
    assert.equal(
      await page.evaluate(() => window.calls.find((c) => c.url.endsWith('/tasks')).body.payload),
      'saveworld',
    );
    await capture('schedule');
    await page.evaluate(() => {
      window.perms = ['schedules.manage'];
      window.stepFailed = true;
      window.render();
    });
    await button('sdtd.tools.title').click();
    await page.getByLabel(catalog['sdtd.tools.name'], { exact: true }).fill('Failed step');
    await button('sdtd.tools.createSchedule').click();
    await page.getByText('lost step reply', { exact: true }).waitFor();
    assert.equal(await button('sdtd.tools.createSchedule').isDisabled(), true);
    assert.deepEqual(errors, []);
    console.log(
      'PASS: permissions, stable point updates, teleport double-click guard, kit catalogue/quality with no grants during edit, partial result, inactive schedules and failed-step lock; desktop/mobile captures.',
    );
  } finally {
    await browser.close();
    await new Promise((r) => server.close(r));
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
