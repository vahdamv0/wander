import { Page, expect, test } from '@playwright/test';

/**
 * Keeps a test run off the basemap servers.
 *
 * The map is drawn from MapLibre vector tiles now, which means a style document,
 * glyphs, sprites and a tile per screenful — considerably more traffic than the
 * raster tiles this used to block, and aimed at a service run by one person on
 * donations. So every page that might render a map gets an *empty but valid*
 * style: MapLibre draws a blank basemap, asks for nothing further, and logs no
 * error — which matters, because these tests assert on console errors.
 *
 * The markers, tooltips and framing are Leaflet's and are unaffected, so
 * everything worth asserting about the map still is.
 */
const EMPTY_STYLE = JSON.stringify({ version: 8, sources: {}, layers: [] });

async function stubBasemap(page: Page): Promise<void> {
  await page.route('**tiles.openfreemap.org/**', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_STYLE }),
  );
}

test.beforeEach(async ({ page }) => {
  await stubBasemap(page);
});

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

test('rename yourself, and the header follows', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  const email = `e2e-rename-${Date.now()}@example.com`;
  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(email);
  await page.locator('input[name=displayName]').fill('Original Name');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();
  await expect(page).toHaveURL(/\/trips$/);

  // The avatar carries the initials, so it is the visible proof the rename
  // reached the shell and not only the page that did it.
  const avatar = page.getByRole('button', { name: /Account menu/ });
  await expect(avatar).toHaveText('ON');

  await avatar.click();
  // The version chip: "dev" for an image built outside a release pipeline, which
  // is what a local run is. Not a menuitem — it is not actionable.
  //
  // Unanchored on purpose: getByText does not normalise whitespace when matching
  // a regex, so a `^` is defeated by the template's own indentation.
  await expect(page.getByText(/wander (dev|v\d)/)).toBeVisible();
  await page.getByRole('menuitem', { name: 'Your account' }).click();
  await expect(page).toHaveURL(/\/account$/);

  await page.locator('input[name=displayName]').fill('  Renamed Person  ');
  await page.getByRole('button', { name: 'Save name' }).click();
  await expect(page.getByText('Name saved.')).toBeVisible();

  // Trimmed by the server, and the field shows what was actually stored.
  await expect(page.locator('input[name=displayName]')).toHaveValue('Renamed Person');
  await expect(avatar).toHaveText('RP');

  // The principal lives in the session, so a reload is what would catch a rename
  // that only touched the database row.
  await page.reload();
  await expect(page.getByRole('button', { name: /Account menu/ })).toHaveText('RP');
  await expect(page.getByText('Renamed Person')).toBeVisible();

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

test('change your own password, and the old one stops working', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    // The 401 from the startup session check, and the 400 the deliberate wrong
    // password produces. Filtered by what they are rather than by being errors
    // at all, so any *other* console error still fails this test.
    if (
      message.type() === 'error' &&
      !message.text().includes('401') &&
      !message.text().includes('400')
    ) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  const email = `e2e-pw-${Date.now()}@example.com`;
  const original = 'correct-horse-battery';
  const replacement = 'a-quite-different-passphrase';

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(email);
  await page.locator('input[name=displayName]').fill('Password Tester');
  await page.locator('input[name=password]').fill(original);
  await page.locator('button[type=submit]').click();
  await expect(page).toHaveURL(/\/trips$/);

  // The avatar opens a menu; the menu is the only way to either of these.
  await page.getByRole('button', { name: /Account menu/ }).click();
  await expect(page.getByRole('menu')).toBeVisible();
  await page.getByRole('menuitem', { name: 'Your account' }).click();
  await expect(page).toHaveURL(/\/account$/);
  // The menu closes behind you rather than hanging over the page it navigated to.
  await expect(page.getByRole('menu')).toHaveCount(0);

  // A wrong current password is refused, and — the part worth asserting — the
  // page stays put. A 401 here would have signed the user out over a typo.
  await page.locator('input[name=currentPassword]').fill('not-the-password');
  await page.locator('input[name=newPassword]').fill(replacement);
  await page.locator('input[name=confirmPassword]').fill(replacement);
  await page.getByRole('button', { name: 'Change password' }).click();
  await expect(page.locator('[role=alert]')).toBeVisible();
  await expect(page).toHaveURL(/\/account$/);

  await page.locator('input[name=currentPassword]').fill(original);
  await page.locator('input[name=newPassword]').fill(replacement);
  await page.locator('input[name=confirmPassword]').fill(replacement);
  await page.getByRole('button', { name: 'Change password' }).click();
  await expect(page.getByText(/Password changed/)).toBeVisible();

  await page.getByRole('button', { name: /Account menu/ }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);

  await page.locator('input[name=email]').fill(email);
  await page.locator('input[name=password]').fill(original);
  await page.locator('button[type=submit]').click();
  await expect(page.locator('[role=alert]')).toBeVisible();

  await page.locator('input[name=password]').fill(replacement);
  await page.locator('button[type=submit]').click();
  await expect(page).toHaveURL(/\/trips$/);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

test('a guessable password is refused, and the form says why', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    // The startup 401 and the 400 this test deliberately provokes.
    if (
      message.type() === 'error' &&
      !message.text().includes('401') &&
      !message.text().includes('400')
    ) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-weak-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Weak Password');
  // Ten characters, so it clears the length bound — this is the blocklist
  // talking, not @Size.
  await page.locator('input[name=password]').fill('password12');
  await page.locator('button[type=submit]').click();

  // The half worth asserting is the *text*. The server answers a validation
  // failure with a generic "Validation failed" in `message` and the real reason
  // in `fields`, so a client that reads only the former leaves somebody staring
  // at a password the form let them type with no idea what is wrong with it.
  await expect(page.locator('[role=alert]')).toContainText(/too easy to guess/i);
  await expect(page).toHaveURL(/\/login/);

  // And a real passphrase still gets through the same form.
  await page.locator('input[name=password]').fill('a-quiet-week-in-kanazawa');
  await page.locator('button[type=submit]').click();
  await expect(page).toHaveURL(/\/trips$/);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

test('a signed-out visitor is sent to the login page, and back afterwards', async ({ page }) => {
  await page.goto('/trips');
  await expect(page).toHaveURL(/\/login\?returnUrl=%2Ftrips$/);

  // Where they were going travels with them. It is what makes an invitation link
  // work for somebody with no account — the whole journey is link, register, back
  // to the link — and it is only ever a path from this application's own router,
  // because a login page that will redirect to an arbitrary URL is a phishing
  // primitive.
  // A *successful* sign-in is the only thing that redirects, so this has to
  // register rather than fail a login — a failed one never leaves the page and
  // would pass this assertion without testing anything.
  await page.goto('/login?returnUrl=https://example.com/phish');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-return-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Return Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();
  await expect(page).toHaveURL(/\/trips$/);
});

/**
 * The itinerary, end to end: derived days render, a place lands on the right
 * one, and reordering survives a round trip through the server (which owns
 * ranks and renumbers a whole day on every move).
 */
/**
 * Note for anyone changing the place row: its name element is a handle these
 * tests identify a place by. It has broken them twice — once when a category chip
 * was added inside it, and once when it became a button so it could open the
 * detail panel. Changing it is fine; changing it without updating these is not.
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

  const placeNames = day1.locator('ol > li button.font-medium');
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

  // The row's × asks before it destroys anything. It is the sixth small icon in
  // the strip, directly after the arrow just used above, and `.row-actions` stay
  // on permanently where there is no hover — so on a phone it is a live target
  // beside a move button. Backing out leaves the place alone.
  await day2.getByRole('button', { name: 'Remove Park Guell', exact: true }).click();
  await day2.getByRole('button', { name: 'Keep Park Guell', exact: true }).click();
  await expect(day2.getByText('Park Guell')).toBeVisible();

  await day2.getByRole('button', { name: 'Remove Park Guell', exact: true }).click();
  await day2.getByRole('button', { name: 'Yes, remove Park Guell', exact: true }).click();
  await expect(day2.getByText('Park Guell')).toHaveCount(0);

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
  const languages: string[] = [];
  await page.route('**/api/geo/search**', async (route) => {
    searchCalls++;
    // The server forwards this to the geocoder, which otherwise answers in the
    // place's own language — a search for Kyoto comes back as 京都. JavaScript
    // is forbidden from setting it, so the only proof it is really sent is here.
    languages.push((await route.request().allHeaders())['accept-language'] ?? '');
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
  expect(languages[0], 'the browser must send Accept-Language').toMatch(/[a-z]{2}/);

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
 * place, the attribution the tile terms require, the label on hover — is testable
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

  // Hovering a marker labels it with its day and name. A tooltip and not a
  // popup, deliberately: a click opens the detail panel over the map, and a
  // popup there is drawn underneath it with nowhere to pan to.
  await pin.hover();
  await expect(page.locator('.leaflet-tooltip')).toContainText('Park Güell');

  // Clicking a marker highlights its row; clicking a row's pin button moves the
  // map to it and labels the pin it landed on.
  await pin.click();
  await day1.getByRole('button', { name: /Show Park Güell on the map/ }).click();
  await expect(page.locator('.leaflet-tooltip')).toContainText('Park Güell');

  await expect(page.locator('[role=alert]')).toHaveCount(0);
  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * The map has to be *visible* to be worth moving.
 *
 * Two mechanisms, one symptom. On a wide screen the map is stuck to the top of
 * its column, which only works because its grid column stretches to the row's
 * height — a sticky element whose containing block is exactly its own size has
 * nowhere to travel, and `items-start` on that grid sized the column to the map
 * and quietly disabled it. On a narrow screen the map is above the days instead,
 * so a pin clicked on day four has to bring it back.
 *
 * Both were invisible to every other test here: the suite asserts on a pin's
 * tooltip and on marker counts, and Playwright's visibility is CSS rather than
 * "in the window", so a map scrolled a thousand pixels off the top is still
 * `toBeVisible`. `toBeInViewport` is the assertion that notices.
 */
test('the map stays in view on a long trip, whichever way the page is laid out', async ({
  page,
}) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await stubBasemap(page);
  await page.route('**/api/geo/search**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          ref: 'node/1',
          name: 'Fushimi Inari',
          address: 'Fushimi, Kyoto, Japan',
          latitude: 34.9671,
          longitude: 135.7727,
          category: 'shrine',
        },
      ]),
    }),
  );

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-stick-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Sticky Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Kyoto in Spring');
  await page.locator('input[name=startDate]').fill('2027-04-02');
  // Nine days, so the list is comfortably longer than the window.
  await page.locator('input[name=endDate]').fill('2027-04-10');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Kyoto/ }).click();

  const day4 = page.locator('ol > li.card').nth(3);
  await day4.getByRole('button', { name: 'Add place' }).click();
  await day4.locator('input[name=name]').fill('fushimi');
  await day4.getByRole('button', { name: /Fushimi Inari/ }).click();
  await day4.getByRole('button', { name: 'Add place' }).click();
  await day4.getByRole('button', { name: 'Done' }).click();

  const map = page.locator('.leaflet-container');

  // Wide: scrolling to the bottom of a nine-day list must not take the map with
  // it. Asserted at a ratio rather than the default "any pixel of it": without
  // the sticky working, a short scroll still leaves the map's lower edge on
  // screen, and a test that accepts one visible pixel would pass on a map that
  // has all but gone.
  await page.locator('ol > li.card').last().scrollIntoViewIfNeeded();
  await expect(map).toBeInViewport({ ratio: 0.9 });

  // Narrow: the map is stacked above the days instead of beside them, so there is
  // nothing to stick to and the pin has to bring it back. The row's pin is
  // matched by its screen-reader name, which carries the place's own.
  await page.setViewportSize({ width: 700, height: 800 });
  const day4Row = page.locator('ol > li.card').nth(3);
  await day4Row.scrollIntoViewIfNeeded();
  await expect(map).not.toBeInViewport();
  await day4Row.getByRole('button', { name: /Show .* on the map/ }).click();
  await expect(map).toBeInViewport({ ratio: 0.9 });

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * The one file the bundler never sees.
 *
 * MapLibre parses vector tiles in a web worker that it loads by URL at runtime,
 * so nothing imports it and no bundler emits it — `angular.json` copies it out
 * of the package instead, along with the shared chunk it imports beside itself.
 * Get that wrong and the request falls through to the SPA fallback, which
 * answers `index.html`: the worker dies on its first line with nothing logged,
 * no MapLibre error event and no failed request, and the only symptom is a
 * basemap that never draws while the style, the sprites and the markers all
 * load perfectly.
 *
 * Nothing else here would catch it — the suite stubs the basemap with an empty
 * style precisely so a test run stays off a donation-funded tile service, and an
 * empty style asks for no vector tiles and so needs no worker.
 */
