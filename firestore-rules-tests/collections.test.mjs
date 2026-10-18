// The guard: every collection backend/app names is in COLLECTIONS, so a new
// collection cannot ship without its deny-all test. Needs no emulator.
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { COLLECTION_NAMES } from './collections.mjs';
import { scanSources, scanTree } from './scan.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, '..');
const BACKEND_APP = join(REPO, 'backend', 'app');

test('every collection backend/app uses is in the rules test list', () => {
  const { files, names, unresolved } = scanTree(BACKEND_APP, REPO);
  assert.ok(files > 10, `scanned only ${files} Python files under backend/app`);
  assert.deepEqual(unresolved, [], 'collection arguments the scan cannot resolve; '
    + 'use a string literal or a module-level NAME = "value" constant:\n'
    + unresolved.join('\n'));
  const missing = [...names.keys()].filter((n) => !COLLECTION_NAMES.has(n)).sort();
  assert.deepEqual(missing, [], 'collections used by backend/app but absent from '
    + 'firestore-rules-tests/collections.mjs (add each with its owner fields):\n'
    + missing.map((n) => `  ${n}  (${names.get(n).slice(0, 3).join(', ')})`).join('\n'));
  // A floor, so a scan that silently stops matching cannot pass: these are
  // the collections the licensing and sync paths cannot work without.
  for (const core of ['users', 'devices', 'sessions', 'files', 'licenses', 'seats', 'audit_logs']) {
    assert.ok(names.has(core), `scan no longer finds ${core}; is the regex stale?`);
  }
});

test('listed collections the backend no longer uses are reported, not failed', (t) => {
  const { names } = scanTree(BACKEND_APP, REPO);
  const unused = [...COLLECTION_NAMES].filter((n) => !names.has(n)).sort();
  if (unused.length) t.diagnostic(`listed but unused by backend/app: ${unused.join(', ')}`);
});

test('the scan resolves literals, constants, attributes and document paths', () => {
  const sources = [
    { file: 'a.py', text: [
      'X = "alpha"',
      'def f():',
      '    db().collection("beta").document(uid)',
      '    db().collection(X)',
      '    db().collection_group(other.GAMMA).stream()',
      '    ref.collection(\'delta\')',
      '    db().document("epsilon/abc")',
      '    # db().collection("commented")',
    ].join('\n') },
    { file: 'b.py', text: 'GAMMA: str = "gamma"  # shared\n' },
  ];
  const { names, unresolved } = scanSources(sources);
  assert.deepEqual([...names.keys()].sort(), ['alpha', 'beta', 'delta', 'epsilon', 'gamma']);
  assert.deepEqual(unresolved, []);
});

test('the scan reports what it cannot resolve', () => {
  const { unresolved } = scanSources([
    { file: 'c.py', text: 'db().collection(f"tenant_{tid}")\ndb().collection(name_for(x))\n' },
  ]);
  assert.equal(unresolved.length, 2);
});
