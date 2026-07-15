#!/usr/bin/env node
/**
 * export-fl30-kb — build the checksummed FL-30 knowledge bundle the OpenCDS KMs execute.
 *
 * FL-34 Phase B / B1. Reads a `breath-ezy` checkout **READ-ONLY** and emits `kb/` + `kb/manifest.json`
 * into this repo, where it is a COMMITTED, versioned release artifact (D-B-1): the gateway image must
 * build reproducibly from its own repo, and a knowledge change must therefore be a deliberate
 * re-export to a new `km_set` rather than something that silently rides along.
 *
 * ══ WHAT THIS IS FOR ══
 * OpenCDS supplies EXECUTION + standards packaging over clinician-signed knowledge. It never
 * introduces clinical knowledge of its own. Every KM mirrors `engine.js`; the gateway's answer is
 * therefore CORROBORATION when it agrees and a DEFECT SIGNAL IN ONE OF THE TWO EXECUTORS when it does
 * not. That is what Phase D's A/B parity exists to measure — and it only measures anything if both
 * executors run the same records under the same identity.
 *
 * ══ THE FOUR GATES, IN ORDER — allowlist FIRST, and sign-off is never sufficient ══
 *   1. ALLOWLIST (F5). Only the 8 capabilities an engine accessor actually reads may become executable
 *      knowledge. Everything else is excluded REGARDLESS of attestation state. This is first
 *      deliberately: `international-dose-guidance` (12 US/EU label doses) is excluded today only
 *      because it happens to be unsigned — an incidental property, and its own attestation says
 *      clinical sign-off "is NOT required". A sign-off-only filter would admit 12 foreign doses into
 *      an executable KM the day that flag flips. Australian context is a hard limit; it does not get
 *      to depend on an accident.
 *   2. SIGN-OFF. Necessary, never sufficient. Dataset-level `attestation.clinical_sign_off === true`.
 *   3. PROVENANCE (abort, not skip). Re-compute `checksumRecords(records)` with breath-ezy's OWN
 *      function and assert it equals the stored seal. Drift means the datastore moved since sign-off,
 *      so the signature no longer covers the bytes — the export ABORTS rather than shipping knowledge
 *      whose attestation has come unstuck. (R-46's lesson, made load-bearing.)
 *   4. PER-RECORD. Drop any record whose `provenance.review_status !== "approved"`. Per-record is
 *      authoritative over the dataset flag.
 * Every exclusion is written to `manifest.excluded[]`. A silent drop is indistinguishable from a bug.
 *
 * ══ WHY THE CHECKSUM FUNCTION IS IMPORTED, NOT RE-IMPLEMENTED ══
 * `checksumRecords` is dynamically imported from the checkout under audit. Re-implementing a canonical
 * -JSON form for a safety artifact is a silent-divergence hazard: the copy agrees until the day it
 * doesn't, and then a real tamper reads as a false alarm (or worse, a real seal reads as intact). The
 * same reasoning is why the Java KM verifies `file_sha256` over raw bytes instead of re-deriving the
 * record form — see the manifest's two-layer integrity note.
 *
 * ══ IDENTITY: THE CODE IS A SIDECAR, AND IT IS GATED (B0b, F5) ══
 * Records are exported BYTE-PURE and stay keyed exactly as signed — and they are not keyed uniformly
 * (`ingredient`, or a `subject`/`object` pair, or a `group`/`members[]` set). So the RxCUI does not
 * re-key anything. It rides as a separate sidecar (`kb/index-identity.json`) mapping code → canonical
 * name; the KM resolves an incoming code to a name and then looks up the name-keyed records. The
 * gateway therefore never RESOLVES identity — it matches a key the pipeline already settled. A second,
 * divergent canonicaliser inside OpenCDS is the E6 defect with extra steps, and F5 refuses it.
 *
 * The sidecar is built by asking the checkout's OWN `identityCode()` — the same gated authority the
 * pipeline asks. It returns null while the drug vocabulary is unsigned, so TODAY the sidecar is EMPTY
 * and `key_policy.rxcui_active` is false: the KB is name-keyed and B0's canonicalisation is what makes
 * that correct. An unsigned identity map may BLOCK, but it must never STEER. When a clinician signs,
 * a re-export to `fl30-kb:v2` populates it. The mechanism is built and tested against both states now,
 * rather than retrofitted under staging pressure later.
 *
 * Usage:
 *   node tools/export-fl30-kb.mjs --datastore ../breath-ezy [--out kb] [--dry-run]
 */
