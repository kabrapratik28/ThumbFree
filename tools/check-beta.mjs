// Checks the Android test page (beta/) in a real Chrome: the cards' order and headings, the next card after each link,
// also from the back-forward cache and with storage blocked, said once to screen readers, the invite, the computer and
// iPhone notes, and the page without JavaScript. Google's pages are stubbed out, so nothing leaves the computer.
//
//   python3 -m http.server 8765 --directory <a folder holding this site as ThumbFree/>
//   cd <a folder where `npm install puppeteer-core` ran> && node <this site>/tools/check-beta.mjs
//
// BASE and CHROME override the site address and the Chrome binary.
import { createRequire } from 'node:module';
import path from 'node:path';
import assert from 'node:assert/strict';

const puppeteer = createRequire(path.join(process.cwd(), 'x.js'))('puppeteer-core');
const BASE = process.env.BASE || 'http://127.0.0.1:8765/ThumbFree/';
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const ANDROID = 'Mozilla/5.0 (Linux; Android 16; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36';
const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1';

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true });

async function open({ ua = ANDROID, js = true, iosLive = false, stay = false, noStorage = false, stub = true } = {}) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  await page.setUserAgent(ua);
  await page.setJavaScriptEnabled(js);
  // Storage blocked, as with cookies off: every use of localStorage throws.
  if (noStorage) await page.evaluateOnNewDocument(() => Object.defineProperty(window, 'localStorage', { get() { throw new DOMException('The operation is insecure.', 'SecurityError'); } }));
  const away = [];
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.setRequestInterception(stub);
  if (stub) page.on('request', async (req) => {
    const url = req.url();
    if (url.startsWith(BASE)) {
      if (!iosLive || !url.endsWith('/site.js')) return req.continue();
      const js = await (await fetch(url)).text();
      return req.respond({ status: 200, contentType: 'application/javascript', body: js.replace(/(ios: \{\s*url: '[^']*',.*\n\s*live: )false/, '$1true') });
    }
    away.push(url);
    // Google's pages; with stay, an app takes the link (the Play Store, say) and the browser stays on this page.
    if (stay) return req.respond({ status: 204 });
    req.respond({ status: 200, contentType: 'text/html', body: '<title>Google</title>' });
  });
  await page.goto(BASE + 'beta/', { waitUntil: 'networkidle0' });
  return { context, page, away, errors };
}

// Back to the page. Chrome may restore it from its back-forward cache, which puppeteer's goBack doesn't wait for.
async function back(page) {
  await page.evaluate(() => setTimeout(() => history.back()));
  await page.waitForFunction(() => location.pathname.endsWith('/beta/') && document.readyState === 'complete');
}
const shown = (page, sel) => page.$eval(sel, (e) => getComputedStyle(e).display !== 'none' && e.getClientRects().length > 0);
const root = (page) => page.evaluate(() => ({ ...document.documentElement.dataset, js: document.documentElement.classList.contains('js') }));
const highlighted = (page) => page.$$eval('[data-step]', (cards) => cards.filter((c) => getComputedStyle(c).boxShadow !== 'none').map((c) => c.dataset.step));
const said = (page) => page.$eval('#next-step', (e) => e.textContent);

// 1. A phone, before any tap: step 1 is the next card; no "After you install", no QR code, no iPhone line.
let { context, page, away, errors } = await open();
assert.deepEqual(await root(page), { device: 'android', opened: '', next: '1', js: true });
assert.deepEqual(await highlighted(page), ['1']);
assert.equal(await shown(page, '.after'), false);
assert.equal(await shown(page, '.desk'), false);
assert.equal(await shown(page, '.iphone'), false);
assert.equal(await shown(page, '[data-step="1"] .opened'), false);
assert.equal(await said(page), '');
assert.deepEqual(away, [], 'the page itself asks nothing of other servers');
// In each card the button comes before its picture of Google's page, and the heading starts with "Step N of 2" for
// screen readers only.
assert.deepEqual(await page.$$eval('[data-step]', (cards) => cards.map((c) =>
  Boolean(c.querySelector('a.button').compareDocumentPosition(c.querySelector('.peek')) & Node.DOCUMENT_POSITION_FOLLOWING))), [true, true]);
assert.deepEqual(await page.$$eval('[data-step] h2', (headings) => headings.map((h) => {
  const sr = h.querySelector('.sr');
  return sr && [sr.textContent, sr.getBoundingClientRect().width];
})), [['Step 1 of 2: ', 1], ['Step 2 of 2: ', 1]]);

// 2. Step 1's link, then back: step 2 is next, step 1 says its link was opened (no tick), and that is said once.
const group = await page.$eval('[data-step="1"] a.button', (a) => a.href); // the links live in the page only
await Promise.all([page.waitForNavigation(), page.click('[data-step="1"] a.button')]);
assert.equal(away[0], group, 'the group, in this tab');
await back(page);
assert.deepEqual(await root(page), { device: 'android', opened: '1', next: '2', js: true });
assert.deepEqual(await highlighted(page), ['2']);
assert.equal(await shown(page, '[data-step="1"] .opened'), true);
assert.equal(await page.$eval('[data-step="1"] .opened', (e) => e.textContent), 'Google Groups link opened');
assert.equal(await said(page), 'Next: step 2 of 2, join the Play test and install.');
assert.equal(await shown(page, '.after'), false);

// 3. Step 2's link, then back: no card is next, "After you install" shows; a reload says nothing again.
away.length = 0;
const playTest = await page.$eval('[data-step="2"] a.button', (a) => a.href);
await Promise.all([page.waitForNavigation(), page.click('[data-step="2"] a.button')]);
assert.equal(away[0], playTest, 'the Play test, in this tab');
await back(page);
assert.deepEqual(await root(page), { device: 'android', opened: '1 2', next: 'after', js: true });
assert.deepEqual(await highlighted(page), []);
assert.equal(await shown(page, '.after'), true);
assert.equal(await said(page), 'Next: after you install, open ThumbFree.');
await page.reload({ waitUntil: 'networkidle0' });
assert.equal(await said(page), '', 'said once');
assert.equal(await shown(page, '.after'), true);

// 4. Invite an Android friend: the share sheet with the page's address, or else the address copied, or, when the
// clipboard says no, the address to copy by hand, with no unhandled rejection.
await page.evaluate(() => { navigator.share = (data) => { window.shared = data; return Promise.resolve(); }; });
await page.click('#invite');
assert.deepEqual(await page.evaluate(() => window.shared), {
  text: 'Help test ThumbFree: free, offline dictation for Android.', url: 'https://kabrapratik28.github.io/ThumbFree/beta/' });
await page.evaluate(() => {
  delete navigator.share;
  Object.defineProperty(Navigator.prototype, 'share', { value: undefined, configurable: true });
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (t) => {
    if (window.refuse) throw new DOMException('Write permission denied.', 'NotAllowedError');
    window.copiedText = t;
  } } });
});
await page.click('#invite');
await page.waitForFunction(() => document.getElementById('copied').textContent === 'Link copied');
assert.equal(await page.evaluate(() => window.copiedText), 'https://kabrapratik28.github.io/ThumbFree/beta/');
await page.evaluate(() => { window.refuse = true; document.getElementById('copied').textContent = ''; });
await page.click('#invite');
await page.waitForFunction(() => document.getElementById('copied').textContent !== '');
assert.equal(await page.$eval('#copied', (e) => e.textContent), 'Copy this link: https://kabrapratik28.github.io/ThumbFree/beta/');
assert.deepEqual(errors, []);
await context.close();