test('the map worker is served, not swallowed by the SPA fallback', async ({ page }) => {
  await page.goto('/login');

  for (const file of ['maplibre-gl-worker.mjs', 'maplibre-gl-shared.mjs']) {
    const response = await page.request.get(new URL(file, page.url()).href);
    expect(response.status(), file).toBe(200);
    // A module worker is refused outright unless the type is a JavaScript one,
    // and `index.html` would arrive as text/html with a 200 beside it.
    expect(response.headers()['content-type'], file).toMatch(/javascript|ecmascript/);
  }
});

/**
 * Dragging a place, within a day and then into the next one.
 *
 * Driven with raw mouse events rather than `dragTo`: the CDK starts a drag only
 * after the pointer has moved a few pixels, and the drop position depends on
 * where the pointer is when the button comes up, so the steps matter.
 */
test('the tab icon is served, not swallowed by the SPA fallback', async ({ page }) => {
  // Same failure as the map worker: a missing file under a client-side routing
  // fallback comes back 200 with index.html in it, so "the request succeeded"
  // proves nothing. Only the content type does — and a favicon that quietly
  // fails leaves a generic page icon that nobody thinks to report.
  for (const [path, type] of [
    ['/favicon.svg', 'image/svg+xml'],
    ['/favicon.ico', 'image/x-icon'],
  ]) {
    const response = await page.request.get(path);
    expect(response.status(), path).toBe(200);
    expect(response.headers()['content-type'], path).toContain(type);
  }

  await page.goto('/login');
  await expect(page.locator('link[rel=icon][type="image/svg+xml"]')).toHaveCount(1);
});

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

  const day1Names = day1.locator('ol > li button.font-medium');
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
  await expect(day2.locator('ol > li button.font-medium')).toHaveText(['Belém']);

  await page.reload();
  await expect(day2.locator('ol > li button.font-medium')).toHaveText(['Belém']);

  await expect(page.locator('[role=alert]')).toHaveCount(0);
  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * A day's note: written from the day header, kept on the itinerary, and cleared
 * by emptying the box. The clear path is the one worth a browser test — it is a
 * PUT with an empty string, not a DELETE, so a client that sent `undefined`
 * instead would look like it worked and change nothing.
 */
