// Regenerate the user-manual screenshots from a RUNNING Mise instance that
// already has an onboarded household (complete onboarding in the UI first).
//
//   BASE_URL=http://localhost:8080 node docs/manual/capture-screenshots.cjs
//
// Requires Node + playwright with a chromium download (npx playwright install
// chromium). Writes into docs/manual/images/; recompile the manual after:
//   typst compile docs/manual/mise-manual.typ docs/manual/mise-manual.pdf
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const IMG = path.join(__dirname, 'images');
fs.mkdirSync(IMG, { recursive: true });

// Watermarked (no-license) builds render a <vaadin-commercial-banner> inside a
// CLOSED shadow root attached to <body> itself, unreachable by CSS or normal
// DOM traversal. Force attachShadow to open mode so it can be removed.
async function forceOpenShadow(ctx) {
  await ctx.addInitScript(() => {
    const orig = Element.prototype.attachShadow;
    Element.prototype.attachShadow = function (init) {
      return orig.call(this, Object.assign({}, init, { mode: 'open' }));
    };
  });
}

async function killBanner(page) {
  await page.evaluate(() => {
    document.body.shadowRoot?.querySelector('vaadin-commercial-banner')?.remove();
  }).catch(() => {});
}

async function shoot(page, route, file, settleMs) {
  await page.goto(BASE + route, { waitUntil: 'networkidle' });
  await page.waitForTimeout(settleMs);
  await killBanner(page);
  await page.screenshot({ path: path.join(IMG, file) });
  console.log('captured', file);
}

(async () => {
  const browser = await chromium.launch();

  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await forceOpenShadow(ctx);
  const page = await ctx.newPage();
  await shoot(page, '/plan', 'plan-desktop.png', 4000);
  await shoot(page, '/shopping', 'shopping-desktop.png', 4000);
  await shoot(page, '/reports', 'reports-desktop.png', 7000); // charts animate in
  await ctx.close();

  const mctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  await forceOpenShadow(mctx);
  const mpage = await mctx.newPage();
  await shoot(mpage, '/plan', 'plan-mobile.png', 4000);
  await mctx.close();

  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
