const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = fs.readFileSync(path.resolve(__dirname, '../../main/assets/spelling-keyboard.js'), 'utf8');

function harness() {
  const listeners = new Map();
  const timers = [];
  const requests = [];
  let input = null;
  let cooldown = false;
  let hidden = false;
  let changed;
  const document = {
    get visibilityState() { return hidden ? 'hidden' : 'visible'; },
    querySelector() { return cooldown ? null : input; },
  };
  const window = {
    AndroidKeyboard: { showSpellingKeyboard(id) { requests.push(id); } },
    addEventListener(name, callback) { listeners.set(name, callback); },
  };
  class MutationObserver {
    constructor(callback) { changed = callback; }
    observe() {}
  }
  vm.runInNewContext(script, {
    document, window, MutationObserver,
    setTimeout(callback) { timers.push(callback); },
  });
  return {
    window, requests,
    card() {
      input = { isConnected: true, offsetWidth: 100, offsetHeight: 20, focus() {} };
      changed();
      return input;
    },
    cooldown(value) { cooldown = value; changed(); },
    hidden(value) { hidden = value; changed(); },
    result(value) { window.__ebvSpellingKeyboardResult(requests.at(-1), value); },
    resume() { listeners.get('ebv-native-lifecycle')({ detail: { type: 'activity-on-resume' } }); },
    flushTimers() { while (timers.length) timers.shift()(); },
    mutate() { changed(); },
  };
}

test('one request per successful card; dismissal and resume do not reopen it', () => {
  const h = harness();
  h.card();
  assert.equal(h.requests.length, 1);
  h.result('shown');
  h.mutate();
  h.resume();
  assert.equal(h.requests.length, 1);
  h.card();
  assert.equal(h.requests.length, 2);
});

test('deferred request is retried on resume for the same active card', () => {
  const h = harness();
  h.card();
  h.result('deferred');
  h.mutate();
  assert.equal(h.requests.length, 1);
  h.resume();
  assert.equal(h.requests.length, 2);
});

test('failed opening gets one bounded retry', () => {
  const h = harness();
  h.card();
  h.result('not-shown');
  h.flushTimers();
  assert.equal(h.requests.length, 2);
  h.result('not-shown');
  h.flushTimers();
  h.resume();
  assert.equal(h.requests.length, 2);
});

test('cooldown and background suppress requests until an active card is visible', () => {
  const h = harness();
  h.cooldown(true);
  h.card();
  assert.equal(h.requests.length, 0);
  h.cooldown(false);
  assert.equal(h.requests.length, 1);
  h.result('shown');
  h.hidden(true);
  h.card();
  assert.equal(h.requests.length, 1);
  h.hidden(false);
  assert.equal(h.requests.length, 2);
});

test('new card can request after an old card receives its late native result', () => {
  const h = harness();
  h.card();
  const oldId = h.requests[0];
  h.card();
  assert.equal(h.requests.length, 1);
  h.window.__ebvSpellingKeyboardResult(oldId, 'shown');
  assert.equal(h.requests.length, 2);
});
