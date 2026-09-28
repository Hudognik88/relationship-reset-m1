import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Script } from 'node:vm';

const root = new URL('../', import.meta.url);
const read = (name) => readFile(new URL(name, root), 'utf8');

test('published pilot remains a passive interest page after TypeScript rendering', async () => {
  const html = await read('pilot.html');
  assert.equal((html.match(/<h1\b/gi) || []).length, 1);
  assert.doesNotMatch(html, /<(?:script|form|input|textarea|iframe|object|embed)\b/i);
  assert.doesNotMatch(html, /\b(?:src|action|onclick)\s*=/i);
  assert.match(html, /mailto:moverelationship@gmail\.com\?subject=/);
  assert.match(html, /990/);
  assert.match(html, /<details\b/);
  assert.ok(Buffer.byteLength(html) <= 128 * 1024);
});

test('legacy questionnaire and saved-response page retain the deployed file interface', async () => {
  const index = await read('index.html');
  const success = await read('success.html');
  assert.match(index, /<script src="reset-logic\.js"><\/script>/);
  assert.match(index, /<script src="app\.js"><\/script>/);
  assert.match(success, /<script src="reset-logic\.js"><\/script>/);
  for (const name of ['app.js', 'reset-logic.js']) new Script(await read(name), { filename: name });
  for (const name of ['success.html', 'privacy.html']) {
    const html = await read(name);
    const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
    assert.equal(scripts.length, 1, `${name} has one compiled interaction script`);
    new Script(scripts[0][1], { filename: name });
    assert.doesNotMatch(html, /<!-- RR_(?:SCRIPT|STYLES) -->/);
  }
});