test('write, edit, and clear the note on a day', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-notes-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Note Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await expect(page).toHaveURL(/\/trips$/);
  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Porto');
  await page.locator('input[name=startDate]').fill('2027-05-10');
  await page.locator('input[name=endDate]').fill('2027-05-12');
  await page.getByRole('button', { name: 'Create trip' }).click();

  await page.getByRole('link', { name: /Porto/ }).click();
  await expect(page).toHaveURL(/\/trips\/\d+$/);

  const day1 = page.locator('ol > li.card').first();
  await day1.getByRole('button', { name: /Add the note for day 1/ }).click();
  await day1.locator('textarea[name=dayNote]').fill('Arrive late — dinner near the station.');
  await day1.getByRole('button', { name: 'Save note' }).click();

  // Rendered from the re-read itinerary, so this proves the note round-tripped.
  await expect(day1.getByText('Arrive late — dinner near the station.')).toBeVisible();
  // And only on the day it was written on.
  const day2 = page.locator('ol > li.card').nth(1);
  await expect(day2.getByText('Arrive late')).toHaveCount(0);

  // It survives a reload, and the button now offers to edit rather than add.
  await page.reload();
  await expect(day1.getByText('Arrive late — dinner near the station.')).toBeVisible();

  await day1.getByRole('button', { name: /Edit the note for day 1/ }).click();
  await expect(day1.locator('textarea[name=dayNote]')).toHaveValue(
    'Arrive late — dinner near the station.',
  );
  await day1.locator('textarea[name=dayNote]').fill('');
  await day1.getByRole('button', { name: 'Save note' }).click();

  await expect(day1.getByText('Arrive late')).toHaveCount(0);
  await expect(day1.getByRole('button', { name: /Add the note for day 1/ })).toBeVisible();

  await expect(page.locator('[role=alert]')).toHaveCount(0);
  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * Sharing, in two browsers.
 *
 * Two contexts rather than two pages: a session is a cookie, and one context
 * would just log the first account out. This is the one flow that cannot be
 * checked from a single browser at all — the point is that what A did shows up
 * for B, with the rights A gave them and no others.
 */
test('share a trip with somebody, who then sees it read-only', async ({ browser }) => {
  const stamp = Date.now();
  const guestEmail = `e2e-guest-${stamp}@example.com`;

  const ownerContext = await browser.newContext();
  const guestContext = await browser.newContext();
  const owner = await ownerContext.newPage();
  const guest = await guestContext.newPage();
  await Promise.all([stubBasemap(owner), stubBasemap(guest)]);

  const consoleErrors: string[] = [];
  for (const page of [owner, guest]) {
    page.on('console', (message) => {
      if (message.type() === 'error' && !message.text().includes('401')) {
        consoleErrors.push(message.text());
      }
    });
    page.on('pageerror', (error) => consoleErrors.push(error.message));
  }

  const register = async (page: typeof owner, email: string, name: string) => {
    await page.goto('/login');
    await page.getByText('Create one').click();
    await page.locator('input[name=email]').fill(email);
    await page.locator('input[name=displayName]').fill(name);
    await page.locator('input[name=password]').fill('correct-horse-battery');
    await page.locator('button[type=submit]').click();
    await expect(page).toHaveURL(/\/trips$/);
  };

  await register(owner, `e2e-owner-${stamp}@example.com`, 'Trip Owner');
  await register(guest, guestEmail, 'Trip Guest');

  // The guest has nothing yet, which is what makes the appearance below mean
  // something.
  await expect(guest.getByText('No trips yet')).toBeVisible();

  await owner.getByRole('button', { name: 'Plan your first trip' }).click();
  await owner.locator('input[name=name]').fill('Oslo');
  await owner.locator('input[name=startDate]').fill('2027-08-02');
  await owner.locator('input[name=endDate]').fill('2027-08-04');
  await owner.getByRole('button', { name: 'Create trip' }).click();
  await owner.getByRole('link', { name: /Oslo/ }).click();
  await expect(owner).toHaveURL(/\/trips\/\d+$/);

  // The panel fetches nothing until it is opened, so the count appears with it.
  await owner.getByRole('button', { name: /People/ }).click();
  // exact: true throughout — the row's controls carry the member's name in their
  // screen-reader labels, so a loose match finds three elements.
  await expect(owner.getByText('Trip Owner (you)')).toBeVisible();

  await owner.locator('input[name=memberEmail]').fill(guestEmail);
  await owner.locator('select[name=memberRole]').selectOption('VIEWER');
  await owner.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(owner.getByText('Trip Guest', { exact: true })).toBeVisible();
  await expect(owner.getByText(guestEmail)).toBeVisible();

  // Over in the other browser: the trip is simply there now.
  await guest.reload();
  await guest.getByRole('link', { name: /Oslo/ }).click();
  await expect(guest).toHaveURL(/\/trips\/\d+$/);
  await expect(guest.getByText('Read only')).toBeVisible();
  await expect(guest.getByRole('heading', { name: /^Day 1/ })).toBeVisible();
  // A viewer gets no controls at all — not a control that fails on click.
  await expect(guest.getByRole('button', { name: 'Add place' })).toHaveCount(0);

  // And they can see who else is on it, without being able to change it.
  await guest.getByRole('button', { name: /People/ }).click();
  await expect(guest.getByText('Trip Owner', { exact: true })).toBeVisible();
  await expect(guest.locator('input[name=memberEmail]')).toHaveCount(0);

  // A viewer is read-only, not offline: the socket's handshake asks for
  // membership, not a write role, so somebody else's edit lands here with no
  // reload — which is most of the point of sharing a trip read-only.
  const ownerDay1 = owner.locator('ol > li.card').first();
  await ownerDay1.getByRole('button', { name: 'Add place' }).click();
  await ownerDay1.locator('input[name=name]').fill('Vigeland Park');
  await ownerDay1.getByRole('button', { name: 'Add place' }).click();
  await expect(guest.locator('ol > li.card').first().getByText('Vigeland Park')).toBeVisible();
  // Still read-only, and still without any controls to change it.
  await expect(guest.getByText('Read only')).toBeVisible();
  await expect(guest.getByRole('button', { name: 'Add place' })).toHaveCount(0);

  // Leaving is the one membership change a viewer may make.
  await guest.getByRole('button', { name: 'Leave trip' }).click();
  await expect(guest).toHaveURL(/\/trips$/);
  await expect(guest.getByText('No trips yet')).toBeVisible();

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
  await ownerContext.close();
  await guestContext.close();
});

/**
 * Live sync, which is the one feature that is *defined* by two browsers.
 *
 * Everything here is asserted without a reload anywhere: the whole point is that
 * a change made in one browser appears in the other on its own. A passing
 * server-side test proves the frame was sent, not that anybody acted on it.
 */
test('one person edits, the other sees it without reloading', async ({ browser }) => {
  const stamp = Date.now();
  const editorEmail = `e2e-sync-editor-${stamp}@example.com`;

  const ownerContext = await browser.newContext();
  const editorContext = await browser.newContext();
  const owner = await ownerContext.newPage();
  const editor = await editorContext.newPage();
  await Promise.all([stubBasemap(owner), stubBasemap(editor)]);

  const consoleErrors: string[] = [];
  for (const page of [owner, editor]) {
    page.on('console', (message) => {
      // This test cuts the editor's network on purpose, and the re-read that
      // fails while it is down is the whole point of the retry being tested.
      // The browser logs that as a console error, so it is filtered by name —
      // and only by name, so any other error still fails the test. It is
      // intermittent because a short outage sometimes ends before the re-read
      // is even attempted, which is what made this look like a flake.
      const expected =
        message.text().includes('401') || message.text().includes('ERR_INTERNET_DISCONNECTED');
      if (message.type() === 'error' && !expected) {
        consoleErrors.push(message.text());
      }
    });
    page.on('pageerror', (error) => consoleErrors.push(error.message));
  }

  const register = async (page: typeof owner, email: string, name: string) => {
    await page.goto('/login');
    await page.getByText('Create one').click();
    await page.locator('input[name=email]').fill(email);
    await page.locator('input[name=displayName]').fill(name);
    await page.locator('input[name=password]').fill('correct-horse-battery');
    await page.locator('button[type=submit]').click();
    await expect(page).toHaveURL(/\/trips$/);
  };

  await register(owner, `e2e-sync-owner-${stamp}@example.com`, 'Sync Owner');
  await register(editor, editorEmail, 'Sync Editor');

  await owner.getByRole('button', { name: 'Plan your first trip' }).click();
  await owner.locator('input[name=name]').fill('Reykjavik');
  await owner.locator('input[name=startDate]').fill('2027-10-05');
  await owner.locator('input[name=endDate]').fill('2027-10-07');
  await owner.getByRole('button', { name: 'Create trip' }).click();
  await owner.getByRole('link', { name: /Reykjavik/ }).click();
  await expect(owner).toHaveURL(/\/trips\/\d+$/);

  await owner.getByRole('button', { name: /People/ }).click();
  await owner.locator('input[name=memberEmail]').fill(editorEmail);
  await owner.locator('select[name=memberRole]').selectOption('EDITOR');
  await owner.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(owner.getByText('Sync Editor', { exact: true })).toBeVisible();

  // Both parties now sit on the trip page and nothing below reloads either of
  // them. This is also the last navigation in the test.
  await editor.goto(owner.url());
  await expect(editor.getByRole('heading', { name: /^Day 1/ })).toBeVisible();

  const ownerDay1 = owner.locator('ol > li.card').first();
  const editorDay1 = editor.locator('ol > li.card').first();

  // Owner adds a place; it turns up in the editor's browser on its own.
  await ownerDay1.getByRole('button', { name: 'Add place' }).click();
  await ownerDay1.locator('input[name=name]').fill('Hallgrimskirkja');
  await ownerDay1.getByRole('button', { name: 'Add place' }).click();
  await expect(editorDay1.getByText('Hallgrimskirkja')).toBeVisible();

  // And the other way, with a day note — which rides along on the itinerary, so
  // it is the same event.
  await editorDay1.getByRole('button', { name: /the note for day 1/ }).click();
  await editorDay1.locator('textarea[name=dayNote]').fill('Northern lights at eleven.');
  await editorDay1.getByRole('button', { name: 'Save note' }).click();
  await expect(ownerDay1.getByText('Northern lights at eleven.')).toBeVisible();

  // A reorder reaches the other side as the server's ordering, not a guess.
  // The add form has to be reopened: editing a day's note closes it, since only
  // one of a day's forms is open at a time.
  await editorDay1.getByRole('button', { name: 'Add place' }).click();
  await editorDay1.locator('input[name=name]').fill('Blue Lagoon');
  await editorDay1.getByRole('button', { name: 'Add place' }).click();
  await expect(ownerDay1.locator('ol > li button.font-medium')).toHaveText([
    'Hallgrimskirkja',
    'Blue Lagoon',
  ]);
  await editorDay1.getByRole('button', { name: 'Done' }).click();
  await editorDay1.getByRole('button', { name: 'Move down' }).first().click();
  await expect(ownerDay1.locator('ol > li button.font-medium')).toHaveText([
    'Blue Lagoon',
    'Hallgrimskirkja',
  ]);

  // A pushed re-read that fails must retry itself. Nobody is watching it: if the
  // one request a live event triggers is dropped, the page sits there quietly
  // wrong, and the next event might be hours away.
  //
  // Chromium's offline emulation leaves an already-open WebSocket alone and only
  // breaks HTTP, which is precisely the case being tested here — the event
  // arrives, the re-read behind it does not.
  await editorContext.setOffline(true);

  // No opener click here: the owner's add form is still open from Hallgrimskirkja,
  // so clicking "Add place" now is the *submit* — and an empty one is a 400.
  await ownerDay1.locator('input[name=name]').fill('Thingvellir');
  await ownerDay1.getByRole('button', { name: 'Add place' }).click();
  await expect(ownerDay1.getByText('Thingvellir')).toBeVisible();
  // The editor was told, and could not act on it. That is the premise.
  await expect(editorDay1.getByText('Thingvellir')).toHaveCount(0);

  await editorContext.setOffline(false);
  // No reload and no second event: a retry is what catches this page up.
  await expect(editorDay1.getByText('Thingvellir')).toBeVisible({ timeout: 20000 });
  await expect(editor.locator('[role=alert]')).toHaveCount(0);

  // Losing access is pushed too: the editor is removed and their page leaves the
  // trip on its own, rather than sitting on a stale itinerary.
  const editorRow = owner.locator('li', { hasText: 'Sync Editor' });
  await editorRow.getByRole('button', { name: /^Remove Sync Editor/ }).click();
  await expect(editor).toHaveURL(/\/trips$/);
  await expect(editor.getByText('No trips yet')).toBeVisible();

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
  await ownerContext.close();
  await editorContext.close();
});

/**
 * A dropped connection has to catch up, not wait.
 *
 * Anything that happens while the socket is down is never delivered — there is
 * no replay — so reconnecting has to re-read unconditionally. Get that wrong and
 * the page looks perfectly healthy while showing an itinerary from before the
 * outage, until the next unrelated edit happens to arrive.
 *
 * The connection is dropped with `routeWebSocket`, and the first few retries are
 * refused, so the outage is a few seconds wide rather than a race: the change
 * below is definitively made while nobody is listening.
 */
test('a dropped connection catches up when it comes back', async ({ browser }) => {
  // Legitimately slow: this test waits out a real exponential backoff — four
  // refused retries at 0.5s, 1s, 2s, 4s — before the client reconnects, which is
  // 8-13 seconds of the run doing nothing on purpose. Alone it finishes inside the
  // default 30s; behind eighteen other tests the setup pushes it past. Tripling
  // the budget is the honest fix, rather than shortening a wait the feature needs.
  test.slow();
  const stamp = Date.now();
  const viewerEmail = `e2e-gap-viewer-${stamp}@example.com`;

  const ownerContext = await browser.newContext();
  const viewerContext = await browser.newContext();
  const owner = await ownerContext.newPage();
  const viewer = await viewerContext.newPage();
  await Promise.all([stubBasemap(owner), stubBasemap(viewer)]);

  const consoleErrors: string[] = [];
  for (const page of [owner, viewer]) {
    page.on('console', (message) => {
      if (message.type() === 'error' && !message.text().includes('401')) {
        consoleErrors.push(message.text());
      }
    });
    page.on('pageerror', (error) => consoleErrors.push(error.message));
  }

  const register = async (page: typeof owner, email: string, name: string) => {
    await page.goto('/login');
    await page.getByText('Create one').click();
    await page.locator('input[name=email]').fill(email);
    await page.locator('input[name=displayName]').fill(name);
    await page.locator('input[name=password]').fill('correct-horse-battery');
    await page.locator('button[type=submit]').click();
    await expect(page).toHaveURL(/\/trips$/);
  };

  await register(owner, `e2e-gap-owner-${stamp}@example.com`, 'Gap Owner');
  await register(viewer, viewerEmail, 'Gap Viewer');

  await owner.getByRole('button', { name: 'Plan your first trip' }).click();
  await owner.locator('input[name=name]').fill('Tallinn');
  await owner.locator('input[name=startDate]').fill('2027-07-01');
  await owner.locator('input[name=endDate]').fill('2027-07-03');
  await owner.getByRole('button', { name: 'Create trip' }).click();
  await owner.getByRole('link', { name: /Tallinn/ }).click();
  await expect(owner).toHaveURL(/\/trips\/\d+$/);

  await owner.getByRole('button', { name: /People/ }).click();
  await owner.locator('input[name=memberEmail]').fill(viewerEmail);
  await owner.locator('select[name=memberRole]').selectOption('VIEWER');
  await owner.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(owner.getByText('Gap Viewer', { exact: true })).toBeVisible();

  // Routed before the page ever opens a socket. The first connection is a plain
  // pass-through; the next three are refused, which is the outage; after that it
  // is a pass-through again.
  let attempts = 0;
  let firstConnection: { close: () => Promise<void> } | null = null;
  await viewer.routeWebSocket(/\/api\/ws\/trips\//, (ws) => {
    attempts += 1;
    if (attempts === 1) {
      firstConnection = ws;
      ws.connectToServer();
    } else if (attempts <= 4) {
      void ws.close();
    } else {
      ws.connectToServer();
    }
  });

  await viewer.goto(owner.url());
  await expect(viewer.getByRole('heading', { name: /^Day 1/ })).toBeVisible();

  // Down it goes.
  await firstConnection!.close();
  await expect(viewer.getByText('Reconnecting')).toBeVisible();

  // Made while nobody is listening: no event for this one will ever arrive.
  const ownerDay1 = owner.locator('ol > li.card').first();
  await ownerDay1.getByRole('button', { name: 'Add place' }).click();
  await ownerDay1.locator('input[name=name]').fill('Toompea Castle');
  await ownerDay1.getByRole('button', { name: 'Add place' }).click();
  await expect(ownerDay1.getByText('Toompea Castle')).toBeVisible();
  await expect(viewer.getByText('Toompea Castle')).toHaveCount(0);

  // Coming back is what fetches it — no reload, and no second edit to ride on.
  await expect(viewer.getByText('Toompea Castle')).toBeVisible({ timeout: 30000 });
  await expect(viewer.getByText('Reconnecting')).toHaveCount(0);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
  await ownerContext.close();
  await viewerContext.close();
});

