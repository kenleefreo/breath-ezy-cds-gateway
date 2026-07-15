/**
 * Contract test — the FL-30 KB export filter (FL-34 Phase B / B1).
 *
 * THIS SUITE IS THE FILTER'S ONLY PROOF. The export decides what clinician-signed knowledge becomes
 * EXECUTABLE inside a second engine. Every gate here is a safety gate, and a gate nobody tests is a
 * gate that quietly stops working.
 *
 * The fixtures drive `buildBundle` directly — `checksumRecords`, `identityCode` and the file reader
 * are all injected — so each gate can be exercised in isolation, including the ones that must ABORT.
 * A gate proven only against the real datastore is proven only against the happy path.
 *
 * Run: node --test tools/
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { buildBundle, KM_SET, EXPORTABLE_CAPABILITIES } from "./export-fl30-kb.mjs";

// A deterministic stand-in for breath-ezy's checksumRecords. The REAL function is imported from the
// checkout at export time and never re-implemented (silent-divergence hazard); the fixtures only need
// something stable enough to make drift detectable.
const fakeChecksum = (records) => `sum:${JSON.stringify(records).length}`;

const approved = (extra = {}) => ({ provenance: { review_status: "approved", reviewed_by: "KL" }, ...extra });
const draft = (extra = {}) => ({ provenance: { review_status: "draft", reviewed_by: null }, ...extra });

/** A datastore fixture: capability id → dataset. Missing capabilities default to signed + approved. */
function fixture(overrides = {}) {
  const store = {};
  for (const [cap, spec] of Object.entries(EXPORTABLE_CAPABILITIES)) {
    const subject = spec.subject_keys[0];
    const records = [approved({ [subject]: `${cap}-drug`, ...(spec.name_keys[0] ? { [spec.name_keys[0]]: [`${cap}-member`] } : {}) })];
    store[spec.file] = {
      capability: cap, dataset_version: `${cap}:v0.1.0-dev`, records,
      records_checksum: fakeChecksum(records),
      attestation: { clinical_sign_off: true, regulatory_sign_off: false, reviewer_id: "KL" },
    };
  }
  Object.assign(store, overrides);
  return {
    datastoreDir: "/fake",
    checksumRecords: fakeChecksum,
    sourceCommit: "deadbeef",
    readFile: (p) => {
      const f = p.split("/").pop();
      if (!(f in store)) throw new Error(`fixture: no such file ${f}`);
      return JSON.stringify(store[f]);
    },
    listCapabilities: Object.entries(store).map(([file, d]) => ({ capability: d.capability, file })),
  };
}
/** Re-seal a fixture dataset after mutating its records, so gate 3 isn't tripped by accident. */
const reseal = (ds) => ({ ...ds, records_checksum: fakeChecksum(ds.records) });

// ── (a) an unsigned dataset is excluded AND listed ────────────────────────────────────────────────
test("(a) an unsigned dataset is excluded, and the exclusion is RECORDED", () => {
  const f = fixture();
  const hepatic = JSON.parse(f.readFile("/fake/hepatic.json"));
  hepatic.attestation.clinical_sign_off = false;
  const { manifest } = buildBundle(fixture({ "hepatic.json": hepatic }));

  assert.equal(manifest.capabilities.find((c) => c.capability === "hepatic"), undefined, "unsigned knowledge must not be executable");
  const ex = manifest.excluded.find((e) => e.capability === "hepatic");
  assert.ok(ex, "a silent drop is indistinguishable from a bug — the exclusion must be in manifest.excluded[]");
  assert.equal(ex.reason, "no_clinical_sign_off");
});

// ── (b) a draft record is dropped; its signed siblings survive ────────────────────────────────────
test("(b) a draft record is dropped and its approved siblings survive — per-record is authoritative", () => {
  const f = fixture();
  const renal = JSON.parse(f.readFile("/fake/renal-rules.json"));
  renal.records = [approved({ ingredient: "furosemide" }), draft({ ingredient: "not-yet-reviewed" }), approved({ ingredient: "metformin" })];
  const { manifest, files } = buildBundle(fixture({ "renal-rules.json": reseal(renal) }));

  const cap = manifest.capabilities.find((c) => c.capability === "renal");
  assert.equal(cap.records, 2, "only the approved records may be exported");
  const body = JSON.parse(files["renal.json"]);
  assert.deepEqual(body.records.map((r) => r.ingredient), ["furosemide", "metformin"]);
  const ex = manifest.excluded.find((e) => e.capability === "renal" && e.reason === "records_not_approved");
  assert.ok(ex && ex.records_dropped === 1, "the dropped record must be counted in excluded[], not silently vanish");
});

