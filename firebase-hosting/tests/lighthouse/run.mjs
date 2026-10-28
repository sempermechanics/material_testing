// Lighthouse on every console page and the hosted pages around them: the
// Accessibility and Best Practices scores are each at least 95 (AE2-110,
// readiness 2026-10-28), at phone and desktop size.
//
// The pages are served and answered as console.a11y.spec.mjs serves them
// (../a11y/stub-site.mjs): public/ on 127.0.0.1, the fake Firebase SDK, the
// stub API, and every other host refused, so nothing reaches the network.
// Lighthouse drives a page puppeteer opens in Playwright's Chromium, and the
// stub answers that page's requests through puppeteer's interception.
//
// Run from firebase-hosting/: npm ci && npx playwright install chromium &&
// npm run lighthouse. Each run's JSON report goes to lighthouse-report/
// (gitignored; CI keeps it as an artifact). Exits 1 when a score is short.
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import lighthouse from "lighthouse";
import puppeteer from "puppeteer-core"; // Lighthouse's own driver (its dependency)
import { chromium } from "@playwright/test";
import { serveSite, stubContext, PERSONAS } from "../a11y/stub-site.mjs";

const MIN = 0.95;
const CATEGORIES = ["accessibility", "best-practices"];
const REPORTS = new URL("../../lighthouse-report/", import.meta.url);

/**
 * Each page, signed in as whom it is for, and what is on it once it has
 * settled (the console states the a11y spec opens). The selector is checked
 * after the audit, so a page stuck on "Checking your sign-in…" cannot pass
 * by having nothing on it to fault.
 */
const PAGES = [
  { name: "sign-in (signed out)", path: "/console/", persona: "out", ready: 'body[data-auth="out"]' },
  { name: "front door (staff)", path: "/console/", persona: "staff", ready: "#choices:not([hidden]) a" },
  { name: "account (licensed)", path: "/console/account/", persona: "holder", ready: "#rows tr td" },
  { name: "account (Demo)", path: "/console/account/", persona: "demo", ready: "#claimCard:not([hidden])" },
  { name: "institution roster", path: "/console/institution/?license=L1", persona: "it", ready: "#rows button[data-act]" },
  { name: "operator desk", path: "/console/operator/", persona: "staff", ready: "#licenceRows button[data-edit]" },
  { name: "password-reset landing", path: "/finishReset/", persona: "out", ready: "main #continueLink" },
  { name: "email-link sign-in landing", path: "/finishSignIn/", persona: "out", ready: "main h1" },
  { name: "404", path: "/404.html", persona: "out", ready: "main h1" },
];

const FORM_FACTORS = {
  phone: {}, // Lighthouse's default: a mid-range phone's screen
  desktop: {
    formFactor: "desktop",
    screenEmulation: { mobile: false, width: 1350, height: 940, deviceScaleFactor: 1, disabled: false },
  },
};

/**
 * stubContext() routes a Playwright context; this gives it the same
 * `route(pattern, handler)` over a puppeteer page's request interception.
 * A refused third-party request (the web fonts) is answered empty rather
 * than failed: the deployed page loads them, and a harness-made failure in
 * the console would count against Best Practices.
 */
async function stubPage(page, persona) {
  let handler = null;
  await stubContext({ route: async (_pattern, fn) => { handler = fn; } }, persona);
  await page.setRequestInterception(true);
  page.on("request", (request) => {
    if (request.isInterceptResolutionHandled()) return;
    const url = new URL(request.url());
    if (url.protocol === "data:" || url.protocol === "about:") return void request.continue();
    handler({
      request: () => ({ url: () => request.url(), method: () => request.method() }),
      fulfill: ({ status, body, headers = {}, contentType }) => request.respond({
        status, body, headers, ...(contentType ? { contentType } : {}),
      }),
      abort: () => (/^fonts\.(googleapis|gstatic)\.com$/.test(url.hostname)
        ? request.respond({ status: 200, contentType: url.hostname.startsWith("fonts.googleapis") ? "text/css" : "font/woff2", body: "" })
        : request.abort()),
      continue: () => request.continue(),
    });
  });
}

/** A port nothing listens on just now, for Chromium's DevTools endpoint. */
async function freePort() {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  await new Promise((resolve) => server.close(resolve));
  return port;
}

// Playwright launches its Chromium (the one `playwright install` fetched);
// puppeteer attaches to it over the DevTools port.
const site = await serveSite();
const port = await freePort();
const chrome = await chromium.launch({ args: [`--remote-debugging-port=${port}`] });
const browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${port}` });
await mkdir(REPORTS, { recursive: true });

const short = [];
const table = [];
try {
  for (const target of PAGES) {
    for (const [size, settings] of Object.entries(FORM_FACTORS)) {
      const context = await browser.createBrowserContext();
      const page = await context.newPage();
      await stubPage(page, PERSONAS[target.persona]);
      const result = await lighthouse(site.origin + target.path, { logLevel: "error" }, {
        extends: "lighthouse:default",
        settings: {
          onlyCategories: CATEGORIES, throttlingMethod: "provided", disableFullPageScreenshot: true, ...settings,
        },
      }, page);
      const settled = await page.$(target.ready);
      await context.close();
      const { lhr } = result;
      const slug = `${target.name}-${size}`.replace(/[^\w]+/g, "-").replace(/-+$/, "");
      await writeFile(new URL(`${slug}.json`, REPORTS), JSON.stringify(lhr));
      const scores = CATEGORIES.map((id) => lhr.categories[id].score);
      table.push(`${scores.map((s) => String(Math.round(s * 100)).padStart(4)).join(" ")}  ${target.name} (${size})`);
      CATEGORIES.forEach((id, i) => {
        if (scores[i] >= MIN) return;
        const failing = lhr.categories[id].auditRefs
          .filter((ref) => ref.weight > 0 && lhr.audits[ref.id].score !== null && lhr.audits[ref.id].score < 1)
          .map((ref) => ref.id);
        short.push(`${target.name} (${size}): ${id} ${Math.round(scores[i] * 100)} < ${MIN * 100}` +
          ` — failing: ${failing.join(", ")}`);
      });
      if (lhr.runtimeError) short.push(`${target.name} (${size}): ${lhr.runtimeError.message}`);
      if (!settled) short.push(`${target.name} (${size}): never showed ${target.ready}`);
    }
  }
} finally {
  await browser.disconnect();
  await chrome.close();
  await site.close();
}

console.log(" a11y  b-p  page");
for (const line of table) console.log(line);
if (short.length) {
  console.error(`\nUnder ${MIN * 100}:\n${short.join("\n")}`);
  process.exit(1);
}
console.log(`\nEvery page: Accessibility and Best Practices at least ${MIN * 100} (${table.length} runs).`);
