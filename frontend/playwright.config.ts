import { defineConfig } from '@playwright/test';

// Accessibility scans. Needs the UI (npm run dev) and, for meaningful app pages, the backend.
//   npx playwright install chromium      # once, or use an installed browser:
//   PW_CHANNEL=msedge npm run a11y        # e.g. Microsoft Edge / chrome
//   npm run a11y:report                   # open the HTML report (playwright-report/index.html)
export default defineConfig({
  testDir: './a11y',
  timeout: 60_000,
  reporter: [
    ['list'],                                                          // progress in the terminal
    ['html', { outputFolder: 'playwright-report', open: 'never' }],    // browsable report with attachments
  ],
  use: {
    baseURL: process.env.A11Y_BASE_URL ?? 'http://localhost:5173',
    channel: process.env.PW_CHANNEL || undefined,
    screenshot: 'only-on-failure',   // shown in the report next to the error
    trace: 'retain-on-failure',      // step-by-step replay: DOM, network, console (open it from the report)
  },
});