/**
 * The two places a blank name could reach the server.
 *
 * The add form stays open after a successful add, so the button is sitting there
 * inviting a second click — which used to post an empty name and take a 400 for
 * it. Renaming a place to nothing is the same defect by a different route.
 */
test('a place cannot be added or renamed with an empty name', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-blank-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Blank Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await expect(page).toHaveURL(/\/trips$/);
  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Bruges');
  await page.locator('input[name=startDate]').fill('2027-04-02');
  await page.locator('input[name=endDate]').fill('2027-04-03');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Bruges/ }).click();

  const day1 = page.locator('ol > li.card').first();
  await day1.getByRole('button', { name: 'Add place' }).click();
  // Nothing typed yet, so there is nothing to submit.
  await expect(day1.getByRole('button', { name: 'Add place' })).toBeDisabled();

  await day1.locator('input[name=name]').fill('Markt');
  await expect(day1.getByRole('button', { name: 'Add place' })).toBeEnabled();
  await day1.getByRole('button', { name: 'Add place' }).click();
  await expect(day1.getByText('Markt')).toBeVisible();

  // The form is still open and the box is empty again: this is the click that
  // used to post a blank name.
  await expect(day1.getByRole('button', { name: 'Add place' })).toBeDisabled();
  await day1.getByRole('button', { name: 'Done' }).click();

  // And a place cannot be renamed to nothing either. Renaming happens in the
  // detail panel now — the row's inline form is gone, because two editors for one
  // place meant two places to keep in step.
  await day1.getByRole('button', { name: 'Edit', exact: true }).click();
  const panel = page.locator('aside[role=dialog]');
  await panel.getByRole('button', { name: 'Edit', exact: true }).click();
  await panel.locator('input[name=detailName]').fill('');
  await expect(panel.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await panel.locator('input[name=detailName]').fill('Markt square');
  await panel.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(panel.getByRole('heading', { name: 'Markt square' })).toBeVisible();
  await panel.getByRole('button', { name: 'Close' }).click();
  await expect(day1.getByText('Markt square', { exact: true })).toBeVisible();

  await expect(page.locator('[role=alert]')).toHaveCount(0);
  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * Expenses, splits and balances, in two browsers.
 *
 * The arithmetic is tested thoroughly in Java; what this adds is that it survives
 * the round trip to the screen — the pennies of an uneven split have to be
 * visible and have to add up, or nobody will trust the ledger — and that a second
 * person sees a new expense without reloading.
 */
test('an expense splits unevenly, adds up, and reaches the other browser', async ({ browser }) => {
  const stamp = Date.now();
  const editorEmail = `e2e-exp-editor-${stamp}@example.com`;

  const ownerContext = await browser.newContext();
  const editorContext = await browser.newContext();
  const owner = await ownerContext.newPage();
  const editor = await editorContext.newPage();
  await Promise.all([stubBasemap(owner), stubBasemap(editor)]);

  const consoleErrors: string[] = [];
  for (const page of [owner, editor]) {
    page.on('console', (message) => {
      if (message.type() === 'error' && !message.text().includes('401')) {
        consoleErrors.push(message.text());
      }
    });
    page.on('pageerror', (error) => consoleErrors.push(error.message));
  }

  const register = async (page: typeof owner, email: string, name: string) => {
    await page.goto('/login');
    await page.getByText('Create one').click();
    await page.locator('input[name=email]').fill(email);
    await page.locator('input[name=displayName]').fill(name);
    await page.locator('input[name=password]').fill('correct-horse-battery');
    await page.locator('button[type=submit]').click();
    await expect(page).toHaveURL(/\/trips$/);
  };

  await register(owner, `e2e-exp-owner-${stamp}@example.com`, 'Expense Owner');
  await register(editor, editorEmail, 'Expense Editor');

  await owner.getByRole('button', { name: 'Plan your first trip' }).click();
  await owner.locator('input[name=name]').fill('Porto');
  await owner.locator('input[name=startDate]').fill('2027-09-10');
  await owner.locator('input[name=endDate]').fill('2027-09-12');
  // The instance default is preselected, and the field is a real choice.
  await expect(owner.locator('select[name=currency]')).toHaveValue('EUR');
  await owner.getByRole('button', { name: 'Create trip' }).click();
  await owner.getByRole('link', { name: /Porto/ }).click();

  await owner.getByRole('button', { name: /People/ }).click();
  await owner.locator('input[name=memberEmail]').fill(editorEmail);
  await owner.locator('select[name=memberRole]').selectOption('EDITOR');
  await owner.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(owner.getByText('Expense Editor', { exact: true })).toBeVisible();

  await owner.getByRole('link', { name: 'Expenses' }).click();
  await expect(owner).toHaveURL(/\/expenses$/);
  await expect(owner.getByText('No expenses yet')).toBeVisible();

  // Both parties are on the expenses page from here, and neither reloads again.
  await editor.goto(owner.url());
  await expect(editor.getByRole('heading', { name: 'Expenses' })).toBeVisible();

  // 10.00 two ways is even; 10.01 is not. Use the odd one so the penny shows.
  await owner.getByRole('button', { name: 'Add an expense' }).click();
  await owner.locator('input[name=description]').fill('Pastries');
  await owner.locator('input[name=amount]').fill('10.01');
  // Dated onto day one rather than left at today, so the itinerary's day card has
  // something to total further down. The form defaults to today because most
  // expenses are entered as they happen.
  await owner.locator('input[name=spentOn]').fill('2027-09-10');
  await owner.getByRole('button', { name: 'Save expense' }).click();

  // The split is spelled out, and the two halves differ by exactly one cent.
  const ownerRow = owner.locator('ul > li.card').filter({ hasText: 'Pastries' });
  await expect(ownerRow.getByText('€5.01')).toBeVisible();
  await expect(ownerRow.getByText('€5.00')).toBeVisible();
  await expect(owner.getByText('€10.01 in total')).toBeVisible();

  // The owner fronted it, so the editor owes half. Signed the right way round.
  await expect(owner.getByText('is owed €5.00')).toBeVisible();
  await expect(owner.getByText('owes €5.00')).toBeVisible();

  // And it is in the other browser with no reload and no second event.
  await expect(editor.getByText('Pastries', { exact: true })).toBeVisible();
  await expect(editor.getByText('owes €5.00')).toBeVisible();

  // An exact split that does not add up cannot be submitted at all — the button
  // is the guard, so the server's 400 is a backstop rather than the UX.
  await editor.getByRole('button', { name: 'Add an expense' }).click();
  await editor.locator('input[name=description]').fill('Museum');
  await editor.locator('input[name=amount]').fill('30.00');
  await editor.getByRole('button', { name: 'Exact amounts' }).click();
  await editor.getByLabel('Amount for Expense Owner').fill('20.00');
  await editor.getByLabel('Amount for Expense Editor').fill('5.00');
  await expect(editor.getByText('€5.00 still unallocated')).toBeVisible();
  await expect(editor.getByRole('button', { name: 'Save expense' })).toBeDisabled();

  await editor.getByLabel('Amount for Expense Editor').fill('10.00');
  await expect(editor.getByText('The shares add up')).toBeVisible();
  await expect(editor.getByRole('button', { name: 'Save expense' })).toBeEnabled();
  await editor.getByRole('button', { name: 'Save expense' }).click();

  // Owner: paid 10.01, owes 5.01 of the pastries and 20.00 of the museum -> -15.00.
  // Editor: paid 30.00, owes 5.00 and 10.00 -> +15.00. The odd penny of the
  // pastries is on the owner's side of both columns, so it cancels.
  await expect(editor.getByText('owes €15.00')).toBeVisible();
  await expect(owner.getByText('owes €15.00')).toBeVisible();
  await expect(owner.getByText('is owed €15.00')).toBeVisible();
  // The settle-up line names the direction, with a space before the amount.
  await expect(owner.getByText('Expense Owner pays Expense Editor €15.00')).toBeVisible();

  // A viewer's-eye check that deleting cleans up after itself.
  await owner.getByRole('button', { name: 'Remove Museum' }).click();
  await expect(owner.getByText('€10.01 in total')).toBeVisible();
  await expect(editor.getByText('Museum', { exact: true })).toHaveCount(0);

  // What the day cost, back on the itinerary. Day one has the pastries; days two
  // and three have nothing and say nothing — a card showing "€0.00" would be
  // asserting something nobody entered.
  await owner.getByRole('link', { name: 'Back to the trip' }).click();
  const days = owner.locator('ol > li.card');
  await expect(days.nth(0).getByRole('link', { name: '€10.01' })).toBeVisible();
  await expect(days.nth(1).getByText('€', { exact: false })).toHaveCount(0);
  await expect(days.nth(2).getByText('€', { exact: false })).toHaveCount(0);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
  await ownerContext.close();
  await editorContext.close();
});

/**
 * Settling up, which is what makes a balance mean anything.
 *
 * A payment is stored as an expense whose single share belongs to the recipient,
 * so the interesting assertions are that the *trip total* ignores it while the
 * *balances* respond to it, and that undoing one puts the debt back.
 */
test('recording a payment settles the balance and can be undone', async ({ browser }) => {
  const stamp = Date.now();
  const friendEmail = `e2e-pay-friend-${stamp}@example.com`;

  const ownerContext = await browser.newContext();
  const friendContext = await browser.newContext();
  const owner = await ownerContext.newPage();
  const friend = await friendContext.newPage();
  await Promise.all([stubBasemap(owner), stubBasemap(friend)]);

  const consoleErrors: string[] = [];
  for (const page of [owner, friend]) {
    page.on('console', (message) => {
      if (message.type() === 'error' && !message.text().includes('401')) {
        consoleErrors.push(message.text());
      }
    });
    page.on('pageerror', (error) => consoleErrors.push(error.message));
  }

  const register = async (page: typeof owner, email: string, name: string) => {
    await page.goto('/login');
    await page.getByText('Create one').click();
    await page.locator('input[name=email]').fill(email);
    await page.locator('input[name=displayName]').fill(name);
    await page.locator('input[name=password]').fill('correct-horse-battery');
    await page.locator('button[type=submit]').click();
    await expect(page).toHaveURL(/\/trips$/);
  };

  await register(owner, `e2e-pay-owner-${stamp}@example.com`, 'Pay Owner');
  await register(friend, friendEmail, 'Pay Friend');

  await owner.getByRole('button', { name: 'Plan your first trip' }).click();
  await owner.locator('input[name=name]').fill('Bruges');
  await owner.locator('input[name=startDate]').fill('2027-10-01');
  await owner.locator('input[name=endDate]').fill('2027-10-03');
  await owner.getByRole('button', { name: 'Create trip' }).click();
  await owner.getByRole('link', { name: /Bruges/ }).click();

  await owner.getByRole('button', { name: /People/ }).click();
  await owner.locator('input[name=memberEmail]').fill(friendEmail);
  await owner.locator('select[name=memberRole]').selectOption('EDITOR');
  await owner.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(owner.getByText('Pay Friend', { exact: true })).toBeVisible();

  await owner.getByRole('link', { name: 'Expenses' }).click();
  // Wait for the navigation before reading the URL, or the friend lands on the
  // trip page instead.
  await expect(owner).toHaveURL(/\/expenses$/);
  await friend.goto(owner.url());
  await expect(friend.getByRole('heading', { name: 'Expenses' })).toBeVisible();

  // 40.01 two ways: the friend has the higher user id, so the owner takes the
  // odd cent and the friend owes exactly 20.00.
  await owner.getByRole('button', { name: 'Add an expense' }).click();
  await owner.locator('input[name=description]').fill('Chocolate');
  await owner.locator('input[name=amount]').fill('40.01');
  await owner.getByRole('button', { name: 'Save expense' }).click();
  await expect(owner.getByText('is owed €20.00')).toBeVisible();

  // The friend settles up from their own browser, in one click from the
  // suggestion — the amount and both parties come prefilled.
  await expect(friend.getByText('Pay Friend pays Pay Owner €20.00')).toBeVisible();
  await friend.getByRole('button', { name: 'Record this payment' }).click();
  await expect(friend.locator('input[name=payAmount]')).toHaveValue('20.00');
  await friend.getByRole('button', { name: 'Record payment' }).click();

  // Both sides settle, and the four figures stay honest: the owner's *share* of
  // the chocolate is 20.01, not 40.01 — the payment is shown separately.
  await expect(friend.getByText('Everyone is square')).toBeVisible();
  await expect(owner.getByText('Everyone is square')).toBeVisible();
  await expect(owner.getByText('received €20.00')).toBeVisible();
  await expect(owner.getByText('share €20.01')).toBeVisible();

  // The payment is a row in the ledger, described by who paid whom.
  await expect(owner.getByText('Pay Friend paid Pay Owner')).toBeVisible();
  // And the trip still cost 40.01: money moving between members is not a cost.
  await expect(owner.getByText('€40.01 in total')).toBeVisible();

  // Undoing it puts the debt back, so a payment entered by mistake is not a
  // one-way door.
  await owner.getByRole('button', { name: /^Remove payment from Pay Friend/ }).click();
  await expect(owner.getByText('is owed €20.00')).toBeVisible();
  await expect(friend.getByText('Pay Friend pays Pay Owner €20.00')).toBeVisible();

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
  await ownerContext.close();
  await friendContext.close();
});

/**
 * Editing a trip, which is really about its dates.
 *
 * Days are derived from the range, so a place carries a plain date and nothing in
 * the database stops it referring to a day the trip no longer has. Both halves of
 * the rule are asserted through the screen: a move of the same length brings the
 * itinerary along, and shortening the trip over a place is refused with a message
 * that says so.
 */
test('a trip can be renamed and moved, and will not strand its places', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    // This test provokes a 409 on purpose, and the browser logs every failed
    // response as a console error. Filtered here rather than by relaxing the
    // assertion, so any *other* error still fails the test.
    const expected = message.text().includes('401') || message.text().includes('409');
    if (message.type() === 'error' && !expected) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-edit-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Edit Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await expect(page).toHaveURL(/\/trips$/);
  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Kyto');
  await page.locator('input[name=startDate]').fill('2027-07-12');
  await page.locator('input[name=endDate]').fill('2027-07-15');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Kyto/ }).click();
  await expect(page).toHaveURL(/\/trips\/\d+$/);

  // Something on the last day, so shortening the trip has something to strand.
  const day4 = page.locator('ol > li.card').nth(3);
  await day4.getByRole('button', { name: 'Add place' }).click();
  await day4.locator('input[name=name]').fill('Nishiki Market');
  await day4.getByRole('button', { name: 'Add place' }).click();
  await expect(day4.getByText('Nishiki Market')).toBeVisible();
  await day4.getByRole('button', { name: 'Done' }).click();

  // The typo in the name, which is the whole reason this endpoint exists.
  await page.getByRole('button', { name: 'Edit trip' }).click();
  await page.locator('input[name=tripName]').fill('Kyoto');
  await page.locator('input[name=tripDestination]').fill('Kansai');
  await page.getByRole('button', { name: 'Save trip' }).click();
  await expect(page.getByRole('heading', { name: 'Kyoto' })).toBeVisible();
  await expect(page.getByText('Kansai')).toBeVisible();

  // Shortening it over that place is refused, and the message names the place and
  // its date rather than just counting it — the point of refusing instead of
  // deleting is that somebody can go and move it.
  await page.getByRole('button', { name: 'Edit trip' }).click();
  await page.locator('input[name=tripEnd]').fill('2027-07-13');
  await page.getByRole('button', { name: 'Save trip' }).click();
  await expect(page.locator('[role=alert]')).toContainText('1 place');
  await expect(page.locator('[role=alert]')).toContainText('Nishiki Market on 2027-07-15');
  await expect(page.locator('[role=alert]')).toContainText('Move or delete');
  // The end date alone moved, so shifting could not rescue this and is not offered.
  await expect(page.getByRole('button', { name: 'Move the itinerary with the trip' }))
    .toHaveCount(0);
  // And the trip is untouched: still four days, still holding the place.
  await page.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.getByRole('heading', { name: /^Day 4/ })).toBeVisible();

  // Moving it a week later keeps the same length, so everything comes along.
  await page.getByRole('button', { name: 'Edit trip' }).click();
  await page.locator('input[name=tripStart]').fill('2027-07-19');
  await page.locator('input[name=tripEnd]').fill('2027-07-22');
  await page.getByRole('button', { name: 'Save trip' }).click();

  await expect(page.getByText('Mon, Jul 19')).toBeVisible();
  // The place is still on the last day of the trip, not left behind on the 15th.
  await expect(page.locator('ol > li.card').nth(3).getByText('Nishiki Market')).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Day 4/ })).toBeVisible();

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * Moving a trip *and* changing its length at once.
 *
 * The ambiguous case, and the one that sent a real user to the database to find
 * out which places were in the way: "two days added at the front" wants the plan
 * to keep its dates, "moved a month later and made longer" wants it to come
 * along. The server refuses rather than guessing, and the page asks.
 */
