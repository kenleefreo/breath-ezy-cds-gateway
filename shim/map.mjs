/**
 * The Phase C shim's MAPPING — locked JSON ↔ CDS Hooks R4.
 *
 * Pure by design: every function here is a total function over plain data, so all of the fail-safes
 * below are provable without a container, a network, or a running Tomcat. The IO edge (`server.mjs`)
 * does nothing but fan out and call these.
 *
 * ══ WHAT THIS COMPONENT IS ══
 * A DUMB MAPPER. It translates and nothing else. It does not compose an overall verdict, does not
 * decide continuation, does not gate a dose, and never invents content. Composition and the firewall
 * live upstream in `breath-ezy` behind a frozen contract that re-applies every hard rule regardless of
 * what this gateway says. Anything this shim cannot map becomes NOT_RUN — never a drop, never a PASS.
 *
 * ══ THE ONE RULE THAT IS NOT NEGOTIABLE (F-C3) ══
 * `knowledge_module_set` is read from the KMs' OWN CARDS, never echoed from the request.
 *
 * The client's guard is `if (response.knowledge_module_set !== requested) → BLOCKED_NO_PROOF`. If this
 * shim sourced that value from the request, the comparison would be tautological and could NEVER fail
 * — a gateway running stale v1 knowledge against a v2 client would answer PASS on a lie. Demonstrated
 * before this file existed, and the register's own build_action ("Echoes km_set") prescribed exactly
 * that. The card's `km_set` is the gateway's own claim about what it loaded, and it is a TRUE claim:
 * `Fl30KnowledgeBase` refuses to load a bundle whose km_set ≠ EXPECTED_KM_SET, and a failed-closed KM
 * emits only NOT_RUN, which blocks anyway.
 */

/** check_id → the CDS service that answers it. `route_appropriateness_check` has NO service: engine.js
 *  implements it zero times (F4), so no KM mirrors it and a request for it can only be NOT_RUN. */
export const SERVICE_FOR = Object.freeze({
  allergy_check: "fl30-allergy-check",
  interaction_check: "fl30-interaction-check",
  renal_dosing_check: "fl30-renal-dosing-check",
  nti_check: "fl30-nti-check",
  age_appropriateness_check: "fl30-age-appropriateness-check",
  schedule_8_check: "fl30-schedule-8-check",
  pregnancy_check: "fl30-pregnancy-check",
  hepatic_check: "fl30-hepatic-check",
});

/** The dose KM is not a check and has no check_id. Called unconditionally; the CLIENT gates it. */
export const DOSE_SERVICE = "fl30-dose-candidate";

/**
 * F-C4 — the four-and-four split, read straight off `engine.js`.
 *
 * These four ALWAYS produce a verdict, whatever the drug. So zero cards from one of them is not
 * "not applicable" — it is A BUG, and it must surface as NOT_RUN rather than vanish.
 *
 * The other four (nti, schedule_8, pregnancy, hepatic) are conditional in the engine: a non-NTI drug
 * gets no nti_check at all. Zero cards there is the engine's own shape, mirrored, and emitting NOT_RUN
 * would block every prescription on a check that could not have applied — over-triage, which is what
 * teaches people to override.
 *
 * Conflating the two is how a check the client asked for silently disappears. D-B-2: never a drop.
 */
export const ALWAYS_EMITS = Object.freeze(new Set([
  "allergy_check", "interaction_check", "renal_dosing_check", "age_appropriateness_check",
]));

/** Reported when the gateway cannot say what it ran. Can never equal a real km_set, so the client's
 *  cross-check always blocks — which is the correct outcome and a legible one. */
export const UNKNOWN_KM_SET = "unknown";

const CHECK_STATUS = new Set(["PASS", "WARN", "HARD_FAIL", "NOT_RUN"]);
const SEVERITY = new Set(["critical", "moderate", "minor"]);
const FLAG_TYPES = new Set([
  "nti", "allergy_confirmed", "allergy_cross_reactivity", "interaction_severe", "interaction_moderate",
  "renal_adjustment_required", "renal_contraindicated", "hepatic_adjustment_required",
  "hepatic_contraindicated", "pregnancy_category_x", "pregnancy_category_d",
  "schedule_8_pdmp_required", "schedule_8_authority_required", "age_beers_criteria",
  "age_paediatric_weight_based", "route_not_achievable_in_setting",
  "stewardship_narrow_spectrum_preferred", "stewardship_culture_pending",
]);

