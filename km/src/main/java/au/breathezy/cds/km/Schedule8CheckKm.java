package au.breathezy.cds.km;

import com.google.gson.JsonObject;

/**
 * schedule_8_check — SUSMP Schedule 8 (Controlled Drug) PDMP gate.
 *
 * <p>Mirrors engine.js exactly:
 * <pre>
 *   not S8                              → (no check emitted)
 *   S8 && s8_pdmp_checked == true       → PASS
 *   S8 && anything else                 → HARD_FAIL critical (flag schedule_8_pdmp_required)
 * </pre>
 *
 * <h2>The map-miss net, and why it is an OR</h2>
 * A drug is treated as S8 if EITHER the signed scheduling map says S8, OR the intent declares
 * {@code schedule: "S8"}. The engine's comment is explicit about why: a map miss (a brand or spelling
 * variant that did not resolve) must not be able to SUPPRESS the PDMP check for a controlled drug.
 * So the intent can raise the gate, and — as with NTI — it can never lower it: a caller declaring
 * something is not S8 does not overrule the signed map.
 *
 * <p>General (non-S8) scheduling is informational metadata, not a gated check. The frozen
 * {@code check_id} enum has no general schedule check, and the contract is frozen — so S2/S3/S4/S4D
 * produce no verdict here rather than an off-enum one that the client would have to drop.
 *
 * <p>An absent PDMP check is HARD_FAIL rather than NOT_RUN, mirroring NTI: for an S8 drug,
 * "no PDMP check was performed" is not an unknown — it is the finding.
 */
public final class Schedule8CheckKm extends Fl30Km {

    @Override
    public String checkId() { return "schedule_8_check"; }

    @Override
    public CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        String d = drugKey(kb, drug);
        boolean mapSaysS8 = "S8".equals(kb.getSchedule(d));
        boolean intentSaysS8 = "S8".equals(str(drug, "schedule"));
        if (!mapSaysS8 && !intentSaysS8) return null;  // not S8 — no check

        if (Boolean.TRUE.equals(bool(facts, "s8_pdmp_checked"))) {
            return CheckVerdict.pass(checkId(), "AU schedule S8 (SUSMP); PDMP (SafeScript) check recorded");
        }
        return CheckVerdict.hardFail(checkId(),
                "AU schedule S8 (SUSMP): S8 drug requires a PDMP (SafeScript) check — not performed",
                // engine.js also carries au_reference on this flag; the locked OpenCdsFlagSchema is .strict()
                // and has no such field, so it cannot ride. Dropped, not smuggled.
                Flag.of("schedule_8_pdmp_required", CheckVerdict.Severity.critical,
                        d + " is S8 (SUSMP Poisons Standard); PDMP (SafeScript) check required before prescribing", d));
    }
}