// 5. The Play Store app takes step 2's link and the browser stays: coming back to the browser shows "After you
// install". Step 1 wasn't opened, so it says nothing.
({ context, page, away, errors } = await open({ stay: true }));
await page.click('[data-step="2"] a.button');
await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
assert.deepEqual(await root(page), { device: 'android', opened: '2', next: 'after', js: true });
assert.equal(await shown(page, '.after'), true);
assert.equal(await shown(page, '[data-step="1"] .opened'), false);
assert.deepEqual(errors, []);
await context.close();

// 6. A computer gets the QR code, an iPhone its line, which links the App Store once site.js marks iOS live.
({ context, page, away, errors } = await open({ ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36' }));
assert.equal((await root(page)).device, 'desktop');
assert.equal(await shown(page, '.desk'), true);
assert.ok(await page.$eval('.desk img', (i) => i.naturalWidth > 0), 'the QR code loads');
await context.close();
({ context, page, away, errors } = await open({ ua: IPHONE }));
assert.equal(await shown(page, '.iphone'), true);
assert.equal(await shown(page, '.desk'), false);
assert.equal(await page.$eval('.iphone', (e) => e.textContent), 'This test is for Android. ThumbFree for iPhone is coming to the App Store.');
await context.close();
({ context, page, away, errors } = await open({ ua: IPHONE, iosLive: true }));
await page.waitForSelector('#ios-store a');
assert.equal(await page.$eval('#ios-store a', (a) => a.href), 'https://apps.apple.com/app/id6817631006');
assert.deepEqual(errors, []);
await context.close();

// 7. Without JavaScript: both cards plain with their buttons, "After you install" below them, no invite button.
({ context, page, away, errors } = await open({ js: false }));
assert.deepEqual(await root(page), { js: false });
assert.deepEqual(await highlighted(page), []);
assert.equal(await shown(page, '.after'), true);
assert.equal(await shown(page, '#invite'), false);
assert.equal(await shown(page, '.opened'), false);
await context.close();

// 8. Chrome's back-forward cache keeps the page as it was, and pageshow updates it. Interception keeps a page out of
// that cache, so here the link points to a page of this site instead of Google's.
({ context, page, errors } = await open({ stub: false }));
await page.evaluate((base) => { window.kept = true; document.querySelector('[data-step="1"] a.button').href = base + 'website-privacy.html'; }, BASE);
await Promise.all([page.waitForNavigation(), page.click('[data-step="1"] a.button')]);
await back(page);
assert.equal(await page.evaluate(() => window.kept), true, 'restored from the cache');
assert.deepEqual(await root(page), { device: 'android', opened: '1', next: '2', js: true });
assert.equal(await said(page), 'Next: step 2 of 2, join the Play test and install.');
assert.deepEqual(errors, []);
await context.close();

// 9. Storage blocked: both cards show, step 1 is next, nothing breaks, and an opened link still moves the highlight
// for this visit. The group's page opens in an app here, so the browser stays on this page.
({ context, page, errors } = await open({ noStorage: true, stay: true }));
assert.deepEqual(await root(page), { device: 'android', opened: '', next: '1', js: true });
assert.equal(await shown(page, '[data-step="1"]'), true);
assert.equal(await shown(page, '[data-step="2"]'), true);
await page.click('[data-step="1"] a.button');
await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
assert.deepEqual(await root(page), { device: 'android', opened: '1', next: '2', js: true });
assert.deepEqual(errors, []);
await context.close();

await browser.close();
console.log('test page: cards, next card, back-forward cache, blocked storage, said once, invite, computer, iPhone and no-JS checks all passed');
