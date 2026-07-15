/**
 * Contract test — the Phase C shim's mapping.
 *
 * THE SHIM SITS IN THE MIDDLE OF EVERY SAFETY PROPERTY THE CLIENT HAS. It is a dumb mapper, and the
 * ways a dumb mapper kills someone are all quiet:
 *
 *   - it ECHOES the KB version, and the client's cross-check passes on a lie (F-C3);
 *   - it DROPS a check that could not be mapped, and the client never learns the check did not run;
 *   - it PASSES a check whose KM was unreachable;
 *   - it INVENTS content to fill a required field.
 *
 * Every one of those is a test below, and the F-C3 rule is proven against the REAL client rather than
 * a mock — because "the cross-check works" is a claim about the client, not about this file.
 *
 * Run: node --test shim/*.test.mjs
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { evaluate } from "./server.mjs";
import { mergeResults, cardToVerdict, cardToDose, kmSetOf, ALWAYS_EMITS, SERVICE_FOR, UNKNOWN_KM_SET } from "./map.mjs";

const KM = "fl30-kb:v2";
const DEFAULTS = ["allergy_check", "interaction_check", "renal_dosing_check", "nti_check", "age_appropriateness_check"];
const req = (checks = DEFAULTS) => ({
  request_id: "req-00000001",
  drug: { drug_name: "warfarin", drug_class: "anticoagulant" },
  resolved_facts: { allergens: [], current_medications: ["amiodarone"], egfr_ml_min: 90 },
  checks_requested: checks,
  knowledge_module_set: KM,
  mode: "mock",
});

/** A card exactly as the Java KM builds it. */
const card = (checkId, status, { severity, reason, flags, kmSet = KM } = {}) => ({
  summary: checkId,
  indicator: status === "HARD_FAIL" ? "critical" : status === "PASS" ? "info" : "warning",
  detail: reason || status,
  extension: {
    "breathezy.verdict": { check_id: checkId, status, ...(severity ? { severity } : {}), ...(reason ? { reason } : {}), ...(flags ? { flags } : {}) },
    km_set: kmSet,
  },
});
const doseCard = (dose, kmSet = KM) => ({ summary: "dose_candidate", extension: { "breathezy.dose_candidate": dose, advisory: true, km_set: kmSet } });
const ok = (check_id, cards) => ({ check_id, ok: true, cards });

// ── F-C3: the km_set comes from the CARDS, never the request ─────────────────────────────────────

test("F-C3 — knowledge_module_set is read from the CARDS, not echoed from the request", () => {
  // The gateway is really running v1. The request asks for v2. The shim must report WHAT RAN.
  const r = mergeResults(req(["allergy_check"]), [ok("allergy_check", [card("allergy_check", "PASS", { kmSet: "fl30-kb:v1" })])], { ok: false, error: "x" });
  assert.equal(r.knowledge_module_set, "fl30-kb:v1",
    "the shim must report the version the CARDS claim. Echoing the request would make the client's cross-check tautological — it could never fail, and a stale gateway would answer PASS on a lie.");
});

test("F-C3 — and the REAL client then blocks. Proven end-to-end, not asserted.", async () => {
  // This is the point of the rule, so it is tested against the actual client rather than a mock of it.
  const { queryOpenCds } = await import("/Users/sleekjazz/Documents/CLAUDE/mcp/servers/pharmacology/cds-adapter/opencds-client.js");
  const intent = {
    intent_id: "i-000001", session_ref: "enc-000001", intent_type: "new_prescription",
    drug_intent: { drug_name: "warfarin", drug_class: "anticoagulant" },
    patient_facts_ref: {}, clinical_context: { patient_age_years: 60 }, mode: "mock",
  };
  // A shim wired to a gateway serving STALE v1 knowledge.
  const staleGateway = async (_url, opts) => {
    const request = JSON.parse(opts.body);
    const body = mergeResults(request, request.checks_requested.map((c) => ok(c, [card(c, "PASS", { kmSet: "fl30-kb:v1" })])), { ok: false, error: "n/a" });
    return { ok: true, json: async () => body };
  };
  const res = await queryOpenCds(intent, {}, { endpoint: "https://gw.example.invalid/x", fetchImpl: staleGateway, knowledgeModuleSet: "fl30-kb:v2" });
  assert.equal(res.verdict, "BLOCKED_NO_PROOF",
    "a v2 client against a v1 gateway MUST block. If this ever passes, the shim has started lying about what ran.");
  assert.match(res.reason, /KB version mismatch/);
});

