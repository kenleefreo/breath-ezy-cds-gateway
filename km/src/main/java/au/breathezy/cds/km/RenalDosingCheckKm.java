package au.breathezy.cds.km;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * renal_dosing_check — eGFR-threshold rules.
 *
 * <p>Mirrors engine.js §3 exactly:
 * <pre>
 *   no renal rule for the drug            → PASS ("no renal rule for this drug")
 *   rule exists, eGFR not supplied        → NOT_RUN (missing: egfr)
 *   eGFR below threshold, contraindicated → HARD_FAIL critical (flag renal_contraindicated)
 *   eGFR below threshold, adjustment      → WARN moderate      (flag renal_adjustment_required)
 *   eGFR at/above threshold               → PASS
 * </pre>
 *
 * <p>The ORDER matters and is easy to get wrong: "no rule → PASS" is evaluated BEFORE the eGFR fact
 * is required. A drug with no renal rule does not need an eGFR, so demanding one would block every
 * prescription on a fact that could not change the answer — over-triage that trains people to
 * override the system. But once a rule exists, a missing eGFR is NOT_RUN, never an assumed-normal
 * kidney.
 */
public final class RenalDosingCheckKm extends Fl30Km {

    @Override
    public String checkId() { return "renal_dosing_check"; }

    @Override
    public CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        String d = drugKey(kb, drug);
        JsonObject rule = kb.getRenalRule(d);
        if (rule == null) return CheckVerdict.pass(checkId(), "no renal rule for this drug");

        Double egfr = num(facts, "egfr_ml_min");
        if (egfr == null) {
            return CheckVerdict.notRun(checkId(), "renal function (eGFR) not provided", List.of("egfr"));
        }
        // MIRROR THE ENGINE'S COALESCE, and note the order:
        //     egfr_threshold_ml_min = contraindicated_below_egfr ?? dose_reduction_below_egfr
        // Reading only the first field is not a near-miss — 63 of the 104 signed renal records carry
        // ONLY dose_reduction_below_egfr, so a KM that ignored it would report "no threshold → PASS"
        // for the majority of the renal knowledge base. A silent PASS on a renal adjustment rule is
        // exactly the failure a second executor is supposed to CATCH, not commit.
        Double threshold = num(rule, "contraindicated_below_egfr");
        if (threshold == null) threshold = num(rule, "dose_reduction_below_egfr");
        String action = str(rule, "action");
        // 16 records legitimately carry neither threshold. The engine then compares against undefined,
        // which is false, so it PASSes — mirrored here rather than "fixed" into a block.
        if (threshold == null) return CheckVerdict.pass(checkId(), "renal rule carries no eGFR threshold");

        if (egfr < threshold) {
            boolean contra = "renal_contraindicated".equals(action);
            String reason = d + ": " + action + " below eGFR " + fmt(threshold);
            return contra
                    ? CheckVerdict.hardFail(checkId(), reason, "renal_contraindicated")
                    : CheckVerdict.warn(checkId(), CheckVerdict.Severity.moderate, reason, "renal_adjustment_required");
        }
        return CheckVerdict.pass(checkId());
    }

    /** Match engine.js's rendering: an integral threshold reads "30", not "30.0". */
    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
}
