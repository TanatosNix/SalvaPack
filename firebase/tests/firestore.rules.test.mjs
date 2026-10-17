import test, { after, before, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { readFile } from 'node:fs/promises';
import { doc, getDoc, getDocs, collection, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, Timestamp } from 'firebase/firestore';

const PROJECT = 'demo-salvapack';
const FIRST = '0123456789ABCDEF0123456789ABCDEF';
const SECOND = 'FEDCBA9876543210FEDCBA9876543210';
const DATE = Timestamp.fromDate(new Date('2026-01-01T00:00:00Z'));
let env;

const db = (uid) => env.authenticatedContext(uid).firestore();
const guest = () => env.unauthenticatedContext().firestore();
const bag = (store, id = FIRST) => doc(store, 'backpacks', id);
const profile = (store, uid) => doc(store, 'users', uid);
const product = (store, id = 'water') => doc(store, 'backpacks', FIRST, 'products', id);
const validProduct = (changes = {}) => ({
  id: 'water', name: 'Agua', category: 'FOOD', expiryDate: '2027-02-28',
  expiryDates: ['2027-02-28'], quantity: 1, photoBase64: null, ...changes,
});

async function seedBackpack(id = FIRST) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(bag(context.firestore(), id), { createdAt: DATE });
  });
}

async function assign(uid, id = FIRST) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(profile(context.firestore(), uid), { backpackId: id });
  });
}

async function createAtomic(uid, id = FIRST) {
  const store = db(uid);
  const batch = writeBatch(store);
  batch.set(bag(store, id), { createdAt: serverTimestamp() });
  batch.set(profile(store, uid), { backpackId: id });
  return batch.commit();
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: await readFile(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
beforeEach(async () => env.clearFirestore());
after(async () => env.cleanup());

test('a private backpack is created atomically with the anonymous device profile', async () => {
  await assertSucceeds(createAtomic('phone-a'));
  assert.equal((await getDoc(profile(db('phone-a'), 'phone-a'))).data().backpackId, FIRST);
});

test('a backpack cannot be created without assigning the same device atomically', async () => {
  await assertFails(setDoc(bag(db('phone-a')), { createdAt: serverTimestamp() }));
});

test('anonymous or other devices cannot read a private device profile', async () => {
  await seedBackpack(); await assign('phone-a');
  await assertFails(getDoc(profile(guest(), 'phone-a')));
  await assertFails(getDoc(profile(db('phone-b'), 'phone-a')));
  await assertSucceeds(getDoc(profile(db('phone-a'), 'phone-a')));
});

test('a second phone joins only when it knows an existing 128-bit code', async () => {
  await seedBackpack();
  await assertSucceeds(setDoc(profile(db('phone-b'), 'phone-b'), { backpackId: FIRST }));
  await assertFails(setDoc(profile(db('phone-c'), 'phone-c'), { backpackId: SECOND }));
});

test('backpacks cannot be listed and malformed codes are denied', async () => {
  await seedBackpack();
  await assertFails(getDocs(collection(db('phone-a'), 'backpacks')));
  await assertFails(setDoc(doc(db('phone-a'), 'backpacks', 'SHORT'), { createdAt: serverTimestamp() }));
});

test('a device cannot switch to another backpack after it is paired', async () => {
  await seedBackpack(); await seedBackpack(SECOND); await assign('phone-a');
  await assertFails(updateDoc(profile(db('phone-a'), 'phone-a'), { backpackId: SECOND }));
});

test('only a device paired with the code can use its products', async () => {
  await seedBackpack(); await assign('phone-a');
  await assertSucceeds(setDoc(product(db('phone-a')), validProduct()));
  await assertFails(getDoc(product(db('phone-b'))));
  await assertFails(setDoc(product(db('phone-b')), validProduct()));
  await assertFails(getDoc(product(guest())));
});

test('both paired phones can read, edit and delete the shared inventory', async () => {
  await seedBackpack(); await assign('phone-a'); await assign('phone-b');
  await assertSucceeds(setDoc(product(db('phone-a')), validProduct()));
  await assertSucceeds(updateDoc(product(db('phone-b')), { name: 'Agua potable' }));
  assert.equal((await getDoc(product(db('phone-a')))).data().name, 'Agua potable');
  await assertSucceeds(deleteDoc(product(db('phone-b'))));
});

test('product schema, dates, categories, IDs and photo size are validated', async () => {
  await seedBackpack(); await assign('phone-a');
  const store = db('phone-a');
  await assertFails(setDoc(product(store), validProduct({ name: '   ' })));
  await assertFails(setDoc(product(store), validProduct({ category: 'OTHER' })));
  await assertFails(setDoc(product(store), validProduct({ expiryDate: '2025-02-29' })));
  await assertFails(setDoc(product(store), validProduct({ quantity: 0 })));
  await assertFails(setDoc(product(store), validProduct({ quantity: 1, expiryDates: ['2027-02-28', '2028-02-29'] })));
  await assertFails(setDoc(product(store), validProduct({ expiryDate: '2027-02-28', expiryDates: ['invalid'] })));
  await assertFails(setDoc(product(store), validProduct({ photoBase64: 'A'.repeat(350001) })));
  await assertFails(setDoc(product(store, 'different'), validProduct()));
  await assertSucceeds(setDoc(product(store), validProduct({ expiryDate: '2028-02-29', expiryDates: ['2028-02-29'], quantity: 3 })));
});

test('backpack documents are immutable and cannot be deleted', async () => {
  await seedBackpack(); await assign('phone-a');
  await assertFails(updateDoc(bag(db('phone-a')), { createdAt: serverTimestamp() }));
  await assertFails(deleteDoc(bag(db('phone-a'))));
});