test("F-C3 — no cards at all → 'unknown', which can never match, so the client blocks", () => {
  const r = mergeResults(req(["allergy_check"]), [{ check_id: "allergy_check", ok: false, error: "connection refused" }], { ok: false, error: "x" });
  assert.equal(r.knowledge_module_set, UNKNOWN_KM_SET,
    "if every KM failed, the gateway cannot say what it ran — and must not guess. 'unknown' never matches, so the client blocks with a legible reason.");
  assert.equal(r.check_verdicts[0].status, "NOT_RUN");
});

test("F-C3 — cards disagreeing on km_set poison the WHOLE response", () => {
  const r = mergeResults(req(["allergy_check", "interaction_check"]), [
    ok("allergy_check", [card("allergy_check", "PASS", { kmSet: "fl30-kb:v1" })]),
    ok("interaction_check", [card("interaction_check", "PASS", { kmSet: "fl30-kb:v2" })]),
  ], { ok: true, cards: [doseCard({ safe_dose_range: "5 mg" })] });
  assert.equal(r.knowledge_module_set, UNKNOWN_KM_SET);
  assert.ok(r.check_verdicts.every((v) => v.status === "NOT_RUN"),
    "an inconsistent deploy means no single version is true, so NO verdict from it can be trusted — not just the odd one out");
  assert.equal(r.dose_candidate, undefined, "and certainly no dose");
});

// ── F-C4: the four-and-four split ────────────────────────────────────────────────────────────────

test("F-C4 — zero cards from an ALWAYS-emits check is a BUG → NOT_RUN, never a drop", () => {
  // EVERY CASE HERE PAIRS THE SILENT CHECK WITH A CHECK THAT ANSWERS. The first version of this test
  // asked for the silent check ALONE — which left check_verdicts empty, which tripped the
  // empty-verdicts floor, which pushed a NOT_RUN of its own. The test passed for the wrong reason and
  // proved nothing: deleting the ALWAYS_EMITS branch entirely left it green. The floor was doing the
  // work. Pairing keeps the array non-empty so the floor stays out of it and the branch is on its own.
  for (const checkId of ALWAYS_EMITS) {
    const r = mergeResults(req([checkId, "hepatic_check"]), [
      ok(checkId, []),                                            // the ALWAYS check says nothing — a bug
      ok("hepatic_check", [card("hepatic_check", "PASS")]),       // …while something else answers
    ], { ok: false, error: "x" });

    const v = r.check_verdicts.find((x) => x.check_id === checkId);
    assert.ok(v, `${checkId}: the check must not VANISH — engine.js always produces a verdict for it, so silence is a broken KM, and dropping it means the client never learns the check did not run`);
    assert.equal(v.status, "NOT_RUN", `${checkId}: silence from an always-emits check must be NOT_RUN — reporting PASS would be worse still`);
    assert.equal(r.check_verdicts.length, 2, "the floor must not be involved: the sibling check answered, so the array was never empty");
  }
});

test("F-C4 — …and the ALWAYS/CONDITIONAL split is what separates them, side by side", () => {
  // Same call, same silence, opposite meanings. This is the whole rule in one assertion.
  const r = mergeResults(req(["allergy_check", "nti_check"]), [
    ok("allergy_check", []),   // ALWAYS → a bug
    ok("nti_check", []),       // CONDITIONAL → legitimately not applicable
  ], { ok: true, cards: [doseCard({ safe_dose_range: "5 mg" })] });   // a dose card keeps km_set resolvable

  assert.equal(r.check_verdicts.length, 1, "exactly one of the two silences is a finding");
  assert.equal(r.check_verdicts[0].check_id, "allergy_check");
  assert.equal(r.check_verdicts[0].status, "NOT_RUN");
});

test("F-C4 — zero cards from a CONDITIONAL check is 'not applicable' → no verdict, mirroring engine.js", () => {
  // A non-NTI drug gets no nti_check from the engine at all. Emitting NOT_RUN would block every
  // prescription on a check that could not have applied — over-triage, which teaches people to override.
  const r = mergeResults(req(["allergy_check", "nti_check"]), [
    ok("allergy_check", [card("allergy_check", "PASS")]),
    ok("nti_check", []),
  ], { ok: false, error: "x" });
  assert.equal(r.check_verdicts.length, 1);
  assert.equal(r.check_verdicts[0].check_id, "allergy_check");
  assert.ok(!r.check_verdicts.some((v) => v.check_id === "nti_check"), "a non-applicable check emits no verdict, exactly as the engine emits none");
});

// ── never a PASS, never a drop ───────────────────────────────────────────────────────────────────

