// Checks the consent banner and the Meta Pixel, the store links and the UTM pass-through in a real Chrome, with
// Meta's servers and the stores stubbed out, so nothing leaves the computer.
//
//   python3 -m http.server 8765 --directory <a folder holding this site as ThumbFree/>
//   cd <a folder where `npm install puppeteer-core` ran> && node <this site>/tools/check-pixel.mjs
//
// BASE and CHROME override the site address and the Chrome binary.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const puppeteer = createRequire(path.join(process.cwd(), 'x.js'))('puppeteer-core');
const BASE = process.env.BASE || 'http://127.0.0.1:8765/ThumbFree/';
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const SITE_JS = fs.readFileSync(new URL('../site.js', import.meta.url), 'utf8');

const STUB = `window.__fb=window.__fb||[];for(const a of fbq.queue)__fb.push(Array.from(a));fbq.queue=[];
fbq.callMethod=function(){__fb.push(Array.from(arguments))};document.cookie='_fbp=fb.1.test; path=/';`;

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true });

async function open({ pixel = false, live = false, gpc = false, query = '', ua } = {}, context) {
  const page = await context.newPage();
  if (ua) await page.setUserAgent(ua);
  const meta = [];
  if (gpc) await page.evaluateOnNewDocument(() => Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', { get: () => true }));
  await page.setRequestInterception(true);
  page.on('request', (req) => {
    const url = req.url();
    if (/facebook\.(net|com)/.test(url)) { meta.push(url); return req.respond({ status: 200, contentType: 'application/javascript', body: STUB }); }
    if (/play\.google\.com|apps\.apple\.com/.test(url)) return req.respond({ status: 200, contentType: 'text/html', body: 'store' });
    if (url.endsWith('/site.js')) {
      let js = SITE_JS;
      if (pixel) js = js.replace("const PIXEL_ID = '';", `const PIXEL_ID = '${pixel === true ? '1234567890' : pixel}';`);
      if (live) js = js.replaceAll('live: false', 'live: true');
      if (live === true) js = js.replace('idAPP_STORE_ID', 'id1234567890');
      return req.respond({ status: 200, contentType: 'application/javascript', body: js });
    }
    req.continue();
  });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(BASE + query, { waitUntil: 'networkidle0' });
  return { page, meta, errors };
}
const visible = (page, sel) => page.$eval(sel, (e) => !e.hidden && getComputedStyle(e).display !== 'none');

// 1. No Pixel ID: no Meta request, no banner, no Privacy choices, no cookies, no errors.
let ctx = await browser.createBrowserContext();
let { page, meta, errors } = await open({ live: true }, ctx);
assert.equal(meta.length, 0, 'no Meta request without an ID');
assert.equal(await visible(page, '#consent'), false);
assert.equal(await visible(page, '#privacy-choices'), false);
assert.equal(await page.evaluate(() => document.cookie), '');
assert.deepEqual(errors, []);
await ctx.close();

// 2. With an ID: banner, nothing from Meta before a choice; Reject keeps it off, also after a reload.
ctx = await browser.createBrowserContext();
({ page, meta } = await open({ pixel: true, live: true }, ctx));
assert.equal(await visible(page, '#consent'), true, 'banner shows');
assert.equal(meta.length, 0, 'no Meta request before a choice');
await page.click('[data-consent="reject"]');
assert.equal(await visible(page, '#consent'), false);
await page.reload({ waitUntil: 'networkidle0' });
assert.equal(await visible(page, '#consent'), false, 'reject remembered');
assert.equal(meta.length, 0, 'no Meta request after reject');
assert.equal(await page.evaluate(() => typeof window.fbq), 'undefined');

// 3. Privacy choices, then Accept: the pixel loads with autoConfig off, grant and one PageView.
await page.click('#privacy-choices');
assert.equal(await visible(page, '#consent'), true, 'Privacy choices reopens the banner');
await page.click('[data-consent="accept"]');
await page.waitForFunction(() => window.__fb && window.__fb.length >= 4);
let calls = await page.evaluate(() => window.__fb.map((c) => c.slice(0, 3).join(' ')));
assert.deepEqual(calls, ['set autoConfig false', 'init 1234567890', 'consent grant', 'track PageView'], calls.join(' | '));
assert.ok(meta.some((u) => u.includes('fbevents.js')));

// 4. A live store click sends StoreClick with the platform only.
await page.evaluate(() => document.querySelector('.hero a.badge[data-store="android"]').addEventListener('click', (e) => e.preventDefault()));
await page.evaluate(() => document.querySelector('.hero a.badge[data-store="android"]').click());
calls = await page.evaluate(() => window.__fb.map((c) => JSON.stringify(c)));
assert.ok(calls.includes('["trackCustom","StoreClick",{"platform":"android"}]'), calls.join(' | '));

// 5. Reject after Accept: revoke, Meta's cookie is gone and store clicks stop counting. Accept again grants again,
// without a second PageView. Then Reject, and after a reload nothing loads.
await page.click('#privacy-choices');
await page.click('[data-consent="reject"]');
calls = await page.evaluate(() => window.__fb.map((c) => c.join(' ')));
assert.equal(calls.at(-1), 'consent revoke');
assert.ok(!(await page.evaluate(() => document.cookie)).includes('_fbp'), 'cookie removed');
await page.evaluate(() => document.querySelector('.hero a.badge[data-store="android"]').click());
assert.equal((await page.evaluate(() => window.__fb.map((c) => c.join(' ')))).at(-1), 'consent revoke', 'no StoreClick after reject');
await page.click('#privacy-choices');
await page.click('[data-consent="accept"]');
calls = await page.evaluate(() => window.__fb.map((c) => c.join(' ')));
assert.equal(calls.at(-1), 'consent grant', 'accept again grants again');
assert.equal(calls.filter((c) => c === 'track PageView').length, 1, 'one PageView per page');
await page.click('#privacy-choices');
await page.click('[data-consent="reject"]');
const before = meta.length;
await page.reload({ waitUntil: 'networkidle0' });
assert.equal(meta.length, before, 'nothing from Meta after withdrawal');
await ctx.close();

// 6. Global Privacy Control: no banner and no Meta request, even when a previous Accept is stored; Privacy choices
// shows why, with Accept turned off. A Pixel ID that isn't a number loads nothing.
ctx = await browser.createBrowserContext();
({ page, meta } = await open({ pixel: true, gpc: true }, ctx));
assert.equal(await visible(page, '#consent'), false, 'GPC: no banner');
await page.click('#privacy-choices');
assert.equal(await visible(page, '#consent .gpc'), true, 'GPC note');
assert.equal(await page.$eval('[data-consent="accept"]', (b) => b.disabled), true, 'GPC: Accept off');
await page.evaluate(() => localStorage.setItem('thumbfree-meta-consent-v1', JSON.stringify({ choice: 'accept', at: Date.now() })));
await page.reload({ waitUntil: 'networkidle0' });
assert.equal(meta.length, 0, 'GPC beats a stored Accept');
await ctx.close();
ctx = await browser.createBrowserContext();
({ page, meta } = await open({ pixel: 'PIXEL_ID_HERE' }, ctx));
assert.equal(await visible(page, '#consent'), false, 'a placeholder ID shows no banner');
assert.equal(meta.length, 0);
await ctx.close();

// 7. Stores not live: "Coming soon", no store links at all, in the HTML (what visitors without JavaScript get) and
// on the page. Live: UTM tags reach Google Play, fbclid doesn't.
const html = fs.readFileSync(new URL('../index.html', import.meta.url), 'utf8');
assert.ok(!/<a[^>]*data-store/.test(html.replace(/<!--[\s\S]*?-->/g, '')), 'the HTML has no store link');
ctx = await browser.createBrowserContext();
({ page } = await open({}, ctx));
assert.equal(await page.$$eval('a[data-store]', (a) => a.length), 0, 'no store links while not live');
assert.match(await page.$eval('.hero .badges', (e) => e.textContent), /Coming soon to the App Store\s*Coming soon to Google Play/);
({ page } = await open({ live: true, query: '?utm_source=facebook&utm_campaign=launch%20week&fbclid=abc123&x=1' }, ctx));
const play = await page.$eval('.hero a.badge[data-store="android"]', (a) => a.href);
assert.equal(play, 'https://play.google.com/store/apps/details?id=io.github.kabrapratik28.thumbfree&referrer=utm_source%3Dfacebook%26utm_campaign%3Dlaunch%2Bweek');
const apple = await page.$eval('.hero a.badge[data-store="ios"]', (a) => a.href);
assert.equal(apple, 'https://apps.apple.com/app/id1234567890', 'no App Store campaign without a provider token');
({ page } = await open({ live: 'placeholder' }, ctx));
assert.equal(await page.$eval('.hero .badge[data-store="ios"]', (e) => e.tagName + ' ' + e.textContent), 'SPAN Coming soon to the App Store',
  'a live flag with the placeholder App Store URL still says Coming soon');
assert.equal(await page.$eval('.hero .badge[data-store="android"]', (e) => e.tagName), 'A');
await ctx.close();

// 8. Device layout: Android gets Google Play and the App Store as a text link; iPad (a Mac with touch) gets the App Store.
ctx = await browser.createBrowserContext();
({ page } = await open({ live: true, ua: 'Mozilla/5.0 (Linux; Android 16; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36' }, ctx));
assert.equal(await page.evaluate(() => document.documentElement.dataset.device), 'android');
assert.equal(await visible(page, '.hero .badge[data-store="ios"]'), false);
assert.equal(await visible(page, '.hero .also-ios'), true);
const ipad = await ctx.newPage();
await ipad.evaluateOnNewDocument(() => Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: () => 5 }));
await ipad.setUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15');
await ipad.goto(BASE, { waitUntil: 'networkidle0' });
assert.equal(await ipad.evaluate(() => document.documentElement.dataset.device), 'ios');
assert.equal(await ipad.evaluate(() => document.documentElement.dataset.platform), 'ios');
await ctx.close();

await browser.close();
console.log('pixel, consent, store links and UTM checks: all passed');
