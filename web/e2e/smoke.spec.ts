import { expect, test } from '@playwright/test';

/**
 * The loop no server-side test can see.
 *
 * Both of these guard bugs that shipped once already and were invisible to the
 * Java suite, because curl and RestClient were perfectly happy:
 *
 *  - springdoc documented every response as a wildcard media type, so the
 *    generated client asked for a Blob and every payload arrived unparsed. The
 *    API was correct; only the browser was broken.
 *  - the SPA needs one GET before its first POST to be handed an XSRF-TOKEN
 *    cookie, which is invisible in production (the shell comes from Spring) and
 *    fatal under `ng serve`.
 *
 * A console-error check is part of the assertion on purpose: a template that
 * throws on every change detection still renders something, so a screenshot or
 * a status code would not have caught it.
 */
test('register, create a trip, and see it listed', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    // A 401 from the startup session check is expected for a fresh visitor.
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();

  const email = `e2e-${Date.now()}@example.com`;
  await page.locator('input[name=email]').fill(email);
  await page.locator('input[name=displayName]').fill('E2E Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await expect(page).toHaveURL(/\/trips$/);
  await expect(page.getByText('No trips yet')).toBeVisible();

  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Kyoto in spring');
  await page.locator('input[name=destination]').fill('Kyoto, Japan');
  await page.locator('input[name=startDate]').fill('2027-03-28');
  await page.locator('input[name=endDate]').fill('2027-04-05');
  await page.getByRole('button', { name: 'Create trip' }).click();

  // The card renders from the POST response, so this asserts the body was
  // parsed as JSON rather than handed over as a Blob.
  await expect(page.getByRole('heading', { name: 'Kyoto in spring' })).toBeVisible();
  await expect(page.getByText('9 days')).toBeVisible();
  await expect(page.locator('[role=alert]')).toHaveCount(0);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

test('a signed-out visitor is sent to the login page', async ({ page }) => {
  await page.goto('/trips');
  await expect(page).toHaveURL(/\/login$/);
});

/**
 * The itinerary, end to end: derived days render, a place lands on the right
 * one, and reordering survives a round trip through the server (which owns
 * ranks and renumbers a whole day on every move).
 */
test('add places to a day and reorder them', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-places-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Itinerary Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await expect(page).toHaveURL(/\/trips$/);
  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Barcelona');
  await page.locator('input[name=startDate]').fill('2027-11-03');
  await page.locator('input[name=endDate]').fill('2027-11-05');
  await page.getByRole('button', { name: 'Create trip' }).click();

  await page.getByRole('link', { name: /Barcelona/ }).click();
  await expect(page).toHaveURL(/\/trips\/\d+$/);

  // Three days, all present, including the ones with nothing on them.
  await expect(page.getByRole('heading', { name: /^Day 1/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Day 3/ })).toBeVisible();

  const day1 = page.locator('ol > li.card').first();
  await day1.getByRole('button', { name: 'Add place' }).click();
  await day1.locator('input[name=name]').fill('Sagrada Familia');
  await day1.getByRole('button', { name: 'Add place' }).click();
  await expect(day1.getByText('Sagrada Familia')).toBeVisible();

  await day1.locator('input[name=name]').fill('Park Guell');
  await day1.getByRole('button', { name: 'Add place' }).click();
  await expect(day1.getByText('Park Guell')).toBeVisible();
  await day1.getByRole('button', { name: 'Done' }).click();

  const placeNames = day1.locator('ol > li p.font-medium');
  await expect(placeNames).toHaveText(['Sagrada Familia', 'Park Guell']);

  // Reordering is a server round trip, so this asserts the move endpoint and
  // the renumbering that follows it.
  await day1.getByRole('button', { name: 'Move down' }).first().click();
  await expect(placeNames).toHaveText(['Park Guell', 'Sagrada Familia']);

  // And across days: the second day is the next card down.
  const day2 = page.locator('ol > li.card').nth(1);
  await day1.getByRole('button', { name: 'Move to next day' }).first().click();
  await expect(placeNames).toHaveText(['Sagrada Familia']);
  await expect(day2.getByText('Park Guell')).toBeVisible();

  await expect(page.locator('[role=alert]')).toHaveCount(0);
  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * Search-to-add, with the search endpoint stubbed in the browser.
 *
 * Stubbing here rather than pointing the server at a fake Nominatim keeps the
 * test off the public geocoder — whose policy allows one request a second and
 * would rank differently over time anyway, making the assertions flaky. What is
 * being tested is the UI contract: a hit can be picked, and picking it saves the
 * point rather than just the name.
 */
test('search for a place, pick a suggestion, and keep its address', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  let searchCalls = 0;
  await page.route('**/api/geo/search**', async (route) => {
    searchCalls++;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          ref: 'way/34633854',
          name: 'Basílica de la Sagrada Família',
          address: 'Basílica de la Sagrada Família, Carrer de Mallorca, Barcelona, Spain',
          latitude: 41.4034984,
          longitude: 2.1744573,
          category: 'church',
        },
      ]),
    });
  });

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-search-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Search Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Barcelona');
  await page.locator('input[name=startDate]').fill('2027-11-03');
  await page.locator('input[name=endDate]').fill('2027-11-05');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Barcelona/ }).click();

  const day1 = page.locator('ol > li.card').first();
  await day1.getByRole('button', { name: 'Add place' }).click();
  await day1.locator('input[name=name]').fill('sagrada');

  // The suggestion list appears only after the debounce, which is the whole
  // point of it — one search per pause, not one per keystroke.
  const suggestion = day1.getByRole('button', { name: /Basílica de la Sagrada Família/ });
  await expect(suggestion).toBeVisible();
  expect(searchCalls, 'one debounced search, not one per character').toBe(1);

  await suggestion.click();
  await expect(day1.locator('input[name=name]')).toHaveValue('Basílica de la Sagrada Família');
  // Picking shows the address that will be saved with the place.
  await expect(day1.getByText('Carrer de Mallorca', { exact: false })).toBeVisible();

  await day1.getByRole('button', { name: 'Add place' }).click();

  // The saved row carries the address, so the coordinates survived the round
  // trip through the API and the database.
  const saved = day1.locator('ol > li').first();
  // Exact, because the address line below the name contains it too.
  await expect(saved.getByText('Basílica de la Sagrada Família', { exact: true })).toBeVisible();
  await expect(saved.getByText(/Carrer de Mallorca/)).toBeVisible();

  await expect(page.locator('[role=alert]')).toHaveCount(0);
  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});
