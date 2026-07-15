package au.breathezy.cds.km;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * age_appropriateness_check — the paediatric hard limit.
 *
 * <p>Mirrors engine.js exactly:
 * <pre>
 *   age known and < 18  → HARD_FAIL critical (flag age_paediatric_weight_based)
 *   age known and >= 18 → PASS
 *   age UNKNOWN         → NOT_RUN (missing: patient_age) → forces BLOCKED_NO_PROOF upstream
 * </pre>
 *
 * <p>This is the only KM in tranche 1 that needs no knowledge base — it is pure age logic, which is
 * why the export has nothing to ship for it.
 *
 * <p><b>Why an unknown age blocks rather than passes.</b> The system holds no paediatric dosing
 * tables at all. If age is unknown and this check passed, the pipeline could reach a dose for a
 * patient who might be a child, sourced from an adult-only dataset. So an unknown age is NOT_RUN,
 * which forces BLOCKED_NO_PROOF and withholds the dose until age is confirmed. Missing proof →
 * blocked, never a plausible default.
 *
 * <p>A known under-18 is a HARD_FAIL that carries a FLAG and no dose — it routes to in-person review.
 * The system is not refusing to help; it is refusing to guess a paediatric dose it does not have.
 */
public final class AgeAppropriatenessCheckKm extends Fl30Km {

    @Override
    public String checkId() { return "age_appropriateness_check"; }

    @Override
    public CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        Double age = num(facts, "patient_age_years");
        if (age == null) {
            return CheckVerdict.notRun(checkId(),
                    "patient age not provided — cannot confirm the patient is an adult, and no paediatric dosing tables exist",
                    List.of("patient_age"));
        }
        if (age < 18) {
            return CheckVerdict.hardFail(checkId(),
                    "paediatric (<18): no paediatric dosing tables — in-person review required",
                    "age_paediatric_weight_based");
        }
        return CheckVerdict.pass(checkId());
    }
}
