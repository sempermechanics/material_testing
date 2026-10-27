// firestore.rules against the Firestore emulator: no client may read, list,
// query or write any collection the backend uses, whether signed out, signed
// in as the document's owner, signed in as someone else, or signed in with
// admin-looking custom claims. The backend's Admin SDK bypasses rules, so a
// rule that let anything through would only ever widen client access.
//
// Run through the emulator (it sets FIRESTORE_EMULATOR_HOST):
//   npx firebase emulators:exec --only firestore --project demo-rules "node --test"
// The `demo-` project id keeps the emulator from ever reaching a real project.
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  addDoc,
  collection,
  collectionGroup,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  limit,
  query,
  setDoc,
  setLogLevel,
  updateDoc,
  where,
  writeBatch,
} from 'firebase/firestore';

import { COLLECTIONS } from './collections.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
// RULES_FILE lets a mutation check point the suite at a weakened copy.
const RULES = readFileSync(process.env.RULES_FILE || join(HERE, '..', 'firestore.rules'), 'utf8');
const PROJECT = 'demo-rules';

const ALICE = 'alice-uid-0001';
const BOB = 'bob-uid-0002';
const ALICE_EMAIL = 'alice@example.test';
const BOB_EMAIL = 'bob@example.test';
const PARENT = 'lic-1';

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error('FIRESTORE_EMULATOR_HOST is not set: run through '
    + '`firebase emulators:exec --only firestore --project demo-rules "node --test"`.');
}

let env;

// Every denial is a PERMISSION_DENIED the SDK would log as a warning.
setLogLevel('silent');

/** Document ids per collection: Alice's and Bob's, seeded with their owner
 * fields. `ids(spec, BOB)` swaps them, so "own" is always the caller's. */
function ids(spec, caller = ALICE) {
  const alice = spec.idIsUid ? ALICE : `${spec.name}-own`;
  const bob = spec.idIsUid ? BOB : `${spec.name}-other`;
  return caller === BOB ? { own: bob, other: alice } : { own: alice, other: bob };
}

before(async () => {
  const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(':');
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: RULES, host, port: Number(port) },
  });
  await env.clearFirestore();
  // Seed every collection as the backend would, past the rules, so reads hit
  // real documents: a denial then means the rules said no, not "not found".
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const parent of ['licenses', 'deleted_licenses']) {
      await setDoc(doc(db, `${parent}/${PARENT}`), { redeemedByUid: ALICE });
    }
    for (const spec of COLLECTIONS) {
      const { own, other } = ids(spec);
      await setDoc(doc(db, spec.path(own, PARENT)), spec.ownerFields(ALICE, ALICE_EMAIL));
      await setDoc(doc(db, spec.path(other, PARENT)), spec.ownerFields(BOB, BOB_EMAIL));
    }
  });
});

after(async () => {
  if (!env) return;
  // The emulator's rule coverage report, kept for CI's artifact upload.
  try {
    const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(':');
    const url = `http://${host}:${port}/emulator/v1/projects/${PROJECT}:ruleCoverage.html`;
    const res = await fetch(url);
    if (res.ok) {
      mkdirSync(join(HERE, 'coverage'), { recursive: true });
      writeFileSync(join(HERE, 'coverage', 'firestore-rules-coverage.html'), await res.text());
    }
  } catch {
    // Coverage is evidence, not a gate.
  }
  await env.cleanup();
});

test('the seed is really there (rules bypassed)', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    for (const spec of COLLECTIONS) {
      const snap = await getDoc(doc(ctx.firestore(), spec.path(ids(spec).own, PARENT)));
      assert.ok(snap.exists(), `seed missing for ${spec.name}`);
    }
  });
});

/** The ways a client could touch one collection, each expected to fail. */
function attempts(db, spec, uid, email) {
  const { own, other } = ids(spec, uid);
  const col = collection(db, spec.collectionPath(PARENT));
  const mine = spec.ownerFields(uid, email);
  const ownerKey = Object.keys(mine).find((k) => k === 'uid' || k === 'redeemedByUid' || k === 'email');
  return {
    'get own doc': () => getDoc(doc(db, spec.path(own, PARENT))),
    'get other doc': () => getDoc(doc(db, spec.path(other, PARENT))),
    'list collection': () => getDocs(query(col, limit(5))),
    ...(ownerKey ? {
      [`query where ${ownerKey} == self`]: () => getDocs(query(col, where(ownerKey, '==', mine[ownerKey]))),
    } : {}),
    'collectionGroup query': () => getDocs(query(collectionGroup(db, spec.name), limit(5))),
    ...(ownerKey ? {
      'collectionGroup where self': () => getDocs(
        query(collectionGroup(db, spec.name), where(ownerKey, '==', mine[ownerKey]))),
    } : {}),
    'create doc with own id': () => setDoc(doc(db, spec.path(`${spec.name}-new-${uid}`, PARENT)), mine),
    'create doc with auto id': () => addDoc(col, mine),
    'overwrite own doc': () => setDoc(doc(db, spec.path(own, PARENT)), mine),
    'update own doc': () => updateDoc(doc(db, spec.path(own, PARENT)), { touched: true }),
    'update other doc': () => updateDoc(doc(db, spec.path(other, PARENT)), { uid }),
    'delete own doc': () => deleteDoc(doc(db, spec.path(own, PARENT))),
    'delete other doc': () => deleteDoc(doc(db, spec.path(other, PARENT))),
    'batch write': () => {
      const batch = writeBatch(db);
      batch.set(doc(db, spec.path(`${spec.name}-batch`, PARENT)), mine);
      return batch.commit();
    },
  };
}

