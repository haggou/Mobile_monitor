const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_101";
const BOB_UID = "bob_uid_202";
const pastTimestamp = () => new Date(Date.now() - 5000);

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
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
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or list any protected collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("call_records").where("ownerUserId", "==", ALICE_UID).get());
  await assertFails(unauthDb.collection("sms_records").where("ownerUserId", "==", ALICE_UID).get());
  await assertFails(unauthDb.collection("recordings").where("ownerUserId", "==", ALICE_UID).get());
  await assertFails(unauthDb.collection("devices").where("ownerUserId", "==", ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
});

test("Owner can create and query own user profile, device, call record, sms record, and consent", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com", email_verified: true }).firestore();
  const ts = pastTimestamp();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      syncEnabled: true,
      consentAccepted: true,
      consentVersion: "1.0",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("devices").doc("dev_alice_1").set({
      ownerUserId: ALICE_UID,
      deviceId: "dev_alice_1",
      deviceName: "Pixel 9",
      manufacturer: "Google",
      model: "Pixel 9",
      osVersion: "15",
      isConnected: true,
      callRecordingSupported: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("call_records").doc("call_1").set({
      ownerUserId: ALICE_UID,
      deviceId: "dev_alice_1",
      source: "android_call_log",
      recordHash: "hash_call_001",
      phoneNumber: "+15550101",
      contactName: "Office",
      callType: "incoming",
      callTimestampMillis: 1700000000000,
      durationSeconds: 120,
      syncedAt: ts,
      createdAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("sms_records").doc("sms_1").set({
      ownerUserId: ALICE_UID,
      deviceId: "dev_alice_1",
      source: "user_import",
      recordHash: "hash_sms_001",
      address: "+15550101",
      contactName: "Office",
      messageBody: "Meeting confirmed",
      direction: "incoming",
      deliveryStatus: "received",
      messageTimestampMillis: 1700000005000,
      syncedAt: ts,
      createdAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("call_records").where("ownerUserId", "==", ALICE_UID).get()
  );
});

test("Cross-user access and unfiltered list queries are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com", email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, { email: "bob@example.com", email_verified: true }).firestore();
  const ts = pastTimestamp();

  await assertSucceeds(
    aliceDb.collection("devices").doc("dev_alice_iso").set({
      ownerUserId: ALICE_UID,
      deviceId: "dev_alice_iso",
      deviceName: "Pixel 9",
      manufacturer: "Google",
      model: "Pixel 9",
      osVersion: "15",
      isConnected: true,
      callRecordingSupported: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("call_records").doc("alice_call_1").set({
      ownerUserId: ALICE_UID,
      deviceId: "dev_alice_iso",
      source: "android_call_log",
      recordHash: "hash_call_002",
      phoneNumber: "+15550102",
      contactName: "Bob",
      callType: "outgoing",
      callTimestampMillis: 1700000000000,
      durationSeconds: 45,
      syncedAt: ts,
      createdAt: ts,
    })
  );

  await assertFails(bobDb.collection("devices").doc("dev_alice_iso").get());
  await assertFails(bobDb.collection("call_records").doc("alice_call_1").get());
  await assertFails(bobDb.collection("call_records").where("ownerUserId", "==", ALICE_UID).get());
  await assertFails(bobDb.collection("call_records").get());
  await assertFails(
    bobDb.collection("call_records").doc("spoof_call").set({
      ownerUserId: ALICE_UID,
      deviceId: "dev_bob_1",
      source: "android_call_log",
      recordHash: "hash_call_003",
      phoneNumber: "+15550103",
      contactName: "Spoof",
      callType: "missed",
      callTimestampMillis: 1700000000000,
      durationSeconds: 0,
      syncedAt: ts,
      createdAt: ts,
    })
  );
});

test("Shadow fields, invalid enums, and email spoofing are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com", email_verified: true }).firestore();
  const unverifiedViewerDb = testEnv.authenticatedContext(BOB_UID, { email: "viewer@example.com", email_verified: false }).firestore();
  const verifiedViewerDb = testEnv.authenticatedContext(BOB_UID, { email: "viewer@example.com", email_verified: true }).firestore();
  const ts = pastTimestamp();

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      syncEnabled: true,
      consentAccepted: true,
      consentVersion: "1.0",
      isAdmin: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    aliceDb.collection("call_records").doc("bad_call").set({
      ownerUserId: ALICE_UID,
      deviceId: "dev_alice_1",
      source: "android_call_log",
      recordHash: "hash_call_bad",
      phoneNumber: "+15550109",
      contactName: "Test",
      callType: "secret_wiretap",
      callTimestampMillis: 1700000000000,
      durationSeconds: 10,
      syncedAt: ts,
      createdAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("authorized_shares").doc("share_1").set({
      ownerUserId: ALICE_UID,
      deviceId: "dev_alice_1",
      source: "privacy_dashboard",
      viewerEmail: "viewer@example.com",
      scope: "calls",
      status: "active",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(unverifiedViewerDb.collection("authorized_shares").doc("share_1").get());
  await assertSucceeds(verifiedViewerDb.collection("authorized_shares").doc("share_1").get());
});
