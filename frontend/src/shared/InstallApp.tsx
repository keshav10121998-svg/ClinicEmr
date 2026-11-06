import { useEffect, useState } from 'react';

/** Chrome's install event. It fires once, often before any screen is shown, so it is caught here at load. */
interface InstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

let deferred: InstallPromptEvent | undefined;
// Chrome's install box was shown and closed without installing: offer the menu steps instead of hiding the banner
let promptClosed = false;
// "Not now" hides the banner until the app is opened again, not for good
let notNowThisVisit = false;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    // Our own banner asks instead of Chrome's small bar at the bottom
    e.preventDefault();
    deferred = e as InstallPromptEvent;
    // Chrome only offers this when the app isn't installed (for example it was removed again)
    forgetInstalled();
    notify();
  });
  window.addEventListener('appinstalled', () => {
    deferred = undefined;
    rememberInstalled();
    notify();
  });
}

/** Registers the service worker that makes the app installable. Only in the built app, not under the dev server. */
export function registerServiceWorker() {
  if (import.meta.env.PROD && 'serviceWorker' in navigator) {
    window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}));
  }
}

const KEY = 'app-installed';

function rememberInstalled() {
  try { localStorage.setItem(KEY, '1'); localStorage.removeItem('install-banner-hidden-until'); } catch { /* private mode */ }
}

function forgetInstalled() {
  try { localStorage.removeItem(KEY); } catch { /* private mode */ }
}

/** Running as the installed app, or installed from this browser (until Chrome says it's gone again). */
function installed() {
  if (window.matchMedia('(display-mode: standalone)').matches
    || (navigator as Navigator & { standalone?: boolean }).standalone === true) return true;
  try { return localStorage.getItem(KEY) === '1'; } catch { return false; }
}

const ua = typeof navigator !== 'undefined' ? navigator.userAgent : '';
const isIos = /iphone|ipad|ipod/i.test(ua) || (/macintosh/i.test(ua) && typeof navigator !== 'undefined' && navigator.maxTouchPoints > 1);
const isAndroid = /android/i.test(ua);
// WhatsApp, Instagram and Facebook open links in their own browser, which can't install apps
const inAppBrowser = /wv\)|FBAN|FBAV|Instagram|WhatsApp/i.test(ua);

/**
 * "Add ClinicEMR to your home screen" with an Install button: one tap when Chrome allows it, otherwise (iPhone,
 * WhatsApp's browser, or before Chrome is ready) the button shows the steps for that phone. Shown on the sign-in
 * pages and the home screens until the app is installed; Not now hides it only until the app is opened again.
 */
export function InstallApp({ appName }: { appName: string }) {
  const [, rerender] = useState(0);
  const [dismissed, setDismissed] = useState(notNowThisVisit);
  const [showSteps, setShowSteps] = useState(false);

  useEffect(() => {
    const l = () => rerender((n) => n + 1);
    listeners.add(l);
    return () => { listeners.delete(l); };
  }, []);

  if (dismissed || installed()) return null;
  // Phones always see the banner; computers only when Chrome can install (or its box was closed)
  const manual = isIos || isAndroid || promptClosed;
  if (!deferred && !manual) return null;
  // Chrome offers its own install box only after the page has been used for a bit (about 30 seconds and a tap),
  // never inside WhatsApp's browser or on iPhone, and not again for a while after it was closed
  const oneTap = !!deferred && !inAppBrowser;

  async function install() {
    if (!deferred) return;
    const e = deferred;
    await e.prompt();
    const { outcome } = await e.userChoice;
    deferred = undefined;
    if (outcome === 'accepted') rememberInstalled();
    else promptClosed = true;
    rerender((n) => n + 1);
  }

  function notNow() {
    notNowThisVisit = true;
    setDismissed(true);
  }

  return (
    <div className="install-app" role="region" aria-label={`Install ${appName}`}>
      <style>{CSS}</style>
      <img src="/icon-192.png" alt="" width={44} height={44} />
      <div className="install-text">
        <b>Add {appName} to your home screen</b>
        <span>Opens with one tap like any app, full screen, and stays signed in.</span>
        {showSteps && <Steps />}
      </div>
      <div className="install-actions">
        {/* Always "Install": one tap where Chrome allows it right now, otherwise the steps for this phone */}
        {(oneTap || !showSteps) && (
          <button type="button" className="install-go" onClick={oneTap ? install : () => setShowSteps(true)}>Install</button>
        )}
        <button type="button" className="install-later" onClick={notNow}>Not now</button>
      </div>
    </div>
  );
}

function Steps() {
  if (inAppBrowser) {
    return (
      <ol>
        <li>Tap <b>⋮</b> at the top right.</li>
        <li>Tap <b>Open in Chrome</b> (or <b>Open in browser</b>).</li>
        <li>In Chrome, tap <b>Install</b> on this card.</li>
      </ol>
    );
  }
  if (isIos) {
    return (
      <ol>
        <li>Open this page in <b>Safari</b>.</li>
        <li>Tap the <b>Share</b> button <ShareGlyph /> at the bottom.</li>
        <li>Scroll down and tap <b>Add to Home Screen</b>, then <b>Add</b>.</li>
      </ol>
    );
  }
  return (
    <ol>
      <li>Tap <b>⋮</b> at the top right of Chrome.</li>
      <li>Tap <b>Add to Home screen</b> (or <b>Install app</b>).</li>
      <li>Tap <b>Install</b> or <b>Add</b>.</li>
    </ol>
  );
}

function ShareGlyph() {
  return (
    <svg width="14" height="16" viewBox="0 0 14 18" aria-label="Share" style={{ verticalAlign: '-2px' }}>
      <path d="M7 1v11M3.5 4.5 7 1l3.5 3.5M2.5 8H1v9h12V8h-1.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

const CSS = `
.install-app { display: flex; gap: 12px; align-items: flex-start; flex-wrap: wrap; padding: 12px 14px; margin: 0 0 12px;
  background: var(--accent-soft, #e1f0eb); border: 1px solid var(--accent, #0d6b5a); border-radius: 10px; color: var(--ink, #18221f); }
.install-app img { border-radius: 10px; flex: none; }
.install-text { flex: 1 1 200px; display: grid; gap: 2px; }
.install-text span { color: var(--muted, #5b6a65); font-size: 0.92em; }
.install-text ol { margin: 6px 0 0; padding-left: 20px; display: grid; gap: 4px; }
.install-actions { display: flex; gap: 8px; align-items: center; margin-left: auto; }
.auth > .install-app { width: min(420px, 100%); margin: 0; }
.install-actions button { font: inherit; min-height: 44px; padding: 0 16px; border-radius: 8px; cursor: pointer; }
.install-go { background: var(--accent, #0d6b5a); color: #fff; border: 0; font-weight: 600 !important; }
.install-later { background: transparent; border: 0; color: var(--accent, #0d6b5a); text-decoration: underline; }
`;
