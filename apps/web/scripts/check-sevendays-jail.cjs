// Synthetic fixture, actual components and production CSS; no game or credentials.
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
    fs.readFileSync(path.join(repo, 'apps/web/src/i18n/catalogs/en.json')),
  );
  const bundle = await req('esbuild').build({
    stdin: {
      contents: `import React from 'react';import{createRoot}from'react-dom/client';import{ToastProvider}from'./apps/web/src/components/Toast';import{SevenDaysQuickActionsWidget}from'./apps/web/src/modules/sevendays/tabs';createRoot(document.getElementById('root')).render(<ToastProvider><SevenDaysQuickActionsWidget serverId="fixture"/></ToastProvider>);`,
      loader: 'tsx',
      resolveDir: repo,
    },
    bundle: true,
    write: false,
    jsx: 'automatic',
    plugins: [
      {
        name: 'fixture',
        setup(b) {
          b.onResolve({ filter: /\/i18n$/ }, () => ({ path: 'locale', namespace: 'mock' }));
          b.onResolve({ filter: /\/lib\/api$/ }, () => ({ path: 'api', namespace: 'mock' }));
          b.onResolve({ filter: /\/lib\/auth$/ }, () => ({ path: 'auth', namespace: 'mock' }));
          b.onLoad({ filter: /api/, namespace: 'mock' }, () => ({
            loader: 'js',
            contents: `
        import {defaultZoneMovement, defaultZoneSchedule} from '@aurum/shared';
        window.calls=[];window.writes=[];
        const zone=(id,name,x)=>({id,name,type:'prison',enabled:true,x1:x,z1:0,x2:x+10,z2:10,noPvp:true,noDamage:false,noCreatureBlockDamage:false,noExplosionBlockDamage:false,blockSpawn:0,despawn:0,bonuses:{regeneration:0,stamina:0,speed:0},enter:'',exit:'',commandsEnabled:false,commandCooldown:30,enterCommands:[],exitCommands:[],movement:{...defaultZoneMovement(),mode:'prison',x:x+2,z:2},schedule:defaultZoneSchedule()});
        window.zones={revision:7,worldId:'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',zones:[zone('prison-one','Central prison',0),zone('prison-two','Northern prison',30)]};
        window.players=[{entityId:1,name:'[VIP] Łukasz / 雪',platformId:'Steam_1',crossId:'EOS_1'},{entityId:2,name:'[VIP] Łukasz / 雪',platformId:'Steam_2',crossId:'EOS_2'},{entityId:3,name:'Visitor',platformId:'Steam_3',crossId:null}];
        export async function api(url,options={}){
          window.calls.push(url);
          if(url.endsWith('/actions')) return {actions:[{id:'save',label:'Save world',permission:'sevendays.quick-actions',args:[]}]};
          if(window.loadError && options.method!=='PUT')throw Error('mod_unavailable');
          if(url.endsWith('/players'))return {players:window.players,online:window.players.length};
          if(url.endsWith('/zones')) {
            if(options.method==='PUT') {
              window.writes.push(JSON.parse(options.body));
              await new Promise(r=>setTimeout(r,120));
              if(window.conflict)throw Error('zones_revision_conflict');
              window.zones={...JSON.parse(options.body),revision:window.zones.revision+1};
            }
            return structuredClone(window.zones);
          }
          throw Error('Unexpected URL: '+url);
        }`,
            resolveDir: repo,
          }));
          b.onLoad({ filter: /.*/, namespace: 'mock' }, ({ path: name }) => ({
            loader: 'js',
            contents:
              name === 'auth'
                ? `export const useAuth=()=>({hasPermission:p=>!window.denied?.includes(p)});`
                : `const c=${JSON.stringify(catalog)};export const useI18n=()=>({t:k=>c[k]||k});export const useT=()=>useI18n().t;`,
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
    const url = `http://127.0.0.1:${server.address().port}`;
    await page.goto(url);
    const open = () =>
      page.getByRole('button', { name: catalog['sdtd.jail.action'], exact: true }).click();
    const dialog = page.getByRole('dialog');
    const submit = () =>
      dialog.getByRole('button', { name: catalog['sdtd.jail.action'], exact: true });
    await page.getByRole('button', { name: catalog['sdtd.jail.action'] }).waitFor();
    assert.equal(
      await page.evaluate(() => window.calls.length),
      1,
      'no background player or zone request',
    );
    await open();
    await page.getByPlaceholder(catalog['sdtd.jail.search']).fill('雪');
    assert.equal(await dialog.getByRole('radio').count(), 2);
    await dialog.locator('input[value="Steam_2"]').check();
    await dialog
      .getByLabel(catalog['sdtd.jail.prison'], { exact: true })
      .selectOption('prison-two');
    await dialog.getByLabel(catalog['sdtd.jail.duration'], { exact: true }).fill('30');
    const shots = path.join(repo, 'apps/web/.impeccable/review');
    fs.mkdirSync(shots, { recursive: true });
    await page.screenshot({ path: path.join(shots, '7dtd-jail-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(shots, '7dtd-jail-mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await submit().click();
    await dialog.waitFor({ state: 'hidden' });
    const write = await page.evaluate(() => window.writes[0]);
    assert.equal(write.revision, 7);
    assert.equal(write.zones[0].movement.sentences.length, 0);
    assert.equal(write.zones[1].movement.sentences[0].player, 'Steam_2');
    assert.ok(Math.abs(write.zones[1].movement.sentences[0].until - Date.now() / 1000 - 1800) < 10);
    assert.equal(await page.evaluate(() => window.writes.length), 1);
    await open();
    await dialog.locator('input[value="Steam_2"]').check();
    await page.getByText(catalog['sdtd.jail.already'], { exact: false }).waitFor();
    assert.ok(await submit().isDisabled());
    await dialog.locator('input[value="Steam_1"]').check();
    await dialog
      .getByLabel(catalog['sdtd.jail.prison'], { exact: true })
      .selectOption('prison-one');
    await page.evaluate(() => (window.conflict = true));
    await submit().click();
    await page.getByText(/zones_revision_conflict/).waitFor();
    assert.equal(await dialog.count(), 1);
    assert.equal(
      await page.evaluate(() => window.writes[1].zones[0].movement.sentences[0].until),
      0,
    );
    await page.evaluate(() => {
      window.conflict = false;
      window.zones.zones = [];
      window.players = [];
    });
    await dialog.getByRole('button', { name: catalog['sdtd.jail.refresh'] }).click();
    await page.getByText(catalog['sdtd.jail.noPlayers']).waitFor();
    await page.getByText(catalog['sdtd.jail.noPrisons']).waitFor();
    assert.ok(await submit().isDisabled());
    await page.evaluate(() => (window.loadError = true));
    await dialog.getByRole('button', { name: catalog['sdtd.jail.refresh'] }).click();
    await page.getByText('mod_unavailable', { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    await dialog.waitFor({ state: 'hidden' });
    assert.equal(await page.locator(':focus').textContent(), catalog['sdtd.jail.action']);
    await page.addInitScript(() => (window.denied = ['sevendays.zones.manage']));
    await page.reload();
    await page.getByRole('button', { name: 'Save world' }).waitFor();
    assert.equal(await page.getByRole('button', { name: catalog['sdtd.jail.action'] }).count(), 0);
    assert.deepEqual(errors, []);
    console.log(
      'Jail quick action: ID selection, duplicate names, duration, revision conflict, empty/error/permission states and mobile layout passed.',
    );
  } finally {
    await browser.close();
    await new Promise((r) => server.close(r));
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