/**
 * Build the CDS Hooks R4 request for one service. The KMs read `drug` + `resolved_facts` from context.
 *
 * ══ F-C7 — OpenCDS demands a "focal person id", and we have no patient to name ══
 * Without `context.patientId` the adapter rejects the call outright: HTTP 500, "No focal person id
 * (patient ID) found." Found the first time a real container was in the loop; no unit test could have
 * shown it, because the requirement lives in OpenCDS's adapter, not in our contract.
 *
 * But the locked `OpenCdsRequest` carries NO patient identifier, and that is deliberate — trust
 * boundary #4: the IHI and demographics stay inside the identity boundary, and everything downstream
 * works from encounter-scoped references. There is nothing here that names a person, by design, and
 * the fix must not invent one.
 *
 * So the focal person id is the REQUEST ID. It satisfies the adapter and asserts nothing about a
 * human being:
 *   - it is OPAQUE — no demographics, no IHI, nothing re-identifiable;
 *   - it is UNIQUE PER REQUEST, which matters: a fixed placeholder would make every consultation look
 *     like the same "patient" to OpenCDS, and any per-person state it kept would silently carry across
 *     encounters. A cross-patient leak is not a price worth paying for a tidier constant;
 *   - it is already the correlation key threaded through the pipeline, so an operator tracing a
 *     gateway call finds the same id they started with.
 *
 * It is NOT a patient identifier and must never be read as one. Our KMs never look at it.
 */
export function buildHookRequest(request, hookInstance) {
  return {
    hook: "order-sign",
    hookInstance,
    context: {
      // See F-C7 above: an opaque per-request token, NOT a person.
      patientId: request.request_id,
      drug: request.drug,
      resolved_facts: request.resolved_facts ?? {},
    },
  };
}

/** A NOT_RUN verdict — the shim's answer to every question it cannot answer honestly. */
const notRun = (check_id, reason) => ({ check_id, status: "NOT_RUN", reason });

/**
 * Unwrap a card's extension. `Card.setExtension(Object)` serialises the VALUE, but a Java
 * `Extension` wrapper may surface as `{value: {...}}` depending on the serialiser — so both shapes are
 * accepted. Anything else is unmappable, which means NOT_RUN, not a guess.
 */
function extensionOf(card) {
  const e = card?.extension;
  if (!e || typeof e !== "object") return null;
  if (e.value && typeof e.value === "object" && !Array.isArray(e.value)) return e.value;
  return e;
}

/** The km_set a card claims. Null when absent — the caller then cannot report a version. */
export function kmSetOf(card) {
  const ext = extensionOf(card);
  const v = ext?.km_set;
  return typeof v === "string" && v.length ? v : null;
}

/**
 * One card → one verdict (+ its flags), or a NOT_RUN when the card cannot be trusted.
 * Off-enum content is REFUSED rather than passed through: the client's contract is strict and a
 * malformed verdict would fail the whole response, so an unmappable card must degrade here.
 */
export function cardToVerdict(card, checkId) {
  const ext = extensionOf(card);
  const v = ext?.["breathezy.verdict"];
  if (!v || typeof v !== "object") return { verdict: notRun(checkId, "gateway card carried no structured verdict"), flags: [] };
  if (v.check_id !== checkId) {
    return { verdict: notRun(checkId, `gateway answered for '${v.check_id}' but '${checkId}' was asked`), flags: [] };
  }
  if (!CHECK_STATUS.has(v.status)) {
    return { verdict: notRun(checkId, `gateway returned an unrecognised status '${v.status}'`), flags: [] };
  }

  const verdict = { check_id: checkId, status: v.status };
  if (v.severity && SEVERITY.has(v.severity)) verdict.severity = v.severity;
  if (typeof v.reason === "string" && v.reason.length) verdict.reason = v.reason;

  // Flags: only what the LOCKED, .strict() OpenCdsFlagSchema allows. A flag missing a required field,
  // or naming a type outside the frozen enum, is DROPPED — it cannot be repaired, and including it
  // would fail the whole response and black out every other check on this request.
  const flags = [];
  for (const f of Array.isArray(v.flags) ? v.flags : []) {
    if (!FLAG_TYPES.has(f?.flag_type) || !SEVERITY.has(f?.severity)) continue;
    if (typeof f.description !== "string" || !f.description.length) continue;
    const out = { flag_type: f.flag_type, severity: f.severity, description: f.description };
    if (typeof f.drug_a === "string" && f.drug_a.length) out.drug_a = f.drug_a;
    if (typeof f.drug_b === "string" && f.drug_b.length) out.drug_b = f.drug_b;
    flags.push(out);
  }
  return { verdict, flags };
}