test('moving and lengthening a trip at once offers to bring the itinerary', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    // The refusal is provoked deliberately; see the trip-editing test above.
    const expected = message.text().includes('401') || message.text().includes('409');
    if (message.type() === 'error' && !expected) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-shift-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Shift Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await expect(page).toHaveURL(/\/trips$/);
  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Kyoto in Spring');
  await page.locator('input[name=startDate]').fill('2026-08-28');
  await page.locator('input[name=endDate]').fill('2026-09-05');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Kyoto in Spring/ }).click();
  await expect(page).toHaveURL(/\/trips\/\d+$/);

  const day1 = page.locator('ol > li.card').first();
  await day1.getByRole('button', { name: 'Add place' }).click();
  await day1.locator('input[name=name]').fill('Fushimi Inari Shrine');
  await day1.getByRole('button', { name: 'Add place' }).click();
  await expect(day1.getByText('Fushimi Inari Shrine')).toBeVisible();
  await day1.locator('input[name=name]').fill('Shin-Osaka');
  await day1.getByRole('button', { name: 'Add place' }).click();
  await expect(day1.getByText('Shin-Osaka')).toBeVisible();
  await day1.getByRole('button', { name: 'Done' }).click();

  // Nine days becoming thirteen, a month later: refused, and both places named.
  await page.getByRole('button', { name: 'Edit trip' }).click();
  await page.locator('input[name=tripStart]').fill('2026-10-02');
  await page.locator('input[name=tripEnd]').fill('2026-10-14');
  await page.getByRole('button', { name: 'Save trip' }).click();
  await expect(page.locator('[role=alert]')).toContainText('2 places');
  await expect(page.locator('[role=alert]')).toContainText('Fushimi Inari Shrine on 2026-08-28');

  // One click instead of two saves: this is the whole point of the offer.
  await page.getByRole('button', { name: 'Move the itinerary with the trip' }).click();

  await expect(page.getByText('13 days')).toBeVisible();
  await expect(page.getByText('Fri, Oct 2')).toBeVisible();
  const newDay1 = page.locator('ol > li.card').first();
  await expect(newDay1.getByText('Fushimi Inari Shrine')).toBeVisible();
  await expect(newDay1.getByText('Shin-Osaka')).toBeVisible();
  await expect(page.locator('[role=alert]')).toHaveCount(0);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * The packing list, in two browsers.
 *
 * The section an item sits in *is* who is bringing it, so the assertions are
 * about things landing in the right one — and about a tick made in one browser
 * moving the other's count without a reload.
 */
