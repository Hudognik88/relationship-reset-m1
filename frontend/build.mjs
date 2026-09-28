import { readFile, writeFile, rm } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const require = createRequire(import.meta.url);
const args = process.argv.slice(2);
if (args.some((arg) => arg !== '--check') || args.length > 1) {
  throw new Error('Usage: node frontend/build.mjs [--check]');
}
const check = args.includes('--check');
const buildDir = join(root, '.frontend-build');
await rm(buildDir, { recursive: true, force: true });

for (const config of ['browser', 'landing']) {
  const compile = spawnSync(process.execPath, [
    require.resolve('typescript/bin/tsc'),
    '--project', join(root, `frontend/tsconfig.${config}.json`),
  ], { cwd: root, stdio: 'inherit' });
  if (compile.error) throw compile.error;
  if (compile.status !== 0) process.exit(compile.status || 1);
}

const css = await readFile(join(root, 'frontend/styles/site.css'), 'utf8');
const { renderPage } = require(join(buildDir, 'landing/page.js'));
const files = new Map([['pilot.html', renderPage(css)]]);

function replaceMarker(source, marker, replacement, required) {
  const count = source.split(marker).length - 1;
  if (count > 1 || (required && count !== 1)) {
    throw new Error(`Expected ${required ? 'one' : 'zero or one'} ${marker} marker`);
  }
  return source.replace(marker, replacement);
}

for (const name of ['index', 'terms', 'success', 'privacy']) {
  let page = await readFile(join(root, `frontend/templates/${name}.html`), 'utf8');
  page = replaceMarker(page, '<!-- RR_STYLES -->', `<style>\n${css}\n</style>`, false);
  if (name === 'success' || name === 'privacy') {
    const script = await readFile(join(buildDir, `browser/${name}.js`), 'utf8');
    page = replaceMarker(page, '<!-- RR_SCRIPT -->',
      `<script>\n${script.replace(/<\/script/gi, '<\\/script')}\n</script>`, true);
  }
  if (/<!-- RR_(?:SCRIPT|STYLES) -->/.test(page)) throw new Error(`Unresolved template marker: ${name}`);
  files.set(`${name}.html`, page);
}
for (const name of ['app', 'reset-logic']) {
  files.set(`${name}.js`, await readFile(join(buildDir, `browser/${name}.js`), 'utf8'));
}

const stale = [];
for (const [name, content] of files) {
  if (typeof content !== 'string' || !content) throw new Error(`Empty generated file: ${name}`);
  if (check) {
    let committed;
    try { committed = await readFile(join(root, name), 'utf8'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    if (committed !== content) stale.push(name);
  } else {
    await writeFile(join(root, name), content, 'utf8');
  }
}
if (stale.length) {
  throw new Error(`Generated frontend is stale: ${stale.join(', ')}. Run npm run build and commit the outputs.`);
}
console.log(`PASS: strict TypeScript ${check ? 'rebuild matches' : 'build produced'} ${files.size} public files.`);
console.log('Pilot remains static HTML. Hosting, routes, payment settings and Kotlin API are unchanged.');
