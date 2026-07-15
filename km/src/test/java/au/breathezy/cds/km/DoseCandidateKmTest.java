package au.breathezy.cds.km;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dose KM (B4) — the only module that emits a dose, so the tightest suite in the repo.
 *
 * <p>The invariant under test is <b>no autonomous prescription</b>: every dose that reaches a
 * clinician comes from a clinician-signed record, never an inference and never a foreign label.
 */
class DoseCandidateKmTest {

    private static final Gson GSON = new Gson();
    private final Fl30KnowledgeBase kb = Fl30KnowledgeBase.get();
    private final DoseCandidateKm km = new DoseCandidateKm();

    private static JsonObject drug(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("drug_name", name);
        return o;
    }
    private static JsonObject adult() { return GSON.fromJson("{\"patient_age_years\":40}", JsonObject.class); }

    // ── it emits a real dose, from the real signed record ────────────────────────────────────────

    @Test
    void an_adult_gets_the_dose_from_the_SIGNED_record() {
        JsonObject rec = firstDoseRecord();
        String ingredient = rec.get("ingredient").getAsString();
        JsonObject dose = km.doseCandidate(kb, drug(ingredient), adult());
        assertNotNull(dose, "the signed record must produce a dose candidate for " + ingredient);
        assertEquals(rec.get("safe_dose_range").getAsString(), dose.get("safe_dose_range").getAsString(),
                "the dose must be the SIGNED record's text, verbatim — not a rewording, not a summary");
    }

    @Test
    void the_pick_matches_engine_js_DOSE_KEYS_and_carries_nothing_else() {
        // engine.js picks ONLY the frozen dose keys. Copying the whole record would put provenance
        // and the clinician's attestation onto the wire, inside a field that is explicitly ADVISORY —
        // a signature attached to something it was not given for.
        JsonObject dose = km.doseCandidate(kb, drug(firstDoseRecord().get("ingredient").getAsString()), adult());
        for (String k : dose.keySet()) {
            assertTrue(java.util.List.of("safe_dose_range", "adjustment_required", "adjustment_reason",
                            "monitoring_required", "duration_guidance", "pbs_authority_required", "pbs_item_code").contains(k),
                    "'" + k + "' is not a frozen dose key and must not travel");
        }
        assertFalse(dose.has("provenance"), "the clinician's provenance block must never ride inside an advisory dose");
        assertFalse(dose.has("source_statement"), "the verbatim source statement is evidence-plane content, not a dose field");
    }

    @Test
    void all_451_signed_records_yield_a_dose_for_an_adult() {
        // The whole point of F3's flip: the source EXISTS. If this KM could only answer for a handful
        // of drugs it would not be worth the Java.
        int emitted = 0;
        for (JsonObject r : doseRecords()) {
            if (km.doseCandidate(kb, drug(r.get("ingredient").getAsString()), adult()) != null) emitted++;
        }
        assertEquals(451, emitted, "every clinician-attested record must be reachable through the KM");
    }

    // ── the refusals ─────────────────────────────────────────────────────────────────────────────

    @Test
    void PAEDIATRIC_never_gets_a_dose() {
        String d = firstDoseRecord().get("ingredient").getAsString();
        assertNull(km.doseCandidate(kb, drug(d), GSON.fromJson("{\"patient_age_years\":7}", JsonObject.class)),
                "there are no paediatric dosing tables — a child must never receive an adult dose");
        assertNull(km.doseCandidate(kb, drug(d), GSON.fromJson("{\"patient_age_years\":17}", JsonObject.class)),
                "the boundary is 18, mirroring engine.js");
        assertNotNull(km.doseCandidate(kb, drug(d), GSON.fromJson("{\"patient_age_years\":18}", JsonObject.class)));
    }

    @Test
    void an_UNKNOWN_age_never_gets_a_dose() {
        // Unknown age is not "probably an adult". This dataset is adult-only by construction — the 232
        // paediatric rows were deliberately excluded from the attestation — so handing it out without
        // confirming age could hand a child an adult dose. Missing proof → nothing.
        assertNull(km.doseCandidate(kb, drug(firstDoseRecord().get("ingredient").getAsString()), new JsonObject()),
                "an unconfirmed age must withhold the dose");
    }

    @Test
    void a_drug_with_NO_signed_dose_gets_NO_dose_and_never_a_substitute() {
        assertNull(km.doseCandidate(kb, drug("a-drug-with-no-signed-dose"), adult()),
                "absent record → no dose. Never a neighbouring record, never an inference, never a default.");
    }

