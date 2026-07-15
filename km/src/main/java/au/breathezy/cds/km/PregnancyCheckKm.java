package au.breathezy.cds.km;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * pregnancy_check — TGA pregnancy categorisation, with the D-FL05-1 age-gated fail-safe.
 *
 * <p>Mirrors engine.js exactly. The drug must have a pregnancy record, or no check is emitted:
 * <pre>
 *   pregnant     && (category X || contraindicated) → HARD_FAIL critical (flag pregnancy_category_x)
 *   pregnant     && category D                      → WARN moderate      (flag pregnancy_category_d)
 *   pregnant     && A/B/C                           → PASS
 *   not_pregnant                                    → PASS
 *   UNKNOWN      && high risk && childbearing       → NOT_RUN (missing: pregnancy_status)   ← D-FL05-1
 *   UNKNOWN      && high risk && NOT childbearing   → PASS
 *   UNKNOWN      && low risk                        → PASS
 * </pre>
 *
 * <h2>D-FL05-1: why the fail-safe is AGE-GATED, and why that is not a weakening</h2>
 * An unknown pregnancy status blocks a known teratogen — but only for a patient who could be
 * pregnant (age 12-55, or age unknown). The naive version blocks on unknown status full stop, which
 * sounds safer and is worse: it would halt warfarin for an 80-year-old on a question that cannot
 * apply to them. That is over-triage, and over-triage is not free — it is what teaches clinicians to
 * click through the block, which is how the block stops working on the patient it was built for.
 *
 * <p>The gate opens both ways deliberately. An UNKNOWN age is treated as childbearing potential, so
 * the absence of information blocks rather than excuses. Only a known age outside 12-55 relaxes it.
 *
 * <p>Paediatric patients are already HARD_FAILed by age_appropriateness_check, which dominates — so
 * the lower bound here is not the paediatric safety net, and must not be relied on as one.
 *
 * <p>No dose is ever emitted from this check, in any branch.
 */
public final class PregnancyCheckKm extends Fl30Km {

    @Override
    public String checkId() { return "pregnancy_check"; }

    @Override
    public CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        String d = drugKey(kb, drug);
        JsonObject rec = kb.getPregnancyRisk(d);
        if (rec == null) return null;  // no record → engine emits no check

        String cat = str(rec, "tga_category") == null ? "" : str(rec, "tga_category").toUpperCase(Locale.ROOT);
        boolean contraindicated = Boolean.TRUE.equals(bool(rec, "contraindicated"));
        boolean highRisk = "X".equals(cat) || "D".equals(cat) || contraindicated;
        String guidance = str(rec, "guidance");
        String status = str(facts, "pregnancy_status");   // "pregnant" | "not_pregnant" | null

        if ("pregnant".equals(status)) {
            if ("X".equals(cat) || contraindicated) {
                // engine.js also carries au_reference ("TGA Prescribing Medicines in Pregnancy") on this
                // flag; the locked OpenCdsFlagSchema is .strict() and has no such field. Dropped, not
                // smuggled — a forbidden field would fail the whole response.
                return CheckVerdict.hardFail(checkId(),
                        "TGA pregnancy category " + (cat.isEmpty() ? "X" : cat) + ": "
                                + (guidance == null ? "contraindicated in pregnancy" : guidance),
                        Flag.of("pregnancy_category_x", CheckVerdict.Severity.critical,
                                d + " — TGA category " + (cat.isEmpty() ? "X" : cat) + ": "
                                        + (guidance == null ? "teratogen; contraindicated in pregnancy" : guidance), d));
            }
            if ("D".equals(cat)) {
                return CheckVerdict.warn(checkId(), CheckVerdict.Severity.moderate,
                        "TGA pregnancy category D: "
                                + (guidance == null ? "evidence of fetal risk; use only if benefit justifies" : guidance),
                        Flag.of("pregnancy_category_d", CheckVerdict.Severity.moderate,
                                d + " — TGA category D: " + (guidance == null ? "evidence of fetal risk" : guidance), d));
            }
            return CheckVerdict.pass(checkId(), "TGA pregnancy category " + (cat.isEmpty() ? "A/B/C" : cat));
        }

        if ("not_pregnant".equals(status)) return CheckVerdict.pass(checkId(), "not pregnant");

        // Status UNKNOWN from here.
        if (highRisk) {
            Double age = num(facts, "patient_age_years");
            boolean childbearingPotential = age == null || (age >= 12 && age <= 55);
            if (childbearingPotential) {
                return CheckVerdict.notRun(checkId(),
                        "TGA category " + cat + ": pregnancy status not provided — must be confirmed before "
                                + "prescribing a teratogenic/high-risk drug to a patient of childbearing potential",
                        List.of("pregnancy_status"));
            }
            return CheckVerdict.pass(checkId(),
                    "TGA category " + cat + ": not of childbearing potential (age " + fmt(age)
                            + ") — pregnancy status not required");
        }
        return CheckVerdict.pass(checkId(), "TGA category " + (cat.isEmpty() ? "A/B/C" : cat) + " (low pregnancy risk)");
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
}