import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, readdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { execFileSync } from "node:child_process";

/**
 * The KM set this bundle answers to. MUST equal `DEFAULT_KM_SET` in breath-ezy's
 * `cds-adapter/opencds-client.js` — the client cross-checks it on every response, so a mismatch means
 * the gateway is executing knowledge the client did not ask for. Bumped only by a deliberate
 * re-export (D-B-1); the checked-in value is asserted by the test suite.
 *
 * ══ v1 → v2 (2026-07-15): the identity sidecar went live ══
 * v1 was exported while the drug vocabulary was UNSIGNED, so `identityCode()` returned null for every
 * drug: the sidecar was empty, `rxcui_active` was false, and the KB matched by name. KL signed the
 * vocabulary on 2026-07-15 and the same export now yields 522 codes.
 *
 * That is a KNOWLEDGE CHANGE — it changes how a KM resolves which drug a request is about — so it
 * gets a new km_set rather than silently riding along inside v1. The bump is the point: the client
 * cross-checks the version on every response, so a gateway still serving v1 to a v2 client
 * BLOCKS (BLOCKED_NO_PROOF) instead of quietly answering from knowledge nobody asked for. Both
 * directions of the transition fail safe, which is why this can be bumped before any gateway is
 * deployed.
 */
export const KM_SET = "fl30-kb:v2";

/**
 * F5 — the capability allowlist. Exactly the 8 capabilities backed by an `engine.js` accessor, i.e.
 * the only knowledge a KM could mirror. `age_appropriateness_check` is pure age logic and needs no KB.
 * `route_appropriateness_check` is in the frozen enum but the engine implements it ZERO times (F4), so
 * a route KM would have nothing to mirror — that would be OpenCDS INTRODUCING knowledge, which the
 * gateway's whole premise forbids.
 *
 * Excluded here by design, and NOT because of their signing state:
 *   international_dose_guidance — foreign labels; Australian-context hard limit (see gate 1 above)
 *   drug_vocabulary, ingredient_identity — identity, not clinical execution (see sidecar note above)
 * Plus formulations / pbs (unsigned) and the signed reference-only capabilities no accessor reads.
 */
export const EXPORTABLE_CAPABILITIES = Object.freeze({
  allergy: { file: "allergy-cross-reactivity.json", check_id: "allergy_check", accessor: "getAllergyGroup", subject_keys: ["group"], name_keys: ["members"] },
  interactions: { file: "drug-interactions.json", check_id: "interaction_check", accessor: "getInteractions", subject_keys: ["subject", "object"], name_keys: [] },
  renal: { file: "renal-rules.json", check_id: "renal_dosing_check", accessor: "getRenalRule", subject_keys: ["ingredient"], name_keys: [] },
  nti: { file: "nti-register.json", check_id: "nti_check", accessor: "getNti", subject_keys: ["ingredient"], name_keys: [] },
  scheduling: { file: "au-scheduling.json", check_id: "schedule_8_check", accessor: "getSchedule", subject_keys: ["ingredient"], name_keys: [] },
  pregnancy_risk: { file: "pregnancy-risk.json", check_id: "pregnancy_check", accessor: "getPregnancyRisk", subject_keys: ["subject"], name_keys: [] },
  hepatic: { file: "hepatic.json", check_id: "hepatic_check", accessor: "getHepatic", subject_keys: ["ingredient"], name_keys: [] },
  // F3 FLIPPED. The dose KM's two objections are both dead: the source exists (451 clinician-attested
  // records) and the consumer exists (the E3 evidence plane renders a `cds_dose_candidate`). Its output
  // is ADVISORY — a second independent executor's opinion shown beside the AU dose, never
  // `PharmCheck.dose_guidance`. `assertNoAdvisoryInDose()` throws if that is ever violated.
  dose_guidance: { file: "dose-guidance.json", check_id: null, accessor: "getDoseGuidance", subject_keys: ["ingredient"], name_keys: [] },
});