const CALLERS = {
  unauthenticated: () => ({ db: env.unauthenticatedContext().firestore(), uid: 'nobody', email: '' }),
  'signed in as the owner': () => ({
    db: env.authenticatedContext(ALICE, { email: ALICE_EMAIL, email_verified: true }).firestore(),
    uid: ALICE,
    email: ALICE_EMAIL,
  }),
  'signed in as another user': () => ({
    db: env.authenticatedContext(BOB, { email: BOB_EMAIL, email_verified: true }).firestore(),
    uid: BOB,
    email: BOB_EMAIL,
  }),
  // Claims a client cannot mint, but a rule that trusted them would open up.
  'signed in with admin claims': () => ({
    db: env.authenticatedContext(ALICE, {
      email: ALICE_EMAIL, email_verified: true, admin: true, role: 'admin', tenantId: 't-1',
    }).firestore(),
    uid: ALICE,
    email: ALICE_EMAIL,
  }),
};

for (const spec of COLLECTIONS) {
  describe(`${spec.collectionPath('{id}')} (${spec.source})`, () => {
    for (const [caller, make] of Object.entries(CALLERS)) {
      test(`${caller}: every read, list, query and write is denied`, async () => {
        const { db, uid, email } = make();
        for (const [what, op] of Object.entries(attempts(db, spec, uid, email))) {
          await assert.doesNotReject(
            assertFails(op()),
            `${spec.name}: ${caller} could ${what}`,
          );
        }
      });
    }
  });
}

// TD-199 (framework 5.3 Verify: "Attempt to edit or delete audit entries as
// Org Owner and expect failure"). The deny-all above already covers it; this
// names the acceptance, with an institution owner's claims and a merge write.
describe('audit_logs is append-only for every client', () => {
  const ORG_OWNER = () => env.authenticatedContext(ALICE, {
    email: ALICE_EMAIL, email_verified: true, role: 'owner', orgOwner: true,
    institutionAdmin: true, licenseId: PARENT,
  }).firestore();
  const callers = { ...Object.fromEntries(
    Object.entries(CALLERS).map(([name, make]) => [name, () => make().db])), 'Org Owner': ORG_OWNER };
  for (const [caller, make] of Object.entries(callers)) {
    test(`${caller}: an audit row cannot be edited, merged over or deleted`, async () => {
      const db = make();
      const row = doc(db, 'audit_logs/audit_logs-own');
      await assertFails(updateDoc(row, { outcome: 'OK', action: 'NOTHING_HAPPENED' }));
      await assertFails(setDoc(row, { detail: {} }, { merge: true }));
      await assertFails(setDoc(row, { uid: ALICE }));
      await assertFails(deleteDoc(row));
    });
  }

  test('the row is still there, unchanged, after every attempt', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      const snap = await getDoc(doc(ctx.firestore(), 'audit_logs/audit_logs-own'));
      assert.ok(snap.exists());
      assert.equal(snap.data().action, 'AUTH_DENIED');
    });
  });
});

describe('catch-all', () => {
  test('a collection nobody uses yet is denied too', async () => {
    const db = env.authenticatedContext(ALICE, { email: ALICE_EMAIL }).firestore();
    await assertFails(getDoc(doc(db, `not_a_collection/${ALICE}`)));
    await assertFails(setDoc(doc(db, `not_a_collection/${ALICE}`), { uid: ALICE }));
    await assertFails(getDocs(collectionGroup(db, 'not_a_collection')));
  });

  test('the harness can tell allow from deny (sanity)', async () => {
    // Under an allow-all ruleset the same call succeeds, so the denials above
    // are the rules' doing, not a broken harness.
    const open = await initializeTestEnvironment({
      projectId: `${PROJECT}-open`,
      firestore: {
        rules: "rules_version = '2'; service cloud.firestore { match /databases/{d}/documents { match /{x=**} { allow read, write: if true; } } }",
        host: process.env.FIRESTORE_EMULATOR_HOST.split(':')[0],
        port: Number(process.env.FIRESTORE_EMULATOR_HOST.split(':')[1]),
      },
    });
    try {
      await assertSucceeds(getDoc(doc(open.unauthenticatedContext().firestore(), 'users/x')));
    } finally {
      await open.cleanup();
    }
  });
});
