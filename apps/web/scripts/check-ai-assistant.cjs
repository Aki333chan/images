// Synthetic desktop/mobile regression. No provider calls, live keys, server writes or billing.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const repo = path.resolve(__dirname, '../../..');
const local = createRequire(path.join(repo, 'package.json'));
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

(async () => {
  const catalogs = Object.fromEntries(
    ['ru', 'en', 'pl'].map((locale) => [
      locale,
      JSON.parse(
        fs.readFileSync(path.join(repo, `apps/web/src/i18n/catalogs/${locale}.json`), 'utf8'),
      ),
    ]),
  );
  const bundle = await local('esbuild').build({
    stdin: {
      resolveDir: repo,
      loader: 'tsx',
      contents: `
    import React from 'react'; import {createRoot} from 'react-dom/client'; import {BrowserRouter} from 'react-router-dom';
    import {AiSettingsCard} from './apps/web/src/pages/AiSettingsCard'; import {AiAssistant} from './apps/web/src/components/AiAssistant';
    import {useAuth} from './apps/web/src/lib/auth';
    function Fixture(){const {hasPermission}=useAuth();return <BrowserRouter><main className="mx-auto max-w-3xl p-4">
      <button data-tab="economy" aria-current="page">Economy · QA</button>{hasPermission('users.manage')&&<AiSettingsCard/>}<AiAssistant/>
    </main></BrowserRouter>};createRoot(document.getElementById('root')).render(<Fixture/>);
  `,
    },
    bundle: true,
    write: false,
    jsx: 'automatic',
    plugins: [
      {
        name: 'qa-fixtures',
        setup(build) {
          build.onResolve({ filter: /\/i18n$/ }, () => ({ path: 'i18n', namespace: 'qa' }));
          build.onResolve({ filter: /\/lib\/(auth|api)$/ }, ({ path: name }) => ({
            path: name.endsWith('auth') ? 'auth' : 'api',
            namespace: 'qa',
          }));
          build.onLoad({ filter: /.*/, namespace: 'qa' }, ({ path: name }) => ({
            loader: 'js',
            contents:
              name === 'i18n'
                ? `
      const catalogs=${JSON.stringify(catalogs)};const locale=new URL(location.href).searchParams.get('locale')||'ru';
      const t=(k,v={})=>Object.entries(v).reduce((s,[key,value])=>s.replaceAll('{'+key+'}',String(value)),catalogs[locale][k]||k);
      export const useT=()=>t; export const useI18n=()=>({t,locale});
    `
                : name === 'auth'
                  ? `
      const moderator=new URL(location.href).searchParams.get('role')==='moderator';
      export const useAuth=()=>({hasPermission:k=>!moderator||['ai.chat','servers.view','minecraft.economy.view'].includes(k)});
    `
                  : `
      window.calls=[]; window.keys={deepseek:true,gemini:false}; window.models={deepseek:'deepseek-v4-flash',gemini:'gemini-3.8-flash'};
      window.settings={enabled:true,hasApiKey:true,provider:'deepseek',providerKeys:window.keys,model:window.models.deepseek,
        systemPrompt:'Be concise.',requestsPerHour:30,tokensPerDay:200000,maxInputTokens:32768,maxOutputTokens:4096,dailyBudgetUsd:1,monthlyBudgetUsd:10};
      window.action={id:'66da2285-0983-4ce6-bc6a-eed51b0e21ea',tool:'transfer_economy_accounts',summary:'Перевести 500 coins: казна → фонд финала',
        args:{amount:'500',currency:'coins',sourceProfile:'treasury:global',sourceRole:'main',targetProfile:'arena:colosseum',targetRole:'final',reason:'fund setup',
          preview:{before:{balance:'0'},after:{balance:'500'},warnings:[]}},status:'pending',fromUntrustedInput:true};
      export const getAccessToken=()=>null;
      export async function api(url,init){const body=init?.body?JSON.parse(init.body):null;window.calls.push({url,body,method:init?.method||'GET'});
        if(url.endsWith('/settings')){if(body){const p=body.provider||window.settings.provider;if(body.apiKey)window.keys[p]=true;if(body.clearApiKey)window.keys[p]=false;
          if(body.model)window.models[p]=body.model;window.settings={...window.settings,...body,provider:p,model:window.models[p],providerKeys:{...window.keys},hasApiKey:window.keys[p]};
          delete window.settings.apiKey;delete window.settings.clearApiKey;}return structuredClone(window.settings);}
        if(url.endsWith('/usage'))return {requestsLastHour:1,requestsPerHour:30,tokensToday:40,tokensPerDay:200000,dailyCostUsd:0.01,monthlyCostUsd:0.02,dailyBudgetUsd:1,monthlyBudgetUsd:10};
        if(url.endsWith('/tools'))return {tools:[{name:'economy_rules',kind:'safe'},{name:'transfer_economy_accounts',kind:'destructive'}]};
        if(url.includes('/actions/')){if(body){await new Promise(r=>setTimeout(r,150));window.action.status=body.approve?'approved':'rejected';window.action.result='committed';
          if(window.lostReply)throw Error('lost reply');}return structuredClone(window.action);}throw Error('Unexpected fixture '+url);
      }
    `,
          }));
        },
      },
    ],
  });
  const assets = path.join(repo, 'apps/web/dist/assets');
  const css = fs.readFileSync(
    path.join(
      assets,
      fs.readdirSync(assets).find((name) => name.endsWith('.css')),
    ),
  );
  const requests = [];
  let aborted = 0;
  const server = http.createServer((req, res) => {
    if (req.url === '/app.js') {
      res.setHeader('content-type', 'text/javascript');
      return res.end(bundle.outputFiles[0].contents);
    }
    if (req.url === '/style.css') {
      res.setHeader('content-type', 'text/css');
      return res.end(css);
    }
    if (req.url?.startsWith('/assets/')) {
      const file = path.join(assets, path.basename(req.url));
      if (fs.existsSync(file)) return res.end(fs.readFileSync(file));
      res.statusCode = 404;
      return res.end();
    }
    if (req.url === '/api/ai/chat') {
      let input = '';
      req.on('data', (chunk) => (input += chunk));
      req.on('end', () => {
        const body = JSON.parse(input);
        requests.push({ body, locale: req.headers['accept-language'] });
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write(
          `data: ${JSON.stringify({ type: 'delta', text: 'Готово к подтверждению.' })}\n\n`,
        );
        if (body.messages.at(-1).content === 'slow') {
          const timer = setTimeout(() => res.end(), 10000);
          res.on('close', () => {
            clearTimeout(timer);
            if (!res.writableEnded) aborted++;
          });
        } else {
          res.write(
            `data: ${JSON.stringify({
              type: 'action',
              action: {
                id: '66da2285-0983-4ce6-bc6a-eed51b0e21ea',
                tool: 'transfer_economy_accounts',
                summary: 'Перевести 500 coins: казна → фонд финала',
                args: {
                  amount: '500',
                  currency: 'coins',
                  sourceProfile: 'treasury:global',
                  sourceRole: 'main',
                  targetProfile: 'arena:colosseum',
                  targetRole: 'final',
                  reason: 'fund setup',
                  preview: { before: { balance: '0' }, after: { balance: '500' }, warnings: [] },
                },
                status: 'pending',
                fromUntrustedInput: true,
              },
            })}\n\n`,
          );
          res.end('data: {"type":"done"}\n\n');
        }
      });
      return;
    }
    res.setHeader('content-type', 'text/html');
    res.end(
      '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/style.css"></head><body><div id="root"></div><script src="/app.js"></script></body></html>',
    );
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const output = path.join(repo, 'apps/web/.impeccable/review');
  fs.mkdirSync(output, { recursive: true });
  const origin = `http://127.0.0.1:${server.address().port}`;
  const errors = [];
  try {
    for (const [name, width, height, locale] of [
      ['desktop', 1440, 900, 'ru'],
      ['mobile', 390, 844, 'pl'],
    ]) {
      const page = await browser.newPage({ viewport: { width, height }, reducedMotion: 'reduce' });
      page.on('pageerror', (e) => errors.push(e.message));
      await page.goto(`${origin}/servers/qa-server?locale=${locale}`);
      await page.locator('#ai-provider').waitFor();
      await page.locator('#ai-provider').selectOption('gemini');
      await page.getByText(catalogs[locale]['set.ai.noKey'], { exact: true }).waitFor();
      assert.equal(await page.locator('#ai-model').inputValue(), 'gemini-3.8-flash');
      await page.locator('#ai-api-key').fill('qa-gemini-not-a-real-key');
      await page
        .getByRole('button', { name: catalogs[locale]['set.ai.keySave'], exact: true })
        .click();
      await page.getByText(catalogs[locale]['set.ai.working'], { exact: true }).waitFor();
      await page
        .getByRole('button', { name: catalogs[locale]['set.ai.keyDelete'], exact: true })
        .click();
      await page.getByText(catalogs[locale]['set.ai.noKey'], { exact: true }).waitFor();
      assert.equal(await page.evaluate(() => window.keys.deepseek), true);
      await page.getByText(catalogs[locale]['set.ai.limits'], { exact: true }).click();
      await page.locator('#ai-maxOutputTokens').fill('2048');
      await page
        .getByRole('button', { name: catalogs[locale]['common.save'], exact: true })
        .click();
      await page.waitForFunction(() => window.settings.maxOutputTokens === 2048);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.screenshot({ path: path.join(output, `ai-settings-${name}.png`), fullPage: true });
      await page.getByRole('button', { name: catalogs[locale]['ai.help.open'] }).click();
      await page.getByRole('button', { name: catalogs[locale]['ai.help.askAi'] }).click();
      await page.getByPlaceholder(catalogs[locale]['ai.ask']).fill('transfer');
      await page.getByRole('button', { name: catalogs[locale]['ai.send'] }).click();
      const approve = page.getByRole('button', {
        name: catalogs[locale]['ai.approve'],
        exact: true,
      });
      await approve.waitFor();
      await page.screenshot({
        path: path.join(output, `ai-confirmation-${name}.png`),
        fullPage: true,
      });
      await page.evaluate(() => {
        window.lostReply = true;
      });
      await approve.click();
      assert.equal(await page.getByRole('button', {name: catalogs[locale]['ai.status.executing'], exact: true}).isDisabled(), true);
      await page.getByText('committed', { exact: false }).waitFor();
      assert.equal(
        await page.evaluate(
          () =>
            window.calls.filter((c) => c.url.includes('/actions/') && c.method === 'POST').length,
        ),
        1,
      );
      assert.ok(
        requests.at(-1).body.context.serverId === 'qa-server' &&
          requests.at(-1).body.context.tab === 'economy',
      );
      assert.equal(requests.at(-1).locale, locale);
      await page.getByPlaceholder(catalogs[locale]['ai.ask']).fill('slow');
      await page.getByRole('button', { name: catalogs[locale]['ai.send'] }).click();
      await page.getByRole('button', { name: catalogs[locale]['ai.stop'] }).waitFor();
      await page
        .getByRole('button', { name: catalogs[locale]['ai.clear'] })
        .isDisabled()
        .then((value) => assert.equal(value, true));
      await page.getByRole('button', { name: catalogs[locale]['ai.stop'] }).click();
      await page.getByText(catalogs[locale]['ai.cancelled'], { exact: true }).waitFor();
      await page
        .getByRole('button', { name: catalogs[locale]['common.close'], exact: true })
        .click();
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.close();
    }
    const limited = await browser.newPage({ viewport: { width: 390, height: 844 } });
    limited.on('pageerror', (e) => errors.push(e.message));
    await limited.goto(`${origin}/servers/qa-server?role=moderator`);
    await limited.getByRole('button', { name: catalogs.ru['ai.help.open'] }).waitFor();
    assert.equal(await limited.locator('#ai-provider').count(), 0);
    assert.ok(aborted >= 2, 'stop must disconnect both streamed responses');
    assert.deepEqual(errors, []);
    console.log(
      'AI QA: desktop/mobile, provider keys/models, limits, confirmation recovery, single submit, page context, locale, stop and limited-role visibility passed.',
    );
  } finally {
    await browser.close();
    await new Promise((resolve) => server.close(resolve));
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
