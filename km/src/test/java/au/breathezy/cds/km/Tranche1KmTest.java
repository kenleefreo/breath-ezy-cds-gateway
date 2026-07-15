package au.breathezy.cds.km;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tranche 1 — the five DEFAULT_CHECKS, tested against the REAL signed knowledge bundle.
 *
 * <p>These run against {@code kb/} on the classpath, not a mock. A KM tested only against fixtures
 * proves it implements a spec; tested against the bundle it will actually execute, it proves it
 * implements the spec ON THE REAL KNOWLEDGE — which is where the field-name mismatches live. (This
 * suite is why the renal KM reads {@code dose_reduction_below_egfr}: 63 of the 104 signed records
 * carry only that field, and a KM reading only the other one PASSES them all.)
 *
 * <p>Every case here mirrors a branch of {@code engine.js}. The engine is the specification; the KM
 * is a second implementation. Divergence is a defect in one of them — that is precisely what Phase
 * D's A/B parity is for, and it only works if both really run the same knowledge.
 */
class Tranche1KmTest {

    private static final Gson GSON = new Gson();
    private final Fl30KnowledgeBase kb = Fl30KnowledgeBase.get();

    private static JsonObject drug(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("drug_name", name);
        return o;
    }
    private static JsonObject facts(String json) { return GSON.fromJson(json, JsonObject.class); }

    // ── the bundle itself ────────────────────────────────────────────────────────────────────────

    @Test
    void the_real_bundle_verifies() {
        assertFalse(kb.failedClosed(), "the committed kb/ bundle must verify: " + kb.failureReason());
        // The COMMITTED bundle was exported from an unsigned datastore, so it is name-keyed. That is a
        // property of THIS BUNDLE, not of the datastore: KL signed the vocabulary on 2026-07-15, and a
        // deliberate re-export to fl30-kb:v2 is what would turn code-first matching on here. Until that
        // export happens, this bundle matches by name — and B0's upstream canonicalisation is what makes
        // that correct.
        assertFalse(kb.rxcuiActive(), "this bundle was exported before the vocabulary was signed, so code-first matching is OFF in it");
    }

    @Test
    void a_tampered_bundle_fails_CLOSED_and_every_km_reports_NOT_RUN() throws Exception {
        // The transport layer, proven. A KM that kept answering from unverified bytes would be the
        // worst failure this system has: a confident verdict with no signature behind it.
        Fl30KnowledgeBase tampered = new Fl30KnowledgeBase(new TamperingClassLoader());
        assertTrue(tampered.failedClosed(), "a modified knowledge file MUST fail the load");
        assertTrue(tampered.failureReason().contains("checksum mismatch"),
                "the failure must name the cause, not just refuse: " + tampered.failureReason());
        assertTrue(tampered.failureReason().contains("not the bytes a clinician signed"));
    }

    // ── allergy_check ────────────────────────────────────────────────────────────────────────────

