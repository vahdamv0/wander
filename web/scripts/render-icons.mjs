/**
 * Rasterises the app icons from `public/icon.svg`. Run with `npm run icons`
 * when the mark changes; the output is committed, so the Docker build and a
 * fresh clone need no browser download.
 *
 * Playwright's Chromium rather than `sharp` or `resvg`: it is already a
 * devDependency, and it is the engine that will draw the icon in the browser.
 */
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
// `@playwright/test`, not `playwright`: the former is the declared
// devDependency, the latter a transitive one hoisting could move.
import { chromium } from '@playwright/test';

const webDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const publicDir = join(webDir, 'public');

/** The tile's teal, repeated here so a full-bleed variant matches it. */
const TILE = '#0f766e';

const source = readFileSync(join(publicDir, 'icon.svg'), 'utf8');
const dataUri = `data:image/svg+xml;base64,${Buffer.from(source).toString('base64')}`;

/**
 * `scale` is the fraction of the square the source occupies; `background` is
 * what fills the rest, or null to leave it transparent.
 *
 * @type {{ file: string, size: number, scale: number, background: string | null }[]}
 */
const variants = [
  { file: 'icon-192.png', size: 192, scale: 1, background: null },
  { file: 'icon-512.png', size: 512, scale: 1, background: null },
  // 0.8 is the maskable safe zone: a launcher may crop to a circle of that
  // diameter. iOS crops far less, so apple-touch-icon keeps more room — and it
  // is opaque, because a transparent PNG there is composited onto black.
  { file: 'icon-maskable-512.png', size: 512, scale: 0.8, background: TILE },
  { file: 'apple-touch-icon.png', size: 180, scale: 0.9, background: TILE },
];

const page = async (size, scale, background) => `<!doctype html>
<meta charset="utf-8">
<style>
  html, body { margin: 0; padding: 0; }
  body {
    width: ${size}px;
    height: ${size}px;
    ${background ? `background: ${background};` : ''}
    display: grid;
    place-items: center;
  }
  img { width: ${Math.round(size * scale)}px; height: ${Math.round(size * scale)}px; }
</style>
<img src="${dataUri}" alt="">`;

const browser = await chromium.launch();
try {
  for (const { file, size, scale, background } of variants) {
    const context = await browser.newContext({
      viewport: { width: size, height: size },
      deviceScaleFactor: 1,
    });
    const tab = await context.newPage();
    await tab.setContent(await page(size, scale, background), { waitUntil: 'load' });
    const png = await tab.screenshot({ omitBackground: background === null });
    mkdirSync(publicDir, { recursive: true });
    writeFileSync(join(publicDir, file), png);
    await context.close();
    console.log(`${file}  ${size}x${size}${background ? '' : '  (transparent)'}`);
  }
} finally {
  await browser.close();
}