const sha256 = (s) => createHash("sha256").update(s).digest("hex");
const canonJson = (o) => JSON.stringify(o, null, 2) + "\n";

/** Collect every drug name a record is keyed on, so the identity sidecar can cover the KB. */
function subjectsOf(record, spec) {
  const out = [];
  for (const k of spec.subject_keys) if (typeof record[k] === "string") out.push(record[k].toLowerCase());
  for (const k of spec.name_keys) for (const m of record[k] || []) if (typeof m === "string") out.push(m.toLowerCase());
  return out;
}

/**
 * Build the bundle. Pure over `{datastoreDir, checksumRecords, identityCode, sourceCommit}` so the
 * fixtures can drive every gate — including the ones that must ABORT — without a repo on disk.
 */
export function buildBundle({ datastoreDir, checksumRecords, identityCode = () => null, sourceCommit = null, readFile = (p) => readFileSync(p, "utf8"), listCapabilities = null }) {
  const capabilities = [];
  const excluded = [];
  const files = {};
  const nameOnly = new Set();
  const codeIndex = {};

  // Gate 1 runs over EVERY dataset present, not just the allowlisted ones, so that an excluded
  // capability is RECORDED as excluded rather than merely absent. "Absent" and "filtered" look
  // identical in an artifact, and only one of them is evidence.
  const present = listCapabilities || defaultListCapabilities(datastoreDir, readFile);
  for (const { capability, file } of present) {
    if (!(capability in EXPORTABLE_CAPABILITIES)) {
      excluded.push({ capability, file, reason: "not_executable_knowledge", detail: exclusionDetail(capability) });
      continue;
    }
  }

  for (const [capability, spec] of Object.entries(EXPORTABLE_CAPABILITIES)) {
    const path = join(datastoreDir, spec.file);
    const ds = JSON.parse(readFile(path));

    // Gate 2 — sign-off. Necessary, never sufficient (gate 1 already ran).
    if (ds.attestation?.clinical_sign_off !== true) {
      excluded.push({ capability, file: spec.file, reason: "no_clinical_sign_off", detail: "dataset attestation.clinical_sign_off is not true; unsigned knowledge cannot be executed" });
      continue;
    }

    // Gate 3 — provenance. ABORT, never skip: a broken seal means the clinician's signature no longer
    // covers these bytes, and quietly dropping the dataset would let a tamper look like a filter.
    const actual = checksumRecords(ds.records);
    if (actual !== ds.records_checksum) {
      throw new Error(
        `export-fl30-kb: ABORT — provenance drift in ${spec.file}.\n` +
        `  stored records_checksum: ${ds.records_checksum}\n` +
        `  recomputed            : ${actual}\n` +
        `The datastore has moved since sign-off, so the attestation no longer covers these records. ` +
        `Re-seal in breath-ezy (npm run pharm:reseal) and re-export. Refusing to ship knowledge whose signature has come unstuck.`
      );
    }

    // Gate 4 — per-record review status. Per-record is authoritative over the dataset flag.
    const approved = ds.records.filter((r) => r?.provenance?.review_status === "approved");
    const dropped = ds.records.length - approved.length;
    if (dropped > 0) {
      excluded.push({ capability, file: spec.file, reason: "records_not_approved", detail: `${dropped} record(s) dropped: provenance.review_status !== "approved"`, records_dropped: dropped });
    }
    if (approved.length === 0) {
      excluded.push({ capability, file: spec.file, reason: "no_approved_records", detail: "every record failed the per-record review gate" });
      continue;
    }

    // Records ride BYTE-PURE, exactly as signed. The identity code is a sidecar (see header) — it is
    // never injected into a signed record.
    const outFile = `${capability}.json`;
    const body = canonJson({ km_set: KM_SET, capability, dataset_version: ds.dataset_version, records: approved });
    files[outFile] = body;

    for (const r of approved) for (const s of subjectsOf(r, spec)) {
      const code = identityCode(s);
      if (code) codeIndex[String(code)] = s; else nameOnly.add(s);
    }

    capabilities.push({
      capability,
      check_id: spec.check_id,
      mirrors_accessor: spec.accessor,
      file: outFile,
      dataset_version: ds.dataset_version,
      records: approved.length,
      records_checksum: ds.records_checksum,
      file_sha256: sha256(body),
      attestation: { clinical_sign_off: true, regulatory_sign_off: ds.attestation?.regulatory_sign_off === true, reviewer_id: ds.attestation?.reviewer_id ?? null },
    });
  }

  // The identity sidecar. EMPTY while the vocabulary is unsigned — `identityCode()` is the gated
  // authority and returns null, so nothing here can steer. `rxcui_active` is DERIVED, never asserted:
  // see the mechanical refusal below.
  const identityBody = canonJson({ km_set: KM_SET, signed: Object.keys(codeIndex).length > 0, rxcui_to_canonical_name: codeIndex });
  files["index-identity.json"] = identityBody;

  const rxcuiActive = Object.keys(codeIndex).length > 0;
  const manifest = {
    km_set: KM_SET,
    source_repo: "kenleefreo/breath-ezy",
    source_commit: sourceCommit,
    exported_by: "tools/export-fl30-kb.mjs (FL-34 Phase B / B1)",
    integrity: {
      note: "Two layers, deliberately. PROVENANCE (Node, export-time): records_checksum re-computed with breath-ezy's own checksumRecords and asserted — drift aborts the export, proving the datastore has not moved since sign-off. TRANSPORT (Java, load-time): the KM verifies file_sha256 over the exported bytes, so Java never re-implements the canonical-JSON record form; a re-implementation is a silent-divergence hazard for a safety artifact. Mismatch → the KM fails closed (all checks NOT_RUN), never a default PASS.",
      provenance_layer: "checksumRecords (imported from the source checkout, never re-implemented)",
      transport_layer: "file_sha256 over exported bytes",
    },
    key_policy: {
      primary: "rxcui",
      fallback: "canonical_name",
      rxcui_active: rxcuiActive,
      note: rxcuiActive
        ? "The identity sidecar is populated from a SIGNED source. The KM resolves an incoming rxnorm_code to a canonical name, then looks up the name-keyed records. It never resolves identity itself."
        : "The drug vocabulary is UNSIGNED, so the checkout's own identityCode() returns null and the sidecar is EMPTY. The KB is name-keyed and B0's canonicalisation is what makes that correct — the pipeline settles identity once, before both executors. An unsigned identity map may BLOCK but must never STEER. Clinician sign-off + a re-export to fl30-kb:v2 populates this.",
    },
    identity_sidecar: { file: "index-identity.json", file_sha256: sha256(identityBody), codes: Object.keys(codeIndex).length, signed: rxcuiActive },
    // The names with no code are NAMED, not assumed away. Today that is every subject in the KB
    // (unsigned vocabulary). After sign-off it becomes the ~14 real gaps: combination products
    // (trimethoprim with sulfamethoxazole) and classes (oestrogens, ferrous salts) that RxNorm models
    // as multi-ingredient concepts a single-name lookup does not resolve. Those carry real signed
    // doses — which is exactly why a code-ONLY contract fails and the name must still ride.
    name_only: [...nameOnly].sort(),
    capabilities,
    excluded,
  };
  files["manifest.json"] = canonJson(manifest);
  return { manifest, files };
}