test('packing items land in the right section and tick across browsers', async ({ browser }) => {
  const stamp = Date.now();
  const friendEmail = `e2e-pack-friend-${stamp}@example.com`;

  const ownerContext = await browser.newContext();
  const friendContext = await browser.newContext();
  const owner = await ownerContext.newPage();
  const friend = await friendContext.newPage();
  await Promise.all([stubBasemap(owner), stubBasemap(friend)]);

  const consoleErrors: string[] = [];
  for (const page of [owner, friend]) {
    page.on('console', (message) => {
      if (message.type() === 'error' && !message.text().includes('401')) {
        consoleErrors.push(message.text());
      }
    });
    page.on('pageerror', (error) => consoleErrors.push(error.message));
  }

  const register = async (page: typeof owner, email: string, name: string) => {
    await page.goto('/login');
    await page.getByText('Create one').click();
    await page.locator('input[name=email]').fill(email);
    await page.locator('input[name=displayName]').fill(name);
    await page.locator('input[name=password]').fill('correct-horse-battery');
    await page.locator('button[type=submit]').click();
    await expect(page).toHaveURL(/\/trips$/);
  };

  await register(owner, `e2e-pack-owner-${stamp}@example.com`, 'Pack Owner');
  await register(friend, friendEmail, 'Pack Friend');

  await owner.getByRole('button', { name: 'Plan your first trip' }).click();
  await owner.locator('input[name=name]').fill('Norway');
  await owner.locator('input[name=startDate]').fill('2027-06-01');
  await owner.locator('input[name=endDate]').fill('2027-06-05');
  await owner.getByRole('button', { name: 'Create trip' }).click();
  await owner.getByRole('link', { name: /Norway/ }).click();

  await owner.getByRole('button', { name: /People/ }).click();
  await owner.locator('input[name=memberEmail]').fill(friendEmail);
  await owner.locator('select[name=memberRole]').selectOption('EDITOR');
  await owner.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(owner.getByText('Pack Friend', { exact: true })).toBeVisible();

  await owner.getByRole('link', { name: 'Packing' }).click();
  await expect(owner).toHaveURL(/\/packing$/);
  // Everybody gets a section straight away, including the one who has added
  // nothing: an absent section reads as missing data.
  await expect(owner.getByRole('heading', { name: 'Everyone' })).toBeVisible();
  await expect(owner.getByRole('heading', { name: /Pack Friend/ })).toBeVisible();

  await friend.goto(owner.url());
  await expect(friend.getByRole('heading', { name: 'Packing' })).toBeVisible();

  // Typing into a section is what assigns the item — no name to pick. Each add is
  // waited for before the next: the submit button is disabled while a save is in
  // flight, so a second Enter arriving too quickly would land on nothing.
  const addItem = async (section: string, description: string) => {
    await owner.getByLabel(`Add an item for ${section}`).fill(description);
    await owner.getByLabel(`Add an item for ${section}`).press('Enter');
    await expect(owner.getByText(description, { exact: true })).toBeVisible();
  };
  await addItem('Everyone', 'Tent');
  await addItem('Pack Friend', 'Chargers');

  // Scoped by heading, not by text: every row's assignee select contains an
  // "Everyone" option, so `hasText` would match every section at once.
  const sectionOf = (page: typeof owner, heading: string | RegExp) =>
    page.locator('li.card').filter({ has: page.getByRole('heading', { name: heading }) });
  const friendSection = sectionOf(friend, /Pack Friend/);
  const sharedSection = sectionOf(friend, 'Everyone');
  // Both arrive in the other browser on their own, in the right sections.
  // exact: true throughout — every row carries the item's name in the screen-reader
  // labels of its controls, so a loose match finds four elements.
  await expect(sharedSection.getByText('Tent', { exact: true })).toBeVisible();
  await expect(friendSection.getByText('Chargers', { exact: true })).toBeVisible();

  // The friend packs the shared tent; the owner's count moves without a reload,
  // and it says who did it — the useful half of "packed" on a shared item.
  await sharedSection.getByRole('checkbox').first().check();
  await expect(friend.getByText('1 of 2 packed')).toBeVisible();
  await expect(owner.getByText('· Pack Friend')).toBeVisible();
  await expect(owner.getByText('1 of 2 packed')).toBeVisible();

  // A packed item stays where it was rather than jumping to the bottom.
  await addItem('Everyone', 'First-aid kit');
  await expect(sectionOf(owner, 'Everyone').locator('li span.truncate'))
    .toHaveText(['Tent', 'First-aid kit']);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
  await ownerContext.close();
  await friendContext.close();
});

/**
 * Bookings, and the only interesting thing about them: the clock.
 *
 * The browser is pinned to Europe/London so "the viewer's own zone" is a known
 * quantity — which is what decides whether a time gets a zone label at all. A
 * booking in Tokyo must say so; one at home must not, or every row on a domestic
 * trip carries noise.
 */
test('bookings show each time in its own zone, ordered by when they really happen', async ({
  browser,
}) => {
  const context = await browser.newContext({ timezoneId: 'Europe/London' });
  const page = await context.newPage();
  await stubBasemap(page);

  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-book-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Booking Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await expect(page).toHaveURL(/\/trips$/);
  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Japan');
  await page.locator('input[name=startDate]').fill('2027-07-12');
  await page.locator('input[name=endDate]').fill('2027-07-20');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Japan/ }).click();
  await page.getByRole('link', { name: 'Bookings' }).click();
  await expect(page).toHaveURL(/\/reservations$/);
  await expect(page.getByText('No bookings yet')).toBeVisible();

  // Added second, but it happens first: 23:00 in Tokyo on the 12th is 14:00 UTC,
  // while 09:15 in London on the 12th is 08:15 UTC. Only the instants say so.
  const addBooking = async (fields: {
    kind: string;
    title: string;
    startDate: string;
    startTime: string;
    startZone: string;
    phone?: string;
    endDate?: string;
    endTime?: string;
    endZone?: string;
  }) => {
    await page.getByRole('button', { name: 'Add a booking' }).click();
    await page.locator('select[name=kind]').selectOption(fields.kind);
    await page.locator('input[name=title]').fill(fields.title);
    if (fields.phone) {
      await page.locator('input[name=phone]').fill(fields.phone);
    }
    await page.locator('input[name=startDate]').fill(fields.startDate);
    await page.locator('input[name=startTime]').fill(fields.startTime);
    await page.locator('select[name=startZone]').selectOption(fields.startZone);
    if (fields.endDate) {
      await page.locator('input[name=endDate]').fill(fields.endDate);
      await page.locator('input[name=endTime]').fill(fields.endTime!);
      await page.locator('select[name=endZone]').selectOption(fields.endZone ?? fields.startZone);
    }
    await page.getByRole('button', { name: 'Save booking' }).click();
    await expect(page.getByText(fields.title, { exact: true })).toBeVisible();
  };

  await addBooking({
    kind: 'TRAIN', title: 'Late train in Tokyo',
    startDate: '2027-07-12', startTime: '23:00', startZone: 'Asia/Tokyo',
    phone: '+81 3-4333-1234 (front desk)',
  });
  await addBooking({
    kind: 'FLIGHT', title: 'BA512 to Osaka',
    startDate: '2027-07-12', startTime: '09:15', startZone: 'Europe/London',
    endDate: '2027-07-13', endTime: '07:40', endZone: 'Asia/Tokyo',
  });

  const rows = page.locator('ul > li.card');
  // Ordered by instant, so the London flight comes first despite being added second.
  await expect(rows.locator('p.font-medium')).toHaveText(['BA512 to Osaka', 'Late train in Tokyo']);

  // Departure is in the viewer's own zone, so it carries no label...
  const flight = rows.filter({ hasText: 'BA512' });
  await expect(flight).toContainText('9:15');
  // ...but the arrival is in another zone and says so.
  await expect(flight).toContainText('7:40');
  await expect(flight).toContainText('GMT+9');

  // A time somewhere else is labelled even when it is the only time on the row.
  await expect(rows.filter({ hasText: 'Late train' })).toContainText('GMT+9');

  // The number is shown exactly as it was typed, remark and all — but the link
  // behind it is what a dialler will take, which is not the same string. The
  // bracketed note is dropped rather than turned into digits, and nothing is
  // added: a bare local number stays local, because guessing a country code is
  // how you ring a stranger at 1am.
  const phone = rows.filter({ hasText: 'Late train' }).getByRole('link');
  await expect(phone).toHaveText('+81 3-4333-1234 (front desk)');
  await expect(phone).toHaveAttribute('href', 'tel:+81343331234');

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
  await context.close();
});

/**
 * Offline reads — the point of milestone 5.
 *
 * Before this, no signal meant no app at all: the browser could not fetch
 * index.html and showed its own offline page, cache or no cache. The assertions
 * below are what "offline" has to mean for a travel planner — the booking
 * reference and the itinerary readable on a plane, honestly labelled as a saved
 * copy, and writes refused rather than quietly queued.
 *
 * The service worker has to be installed before the network goes, so the test
 * waits for it: without a worker, none of this can work and the first assertion
 * would fail for the wrong reason.
 */
