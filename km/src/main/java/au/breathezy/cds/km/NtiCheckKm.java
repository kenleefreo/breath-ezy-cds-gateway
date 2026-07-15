package au.breathezy.cds.km;

import com.google.gson.JsonObject;

/**
 * nti_check — narrow therapeutic index drugs require a documented monitoring plan.
 *
 * <p>Mirrors engine.js exactly:
 * <pre>
 *   not an NTI drug                              → (no check emitted)
 *   NTI && nti_monitoring_documented == true     → PASS
 *   NTI && anything else                         → HARD_FAIL critical (flag nti)
 * </pre>
 *
 * <p>Two subtleties, both load-bearing:
 *
 * <p><b>1. The trigger is a UNION, and it only ever raises.</b> A drug is NTI if the clinician-signed
 * register says so, OR if the intent flags {@code is_nti_candidate}. The intent's flag is a
 * conservative safety-net: it can raise the check but can never suppress it. An intent that said
 * "not a candidate" must not switch off a register entry — that would let the caller talk the safety
 * check out of running, which is the wrong direction of trust.
 *
 * <p><b>2. A missing monitoring fact is HARD_FAIL, not NOT_RUN.</b> This is the one place the engine
 * deliberately does not use NOT_RUN for an absent fact, and it looks like an inconsistency until you
 * read it as the frozen contract's {@code hard_fail_triggers} intends: for an NTI drug, "no monitoring
 * plan is documented" is not an unknown — it is the finding. Undocumented IS the failure.
 */
public final class NtiCheckKm extends Fl30Km {

    @Override
    public String checkId() { return "nti_check"; }

    @Override
    public CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        String d = drugKey(kb, drug);
        JsonObject rec = kb.getNti(d);
        boolean registerSaysNti = rec != null && Boolean.TRUE.equals(bool(rec, "is_nti"));
        boolean intentSaysCandidate = Boolean.TRUE.equals(bool(drug, "is_nti_candidate"));
        if (!registerSaysNti && !intentSaysCandidate) {
            return null;  // not an NTI drug — the engine emits no check at all
        }

        String interval = rec != null && str(rec, "therapeutic_interval") != null
                ? " (target " + str(rec, "therapeutic_interval") + ")" : "";
        if (Boolean.TRUE.equals(bool(facts, "nti_monitoring_documented"))) {
            return CheckVerdict.pass(checkId(), "NTI drug: monitoring plan documented" + interval);
        }
        String hint = rec != null && str(rec, "monitoring_hint") != null ? " — " + str(rec, "monitoring_hint") : "";
        return CheckVerdict.hardFail(checkId(),
                "NTI drug (" + d + ") without a documented monitoring plan" + hint,
                Flag.of("nti", CheckVerdict.Severity.critical,
                        d + " is a narrow therapeutic index drug; a monitoring plan" + interval
                                + " must be documented before prescribing", d));
    }
}
