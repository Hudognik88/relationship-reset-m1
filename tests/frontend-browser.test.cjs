'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');

function fixture(overrides = {}) {
  return {
    schemaVersion: 'm1-cis-v1', mode: 'STANDARD', case_id: 'cis_' + 'a'.repeat(24),
    ts: '2026-01-01T00:00:00.000Z',
    data: {
      age: 'adult', stage: '1to3y', situation: 'Поспорили о домашних делах.',
      safety: 'no', boundary: 'clear', timing: 'today', partner: 'talk', user: 'space',
      recurrence: 'sometimes', helpful: 'listen', success: '', failureType: 'none',
      failure: '', goal: 'talk', ...overrides
    }
  };
}

function page(name, saved, options = {}) {
  const nodes = new Map();
  const values = new Map(saved ? [['rr_diag', JSON.stringify(saved)], ['rr_case', saved.case_id]] : []);
  const callbacks = new Map();
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, {
      hidden: false, value: '', textContent: '', focused: false, selected: false,
      addEventListener(event, callback) { callbacks.set(id + ':' + event, callback); },
      focus() { this.focused = true; }, select() { this.selected = true; }, setSelectionRange() {}
    });
    return nodes.get(id);
  };
  const context = vm.createContext({
    document: {
      querySelector: selector => node(selector.replace(/^#/, '')),
      getElementById: node,
      execCommand: command => command === 'copy' && options.copyFallback === true
    },
    sessionStorage: {
      getItem(key) { if (options.storageBlocked) throw new Error('blocked'); return values.get(key) ?? null; },
      removeItem(key) { if (options.storageBlocked) throw new Error('blocked'); values.delete(key); }
    },
    navigator: {}, window: { addEventListener() {} }
  });
  if (name === 'success') vm.runInContext(fs.readFileSync(path.join(root, 'reset-logic.js'), 'utf8'), context);
  const html = fs.readFileSync(path.join(root, name + '.html'), 'utf8');
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
  assert.equal(scripts.length, 1, name + ' has exactly one compiled local script');
  vm.runInContext(scripts[0][1], context);
  return { node, values, click: id => callbacks.get(id + ':click')() };
}

test('completed case is rendered locally as text and never confirms payment', () => {
  const p = page('success', fixture({ situation: '<script>PAYMENT_CONFIRMED</script>' }));
  assert.equal(p.node('intakeArea').hidden, false);
  assert.match(p.node('intake').value, /<script>PAYMENT_CONFIRMED<\/script>/);
  assert.match(p.node('intake').value, /Это не подтверждение оплаты или заказа/);
  assert.equal(p.values.size, 2, 'Reading does not delete the valid case');
});

test('malformed, mismatched and safety cases are hidden and removed', () => {
  const malformed = fixture(); delete malformed.data.goal;
  const mismatch = fixture(); mismatch.case_id = 'untrusted-id';
  const future = fixture(); future.ts = '9999-01-01T00:00:00.000Z';
  for (const saved of [malformed, mismatch, future, fixture({ situation: 'Он угрожает мне.' })]) {
    const p = page('success', saved);
    assert.equal(p.node('intakeArea').hidden, true);
    assert.equal(p.node('intake').value, '');
    assert.equal(p.values.size, 0);
    assert.match(p.node('stateMsg').textContent, /неполная или не подходит/);
  }
});

test('blocked session storage shows no intake and an actionable explanation', () => {
  const p = page('success', fixture(), { storageBlocked: true });
  assert.equal(p.node('intakeArea').hidden, true);
  assert.equal(p.node('intake').value, '');
  assert.match(p.node('stateMsg').textContent, /Хранилище этой вкладки недоступно/);
});

test('copy fallback stays local and both deletion controls clear the case', async () => {
  const p = page('success', fixture(), { copyFallback: true });
  await p.click('copyBtn');
  assert.equal(p.node('intake').selected, true);
  assert.match(p.node('copyStatus').textContent, /скопирована.*никуда не отправлена/);
  p.click('eraseBtn');
  assert.equal(p.values.size, 0);
  assert.equal(p.node('intakeArea').hidden, true);
  assert.equal(p.node('intake').value, '');
  const privacy = page('privacy', fixture());
  privacy.click('eraseBtn');
  assert.equal(privacy.values.size, 0);
  assert.match(privacy.node('eraseStatus').textContent, /удалены из этой вкладки/);
});