function exclusionDetail(capability) {
  if (capability === "international_dose_guidance")
    return "F5 — US/EU label doses. Australian-context hard limit: a foreign label must never become executable AU knowledge. Excluded by the ALLOWLIST, not by its signing state — its own attestation says clinical sign-off is not required, so a sign-off-only filter would admit it the day that flag flips.";
  if (capability === "drug_vocabulary" || capability === "ingredient_identity")
    return "F5 — identity, not clinical execution. Executing this inside OpenCDS would give the gateway a second, divergent canonicaliser — the E6 defect with extra steps. Identity is settled ONCE by the pipeline (B0/B0b); the gateway only matches the key it is handed.";
  return "no engine accessor reads this capability, so no KM mirrors it — exporting it would let OpenCDS introduce knowledge the engine does not have.";
}

/** The IO edge for gate 1's survey. Injectable via `listCapabilities` so fixtures stay disk-free. */
function defaultListCapabilities(datastoreDir, readFile) {
  const out = [];
  let entries = []; try { entries = readdirSync(datastoreDir); } catch { return out; }
  for (const file of entries) {
    if (!file.endsWith(".json")) continue;
    let d; try { d = JSON.parse(readFile(join(datastoreDir, file))); } catch { continue; }
    if (typeof d?.capability === "string" && Array.isArray(d?.records)) out.push({ capability: d.capability, file });
  }
  return out;
}

