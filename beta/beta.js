// The Android test page's script. It runs in the page's head, so the right card is highlighted from the first paint.
// Without it, both cards are plain and everything shows. The page's three links are in its HTML, each once.

const root = document.documentElement;
const ua = navigator.userAgent;
// The device, by the home page's own test: Android, iPhone, or a computer (an iPad counts as one, as there).
const device = (navigator.userAgentData && navigator.userAgentData.platform === 'Android') || /Android/i.test(ua)
  ? 'android' : /iPhone|iPod/.test(ua) ? 'ios' : 'desktop';
root.classList.add('js');
root.dataset.device = device;

// Which steps' links were opened in this browser ({"1": true, "2": true}) and the last next step read out. It stays in
// this browser's local storage, and in memory when there is none.
const KEY = 'thumbfree-beta-v1';
let state = {};

function load() {
  try {
    state = { ...state, ...JSON.parse(localStorage.getItem(KEY)) };
  } catch { /* no storage: this visit only */ }
}

function remember(change) {
  load();
  state = { ...state, ...change };
  try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* the same */ }
}

const SAY = {
  2: 'Next: step 2 of 2, join the Play test and install.',
  after: 'Next: after you install, open ThumbFree.',
};

// The page can't see what happened on Google's pages, only which links were opened, so the card after the last one
// opened is highlighted, and nothing is ticked. Each new next step is read out once, in the polite live region.
function render() {
  load();
  const next = state[2] ? 'after' : state[1] ? '2' : '1';
  root.dataset.opened = [1, 2].filter((step) => state[step]).join(' ');
  root.dataset.next = next;
  const status = document.getElementById('next-step');
  if (status && SAY[next] && state.said !== next) {
    status.textContent = SAY[next];
    remember({ said: next });
  }
}

render();
// The links open in this tab: coming back shows the page again (pageshow), or the browser again when the Play Store
// app took the link (visibilitychange).
addEventListener('pageshow', render);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') render();
});

document.addEventListener('DOMContentLoaded', () => {
  render(); // the live region exists now

  document.querySelector('.bsteps').addEventListener('click', (event) => {
    const card = event.target.closest('a')?.closest('[data-step]');
    if (card) remember({ [card.dataset.step]: true });
  });

  const copied = document.getElementById('copied');
  document.getElementById('invite').addEventListener('click', async () => {
    const url = document.querySelector('link[rel=canonical]').href;
    if (navigator.share) {
      const text = 'Help test ThumbFree: free, offline dictation for Android.';
      navigator.share({ text, url }).catch(() => { /* the sheet was closed */ });
      return;
    }
    try {
      await navigator.clipboard.writeText(url);
      copied.textContent = 'Link copied';
    } catch {
      copied.textContent = url; // no clipboard here: the link, to copy by hand
    }
  });

  // On an iPhone, the App Store link once site.js marks iOS live. site.js keeps the store settings; tools/check-site.py
  // reads them with the same pattern, and fails if it can't.
  if (device === 'ios') {
    fetch('../site.js').then((response) => response.text()).then((js) => {
      const [, url, live] = js.match(/ios: \{\s*url: '([^']*)',.*\n\s*live: (true|false)/) || [];
      if (live !== 'true' || !/\/id\d+/.test(url)) return;
      const link = document.createElement('a');
      link.href = url;
      link.textContent = 'Get ThumbFree for iPhone on the App Store';
      document.getElementById('ios-store').replaceChildren(link, '.');
    }).catch(() => { /* the line stays as it is */ });
  }
});
