/**
 * Writes the third-party notices for everything that ships in the browser bundle.
 *
 * **Why this is a script and not a package.** Every candidate off the shelf
 * (`license-checker` and its forks) is a dependency added to satisfy a licence
 * obligation, which is a trade this project does not need to make: npm packages
 * carry their own LICENSE files, so the work is walking a tree and concatenating
 * text. Thirty lines here beats a supply-chain entry, and it means the format is
 * ours to keep readable.
 *
 * **Production dependencies only.** `--omit=dev` is the whole point: the build
 * toolchain is not distributed, so Angular's compiler, vitest and lightningcss
 * impose nothing. What lands in `META-INF/resources` inside the boot jar is what
 * needs notices — Leaflet, MapLibre, Angular's runtime, rxjs, tslib.
 *
 * **It fails rather than skips.** A package whose licence file cannot be found
 * stops the build. A notices file that quietly omits somebody is worse than no
 * notices file at all: it is the same legal position, plus a document asserting
 * that the position was checked.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const modulesDir = join(webDir, 'node_modules');
const outFile = process.argv[2] ?? join(webDir, 'build', 'third-party', 'THIRD-PARTY-web.txt');

/** The production tree, flattened to name -> version. */
function productionPackages() {
  const raw = execFileSync('npm', ['ls', '--omit=dev', '--all', '--json'], {
    cwd: webDir,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  const found = new Map();
  const walk = (node) => {
    for (const [name, child] of Object.entries(node.dependencies ?? {})) {
      if (!found.has(name)) {
        found.set(name, child.version);
        walk(child);
      }
    }
  };
  walk(JSON.parse(raw));
  return [...found.entries()].sort(([a], [b]) => a.localeCompare(b));
}

/**
 * Hoisted first, which is where npm puts almost everything, then the nested
 * copies. Returns null so the caller can fail with a name rather than a stack.
 */
function packageDir(name) {
  const hoisted = join(modulesDir, name);
  if (existsSync(join(hoisted, 'package.json'))) return hoisted;
  for (const entry of readdirSync(modulesDir)) {
    const nested = join(modulesDir, entry, 'node_modules', name);
    if (existsSync(join(nested, 'package.json'))) return nested;
  }
  return null;
}

const LICENCE_FILE = /^(licen[cs]e|copying|notice)(\.\w+)?$/i;

function licenceText(dir) {
  const file = readdirSync(dir).find((f) => LICENCE_FILE.test(f));
  return file ? readFileSync(join(dir, file), 'utf8').trim() : null;
}

/** SPDX id from package.json, which may be a string or the legacy object form. */
function declaredLicence(pkg) {
  if (typeof pkg.license === 'string') return pkg.license;
  if (pkg.license?.type) return pkg.license.type;
  if (Array.isArray(pkg.licenses)) return pkg.licenses.map((l) => l.type ?? l).join(' OR ');
  return null;
}

const packages = productionPackages();
const problems = [];
const sections = [];

for (const [name, version] of packages) {
  const dir = packageDir(name);
  if (!dir) {
    problems.push(`${name}@${version}: not found under node_modules`);
    continue;
  }
  const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
  const licence = declaredLicence(pkg);
  const text = licenceText(dir);
  if (!licence && !text) {
    problems.push(`${name}@${version}: no licence field and no licence file`);
    continue;
  }
  const homepage = pkg.homepage ?? pkg.repository?.url ?? pkg.repository ?? '';
  sections.push(
    [
      '-'.repeat(78),
      `${name} ${version}`,
      licence ? `License: ${licence}` : 'License: see text below',
      homepage ? `Homepage: ${String(homepage).replace(/^git\+|\.git$/g, '')}` : '',
      '',
      text ?? '(No licence file shipped in the package; the identifier above is what it declares.)',
      '',
    ]
      .filter((line) => line !== '')
      .join('\n'),
  );
}

if (problems.length > 0) {
  console.error('Cannot write third-party notices:\n  ' + problems.join('\n  '));
  process.exit(1);
}

const header = `wander — third-party notices (browser bundle)

The following packages are redistributed inside wander's boot jar, under
META-INF/resources. Their licences and copyright notices are reproduced in full
below, which is what MIT, BSD and ISC require of a binary distribution.

Build tooling is deliberately absent: it is not distributed, so it imposes
nothing. This list is the production dependency tree only.

Generated from package-lock.json — do not edit by hand.
Packages: ${sections.length}
`;

mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, header + '\n' + sections.join('\n'), 'utf8');
console.log(`third-party notices: ${sections.length} packages -> ${outFile}`);