test('with no connection, a trip you have opened is still readable', async ({ browser }) => {
  // Workers are blocked for the suite (see playwright.config.ts); here the worker
  // is the thing under test, so this context opts back in.
  const context = await browser.newContext({ serviceWorkers: 'allow' });
  const page = await context.newPage();
  await stubBasemap(page);

  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-offline-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Offline Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await expect(page).toHaveURL(/\/trips$/);
  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Iceland');
  await page.locator('input[name=startDate]').fill('2027-09-01');
  await page.locator('input[name=endDate]').fill('2027-09-04');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Iceland/ }).click();
  await expect(page).toHaveURL(/\/trips\/\d+$/);
  const tripUrl = page.url();

  const day1 = page.locator('ol > li.card').first();
  await day1.getByRole('button', { name: 'Add place' }).click();
  await day1.locator('input[name=name]').fill('Blue Lagoon');
  await day1.getByRole('button', { name: 'Add place' }).click();
  await expect(day1.getByText('Blue Lagoon', { exact: true })).toBeVisible();
  await day1.getByRole('button', { name: 'Done' }).click();

  // A booking, because a confirmation number is the thing you most want to read
  // at a desk with no signal.
  await page.getByRole('link', { name: 'Bookings' }).click();
  await expect(page).toHaveURL(/\/reservations$/);
  await page.getByRole('button', { name: 'Add a booking' }).click();
  await page.locator('select[name=kind]').selectOption('FLIGHT');
  await page.locator('input[name=title]').fill('FI451 to Keflavik');
  await page.locator('input[name=confirmation]').fill('REF7788');
  await page.locator('input[name=startDate]').fill('2027-09-01');
  await page.locator('input[name=startTime]').fill('07:30');
  await page.getByRole('button', { name: 'Save booking' }).click();
  await expect(page.getByText('FI451 to Keflavik', { exact: true })).toBeVisible();

  // Nothing below works without the worker, so wait for it rather than racing it.
  await page.waitForFunction(() => navigator.serviceWorker?.controller != null, null,
    { timeout: 15000 });

  await context.setOffline(true);
  await page.reload();

  // Still on the bookings page, not bounced to /login — the identity survives an
  // unreachable server, which is the hinge the whole feature turns on.
  await expect(page).toHaveURL(/\/reservations$/);
  await expect(page.getByText(/you're offline/i)).toBeVisible();
  await expect(page.getByText('FI451 to Keflavik', { exact: true })).toBeVisible();
  await expect(page.getByText('REF7788')).toBeVisible();
  // Labelled as a saved copy. Matched case-insensitively: the chip is uppercased
  // by CSS, so its rendered text is not the text in the template.
  await expect(page.locator('span[title*="saved on this device"]')).toContainText(/saved copy/i);

  // The itinerary too, on a different route, reached with no network at all.
  await page.goto(tripUrl);
  await expect(page.getByText('Blue Lagoon', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: /^Day 1/ })).toBeVisible();

  // Writes are refused, not queued: nothing is pretended and nothing is lost.
  const offlineDay1 = page.locator('ol > li.card').first();
  await offlineDay1.getByRole('button', { name: 'Add place' }).click();
  await offlineDay1.locator('input[name=name]').fill('Should not save');
  await offlineDay1.getByRole('button', { name: 'Add place' }).click();
  await expect(page.locator('[role=alert]')).toContainText(/offline/i);
  await expect(offlineDay1.getByText('Should not save', { exact: true })).toHaveCount(0);

  // Back on the network: fresh data, and the labels go away.
  await context.setOffline(false);
  await page.reload();
  await expect(page.getByText('Blue Lagoon', { exact: true })).toBeVisible();
  await expect(page.getByText(/you're offline/i)).toHaveCount(0);
  await expect(page.locator('span[title*="saved on this device"]')).toHaveCount(0);

  expect(pageErrors, 'unexpected page errors').toEqual([]);
  await context.close();
});

/**
 * The place detail panel, and the attribution that has to travel with it.
 *
 * The upstreams are stubbed in the browser: what matters is not what Wikipedia
 * says but that a description arrives with its source and licence, that the hours
 * are shown as OpenStreetMap's own string with a provenance line rather than a
 * confident "open now", and that **your prose and the fetched text are separate
 * blocks** — a single merged field could not say which half the credit belongs to.
 *
 * This replaced a test about the map popup. There is no popup any more — the
 * label is a hover tooltip, and the geometry assertions the rich popup needed
 * went with the machinery.
 */
test('a place panel shows your notes and what is known, each credited', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.route('**/api/geo/search**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{
        ref: 'way/34633854', name: 'Fushimi Inari-taisha',
        address: 'Fushimi Ward, Kyoto, Japan',
        latitude: 34.9671, longitude: 135.7727, category: 'attraction',
      }]),
    }));
  await page.route('**/enrichment', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        available: true,
        title: 'Fushimi Inari-taisha',
        summary: 'A Shinto shrine at the base of Mount Inari.',
        summaryUrl: 'https://en.wikipedia.org/wiki/Fushimi_Inari-taisha',
        summaryLicence: 'CC BY-SA 4.0',
        openingHours: 'Mo-Su 00:00-24:00',
        website: 'https://inari.jp',
        phone: '+81 75-641-7331',
        fetchedAt: '2026-08-01T10:00:00Z',
        photos: [],
      }),
    }));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-enrich-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Enrich Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await expect(page).toHaveURL(/\/trips$/);
  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Kyoto');
  await page.locator('input[name=startDate]').fill('2027-03-28');
  await page.locator('input[name=endDate]').fill('2027-03-30');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Kyoto/ }).click();
  await expect(page).toHaveURL(/\/trips\/\d+$/);

  const day1 = page.locator('ol > li.card').first();
  await day1.getByRole('button', { name: 'Add place' }).click();
  await day1.locator('input[name=name]').fill('Fushimi');
  await page.getByText('Fushimi Inari-taisha').first().click();
  await day1.locator('textarea[name=notes]').fill('Come before eight.');
  await day1.getByRole('button', { name: 'Add place' }).click();
  await expect(day1.getByText('Fushimi Inari-taisha', { exact: true })).toBeVisible();
  await day1.getByRole('button', { name: 'Done' }).click();

  // A pin is one way in; the row's name is the other, and both land on the panel.
  await page.locator('.leaflet-marker-icon').first().click();
  const panel = page.locator('aside[role=dialog]');
  await expect(panel).toBeVisible();
  // And nothing is drawn under the panel. A popup used to open on this same
  // click, landing squarely behind the drawer where it could never be read —
  // invisible to a passing suite, because occlusion is not a visibility failure.
  await expect(page.locator('.leaflet-popup')).toHaveCount(0);

  // Your prose and the fetched text are both there, and separately.
  await expect(panel).toContainText('Come before eight.');
  await expect(panel).toContainText('From Wikipedia');
  await expect(panel).toContainText('A Shinto shrine at the base of Mount Inari.');
  // The description never appears without its source and terms — CC BY-SA
  // obliges both, and this is the assertion that keeps that true.
  await expect(panel.getByRole('link', { name: 'Wikipedia' }))
    .toHaveAttribute('href', /en\.wikipedia\.org/);
  await expect(panel).toContainText('CC BY-SA 4.0');

  // Hours as OpenStreetMap wrote them, with where they came from and a warning —
  // never computed into "open now", which this data cannot support.
  await expect(panel).toContainText('Mo-Su 00:00-24:00');
  await expect(panel).toContainText('OpenStreetMap');
  await expect(panel).toContainText('may be out of date');

  // Directions offer a choice of routing service rather than picking one, and
  // the order is the point: Google Maps is what most people will want, OSM is
  // where everything else on this page came from. Both carry the coordinates.
  const directions = panel.getByRole('menu', { name: 'Open directions in' });
  await expect(directions).toHaveCount(0);
  await panel.getByRole('button', { name: 'Directions' }).click();
  await expect(directions.getByRole('menuitem')).toHaveText(['Google Maps', 'OpenStreetMap']);
  await expect(directions.getByRole('menuitem', { name: 'Google Maps' }))
    .toHaveAttribute('href', /google\.com\/maps\/dir\/.*destination=34\.9671/);
  await expect(directions.getByRole('menuitem', { name: 'OpenStreetMap' }))
    .toHaveAttribute('href', /openstreetmap\.org\/directions\?to=34\.9671/);

  // Escape closes it without closing the panel behind it.
  await page.keyboard.press('Escape');
  await expect(directions).toHaveCount(0);
  await expect(panel).toBeVisible();

  await panel.getByRole('button', { name: 'Close' }).click();
  await expect(panel).toHaveCount(0);
  // And in from the row's name.
  await day1.getByRole('button', { name: 'Fushimi Inari-taisha', exact: true }).click();
  const reopened = page.locator('aside[role=dialog]');
  await expect(reopened).toBeVisible();

  // Removing is the only irreversible thing on the panel, so it asks — and says
  // what goes with the place, because the notes are the part nobody expects to
  // lose. Backing out leaves everything alone.
  await reopened.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(reopened.getByRole('alert')).toContainText('Fushimi Inari-taisha');
  await expect(reopened.getByRole('alert')).toContainText('note goes with it');
  await reopened.getByRole('button', { name: 'Keep it', exact: true }).click();
  await expect(reopened.getByRole('alert')).toHaveCount(0);
  await expect(day1.getByRole('button', { name: 'Fushimi Inari-taisha', exact: true }))
    .toBeVisible();

  // Meaning it removes the place and closes the panel, which is now about
  // something that no longer exists.
  await reopened.getByRole('button', { name: 'Remove', exact: true }).click();
  await reopened.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(page.locator('aside[role=dialog]')).toHaveCount(0);
  await expect(day1.getByRole('button', { name: 'Fushimi Inari-taisha', exact: true }))
    .toHaveCount(0);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * The forecast on a day card.
 *
 * The endpoint is stubbed rather than left to reach Open-Meteo. Two reasons and
 * both matter: this suite stays off other people's services and rate budgets, and
 * a real forecast is a moving target — the interesting assertions here are about a
 * day that has *no* forecast, which a live answer cannot be made to produce.
 */