/** The dose card → dose_candidate, or null. Only the keys the locked contract allows may travel. */
export function cardToDose(card) {
  const ext = extensionOf(card);
  const d = ext?.["breathezy.dose_candidate"];
  if (!d || typeof d !== "object") return null;
  const ALLOWED = ["safe_dose_range", "adjustment_required", "adjustment_reason", "monitoring_required", "duration_guidance"];
  const out = {};
  for (const k of ALLOWED) if (k in d) out[k] = d[k];
  return Object.keys(out).length ? out : null;
}

/**
 * Merge the fan-out into one locked response.
 *
 * @param request  the validated OpenCdsRequest
 * @param results  [{ check_id, ok, cards, error }] — one per requested check
 * @param doseResult { ok, cards, error } — the dose KM, always called (D-C-4)
 */
export function mergeResults(request, results, doseResult) {
  const check_verdicts = [];
  const flags = [];
  const kmSets = new Set();
  const collect = (cards) => { for (const c of cards || []) { const k = kmSetOf(c); if (k) kmSets.add(k); } };

  for (const r of results) {
    const checkId = r.check_id;

    if (!SERVICE_FOR[checkId]) {
      // route_appropriateness_check is in the frozen enum but no KM mirrors it (F4). Saying NOT_RUN is
      // the truth: nothing ran. Inventing a PASS would be the exact fabrication this system forbids.
      check_verdicts.push(notRun(checkId, "no knowledge module implements this check — the engine implements it zero times, so the gateway has nothing to mirror"));
      continue;
    }
    if (!r.ok) {
      check_verdicts.push(notRun(checkId, `knowledge module did not answer: ${r.error}`));
      continue;
    }
    collect(r.cards);

    if (!r.cards?.length) {
      // F-C4. The split is what separates "not applicable" from "a KM broke".
      if (ALWAYS_EMITS.has(checkId)) {
        check_verdicts.push(notRun(checkId, "knowledge module returned no card for a check that always produces one — treating as failed, never as passed"));
      }
      continue; // conditional → no verdict, mirroring engine.js
    }

    const { verdict, flags: fs } = cardToVerdict(r.cards[0], checkId);
    check_verdicts.push(verdict);
    flags.push(...fs);
  }

  // The dose. The shim never decides whether a dose is allowed — the client gates it on PASS/WARN.
  let dose_candidate;
  if (doseResult?.ok) {
    collect(doseResult.cards);
    if (doseResult.cards?.length) dose_candidate = cardToDose(doseResult.cards[0]) ?? undefined;
  }

  // F-C3 — the version comes from the CARDS. Never from the request.
  let knowledge_module_set;
  if (kmSets.size === 1) {
    knowledge_module_set = [...kmSets][0];
  } else {
    // Zero cards (every KM failed) → we cannot say what ran. More than one → the deploy is
    // inconsistent and no single answer is true. Either way the honest report is "unknown", which can
    // never match what the client asked for, so it blocks — with a legible reason rather than a lie.
    knowledge_module_set = UNKNOWN_KM_SET;
    if (kmSets.size > 1) {
      for (let i = 0; i < check_verdicts.length; i++) {
        check_verdicts[i] = notRun(check_verdicts[i].check_id, `gateway served inconsistent knowledge sets (${[...kmSets].sort().join(", ")}) — no verdict from it can be trusted`);
      }
      flags.length = 0;
      dose_candidate = undefined;
    }
  }

  // The locked contract requires at least one verdict. If every requested check was conditional and
  // legitimately silent there is nothing to report — and "no verdicts" is not representable on this
  // wire. Blocking is the fail-safe direction, and the reason says why rather than looking like a bug.
  if (!check_verdicts.length) {
    for (const r of results) {
      check_verdicts.push(notRun(r.check_id, "check did not apply to this drug, and the gateway contract cannot express an empty verdict set"));
    }
  }

  return {
    request_id: request.request_id,
    engine: "opencds-cds-hooks-r4",
    knowledge_module_set,
    check_verdicts,
    flags,
    ...(dose_candidate ? { dose_candidate } : {}),
  };
}