// ── (c) a tampered seal ABORTS the whole export ───────────────────────────────────────────────────
test("(c) provenance drift ABORTS the export — it does not skip the dataset", () => {
  const f = fixture();
  const nti = JSON.parse(f.readFile("/fake/nti-register.json"));
  nti.records_checksum = "sum:tampered";
  assert.throws(
    () => buildBundle(fixture({ "nti-register.json": nti })),
    (e) => /ABORT — provenance drift in nti-register\.json/.test(e.message) && /come unstuck/.test(e.message),
    "a broken seal means the signature no longer covers the bytes. Skipping would let a TAMPER look like a FILTER — the export must refuse outright."
  );
});

// ── (d) the km_set is pinned ──────────────────────────────────────────────────────────────────────
test("(d) km_set is pinned to the client's DEFAULT_KM_SET", () => {
  // Must equal DEFAULT_KM_SET in breath-ezy cds-adapter/opencds-client.js. The client cross-checks it
  // on every response, so a drift here means the gateway executes knowledge the client did not ask for.
  assert.equal(KM_SET, "fl30-kb:v1");
  const { manifest, files } = buildBundle(fixture());
  assert.equal(manifest.km_set, "fl30-kb:v1");
  for (const [name, body] of Object.entries(files)) {
    if (name === "manifest.json") continue;
    assert.equal(JSON.parse(body).km_set, "fl30-kb:v1", `${name} must carry the km_set it answers to`);
  }
});

// ── (e) F5 — a FOREIGN LABEL stays out even when SIGNED. The load-bearing test. ───────────────────
test("(e) F5 — international_dose_guidance is excluded EVEN WHEN forced to clinical_sign_off:true", () => {
  // THE WHOLE REASON THE ALLOWLIST RUNS FIRST. These 12 US/EU label doses are excluded today only
  // because they happen to be unsigned — an incidental property. Their own attestation says clinical
  // sign-off "is NOT required", and the R-47a worksheet puts a clinician in front of exactly these
  // records. A sign-off-only filter would admit 12 foreign doses into an executable KM the day that
  // flag flips. Australian-context is a hard limit; it does not get to depend on an accident.
  const forced = {
    capability: "international_dose_guidance", dataset_version: "intl:v0.1.0-dev",
    records: [approved({ ingredient: "amlodipine", source_statement: "FDA label: 10 mg daily" })],
    records_checksum: fakeChecksum([approved({ ingredient: "amlodipine", source_statement: "FDA label: 10 mg daily" })]),
    attestation: { clinical_sign_off: true, regulatory_sign_off: true, reviewer_id: "KL" }, // ← forced GREEN
  };
  const { manifest, files } = buildBundle(fixture({ "international-dose-guidance.json": forced }));

  assert.equal(manifest.capabilities.find((c) => c.capability === "international_dose_guidance"), undefined,
    "a foreign label must NEVER become executable knowledge — sign-off is necessary, never sufficient");
  assert.equal(files["international_dose_guidance.json"], undefined, "no file may be emitted for it");
  assert.ok(!JSON.stringify(files).includes("FDA label"), "not one byte of a foreign label may reach the bundle");
  const ex = manifest.excluded.find((e) => e.capability === "international_dose_guidance");
  assert.ok(ex && /Australian-context hard limit/.test(ex.detail), "the exclusion must record WHY, so the next engineer does not 'fix' it");
});

// ── (f) F5 — identity is not executable knowledge ─────────────────────────────────────────────────
test("(f) F5 — drug_vocabulary and ingredient_identity are excluded: identity is not executable knowledge", () => {
  const mk = (capability) => ({
    capability, dataset_version: `${capability}:v0.1.0-dev`, records: [approved({ primary_name: "furosemide" })],
    records_checksum: fakeChecksum([approved({ primary_name: "furosemide" })]),
    attestation: { clinical_sign_off: true, reviewer_id: "KL" }, // signed — and still excluded
  });
  const { manifest, files } = buildBundle(fixture({ "drug-vocabulary.json": mk("drug_vocabulary"), "ingredient-identity.json": mk("ingredient_identity") }));

  for (const cap of ["drug_vocabulary", "ingredient_identity"]) {
    assert.equal(manifest.capabilities.find((c) => c.capability === cap), undefined,
      `${cap} inside OpenCDS would be a SECOND, DIVERGENT canonicaliser — the E6 defect with extra steps. Identity is settled once, by the pipeline, before both executors.`);
    assert.equal(files[`${cap}.json`], undefined);
    assert.ok(manifest.excluded.some((e) => e.capability === cap && /second, divergent canonicaliser/.test(e.detail)));
  }
});

