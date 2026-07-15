package au.breathezy.cds.km;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tranche 2 — schedule_8, pregnancy, hepatic. Against the REAL signed bundle.
 *
 * <p>The centrepiece is the D-FL05-1 table: category × pregnancy status × age. It is the subtlest
 * logic in the engine and the easiest to "simplify" into something that looks safer and is worse.
 */
class Tranche2KmTest {

    private static final Gson GSON = new Gson();
    private final Fl30KnowledgeBase kb = Fl30KnowledgeBase.get();

    private static JsonObject drug(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("drug_name", name);
        return o;
    }
    private static JsonObject facts(String json) { return GSON.fromJson(json, JsonObject.class); }

    // ── schedule_8_check ─────────────────────────────────────────────────────────────────────────

    @Test
    void s8_without_a_pdmp_check_is_HARD_FAIL() {
        String s8 = anS8Drug();
        CheckVerdict v = new Schedule8CheckKm().check(kb, drug(s8), facts("{}"));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status,
                "for an S8 drug, 'no PDMP check performed' is not an unknown — it is the finding");
        assertEquals("schedule_8_pdmp_required", v.flagType);
    }

    @Test
    void s8_with_a_recorded_pdmp_check_is_PASS() {
        CheckVerdict v = new Schedule8CheckKm().check(kb, drug(anS8Drug()), facts("{\"s8_pdmp_checked\":true}"));
        assertEquals(CheckVerdict.Status.PASS, v.status);
    }

    @Test
    void a_non_S8_drug_emits_NO_CHECK() {
        assertNull(new Schedule8CheckKm().check(kb, drug("paracetamol"), facts("{}")),
                "general scheduling is informational — the frozen check_id enum has no general schedule check");
    }

    @Test
    void the_intent_can_RAISE_the_S8_gate_when_the_map_MISSES() {
        // The map-miss net. A brand or spelling variant that did not resolve must not be able to
        // SUPPRESS the PDMP check for a controlled drug.
        JsonObject d = drug("some-unmapped-controlled-drug");
        d.addProperty("schedule", "S8");
        CheckVerdict v = new Schedule8CheckKm().check(kb, d, facts("{}"));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status, "the intent's S8 declaration must raise the gate");
    }

    @Test
    void the_intent_can_NEVER_LOWER_the_S8_gate() {
        JsonObject d = drug(anS8Drug());
        d.addProperty("schedule", "S4");   // caller says it is not controlled; the signed map says it is
        CheckVerdict v = new Schedule8CheckKm().check(kb, d, facts("{}"));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status,
                "a caller must not be able to declare an S8 drug out of its PDMP check");
    }

    // ── hepatic_check ────────────────────────────────────────────────────────────────────────────

    @Test
    void hepatic_no_record_emits_NO_CHECK() {
        assertNull(new HepaticCheckKm().check(kb, drug("a-drug-with-no-hepatic-record"), facts("{}")));
    }

    @Test
    void hepatic_status_unknown_is_NOT_RUN() {
        CheckVerdict v = new HepaticCheckKm().check(kb, drug(aHepaticDrug()), facts("{}"));
        assertEquals(CheckVerdict.Status.NOT_RUN, v.status);
        assertTrue(v.missingFactsRequired.contains("hepatic_impairment"));
    }

    @Test
    void hepatic_not_impaired_is_PASS() {
        CheckVerdict v = new HepaticCheckKm().check(kb, drug(aHepaticDrug()), facts("{\"hepatic_impairment\":false}"));
        assertEquals(CheckVerdict.Status.PASS, v.status);
    }

    @Test
    void hepatic_impaired_blocks_or_warns_by_the_records_action() {
        String d = aHepaticDrug();
        CheckVerdict v = new HepaticCheckKm().check(kb, drug(d), facts("{\"hepatic_impairment\":true}"));
        assertNotEquals(CheckVerdict.Status.PASS, v.status, "an impaired liver with a hepatic rule must never PASS");
        String action = hepaticRecord(d).get("action").getAsString();
        if ("hepatic_contraindicated".equals(action)) {
            assertEquals(CheckVerdict.Status.HARD_FAIL, v.status);
            assertEquals("hepatic_contraindicated", v.flagType);
        } else {
            assertEquals(CheckVerdict.Status.WARN, v.status);
            assertEquals("hepatic_adjustment_required", v.flagType);
        }
    }

    // ── pregnancy_check — the D-FL05-1 table ─────────────────────────────────────────────────────

    @Test
    void pregnancy_no_record_emits_NO_CHECK() {
        assertNull(new PregnancyCheckKm().check(kb, drug("a-drug-with-no-pregnancy-record"), facts("{}")));
    }

    /**
     * D-FL05-1, exhaustively: category × pregnancy status × age.
     *
     * <p>The two rows that matter most are the last two. A known teratogen with an UNKNOWN pregnancy
     * status blocks for a patient who could be pregnant — and does NOT block for one who could not.
     * The naive "unknown always blocks" rule sounds safer and is worse: it halts warfarin for an
     * 80-year-old on a question that cannot apply, and over-triage is what teaches clinicians to click
     * through the block, which is how it stops working on the patient it was built for.
     */
    @ParameterizedTest(name = "cat {0} · status {1} · age {2} → {3}")
    @CsvSource({
        // category, pregnancy_status, age, expected
        "X, pregnant,     30, HARD_FAIL",   // teratogen in a pregnant patient — the core case
        "D, pregnant,     30, WARN",        // fetal risk, but a judgement call for a clinician
        "A, pregnant,     30, PASS",
        "B1, pregnant,    30, PASS",
        "C, pregnant,     30, PASS",
        "X, not_pregnant, 30, PASS",        // an ANSWERED question closes it, whatever the category
        "D, not_pregnant, 30, PASS",
        "X, ,             30, NOT_RUN",     // ← D-FL05-1: unknown + high risk + childbearing → BLOCK
        "D, ,             30, NOT_RUN",
        "X, ,             12, NOT_RUN",     // lower boundary — inclusive
        "X, ,             55, NOT_RUN",     // upper boundary — inclusive
        "X, ,             11, PASS",        // outside the window: the question cannot apply
        "X, ,             56, PASS",
        "X, ,             80, PASS",        // the 80-year-old on warfarin — must NOT be blocked
        "A, ,             30, PASS",        // unknown status, LOW risk → no block
        "C, ,             30, PASS",
    })
    void d_fl05_1_table(String category, String status, int age, String expected) {
        JsonObject facts = new JsonObject();
        facts.addProperty("patient_age_years", age);
        if (status != null && !status.isBlank()) facts.addProperty("pregnancy_status", status);

        CheckVerdict v = new PregnancyCheckKm().check(new StubKb(category, false), drug("x"), facts);
        assertEquals(CheckVerdict.Status.valueOf(expected), v.status,
                "category " + category + " · status " + (status == null || status.isBlank() ? "UNKNOWN" : status) + " · age " + age);
    }

    @Test
    void an_UNKNOWN_age_is_treated_as_childbearing_potential_so_absence_BLOCKS() {
        // The gate opens both ways deliberately: missing information must not become an excuse.
        JsonObject facts = new JsonObject();   // no age, no status
        CheckVerdict v = new PregnancyCheckKm().check(new StubKb("X", false), drug("x"), facts);
        assertEquals(CheckVerdict.Status.NOT_RUN, v.status,
                "an unknown age must not relax the teratogen fail-safe — absence of information is not evidence of safety");
    }

    @Test
    void the_contraindicated_FLAG_hard_fails_even_when_the_category_is_not_X() {
        // highRisk is (X || D || contraindicated). A record flagged contraindicated must block in a
        // pregnant patient regardless of how it is categorised.
        CheckVerdict v = new PregnancyCheckKm().check(new StubKb("B3", true), drug("x"), facts("{\"pregnancy_status\":\"pregnant\"}"));
        assertEquals(CheckVerdict.Status.HARD_FAIL, v.status);
        assertEquals("pregnancy_category_x", v.flagType);
    }

    @Test
    void no_pregnancy_branch_ever_emits_a_dose() {
        // Structural: CheckVerdict has no dose field at all. Asserted so the property is recorded,
        // not merely true by today's accident.
        for (var f : CheckVerdict.class.getFields()) {
            assertFalse(f.getName().toLowerCase().contains("dose"),
                    "a check verdict must have no dose channel — the dose is the client's, from the signed AU record");
        }
    }

    @Test
    void the_real_signed_pregnancy_records_all_evaluate_without_error() {
        // The stub proves the LOGIC; this proves the logic survives the REAL 18 signed records.
        var km = new PregnancyCheckKm();
        int seen = 0;
        for (JsonObject r : records("pregnancy_risk.json")) {
            String subject = r.get("subject").getAsString();
            CheckVerdict v = km.check(kb, drug(subject), facts("{\"pregnancy_status\":\"pregnant\",\"patient_age_years\":30}"));
            assertNotNull(v, "a signed pregnancy record must produce a verdict for " + subject);
            seen++;
        }
        assertEquals(18, seen, "all 18 signed pregnancy records must be reachable");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /** A KB whose pregnancy record is fixed, so the D-FL05-1 table can cover categories the signed set may not hold. */
    private static final class StubKb extends Fl30KnowledgeBase {
        private final JsonObject rec = new JsonObject();
        StubKb(String category, boolean contraindicated) {
            super(Fl30KnowledgeBase.class.getClassLoader());
            rec.addProperty("tga_category", category);
            rec.addProperty("contraindicated", contraindicated);
            rec.addProperty("guidance", "test guidance");
        }
        @Override public JsonObject getPregnancyRisk(String drug) { return rec; }
    }

    private String anS8Drug() {
        for (JsonObject r : records("scheduling.json")) {
            if ("S8".equals(r.get("schedule").getAsString())) return r.get("ingredient").getAsString();
        }
        throw new IllegalStateException("fixture: no S8 drug in the signed KB");
    }

    private String aHepaticDrug() { return records("hepatic.json").get(0).get("ingredient").getAsString(); }

    private JsonObject hepaticRecord(String ingredient) {
        for (JsonObject r : records("hepatic.json")) if (ingredient.equals(r.get("ingredient").getAsString())) return r;
        throw new IllegalStateException("fixture: no hepatic record for " + ingredient);
    }

    private java.util.List<JsonObject> records(String file) {
        try (var in = getClass().getClassLoader().getResourceAsStream("kb/" + file)) {
            var body = GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
            var out = new java.util.ArrayList<JsonObject>();
            body.getAsJsonArray("records").forEach(e -> out.add(e.getAsJsonObject()));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("fixture: cannot read kb/" + file, e);
        }
    }
}