test("an unreachable KM is NOT_RUN — never dropped, never passed", () => {
  const r = mergeResults(req(["allergy_check"]), [{ check_id: "allergy_check", ok: false, error: "timeout after 5000ms" }], { ok: false, error: "x" });
  assert.equal(r.check_verdicts[0].status, "NOT_RUN");
  assert.match(r.check_verdicts[0].reason, /timeout/, "the reason must name the cause — an operator cannot fix 'something went wrong'");
});

test("a card answering for the WRONG check is refused", () => {
  const r = mergeResults(req(["allergy_check"]), [ok("allergy_check", [card("interaction_check", "PASS")])], { ok: false, error: "x" });
  assert.equal(r.check_verdicts[0].status, "NOT_RUN");
  assert.match(r.check_verdicts[0].reason, /answered for 'interaction_check'/);
});

test("an off-enum status is refused rather than passed through", () => {
  const bad = card("allergy_check", "PASS");
  bad.extension["breathezy.verdict"].status = "PROBABLY_FINE";
  const r = mergeResults(req(["allergy_check"]), [ok("allergy_check", [bad])], { ok: false, error: "x" });
  assert.equal(r.check_verdicts[0].status, "NOT_RUN",
    "the client's contract is strict: an off-enum status would fail the WHOLE response, blacking out every other check. It degrades here instead.");
});

test("a card with no structured verdict is refused — the PROSE is never the machine path", () => {
  const r = mergeResults(req(["allergy_check"]), [ok("allergy_check", [{ summary: "allergy_check", detail: "looks fine to me" }])], { ok: false, error: "x" });
  assert.equal(r.check_verdicts[0].status, "NOT_RUN");
});

test("route_appropriateness_check has NO knowledge module → NOT_RUN, never an invented PASS", () => {
  // It is in the frozen check_id enum, but engine.js implements it ZERO times (F4), so no KM mirrors
  // it. Saying NOT_RUN is the truth: nothing ran.
  assert.equal(SERVICE_FOR.route_appropriateness_check, undefined);
  const r = mergeResults(req(["route_appropriateness_check"]), [ok("route_appropriateness_check", [])], { ok: false, error: "x" });
  assert.equal(r.check_verdicts[0].status, "NOT_RUN");
  assert.match(r.check_verdicts[0].reason, /no knowledge module implements this check/);
});

// ── flags ────────────────────────────────────────────────────────────────────────────────────────

test("every flag survives the mapping, one per finding", () => {
  const flags = [
    { flag_type: "interaction_severe", severity: "critical", description: "warfarin + amiodarone: CYP2C9", drug_a: "warfarin", drug_b: "amiodarone" },
    { flag_type: "interaction_moderate", severity: "moderate", description: "warfarin + aspirin: bleeding", drug_a: "warfarin", drug_b: "aspirin" },
  ];
  const r = mergeResults(req(["interaction_check"]), [ok("interaction_check", [card("interaction_check", "HARD_FAIL", { severity: "critical", reason: "interaction(s) detected", flags })])], { ok: false, error: "x" });
  assert.equal(r.flags.length, 2, "TWO findings must reach the clinician as TWO flags — the client filters flags[] to build the interaction list they read");
  assert.equal(r.flags[1].severity, "moderate", "a moderate finding keeps its own severity even though a critical sibling drove the verdict");
  assert.deepEqual(r.flags[0], flags[0]);
});

test("a malformed flag is dropped, and the rest of the response survives", () => {
  const flags = [
    { flag_type: "not_a_real_flag_type", severity: "critical", description: "x" },
    { flag_type: "interaction_severe", severity: "critical", description: "warfarin + amiodarone" },
    { flag_type: "interaction_severe", severity: "critical" },  // no description — required on the wire
  ];
  const r = mergeResults(req(["interaction_check"]), [ok("interaction_check", [card("interaction_check", "HARD_FAIL", { flags })])], { ok: false, error: "x" });
  assert.equal(r.flags.length, 1, "off-enum and incomplete flags cannot be repaired and must not ride — one bad flag would fail the whole response and black out every check");
  assert.equal(r.check_verdicts[0].status, "HARD_FAIL", "…and the VERDICT still stands: dropping an unrenderable flag must not soften the finding");
});

// ── the dose ─────────────────────────────────────────────────────────────────────────────────────

test("the dose rides only in the keys the LOCKED contract allows", () => {
  const d = cardToDose(doseCard({ safe_dose_range: "5 mg daily", pbs_item_code: "1234K", provenance: { reviewed_by: "KL" } }));
  assert.deepEqual(d, { safe_dose_range: "5 mg daily" },
    "pbs_item_code and provenance are not on the locked OpenCdsDoseCandidateSchema — carrying either would fail the WHOLE response (F-C1), and the clinician's signature must never ride inside an advisory dose");
});