// ── (g) F3 — the dose KM's knowledge IS exported ──────────────────────────────────────────────────
test("(g) F3 — dose_guidance IS exported (both objections are dead)", () => {
  const { manifest, files } = buildBundle(fixture());
  const cap = manifest.capabilities.find((c) => c.capability === "dose_guidance");
  assert.ok(cap, "the dose KM's source exists (451 clinician-attested records) and its consumer exists (the E3 evidence plane) — F3 flipped");
  assert.equal(cap.mirrors_accessor, "getDoseGuidance");
  assert.ok(files["dose_guidance.json"], "the dose KB must be emitted");
  assert.equal(cap.check_id, null, "the dose KM emits an ADVISORY dose_candidate, not a check verdict — it is never PharmCheck.dose_guidance");
});

// ── (h) B0b — dual keys, and the gap is NAMED rather than assumed away ────────────────────────────
test("(h) B0b — the sidecar is EMPTY and rxcui_active is FALSE while the identity source is unsigned", () => {
  // identityCode() is the gated authority: it returns null while the drug vocabulary is unsigned, so
  // the sidecar cannot steer. This is the live state today, and the KB is name-keyed — which B0's
  // canonicalisation is what makes correct.
  const { manifest, files } = buildBundle({ ...fixture(), identityCode: () => null });
  assert.equal(manifest.key_policy.rxcui_active, false, "an unsigned identity map may BLOCK, but it must never STEER");
  assert.equal(manifest.identity_sidecar.codes, 0);
  assert.deepEqual(JSON.parse(files["index-identity.json"]).rxcui_to_canonical_name, {});
  assert.ok(manifest.name_only.length > 0, "every subject with no code must be NAMED, so the gap is visible rather than assumed away");
  assert.ok(/UNSIGNED/.test(manifest.key_policy.note), "the manifest must say WHY the codes are absent");
});

test("(h) B0b — rxcui_active is DERIVED from the sidecar, and cannot be asserted by hand", () => {
  // The mechanical bar: a signed source populates it, and nothing else can. Someone editing the
  // manifest to flip this on would be steering on unsigned identity — so the value is computed, never
  // taken on trust.
  const signed = { ...fixture(), identityCode: (d) => (d === "renal-drug" ? "4603" : null) };
  const { manifest, files } = buildBundle(signed);
  assert.equal(manifest.key_policy.rxcui_active, true, "a SIGNED source populates the sidecar and activates code-first matching");
  assert.equal(JSON.parse(files["index-identity.json"]).rxcui_to_canonical_name["4603"], "renal-drug",
    "the sidecar maps CODE → CANONICAL NAME: the KM resolves the code the pipeline settled, it never resolves identity itself");
  assert.ok(!manifest.name_only.includes("renal-drug"), "a subject WITH a code is not name-only");
  assert.ok(manifest.name_only.includes("nti-drug"), "a subject WITHOUT a code must still be named — a code-ONLY contract fails on combination products and classes, which carry real signed doses");
});

// ── the bundle is self-describing and verifiable ──────────────────────────────────────────────────
test("every exported file carries a file_sha256 that matches its bytes", () => {
  const { manifest, files } = buildBundle(fixture());
  const sha = (s) => createHash("sha256").update(s).digest("hex");
  for (const c of manifest.capabilities) {
    assert.equal(c.file_sha256, sha(files[c.file]), `${c.file}: the transport layer is what the Java KM verifies at load time — a mismatch must fail the KM closed, so it has to be right here`);
  }
  assert.equal(manifest.identity_sidecar.file_sha256, sha(files["index-identity.json"]));
});

test("source_commit is recorded — it is the audit link back to the signed records", () => {
  const { manifest } = buildBundle(fixture());
  assert.equal(manifest.source_commit, "deadbeef");
  assert.equal(manifest.source_repo, "kenleefreo/breath-ezy");
});

test("the allowlist is exactly the 8 accessor-backed capabilities — no route KM (F4)", () => {
  assert.deepEqual(Object.keys(EXPORTABLE_CAPABILITIES).sort(),
    ["allergy", "dose_guidance", "hepatic", "interactions", "nti", "pregnancy_risk", "renal", "scheduling"]);
  // route_appropriateness_check is in the frozen check_id enum but engine.js implements it ZERO times.
  // A route KM would have nothing to mirror — that is OpenCDS INTRODUCING knowledge, which the
  // gateway's premise forbids.
  assert.ok(!Object.values(EXPORTABLE_CAPABILITIES).some((s) => s.check_id === "route_appropriateness_check"));
});
