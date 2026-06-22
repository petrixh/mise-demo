// One-off: drive the first-run onboarding chat against a RUNNING Mise instance
// (live LLM endpoint) so the dev H2 gets a populated household + plans, ready
// for screenshot capture. Not part of the build — a manual helper.
//
//   BASE_URL=http://localhost:8080 node docs/manual/onboard-dev.cjs
const { chromium } = require('playwright');

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const MSG =
  "There are 4 of us eating at home in Helsinki. Our weekly grocery budget " +
  "is about 120 euros. We can't stand liver or anchovies, no allergies. " +
  "We love pasta, salmon and berries.";

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  // Land on the app; with no household it forwards to /welcome (onboarding).
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  console.log('landed at', page.url());

  // Type into the vaadin-message-input textarea (Playwright pierces open shadow DOM).
  const input = page.locator('vaadin-message-input textarea').first();
  await input.waitFor({ state: 'visible', timeout: 30000 });
  await input.fill(MSG);
  await input.press('Enter');
  console.log('sent onboarding message, waiting for recordHousehold + navigation to /plan...');

  // The response-complete listener navigates to /plan once recordHousehold fires.
  try {
    await page.waitForURL('**/plan', { timeout: 180000 });
    console.log('navigated to', page.url(), '— onboarding complete');
  } catch (e) {
    console.error('did not navigate to /plan within timeout; current url:', page.url());
    // Dump the last assistant text to help debug.
    const txt = await page.locator('vaadin-message-list').innerText().catch(() => '(no message list)');
    console.error('--- chat transcript ---\n' + txt);
    await browser.close();
    process.exit(2);
  }

  await page.waitForTimeout(3000);
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
