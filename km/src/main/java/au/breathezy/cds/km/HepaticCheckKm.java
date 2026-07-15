package au.breathezy.cds.km;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * hepatic_check — hepatic impairment.
 *
 * <p>Mirrors engine.js exactly:
 * <pre>
 *   no hepatic record for the drug        → (no check emitted)
 *   hepatic_impairment == true, contra    → HARD_FAIL critical (flag hepatic_contraindicated)
 *   hepatic_impairment == true, other     → WARN moderate      (flag hepatic_adjustment_required)
 *   hepatic_impairment == false           → PASS
 *   hepatic_impairment UNKNOWN            → NOT_RUN (missing: hepatic_impairment)
 * </pre>
 *
 * <p>Unlike renal, this dataset is QUALITATIVE — an action plus guidance, with no numeric threshold —
 * so the check keys on a resolved boolean rather than a measured value. That is why an unknown
 * impairment status is NOT_RUN here while an unknown eGFR is only NOT_RUN when a rule exists: renal
 * can ask "could this number matter?", hepatic cannot.
 */
public final class HepaticCheckKm extends Fl30Km {

    @Override
    public String checkId() { return "hepatic_check"; }

    @Override
    public CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        String d = drugKey(kb, drug);
        JsonObject rec = kb.getHepatic(d);
        if (rec == null) return null;  // no record → engine emits no check

        Boolean impaired = bool(facts, "hepatic_impairment");
        if (impaired == null) {
            return CheckVerdict.notRun(checkId(), "hepatic function (impairment) not provided", List.of("hepatic_impairment"));
        }
        if (!impaired) return CheckVerdict.pass(checkId(), "no hepatic impairment");

        String action = str(rec, "action");
        String guidance = str(rec, "guidance");
        boolean contra = "hepatic_contraindicated".equals(action);
        String reason = d + ": " + action + (guidance == null ? "" : " — " + guidance);
        return contra
                ? CheckVerdict.hardFail(checkId(), reason, "hepatic_contraindicated")
                : CheckVerdict.warn(checkId(), CheckVerdict.Severity.moderate, reason, "hepatic_adjustment_required");
    }
}
