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
    void matching_is_name_based_while_the_identity_sidecar_is_UNSIGNED() {
        // B0b: the code rides only from a signed source. THIS BUNDLE was exported before the clinician
        // signed the vocabulary (KL, 2026-07-15), so its sidecar is empty and the NAME is the key — and
        // B0 made that correct by canonicalising once, upstream, before both executors. A re-export to
        // fl30-kb:v2 is the deliberate step that would flip this; it has not been taken.
        assertFalse(kb.rxcuiActive());
        JsonObject withCode = drug(firstDoseRecord().get("ingredient").getAsString());
        withCode.addProperty("rxnorm_code", "4603");   // furosemide's real RxCUI
        assertNotNull(km.doseCandidate(kb, withCode, adult()),
                "an unsigned sidecar must not break the name path — it must simply not steer");
    }

    @Test
    void an_unsigned_code_can_never_redirect_a_dose_lookup() {
        // The asymmetry, at its sharpest. Steering a DOSE on an unverified identity would dose the
        // wrong drug. So while unsigned, a code resolves nothing at all.
        assertNull(kb.canonicalNameForCode("4603"));
        JsonObject wrongCode = drug(firstDoseRecord().get("ingredient").getAsString());
        wrongCode.addProperty("rxnorm_code", "999999");   // a code for something else entirely
        JsonObject dose = km.doseCandidate(kb, wrongCode, adult());
        assertEquals(firstDoseRecord().get("safe_dose_range").getAsString(), dose.get("safe_dose_range").getAsString(),
                "a bogus code must not redirect the lookup — the canonical NAME governs while the sidecar is unsigned");
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
