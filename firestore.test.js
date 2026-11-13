const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

function ts(db) {
  return db?.app?.firebase_?.firestore?.FieldValue?.serverTimestamp?.() ?? new Date(Date.now() - 5000);
}

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

test("Unauthenticated user cannot read or write shared_lists", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(db.collection("shared_lists").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(db.collection("shared_lists").doc("list_1").set({
    ownerId: ALICE_UID,
    memberUids: [ALICE_UID],
    inviteCode: "ABCD12",
    name: "Test",
    createdAt: ts(),
    updatedAt: ts(),
  }));
});

test("Authenticated owner can create and query own shared_lists", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("shared_lists").doc("list_1").set({
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID],
      inviteCode: "FAM2026",
      name: "שבת בבית",
      createdAt: ts(),
      updatedAt: ts(),
    })
  );
  await assertSucceeds(
    aliceDb.collection("shared_lists").where("memberUids", "array-contains", ALICE_UID).get()
  );
});

test("Shadow field and identity spoofing are rejected on create", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("shared_lists").doc("list_spoof").set({
      ownerId: BOB_UID,
      memberUids: [BOB_UID],
      inviteCode: "ABCD12",
      name: "Spoof",
      createdAt: ts(),
      updatedAt: ts(),
    })
  );
  await assertFails(
    aliceDb.collection("shared_lists").doc("list_ghost").set({
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID],
      inviteCode: "ABCD12",
      name: "Ghost",
      isAdmin: true,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );
});

test("Non-member cannot read or query Alice's list", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("shared_lists").doc("list_priv").set({
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID],
      inviteCode: "JOIN77",
      name: "פרטי",
      createdAt: ts(),
      updatedAt: ts(),
    })
  );
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  await assertFails(charlieDb.collection("shared_lists").doc("list_priv").get());
  await assertFails(charlieDb.collection("shared_lists").get());
  await assertFails(
    charlieDb.collection("shared_lists").where("memberUids", "array-contains", ALICE_UID).get()
  );
});

test("Invited member Bob can join shared list and CRUD subcollection items", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("shared_lists").doc("list_fam").set({
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID],
      inviteCode: "JOIN99",
      name: "משפחה",
      createdAt: ts(),
      updatedAt: ts(),
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(
    bobDb.collection("shared_lists").doc("list_fam").update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: ts(),
    })
  );

  // Bob cannot rename via join action or drop Alice
  await assertFails(
    bobDb.collection("shared_lists").doc("list_fam").update({
      memberUids: [BOB_UID],
      updatedAt: ts(),
    })
  );

  // Bob adds item
  await assertSucceeds(
    bobDb.collection("shared_lists").doc("list_fam").collection("items").doc("7290000001").set({
      addedBy: BOB_UID,
      memberUids: [ALICE_UID, BOB_UID],
      code: "7290000001",
      name: "חלב 3%",
      qty: 2,
      checked: false,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );

  // Alice queries items with matching memberUids filter
  await assertSucceeds(
    aliceDb.collection("shared_lists").doc("list_fam").collection("items")
      .where("memberUids", "array-contains", ALICE_UID).get()
  );

  // Charlie cannot read or write subcollection items
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  await assertFails(
    charlieDb.collection("shared_lists").doc("list_fam").collection("items").doc("7290000001").get()
  );
  await assertFails(
    charlieDb.collection("shared_lists").doc("list_fam").delete()
  );
});

test("User watchlist subcollection isolates per user", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("watched").doc("729001").set({
      code: "729001",
      name: "קפה",
      targetAgorot: 1290,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("watched").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("watched").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("watched").doc("729001").set({
      code: "729001",
      name: "קפה",
      targetAgorot: 500,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );
});
