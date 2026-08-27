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

/**
 * The map, with tiles blocked.
 *
 * Blocking them is deliberate: tile requests go to a third-party service, and a
 * test suite has no business hammering one or failing when it is unreachable.
 * Leaflet still builds its DOM, so everything worth asserting — a pin per placed
 * place, the attribution the tile terms require, a popup on click — is testable
 * offline.
 */
test('places with a location get a pin on the map', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  // No outbound tile traffic from a test run. Answered with a transparent pixel
  // rather than aborted: an aborted request logs a console error, and the
  // console-error assertion below is worth more than the shortcut.
  const TRANSPARENT_PNG = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
    'base64',
  );
  await page.route('**://*.openstreetmap.org/**', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: TRANSPARENT_PNG }),
  );

  await page.route('**/api/geo/search**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          ref: 'way/1',
          name: 'Park Güell',
          address: 'Park Güell, Carrer d’Olot, Barcelona, Spain',
          latitude: 41.4145,
          longitude: 2.1527,
          category: 'park',
        },
      ]),
    });
  });

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-map-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Map Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Barcelona');
  await page.locator('input[name=startDate]').fill('2027-11-03');
  await page.locator('input[name=endDate]').fill('2027-11-05');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Barcelona/ }).click();

  // The map is drawn from instance config, so its presence also proves
  // /api/config was fetched and applied.
  const map = page.locator('.leaflet-container');
  await expect(map).toBeVisible();
  // Required by the tile service's terms — worth asserting so a refactor cannot
  // quietly drop it.
  await expect(page.locator('.leaflet-control-attribution')).toContainText('OpenStreetMap');
  await expect(page.locator('.map-pin')).toHaveCount(0);

  const day1 = page.locator('ol > li.card').first();
  await day1.getByRole('button', { name: 'Add place' }).click();
  await day1.locator('input[name=name]').fill('park guell');
  await day1.getByRole('button', { name: /Park Güell/ }).click();
  await day1.getByRole('button', { name: 'Add place' }).click();

  // One pin, labelled with the day it belongs to.
  const pin = page.locator('.map-pin');
  await expect(pin).toHaveCount(1);
  await expect(pin).toHaveText('1');

  // A place typed by hand gets no pin, which is the case the empty-state hint
  // exists to explain.
  await day1.locator('input[name=name]').fill('That cafe we liked');
  await day1.getByRole('button', { name: 'Add place' }).click();
  await expect(day1.getByText('That cafe we liked')).toBeVisible();
  await expect(page.locator('.map-pin')).toHaveCount(1);

  // Clicking a marker highlights its row; clicking a row's pin button moves the
  // map to it.
  await pin.click();
  await expect(page.locator('.leaflet-popup-content')).toContainText('Park Güell');
  await day1.getByRole('button', { name: /Show Park Güell on the map/ }).click();

  await expect(page.locator('[role=alert]')).toHaveCount(0);
  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * Dragging a place, within a day and then into the next one.
 *
 * Driven with raw mouse events rather than `dragTo`: the CDK starts a drag only
 * after the pointer has moved a few pixels, and the drop position depends on
 * where the pointer is when the button comes up, so the steps matter.
 */
test('drag a place within a day and into the next one', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-drag-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Drag Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Lisbon');
  await page.locator('input[name=startDate]').fill('2027-06-01');
  await page.locator('input[name=endDate]').fill('2027-06-02');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Lisbon/ }).click();

  const day1 = page.locator('ol > li.card').first();
  const day2 = page.locator('ol > li.card').nth(1);
  await day1.getByRole('button', { name: 'Add place' }).click();
  for (const name of ['Alfama', 'Belém', 'Time Out Market']) {
    await day1.locator('input[name=name]').fill(name);
    await day1.getByRole('button', { name: 'Add place' }).click();
    await expect(day1.getByText(name, { exact: true })).toBeVisible();
  }
  await day1.getByRole('button', { name: 'Done' }).click();

  const day1Names = day1.locator('ol > li p.font-medium');
  await expect(day1Names).toHaveText(['Alfama', 'Belém', 'Time Out Market']);

  /** Presses the row's handle and moves the pointer to `target` in steps. */
  async function dragTo(rowName: string, target: { x: number; y: number }) {
    const handle = page.getByRole('button', { name: `Drag ${rowName} to reorder` });
    const box = (await handle.boundingBox())!;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    // A few pixels first: below the CDK's threshold nothing starts.
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + 8, { steps: 4 });
    await page.mouse.move(target.x, target.y, { steps: 12 });
    await page.mouse.up();
  }

  // Within the day: Alfama down past the third row.
  const third = (await day1.locator('ol > li').nth(2).boundingBox())!;
  await dragTo('Alfama', { x: third.x + third.width / 2, y: third.y + third.height - 4 });
  await expect(day1Names).toHaveText(['Belém', 'Time Out Market', 'Alfama']);

  // Ranks are the server's, so a reload proves the move was persisted rather
  // than only reordered on screen.
  await page.reload();
  await expect(day1Names).toHaveText(['Belém', 'Time Out Market', 'Alfama']);

  // Across days: into day 2, which is empty.
  const day2Box = (await day2.boundingBox())!;
  await dragTo('Belém', { x: day2Box.x + day2Box.width / 2, y: day2Box.y + day2Box.height / 2 });
  await expect(day1Names).toHaveText(['Time Out Market', 'Alfama']);
  await expect(day2.locator('ol > li p.font-medium')).toHaveText(['Belém']);

  await page.reload();
  await expect(day2.locator('ol > li p.font-medium')).toHaveText(['Belém']);

  await expect(page.locator('[role=alert]')).toHaveCount(0);
  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});
