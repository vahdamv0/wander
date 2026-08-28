import { defineConfig } from '@playwright/test';

/**
 * Browser tests against a running instance (default http://localhost:8080 — the
 * packaged jar, i.e. what actually ships).
 *
 * Start the app first, then `npm run e2e`. Not wired into CI yet: that needs the
 * stack running beside the runner, which is its own piece of work.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  reporter: [['list']],
  use: {
    baseURL: process.env.WANDER_E2E_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    /**
     * Service workers off by default, and deliberately.
     *
     * Once one controls the page it sits between the page and the network, and
     * `page.route` no longer intercepts what goes through it — which silently
     * broke the two tests that stub the geocoder the moment offline support was
     * added. Blocking it here keeps every test that stubs a request honest.
     *
     * The offline test opens its own context with `serviceWorkers: 'allow'`,
     * because there the worker *is* the thing under test.
     */
    serviceWorkers: 'block',
  },
});