    @Test
    void an_UNVERIFIED_knowledge_base_emits_no_dose() throws Exception {
        Fl30KnowledgeBase tampered = new Fl30KnowledgeBase(new ClassLoader(getClass().getClassLoader()) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                java.io.InputStream in = super.getResourceAsStream(name);
                if (in == null || !name.equals("kb/dose_guidance.json")) return in;
                try {
                    String body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    return new java.io.ByteArrayInputStream(body.replaceFirst("10 mg", "100 mg").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) { return null; }
            }
        });
        assertTrue(tampered.failedClosed(), "a doctored dose file must fail the load — a 10x dose edit is the case this exists for");
        assertTrue(tampered.failureReason().contains("dose_guidance.json"));
    }

    // ── identity ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_SIGNED_code_reaches_the_same_dose_the_name_does() {
        // B0b, live in fl30-kb:v2. The code is a KEY the pipeline already settled — never a second
        // identity. If code and name could reach DIFFERENT doses, the code would be a second opinion
        // about which drug this is, which is exactly what the single upstream identity boundary exists
        // to prevent.
        assertTrue(kb.rxcuiActive(), "v2 carries a signed sidecar");
        assertEquals("furosemide", kb.canonicalNameForCode("4603"));

        JsonObject byName = drug("levothyroxine");
        JsonObject byCode = drug("levothyroxine");
        byCode.addProperty("rxnorm_code", "10582");
        JsonObject a = km.doseCandidate(kb, byName, adult());
        JsonObject b = km.doseCandidate(kb, byCode, adult());
        assertNotNull(a, "fixture: levothyroxine carries a signed dose");
        assertEquals(a, b, "the code and the name must reach the SAME signed dose — the code is a key, not a second opinion");
    }

    @Test
    void a_code_this_KB_does_not_hold_falls_back_to_the_NAME_and_never_redirects() {
        // An unknown code resolves NOTHING — it must not silently redirect, and it must not break the
        // name path either. Steering a DOSE on an unverified identity would dose the wrong drug.
        assertNull(kb.canonicalNameForCode("999999"), "a code the sidecar does not hold must resolve to nothing");
        JsonObject unknownCode = drug(firstDoseRecord().get("ingredient").getAsString());
        unknownCode.addProperty("rxnorm_code", "999999");
        JsonObject dose = km.doseCandidate(kb, unknownCode, adult());
        assertEquals(firstDoseRecord().get("safe_dose_range").getAsString(), dose.get("safe_dose_range").getAsString(),
                "an unknown code must leave the canonical NAME governing — B0 canonicalised it upstream");
    }

    @Test
    void a_code_that_CONTRADICTS_the_name_yields_NO_DOSE() {
        // The sharpest form of the hazard v2 activates. A code and a name that disagree mean something
        // upstream is broken; picking a winner would dose the drug the OTHER field named. The KM
        // refuses, and DoseCandidateKm.evaluate turns any throw into no dose at all.
        JsonObject conflict = drug("levothyroxine");
        conflict.addProperty("rxnorm_code", "4603");   // furosemide's code on levothyroxine's name
        assertThrows(IllegalStateException.class, () -> km.doseCandidate(kb, conflict, adult()),
                "a code/name conflict must refuse — a dose is the last place to guess which field is right");

        var resp = km.evaluate(fakeRequest(conflict, adult()), fakeCtx());
        assertEquals(0, resp.getCards().size(),
                "through evaluate(), a conflict must emit NO dose card at all — silence here is safe, because a missing dose_candidate simply means the client has none to render");
    }

    private static org.opencds.hooks.model.request.CdsRequest fakeRequest(JsonObject drug, JsonObject facts) {
        var ctx = new org.opencds.hooks.model.context.WritableHookContext();
        ctx.add("drug", drug);
        ctx.add("resolved_facts", facts);
        var req = new org.opencds.hooks.model.request.WritableCdsRequest();
        req.setHook("order-sign");
        req.setHookInstance("test");
        req.setContext(ctx);
        return req;
    }

    private static org.opencds.hooks.engine.api.CdsHooksEvaluationContext fakeCtx() {
        return org.opencds.hooks.engine.api.CdsHooksEvaluationContext.create(
                new java.util.Date(0), java.net.URI.create("http://localhost/opencds"), "en", "+10:00",
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
    }

    // ── containment ──────────────────────────────────────────────────────────────────────────────

    @Test
    void this_KM_emits_NO_check_verdict() {
        // It is not a check. It must never contribute a status to the composed verdict — the client
        // composes from the eight checks, and this module's output is advisory content, not a vote.
        assertNull(km.checkId());
        assertNull(km.check(kb, drug("anything"), adult()));
    }

    @Test
    void no_FOREIGN_label_dose_is_reachable_from_this_KM() {
        // The Australian-context hard limit, at the point it would actually bite. The export's F5
        // allowlist keeps international-dose-guidance out of the bundle entirely, so there is nothing
        // here to reach — asserted rather than assumed.
        assertNull(kb.getDoseGuidance("a-us-only-drug"));
        for (JsonObject r : doseRecords()) {
            assertFalse(r.toString().contains("FDA label"), "no US label text may be present in the AU dose KB");
            assertFalse(r.toString().contains("EMA SmPC"), "no EU label text may be present in the AU dose KB");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private JsonObject firstDoseRecord() { return doseRecords().get(0); }

    private java.util.List<JsonObject> doseRecords() {
        try (var in = getClass().getClassLoader().getResourceAsStream("kb/dose_guidance.json")) {
            var body = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
            var out = new java.util.ArrayList<JsonObject>();
            body.getAsJsonArray("records").forEach(e -> out.add(e.getAsJsonObject()));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("fixture: cannot read kb/dose_guidance.json", e);
        }
    }
}
