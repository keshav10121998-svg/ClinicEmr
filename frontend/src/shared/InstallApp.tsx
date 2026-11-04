import { useEffect, useState } from 'react';

/** Chrome's install event. It fires once, often before any screen is shown, so it is caught here at load. */
interface InstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

let deferred: InstallPromptEvent | undefined;
const loadedAt = Date.now();
const WAIT_MS = 4000;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    // Our own banner asks instead of Chrome's small bar at the bottom
    e.preventDefault();
    deferred = e as InstallPromptEvent;
    notify();
  });
  window.addEventListener('appinstalled', () => {
    deferred = undefined;
    remember();
    notify();
  });
}

/** Registers the service worker that makes the app installable. Only in the built app, not under the dev server. */
export function registerServiceWorker() {
  if (import.meta.env.PROD && 'serviceWorker' in navigator) {
    window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}));
  }
}

const KEY = 'install-banner-hidden-until';
const HIDE_DAYS = 30;

function remember() {
  try { localStorage.setItem(KEY, String(Date.now() + HIDE_DAYS * 86_400_000)); } catch { /* private mode */ }
}

function hiddenByUser() {
  try { return Number(localStorage.getItem(KEY) ?? 0) > Date.now(); } catch { return false; }
}

function installed() {
  return window.matchMedia('(display-mode: standalone)').matches
    || (navigator as Navigator & { standalone?: boolean }).standalone === true;
}

const ua = typeof navigator !== 'undefined' ? navigator.userAgent : '';
const isIos = /iphone|ipad|ipod/i.test(ua) || (/macintosh/i.test(ua) && typeof navigator !== 'undefined' && navigator.maxTouchPoints > 1);
const isAndroid = /android/i.test(ua);
// WhatsApp, Instagram and Facebook open links in their own browser, which can't install apps
const inAppBrowser = /wv\)|FBAN|FBAV|Instagram|WhatsApp/i.test(ua);

/**
 * "Add ClinicEMR to your home screen": one tap on Android and desktop Chrome; on iPhone (and inside WhatsApp's
 * browser) it shows the steps instead. Never shown in the installed app; Not now hides it for 30 days.
 */
export function InstallApp({ appName }: { appName: string }) {
  const [, rerender] = useState(0);
  const [dismissed, setDismissed] = useState(hiddenByUser);
  const [showSteps, setShowSteps] = useState(false);
  // Chrome may decide a little after load; wait before offering the manual steps on Android
  const [waited, setWaited] = useState(() => Date.now() - loadedAt >= WAIT_MS);

  useEffect(() => {
    const l = () => rerender((n) => n + 1);
    listeners.add(l);
    const t = window.setTimeout(() => setWaited(true), Math.max(0, WAIT_MS - (Date.now() - loadedAt)));
    return () => { listeners.delete(l); window.clearTimeout(t); };
  }, []);

  if (dismissed || installed()) return null;
  const manual = isIos || (isAndroid && waited && (!deferred || inAppBrowser));
  if (!deferred && !manual) return null;

  async function install() {
    if (!deferred) return;
    const e = deferred;
    await e.prompt();
    const { outcome } = await e.userChoice;
    deferred = undefined;
    if (outcome === 'accepted') remember();
    rerender((n) => n + 1);
  }

  function notNow() {
    remember();
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
        {deferred && !inAppBrowser
          ? <button type="button" className="install-go" onClick={install}>Install</button>
          : !showSteps && <button type="button" className="install-go" onClick={() => setShowSteps(true)}>Show me how</button>}
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
.install-actions button { font: inherit; min-height: 44px; padding: 0 16px; border-radius: 8px; cursor: pointer; }
.install-go { background: var(--accent, #0d6b5a); color: #fff; border: 0; font-weight: 600 !important; }
.install-later { background: transparent; border: 0; color: var(--accent, #0d6b5a); text-decoration: underline; }
`;
