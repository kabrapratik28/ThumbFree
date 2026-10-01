// The ThumbFree website's script. The settings come first; the rest needs no changes.

// The store pages. Set a store's live to true when its listing is public; until then it shows "Coming soon to ..."
// instead of its badge. The page's own HTML says "Coming soon" for both, which is what visitors without JavaScript
// see; this script rebuilds every store button from these settings.
const STORES = {
  android: {
    url: 'https://play.google.com/store/apps/details?id=io.github.kabrapratik28.thumbfree',
    live: false,
  },
  ios: {
    url: 'https://apps.apple.com/app/id6817631006', // the app's number in App Store Connect
    live: false,
  },
};

// App Store Connect's provider token, a number (Analytics > Acquisition > Campaigns). Optional: with it, App Store
// links carry the ad's utm_campaign as the campaign, so App Store Connect counts installs per campaign.
const APP_STORE_PROVIDER_TOKEN = '';

// The Meta Pixel ID from Events Manager, a number. Empty means no pixel code loads at all.
const PIXEL_ID = '';

const root = document.documentElement;

// Campaign tags from an ad go on to the store, so its console can count installs per campaign: Google Play reads
// them from its referrer parameter, App Store Connect from ct (at most 30 letters and digits here).
const campaign = new URLSearchParams();
for (const [key, value] of new URLSearchParams(location.search)) {
  if (['utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content'].includes(key)) {
    campaign.set(key, value.slice(0, 100));
  }
}

function storeUrl(store) {
  const url = new URL(STORES[store].url);
  if (campaign.toString() && store === 'android') {
    url.searchParams.set('referrer', campaign.toString());
  } else if (campaign.toString() && /^\d+$/.test(APP_STORE_PROVIDER_TOKEN)) {
    const name = campaign.get('utm_campaign') || campaign.get('utm_source') || '';
    url.searchParams.set('pt', APP_STORE_PROVIDER_TOKEN);
    url.searchParams.set('ct', name.replace(/[^A-Za-z0-9]/g, '').slice(0, 30) || 'web');
    url.searchParams.set('mt', '8');
  }
  return url.href;
}

const BADGES = {
  android: '<img class="gp" src="img/badge-google-play.png" width="646" height="250" alt="Get it on Google Play">',
  ios: '<img src="img/badge-app-store.svg" width="120" height="40" alt="Download on the App Store">',
};
const STORE_NAMES = { android: 'Google Play', ios: 'the App Store' };

// A store counts as live only with a real address: the App Store one ends in the app's number.
const isLive = (store) => STORES[store].live && (store !== 'ios' || /\/id\d+/.test(STORES.ios.url));

// Each store button is a badge (class "badge") or a line of text ("On iPhone? ..."), a link when the store is live.
for (const old of document.querySelectorAll('[data-store]')) {
  const store = old.dataset.store;
  const isBadge = old.classList.contains('badge');
  const live = isLive(store);
  const button = document.createElement(live ? 'a' : 'span');
  button.dataset.store = store;
  if (isBadge) button.className = live ? 'badge' : 'badge soon';
  if (live) {
    button.href = storeUrl(store);
    if (isBadge) button.innerHTML = BADGES[store];
    else button.textContent = 'Get ThumbFree on ' + STORE_NAMES[store];
  } else {
    button.textContent = 'Coming soon to ' + STORE_NAMES[store];
  }
  old.replaceWith(button);
}

// "How it works" shows the visitor's own platform first (set in the page's head), with a switch for the other.
function showPlatform(platform) {
  root.dataset.platform = platform;
  for (const button of document.querySelectorAll('[data-show]')) {
    button.setAttribute('aria-pressed', button.dataset.show === platform);
  }
}
showPlatform(root.dataset.platform || 'android');
for (const button of document.querySelectorAll('[data-show]')) {
  button.addEventListener('click', () => showPlatform(button.dataset.show));
  button.parentElement.hidden = false;
}

// The Meta Pixel: only with an ID, and only after the visitor accepts it. The choice stays in this browser for 180
// days; Global Privacy Control counts as a rejection.
const CONSENT_KEY = 'thumbfree-meta-consent-v1';
const CONSENT_DAYS = 180;

function storedChoice() {
  try {
    const saved = JSON.parse(localStorage.getItem(CONSENT_KEY));
    if (saved && Date.now() - saved.at < CONSENT_DAYS * 864e5) return saved.choice;
  } catch { /* no storage, or nothing saved */ }
  return null;
}

if (/^\d+$/.test(PIXEL_ID)) {
  const banner = document.getElementById('consent');
  const status = document.getElementById('consent-status');
  const privacyChoices = document.getElementById('privacy-choices');
  const gpc = navigator.globalPrivacyControl === true;
  let loaded = false;
  let granted = false;

  const grant = () => {
    if (!loaded) {
      /* eslint-disable */
      !function(f,b,e,v,n,t,s){if(f.fbq)return;n=f.fbq=function(){n.callMethod?
      n.callMethod.apply(n,arguments):n.queue.push(arguments)};if(!f._fbq)f._fbq=n;
      n.push=n;n.loaded=!0;n.version='2.0';n.queue=[];t=b.createElement(e);t.async=!0;
      t.src=v;s=b.getElementsByTagName(e)[0];s.parentNode.insertBefore(t,s)}(window,
      document,'script','https://connect.facebook.net/en_US/fbevents.js');
      /* eslint-enable */
      window.fbq('set', 'autoConfig', false, PIXEL_ID); // no automatic button and page data, only the events below
      window.fbq('init', PIXEL_ID);
    }
    window.fbq('consent', 'grant');
    if (!loaded) window.fbq('track', 'PageView');
    loaded = granted = true;
  };

  const revoke = () => {
    if (loaded) window.fbq('consent', 'revoke');
    granted = false;
    for (const name of ['_fbp', '_fbc']) {
      for (const domain of ['', '; domain=' + location.hostname]) {
        document.cookie = name + '=; Max-Age=0; path=/' + domain;
      }
    }
  };

  // While the banner is open, the page gets room at its end, so nothing focused there hides behind it.
  new ResizeObserver(() => {
    document.body.style.paddingBottom = banner.hidden ? '' : banner.offsetHeight + 32 + 'px';
  }).observe(banner);

  privacyChoices.hidden = false;
  privacyChoices.addEventListener('click', () => {
    banner.hidden = false;
    banner.querySelector('[data-consent="reject"]').focus();
  });
  banner.addEventListener('click', (event) => {
    const choice = event.target.closest('[data-consent]')?.dataset.consent;
    if (!choice) return;
    try { localStorage.setItem(CONSENT_KEY, JSON.stringify({ choice, at: Date.now() })); } catch { /* this page only */ }
    banner.hidden = true;
    if (choice === 'accept') grant(); else revoke();
    status.textContent = choice === 'accept' ? 'Ad measurement is on.' : 'Ad measurement is off.';
  });
  // A store click counts only while consent stands. The event goes out as the store opens; no delay is added.
  document.addEventListener('click', (event) => {
    const link = event.target.closest?.('a[data-store]');
    if (link && granted) window.fbq('trackCustom', 'StoreClick', { platform: link.dataset.store });
  }, { capture: true });

  if (gpc) {
    banner.querySelector('[data-consent="accept"]').disabled = true;
    banner.querySelector('.gpc').hidden = false;
    revoke(); // also clears cookies left from a visit before Global Privacy Control was on
  } else if (storedChoice() === 'accept') {
    grant();
  } else if (!storedChoice()) {
    banner.hidden = false;
  }
}
