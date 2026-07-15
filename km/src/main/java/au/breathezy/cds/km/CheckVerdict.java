package au.breathezy.cds.km;

import java.util.List;

/**
 * One check's verdict — the KM's entire output vocabulary, and deliberately a small one.
 *
 * <p>The status values mirror {@code engine.js} exactly, and the shim maps them back into the frozen
 * {@code pharm-check} contract. A KM may not invent a status: anything the client cannot map is
 * turned into {@code NOT_RUN}, never dropped and never defaulted to PASS.
 *
 * <p>{@link Status#NOT_RUN} is the load-bearing one. It is what a KM emits when a fact it needs was
 * not supplied, or when the knowledge base failed to verify. Upstream it forces
 * {@code BLOCKED_NO_PROOF}, which is the system's fail-safe default: if proof is missing, return
 * blocked — never degrade to a plausible answer.
 */
public final class CheckVerdict {

    public enum Status { PASS, WARN, HARD_FAIL, NOT_RUN }
    public enum Severity { critical, moderate, minor }

    public final String checkId;
    public final Status status;
    public final Severity severity;      // null on PASS / NOT_RUN
    public final String reason;
    public final List<String> missingFactsRequired;  // non-empty only on NOT_RUN
    public final String flagType;        // frozen flag_type enum value, or null

    private CheckVerdict(String checkId, Status status, Severity severity, String reason, List<String> missing, String flagType) {
        this.checkId = checkId;
        this.status = status;
        this.severity = severity;
        this.reason = reason;
        this.missingFactsRequired = missing == null ? List.of() : List.copyOf(missing);
        this.flagType = flagType;
    }

    public static CheckVerdict pass(String checkId) { return new CheckVerdict(checkId, Status.PASS, null, null, null, null); }
    public static CheckVerdict pass(String checkId, String reason) { return new CheckVerdict(checkId, Status.PASS, null, reason, null, null); }
    public static CheckVerdict warn(String checkId, Severity sev, String reason, String flagType) { return new CheckVerdict(checkId, Status.WARN, sev, reason, null, flagType); }
    public static CheckVerdict hardFail(String checkId, String reason, String flagType) { return new CheckVerdict(checkId, Status.HARD_FAIL, Severity.critical, reason, null, flagType); }

    /** A fact was missing. NOT a soft PASS — upstream this forces BLOCKED_NO_PROOF. */
    public static CheckVerdict notRun(String checkId, String reason, List<String> missing) {
        return new CheckVerdict(checkId, Status.NOT_RUN, null, reason, missing, null);
    }

    @Override
    public String toString() { return checkId + "=" + status + (reason == null ? "" : " (" + reason + ")"); }
}