async function main(argv) {
  const arg = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
  const dryRun = argv.includes("--dry-run");
  const datastoreRepo = resolve(arg("--datastore", "../breath-ezy"));
  const outDir = resolve(arg("--out", "kb"));
  const datastoreDir = join(datastoreRepo, "mcp", "servers", "pharmacology", "data");

  if (!existsSync(datastoreDir)) throw new Error(`export-fl30-kb: no datastore at ${datastoreDir} — pass --datastore <path to a breath-ezy checkout>`);

  // The canonical checksum function, from the checkout under audit. Never re-implemented (see header).
  const { checksumRecords } = await import(pathToFileURL(join(datastoreRepo, "scripts", "pharm-author.mjs")).href);
  // The gated identity authority — the same one the pipeline asks. Returns null while unsigned.
  const { SyntheticSelfDevelopedSource } = await import(pathToFileURL(join(datastoreRepo, "mcp", "servers", "pharmacology", "sources", "pharm-data-source.js")).href);
  const source = new SyntheticSelfDevelopedSource();

  let sourceCommit = null;
  try { sourceCommit = execFileSync("git", ["-C", datastoreRepo, "rev-parse", "HEAD"], { encoding: "utf8" }).trim(); } catch { /* not a checkout; recorded as null */ }
  if (!sourceCommit) throw new Error("export-fl30-kb: could not read the datastore's git commit — source_commit is the audit link back to the signed records and is not optional.");

  const { manifest, files } = buildBundle({ datastoreDir, checksumRecords, identityCode: (d) => source.identityCode(d), sourceCommit });

  console.log(`export-fl30-kb: km_set=${manifest.km_set} source_commit=${manifest.source_commit.slice(0, 8)}`);
  for (const c of manifest.capabilities) console.log(`  + ${c.capability.padEnd(16)} ${String(c.records).padStart(6)} records  ${c.file_sha256.slice(0, 12)}  (mirrors ${c.mirrors_accessor})`);
  for (const e of manifest.excluded) console.log(`  - ${String(e.capability).padEnd(16)} EXCLUDED: ${e.reason}`);
  console.log(`export-fl30-kb: identity sidecar — ${manifest.identity_sidecar.codes} code(s), rxcui_active=${manifest.key_policy.rxcui_active}; ${manifest.name_only.length} name-only subject(s)`);

  if (dryRun) { console.log("export-fl30-kb: --dry-run, not writing"); return; }
  rmSync(outDir, { recursive: true, force: true });
  mkdirSync(outDir, { recursive: true });
  for (const [name, body] of Object.entries(files)) writeFileSync(join(outDir, name), body);
  console.log(`export-fl30-kb: wrote ${Object.keys(files).length} file(s) to ${outDir}`);
}

if (import.meta.url === `file://${process.argv[1]}`) main(process.argv).catch((e) => { console.error(e.message); process.exit(1); });
