// Playwright for the consoles' accessibility check (console.a11y.spec.mjs).
// Run from firebase-hosting/: npm ci && npx playwright install chromium &&
// npm run test:a11y. Chromium only: axe's rules do not differ by engine, and
// one browser keeps the CI job to a couple of minutes.
import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  testMatch: /\.a11y\.spec\.mjs$/,
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: { ...devices["Desktop Chrome"], trace: "retain-on-failure" },
  projects: [{ name: "chromium" }],
});