test("the shim NEVER decides whether a dose is allowed — it maps what the KM gave", () => {
  // Even against a HARD_FAIL, the shim reports the dose. Gating is the CLIENT's job (PASS/WARN only);
  // a shim that decided would be composing, which is exactly what it must not do.
  const r = mergeResults(req(["allergy_check"]), [ok("allergy_check", [card("allergy_check", "HARD_FAIL", { severity: "critical", reason: "cross-reactivity" })])],
    { ok: true, cards: [doseCard({ safe_dose_range: "5 mg daily" })] });
  assert.equal(r.dose_candidate.safe_dose_range, "5 mg daily", "the shim maps it…");
  assert.equal(r.check_verdicts[0].status, "HARD_FAIL", "…and the client is what drops it, on the composed verdict");
});

test("no dose card → no dose_candidate at all", () => {
  const r = mergeResults(req(["allergy_check"]), [ok("allergy_check", [card("allergy_check", "PASS")])], { ok: true, cards: [] });
  assert.equal(r.dose_candidate, undefined, "absent is absent — never an empty object the client would have to interpret");
});

// ── the fan-out ──────────────────────────────────────────────────────────────────────────────────

test("evaluate() calls one service per check PLUS the dose, unconditionally", async () => {
  const called = [];
  const fake = async (service) => { called.push(service); return { ok: true, cards: [] }; };
  await evaluate(req(["allergy_check", "nti_check"]), fake);
  assert.deepEqual(called.sort(), ["fl30-allergy-check", "fl30-dose-candidate", "fl30-nti-check"].sort(),
    "the dose KM is called every time (D-C-4) — the client gates it, not the shim");
});

test("evaluate() fans out in PARALLEL", async () => {
  let inFlight = 0, peak = 0;
  const fake = async () => {
    peak = Math.max(peak, ++inFlight);
    await new Promise((r) => setTimeout(r, 20));
    inFlight--;
    return { ok: true, cards: [] };
  };
  await evaluate(req(DEFAULTS), fake);
  assert.ok(peak > 1, `the checks are independent; 6 sequential round-trips would make latency the reason someone turns the gateway off (peak concurrency ${peak})`);
});

test("one KM failing does not take the others down", async () => {
  const fake = async (service) => (service === "fl30-allergy-check"
    ? { ok: false, error: "connection refused" }
    : { ok: true, cards: [card(service.replace("fl30-", "").replace(/-/g, "_").replace("renal_dosing", "renal_dosing"), "PASS")] });
  const r = await evaluate(req(["allergy_check", "interaction_check"]), fake);
  const byId = Object.fromEntries(r.check_verdicts.map((v) => [v.check_id, v.status]));
  assert.equal(byId.allergy_check, "NOT_RUN");
  assert.equal(byId.interaction_check, "PASS", "an unreachable KM must not black out the checks that answered");
});

// ── the contract's own floor ─────────────────────────────────────────────────────────────────────

test("check_verdicts is never empty — the wire requires at least one", () => {
  // Every requested check was conditional and legitimately silent. "No verdicts" is not representable
  // on this wire, so the shim blocks with a reason rather than emitting an invalid response.
  const r = mergeResults(req(["nti_check", "hepatic_check"]), [ok("nti_check", []), ok("hepatic_check", [])], { ok: false, error: "x" });
  assert.ok(r.check_verdicts.length >= 1, "an empty verdict array would fail the client's validation for a confusing reason");
  assert.ok(r.check_verdicts.every((v) => v.status === "NOT_RUN"));
  assert.match(r.check_verdicts[0].reason, /cannot express an empty verdict set/);
});

test("the engine name says what actually ran — not the A1 DSS/vMR assumption", () => {
  const r = mergeResults(req(["allergy_check"]), [ok("allergy_check", [card("allergy_check", "PASS")])], { ok: false, error: "x" });
  assert.equal(r.engine, "opencds-cds-hooks-r4", "Phase A settled the transport as CDS Hooks R4; this is the only place that emits the name, so it is the only place it can be wrong");
});

test("kmSetOf accepts both the bare extension and the Java Extension wrapper", () => {
  // Card.setExtension(Object) wraps the value in org.opencds.hooks.model.Extension; depending on the
  // serialiser that can surface as {value:{...}}. Both are accepted; anything else is unmappable.
  assert.equal(kmSetOf({ extension: { km_set: KM } }), KM);
  assert.equal(kmSetOf({ extension: { value: { km_set: KM } } }), KM);
  assert.equal(kmSetOf({ extension: {} }), null);
  assert.equal(kmSetOf({}), null);
});