test('a day shows its forecast, and a day past the horizon shows nothing', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  // A trip starting tomorrow, so the first days are inside any horizon.
  const day = (offset: number) =>
    new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

  let weatherCalls = 0;
  await page.route('**/api/trips/*/weather', async (route) => {
    weatherCalls++;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        available: true,
        attribution: 'Weather data by Open-Meteo.com (CC BY 4.0)',
        attributionUrl: 'https://open-meteo.com/',
        // Day one and day two only. Day three is the one being tested.
        days: [
          { date: day(1), tempMaxC: 19.4, tempMinC: 7.2, weatherCode: 3, summary: 'Overcast' },
          { date: day(2), tempMaxC: 15.1, tempMinC: 6.8, weatherCode: 61, summary: 'Rain' },
        ],
      }),
    });
  });

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-weather-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Weather Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Forecast');
  await page.locator('input[name=startDate]').fill(day(1));
  await page.locator('input[name=endDate]').fill(day(3));
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Forecast/ }).click();

  const days = page.locator('ol > li.card');
  // Rounded for display — 19.4 and 7.2 become 19 and 7.
  await expect(days.nth(0).getByText('19° / 7°')).toBeVisible();
  await expect(days.nth(1).getByText('15° / 7°')).toBeVisible();

  // The day the stub said nothing about. Nothing on the card, not a dash and not
  // a zero: a placeholder would imply the number is on its way.
  await expect(days.nth(2).getByText(/°/)).toHaveCount(0);

  // The licence obliges the credit, and it is only there because there is a
  // forecast on screen to credit.
  await expect(page.getByRole('link', { name: /Open-Meteo/ })).toBeVisible();

  // Adding a place changes no day's location — the first day had none, so it now
  // has one, which is exactly the case that should ask again.
  const before = weatherCalls;
  await days.nth(0).getByRole('button', { name: 'Add place' }).click();
  await days.nth(0).locator('input[name=name]').fill('Somewhere with no coordinates');
  await days.nth(0).getByRole('button', { name: 'Add place', exact: true }).last().click();
  await expect(days.nth(0).getByText('Somewhere with no coordinates')).toBeVisible();
  // A place with no coordinates cannot move a forecast, so nothing is re-asked.
  expect(weatherCalls, 'a place with no location is not a reason to refetch').toBe(before);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * An invitation link, and the journey it exists for.
 *
 * The point of a link — as opposed to adding somebody by email — is that the
 * recipient has **no account yet**, so the guest here registers *from the link*
 * rather than beforehand. That path only works because `authGuard` carries a
 * `returnUrl`; without it the new account lands on the trip list and the
 * invitation is silently lost, which looks like nothing at all going wrong.
 *
 * The second half is the part worth having: a spent link stops working. It is
 * asserted from a third account, because "already used" and "you are already on
 * this trip" are different answers and only a stranger sees the first.
 */
test('an invitation link admits one new account and is then spent', async ({ browser }) => {
  const stamp = Date.now();

  const ownerContext = await browser.newContext();
  const guestContext = await browser.newContext();
  const strangerContext = await browser.newContext();
  const owner = await ownerContext.newPage();
  const guest = await guestContext.newPage();
  await Promise.all([stubBasemap(owner), stubBasemap(guest)]);
  const stranger = await strangerContext.newPage();
  await stubBasemap(stranger);

  const consoleErrors: string[] = [];
  for (const page of [owner, guest, stranger]) {
    page.on('console', (message) => {
      if (message.type() === 'error' && !message.text().includes('401')) {
        consoleErrors.push(message.text());
      }
    });
    page.on('pageerror', (error) => consoleErrors.push(error.message));
  }

  const register = async (page: typeof owner, email: string, name: string) => {
    await page.getByText('Create one').click();
    await page.locator('input[name=email]').fill(email);
    await page.locator('input[name=displayName]').fill(name);
    await page.locator('input[name=password]').fill('correct-horse-battery');
    await page.locator('button[type=submit]').click();
  };

  await owner.goto('/login');
  await register(owner, `e2e-inviter-${stamp}@example.com`, 'Link Owner');
  await expect(owner).toHaveURL(/\/trips$/);

  await owner.getByRole('button', { name: 'Plan your first trip' }).click();
  await owner.locator('input[name=name]').fill('Lisbon');
  await owner.locator('input[name=startDate]').fill('2027-04-01');
  await owner.locator('input[name=endDate]').fill('2027-04-04');
  await owner.getByRole('button', { name: 'Create trip' }).click();
  await owner.getByRole('link', { name: /Lisbon/ }).click();

  await owner.getByRole('button', { name: /People/ }).click();
  await owner.getByRole('button', { name: 'Create link', exact: true }).click();

  // Shown once, and said so — the server keeps only a hash.
  await expect(owner.getByText('Copy this now — it is not shown again.')).toBeVisible();
  const link = await owner.locator('.font-mono').first().innerText();
  expect(link).toContain('/invite/');
  await expect(owner.getByText('Waiting', { exact: true })).toBeVisible();

  // The guest has no account at all. Opening the link bounces them to the login
  // page and must bring them back afterwards.
  await guest.goto(link);
  await expect(guest).toHaveURL(/\/login\?returnUrl=/);
  await register(guest, `e2e-invited-${stamp}@example.com`, 'Invited Guest');

  await expect(guest.getByRole('heading', { name: 'Lisbon' })).toBeVisible();
  await expect(guest.getByText('by Link Owner')).toBeVisible();
  await guest.getByRole('button', { name: 'Join this trip' }).click();

  // Straight onto the trip, as an editor.
  await expect(guest).toHaveURL(/\/trips\/\d+$/);
  await expect(guest.getByRole('heading', { name: 'Lisbon' })).toBeVisible();
  await expect(guest.getByRole('button', { name: 'Add place' }).first()).toBeVisible();

  // The owner's list says what became of the link, without reloading.
  await expect(owner.getByText('Used', { exact: true })).toBeVisible();
  await expect(owner.getByText('used by Invited Guest')).toBeVisible();

  // Spent: a third person holding the same link is turned away.
  await stranger.goto(link);
  await expect(stranger).toHaveURL(/\/login\?returnUrl=/);
  await register(stranger, `e2e-stranger-${stamp}@example.com`, 'Late Stranger');
  await expect(stranger.getByText('This invitation has already been used.')).toBeVisible();
  await expect(stranger.getByRole('button', { name: 'Join this trip' })).toHaveCount(0);

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});

/**
 * The itinerary as a printable document.
 *
 * Worth testing through `emulateMedia({ media: 'print' })` rather than just
 * asserting the page renders: the whole feature *is* the print stylesheet, and
 * the two things that would ruin a printout — the app shell coming along, and
 * the toolbar printing itself — are invisible on screen by definition. A test
 * that only checked the content would pass on a page that prints a navigation
 * header across the top of every copy.
 *
 * The other half is that bookings are folded into the day they happen on, which
 * is the difference between this and the two pages it is assembled from.
 */
test('the itinerary prints as a document, with its bookings on the right days', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('401')) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));

  await page.goto('/login');
  await page.getByText('Create one').click();
  await page.locator('input[name=email]').fill(`e2e-print-${Date.now()}@example.com`);
  await page.locator('input[name=displayName]').fill('Print Tester');
  await page.locator('input[name=password]').fill('correct-horse-battery');
  await page.locator('button[type=submit]').click();

  await page.getByRole('button', { name: 'Plan your first trip' }).click();
  await page.locator('input[name=name]').fill('Tokyo');
  await page.locator('input[name=startDate]').fill('2027-05-10');
  await page.locator('input[name=endDate]').fill('2027-05-12');
  await page.getByRole('button', { name: 'Create trip' }).click();
  await page.getByRole('link', { name: /Tokyo/ }).click();

  const day1 = page.locator('ol > li.card').first();
  await day1.getByRole('button', { name: 'Add place' }).click();
  await day1.locator('input[name=name]').fill('Senso-ji');
  await day1.locator('textarea[name=notes]').fill('Go early, before the crowds.');
  await day1.getByRole('button', { name: 'Add place', exact: true }).last().click();
  await day1.getByRole('button', { name: 'Done' }).click();

  // A booking on the trip's second day, with a reference somebody has to read
  // off the paper at a desk.
  const tripUrl = page.url();
  await page.getByRole('link', { name: 'Bookings' }).click();
  await page.getByRole('button', { name: 'Add a booking' }).click();
  await page.locator('select[name=kind]').selectOption('HOTEL');
  await page.locator('input[name=title]').fill('Hotel Okura');
  await page.locator('input[name=startDate]').fill('2027-05-11');
  await page.locator('input[name=startTime]').fill('15:00');
  await page.locator('select[name=startZone]').selectOption('Asia/Tokyo');
  await page.locator('input[name=confirmation]').fill('XK29PQ');
  await page.locator('input[name=phone]').fill('+81 3-3582-0111');
  await page.getByRole('button', { name: 'Save booking' }).click();
  await expect(page.getByText('Hotel Okura', { exact: true })).toBeVisible();

  await page.goto(`${tripUrl}/print`);
  await expect(page.getByRole('heading', { name: 'Tokyo' })).toBeVisible();

  // The place, its note, and the booking folded onto its own day rather than
  // sitting in a separate list.
  await expect(page.getByText('Senso-ji')).toBeVisible();
  await expect(page.getByText('Go early, before the crowds.')).toBeVisible();
  const daySections = page.locator('section.print-block');
  await expect(daySections.filter({ hasText: 'Hotel Okura' })).toContainText('Day 2');
  // The reference is the reason to carry paper at all, and the number beside it
  // is the other: on paper there is nothing to tap, so it has to be readable.
  await expect(page.getByText('XK29PQ')).toBeVisible();
  await expect(page.getByText('+81 3-3582-0111')).toBeVisible();
  // Every time on paper carries its zone, unlike on screen where it is shown
  // only when it differs from the reader's. Asserted as "a zone label is there"
  // rather than as an exact string: the clock is rendered by Intl in the
  // browser's own locale, so it is "15:00" in some and "3:00 PM" in others, and
  // pinning the format would make this a test of the test runner's locale.
  await expect(daySections.filter({ hasText: 'Hotel Okura' })).toContainText(/GMT\+9|JST/);

  // Now as the printer sees it. The document survives; the furniture does not.
  await page.emulateMedia({ media: 'print' });
  await expect(page.getByText('Senso-ji')).toBeVisible();
  await expect(page.getByText('XK29PQ')).toBeVisible();
  await expect(page.getByText('+81 3-3582-0111')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Print or save as PDF' })).toBeHidden();
  await expect(page.getByRole('link', { name: 'Back to the trip' })).toBeHidden();
  // The app shell's header would otherwise land across the top of every copy.
  // Matched by the marker class rather than `app-shell header`: the document has
  // a header of its own for the trip title, and that one is supposed to print.
  await expect(page.locator('header.print-hide')).toBeHidden();
  await expect(page.getByRole('heading', { name: 'Tokyo' })).toBeVisible();
  await page.emulateMedia({ media: 'screen' });

  expect(consoleErrors, 'unexpected console errors').toEqual([]);
});