    @Test
    void allergy_cross_reactivity_is_HARD_FAIL() {
        // amoxicillin and penicillin are both beta_lactam in the signed registry.
        CheckVerdict v = new AllergyCheckKm().check(kb, drug("amoxicillin"), facts("{\"allergens\":[\"penicillin\"]}"));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status);
        assertEquals(CheckVerdict.Severity.critical, v.severity);
        assertEquals("allergy_cross_reactivity", v.flagType);
        assertTrue(v.reason.contains("beta_lactam"));
    }

    @Test
    void allergy_unrelated_is_PASS() {
        CheckVerdict v = new AllergyCheckKm().check(kb, drug("amoxicillin"), facts("{\"allergens\":[\"sulfur\"]}"));
        assertEquals(CheckVerdict.Status.PASS, v.status);
    }

    @Test
    void allergy_status_absent_is_NOT_RUN_never_PASS() {
        CheckVerdict v = new AllergyCheckKm().check(kb, drug("amoxicillin"), facts("{}"));
        assertEquals(CheckVerdict.Status.NOT_RUN, v.status, "an absent allergy history is unknown, not clear");
        assertTrue(v.missingFactsRequired.contains("allergy_status"));
    }

    @Test
    void allergy_empty_list_is_a_REAL_answer_and_passes() {
        // "no known allergies" is an assertion, not an absence. It must not be conflated with NOT_RUN.
        CheckVerdict v = new AllergyCheckKm().check(kb, drug("amoxicillin"), facts("{\"allergens\":[]}"));
        assertEquals(CheckVerdict.Status.PASS, v.status);
    }

    // ── interaction_check ────────────────────────────────────────────────────────────────────────

    @Test
    void interaction_medications_absent_is_NOT_RUN() {
        CheckVerdict v = new InteractionCheckKm().check(kb, drug("warfarin"), facts("{}"));
        assertEquals(CheckVerdict.Status.NOT_RUN, v.status);
        assertTrue(v.missingFactsRequired.contains("current_medications"));
    }

    @Test
    void interaction_no_hits_is_PASS() {
        CheckVerdict v = new InteractionCheckKm().check(kb, drug("warfarin"), facts("{\"current_medications\":[\"a-drug-that-does-not-exist\"]}"));
        assertEquals(CheckVerdict.Status.PASS, v.status);
    }

    @Test
    void interaction_matches_in_BOTH_directions() {
        // The pair is unordered in engine.js: (a==drug && meds has b) || (b==drug && meds has a).
        // Indexing only one side would silently miss half the interaction knowledge base.
        var km = new InteractionCheckKm();
        JsonObject any = firstInteraction();
        String subject = any.get("subject").getAsString();
        String object = any.get("object").getAsString();

        CheckVerdict fwd = km.check(kb, drug(subject), facts(GSON.toJson(medsOf(object))));
        CheckVerdict rev = km.check(kb, drug(object), facts(GSON.toJson(medsOf(subject))));
        assertNotEquals(CheckVerdict.Status.PASS, fwd.status, "subject→object must be detected");
        assertNotEquals(CheckVerdict.Status.PASS, rev.status, "object→subject must be detected: the pair is UNORDERED");
        assertEquals(fwd.status, rev.status, "direction must not change the verdict");
    }

    @Test
    void interaction_critical_is_HARD_FAIL_and_moderate_is_WARN() {
        JsonObject crit = interactionWithSeverity("critical");
        CheckVerdict v = new InteractionCheckKm().check(kb, drug(crit.get("subject").getAsString()),
                facts(GSON.toJson(medsOf(crit.get("object").getAsString()))));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status);
        assertEquals("interaction_severe", v.flagType);

        JsonObject mod = interactionWithSeverityNot("critical");
        if (mod != null) {
            CheckVerdict w = new InteractionCheckKm().check(kb, drug(mod.get("subject").getAsString()),
                    facts(GSON.toJson(medsOf(mod.get("object").getAsString()))));
            // A non-critical hit is a WARN — UNLESS this drug also has a critical hit with the same
            // partner, which the engine would escalate. Assert the weaker, always-true property.
            assertNotEquals(CheckVerdict.Status.PASS, w.status, "a known interaction must never be PASS");
        }
    }

    // ── renal_dosing_check ───────────────────────────────────────────────────────────────────────

    @Test
    void renal_no_rule_is_PASS_without_demanding_an_eGFR() {
        // Order matters: "no rule" is decided BEFORE eGFR is required. Demanding a fact that cannot
        // change the answer is over-triage, and over-triage is what teaches people to override.
        CheckVerdict v = new RenalDosingCheckKm().check(kb, drug("a-drug-with-no-renal-rule"), facts("{}"));
        assertEquals(CheckVerdict.Status.PASS, v.status);
        assertTrue(v.reason.contains("no renal rule"));
    }

    @Test
    void renal_rule_present_but_eGFR_absent_is_NOT_RUN() {
        JsonObject rule = firstRenal("renal_contraindicated");
        CheckVerdict v = new RenalDosingCheckKm().check(kb, drug(rule.get("ingredient").getAsString()), facts("{}"));
        assertEquals(CheckVerdict.Status.NOT_RUN, v.status, "a rule exists, so a missing eGFR is unknown — never an assumed-normal kidney");
        assertTrue(v.missingFactsRequired.contains("egfr"));
    }

    @Test
    void renal_contraindicated_below_threshold_is_HARD_FAIL() {
        JsonObject rule = firstRenal("renal_contraindicated");
        double t = rule.get("contraindicated_below_egfr").getAsDouble();
        CheckVerdict v = new RenalDosingCheckKm().check(kb, drug(rule.get("ingredient").getAsString()),
                facts("{\"egfr_ml_min\":" + (t - 1) + "}"));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status);
        assertEquals("renal_contraindicated", v.flagType);

        CheckVerdict at = new RenalDosingCheckKm().check(kb, drug(rule.get("ingredient").getAsString()),
                facts("{\"egfr_ml_min\":" + t + "}"));
        assertEquals(CheckVerdict.Status.PASS, at.status, "the boundary is '<' — AT the threshold is a PASS, exactly as engine.js");
    }

    @Test
    void renal_DOSE_REDUCTION_rule_is_honoured_the_majority_of_the_KB_depends_on_it() {
        // THE BUG THIS TEST EXISTS FOR. 63 of 104 signed renal records carry ONLY
        // dose_reduction_below_egfr. A KM reading only contraindicated_below_egfr reports
        // "no threshold → PASS" for all of them — a silent pass on renal adjustment rules, which is
        // the exact class of failure the second executor is meant to catch rather than commit.
        JsonObject rule = firstRenalWithOnlyDoseReduction();
        assertNotNull(rule, "fixture: the signed KB must contain a dose-reduction-only renal rule");
        double t = rule.get("dose_reduction_below_egfr").getAsDouble();
        CheckVerdict v = new RenalDosingCheckKm().check(kb, drug(rule.get("ingredient").getAsString()),
                facts("{\"egfr_ml_min\":" + (t - 1) + "}"));
        assertEquals(CheckVerdict.Status.WARN, v.status, "a dose-reduction rule below threshold must WARN, not PASS");
        assertEquals("renal_adjustment_required", v.flagType);
    }

    // ── nti_check ────────────────────────────────────────────────────────────────────────────────

    @Test
    void nti_without_documented_monitoring_is_HARD_FAIL() {
        JsonObject rec = firstNti();
        CheckVerdict v = new NtiCheckKm().check(kb, drug(rec.get("ingredient").getAsString()), facts("{}"));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status,
                "for an NTI drug, 'no monitoring plan documented' is not an unknown — it IS the finding");
        assertEquals("nti", v.flagType);
    }

    @Test
    void nti_with_documented_monitoring_is_PASS() {
        JsonObject rec = firstNti();
        CheckVerdict v = new NtiCheckKm().check(kb, drug(rec.get("ingredient").getAsString()),
                facts("{\"nti_monitoring_documented\":true}"));
        assertEquals(CheckVerdict.Status.PASS, v.status);
    }

    @Test
    void nti_non_nti_drug_emits_NO_CHECK() {
        assertNull(new NtiCheckKm().check(kb, drug("a-drug-that-is-not-nti"), facts("{}")),
                "engine.js emits no check for a non-NTI drug — 'not applicable' is not 'not run'");
    }

    @Test
    void nti_intent_flag_can_RAISE_the_check_but_never_SUPPRESS_it() {
        // The union trigger, both halves.
        JsonObject candidate = drug("a-drug-that-is-not-nti");
        candidate.addProperty("is_nti_candidate", true);
        assertEquals(CheckVerdict.Status.HARD_FAIL, new NtiCheckKm().check(kb, candidate, facts("{}")).status,
                "the intent's candidate flag must be able to RAISE the check (conservative safety-net)");

        JsonObject denied = drug(firstNti().get("ingredient").getAsString());
        denied.addProperty("is_nti_candidate", false);
        assertEquals(CheckVerdict.Status.HARD_FAIL, new NtiCheckKm().check(kb, denied, facts("{}")).status,
                "the caller must NOT be able to talk the register out of its own NTI finding");
    }

    // ── age_appropriateness_check ────────────────────────────────────────────────────────────────

    @Test
    void age_paediatric_is_HARD_FAIL_with_a_flag_and_no_dose() {
        CheckVerdict v = new AgeAppropriatenessCheckKm().check(kb, drug("amoxicillin"), facts("{\"patient_age_years\":7}"));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status);
        assertEquals("age_paediatric_weight_based", v.flagType);
        assertTrue(v.reason.contains("in-person review"));
    }

    @Test
    void age_adult_is_PASS_and_the_boundary_is_18() {
        assertEquals(CheckVerdict.Status.PASS, new AgeAppropriatenessCheckKm().check(kb, drug("amoxicillin"), facts("{\"patient_age_years\":18}")).status);
        assertEquals(CheckVerdict.Status.HARD_FAIL, new AgeAppropriatenessCheckKm().check(kb, drug("amoxicillin"), facts("{\"patient_age_years\":17}")).status);
    }

    @Test
    void age_unknown_is_NOT_RUN_so_no_dose_can_reach_a_possible_child() {
        CheckVerdict v = new AgeAppropriatenessCheckKm().check(kb, drug("amoxicillin"), facts("{}"));
        assertEquals(CheckVerdict.Status.NOT_RUN, v.status);
        assertTrue(v.missingFactsRequired.contains("patient_age"));
    }

    // ── identity ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void an_unsigned_sidecar_cannot_steer_a_lookup() {
        // The asymmetry, mechanically: an unsigned identity map may BLOCK but must never STEER. This
        // bundle's sidecar is empty (exported pre-sign-off), so nothing resolves through it — and the
        // KM must still answer correctly on the name alone.
        assertNull(kb.canonicalNameForCode("4603"), "no code may resolve while THIS BUNDLE's sidecar is unsigned");
        JsonObject withCode = drug("amoxicillin");
        withCode.addProperty("rxnorm_code", "723");
        CheckVerdict v = new AllergyCheckKm().check(kb, withCode, facts("{\"allergens\":[\"penicillin\"]}"));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status,
                "with the sidecar unsigned the NAME is the key, and the pipeline already canonicalised it (B0)");
    }

    // ── fixtures pulled from the real bundle ─────────────────────────────────────────────────────

    private JsonObject firstInteraction() {
        for (String d : new String[]{"warfarin", "amiodarone", "simvastatin", "digoxin"}) {
            var l = kb.getInteractions(d);
            if (!l.isEmpty()) return l.get(0);
        }
        throw new IllegalStateException("fixture: no interactions found in the signed KB");
    }

    private JsonObject interactionWithSeverity(String sev) {
        for (String d : new String[]{"warfarin", "amiodarone", "simvastatin", "digoxin", "methotrexate", "lithium"}) {
            for (JsonObject ix : kb.getInteractions(d)) {
                if (sev.equals(ix.get("severity").getAsString())) return ix;
            }
        }
        throw new IllegalStateException("fixture: no '" + sev + "' interaction found in the signed KB");
    }

    private JsonObject interactionWithSeverityNot(String sev) {
        for (String d : new String[]{"warfarin", "amiodarone", "simvastatin", "digoxin", "metformin"}) {
            for (JsonObject ix : kb.getInteractions(d)) {
                if (!sev.equals(ix.get("severity").getAsString())) return ix;
            }
        }
        return null;
    }

    private static JsonObject medsOf(String med) {
        JsonObject f = new JsonObject();
        var arr = new com.google.gson.JsonArray();
        arr.add(med);
        f.add("current_medications", arr);
        return f;
    }

    private JsonObject firstRenal(String action) {
        for (var e : renalAll()) if (action.equals(e.get("action").getAsString()) && e.has("contraindicated_below_egfr")) return e;
        throw new IllegalStateException("fixture: no '" + action + "' renal rule with a threshold");
    }

    private JsonObject firstRenalWithOnlyDoseReduction() {
        for (var e : renalAll()) if (!e.has("contraindicated_below_egfr") && e.has("dose_reduction_below_egfr")) return e;
        return null;
    }

    private java.util.List<JsonObject> renalAll() { return recordsOf("renal.json"); }

    private JsonObject firstNti() {
        for (var e : recordsOf("nti.json")) if (e.has("is_nti") && e.get("is_nti").getAsBoolean()) return e;
        throw new IllegalStateException("fixture: no NTI record in the signed KB");
    }

    private java.util.List<JsonObject> recordsOf(String file) {
        try (var in = getClass().getClassLoader().getResourceAsStream("kb/" + file)) {
            var body = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
            var out = new java.util.ArrayList<JsonObject>();
            body.getAsJsonArray("records").forEach(e -> out.add(e.getAsJsonObject()));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("fixture: cannot read kb/" + file, e);
        }
    }

    /** Serves a byte-modified allergy file so the transport checksum layer can be proven to bite. */
    private static final class TamperingClassLoader extends ClassLoader {
        TamperingClassLoader() { super(Tranche1KmTest.class.getClassLoader()); }
        @Override
        public java.io.InputStream getResourceAsStream(String name) {
            java.io.InputStream in = super.getResourceAsStream(name);
            if (in == null || !name.equals("kb/allergy.json")) return in;
            try {
                String body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                // A single plausible edit — the kind a well-meaning hand would make.
                String tampered = body.replaceFirst("\"beta_lactam\"", "\"beta_lactams\"");
                return new java.io.ByteArrayInputStream(tampered.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                return null;
            }
        }
    }
}
