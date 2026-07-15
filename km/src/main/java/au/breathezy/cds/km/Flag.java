package au.breathezy.cds.km;

/**
 * One flag — the clinician-visible finding behind a verdict.
 *
 * <h2>Why this is a LIST on a verdict, and not one field</h2>
 * A verdict answers "did this check pass?". A flag answers "what did it find?", and one check can find
 * several things: {@code engine.js} pushes a flag PER INTERACTION HIT while emitting a single
 * {@code interaction_check}. Warfarin with amiodarone + aspirin is ONE verdict and TWO flags.
 *
 * The first cut of {@link CheckVerdict} carried a single {@code flagType} string. That collapsed N
 * findings into 1, and the loss is not cosmetic: the client filters {@code flags[]} to build the
 * interaction list a clinician reads, so they would have seen ONE interaction where there are two —
 * and Phase D would have read our own modelling gap as a knowledge divergence between the executors.
 * That is the F6 class of defect exactly: a parity harness chasing an artifact we made ourselves.
 *
 * <h2>The wire is STRICT, and it takes less than the engine carries</h2>
 * {@code OpenCdsFlagSchema} is {@code .strict()} with exactly: {@code flag_type}, {@code severity},
 * {@code description}, and optional {@code drug_a} / {@code drug_b}. The engine's own flags also carry
 * {@code renal_threshold} and {@code au_reference}; those CANNOT ride this contract, and the contract
 * is locked. So they are dropped here deliberately rather than smuggled — a field the wire forbids
 * would fail the whole response, which is fail-safe and useless.
 */
public final class Flag {

    /** A value from the FROZEN flag_type enum. Anything else cannot even parse into a recognised flag. */
    public final String flagType;
    public final CheckVerdict.Severity severity;
    /** Required on the wire (min 1) — a flag the clinician cannot read is not a finding. */
    public final String description;
    public final String drugA;   // nullable — the age flag legitimately names no drug
    public final String drugB;   // nullable — only an interaction has two parties

    private Flag(String flagType, CheckVerdict.Severity severity, String description, String drugA, String drugB) {
        this.flagType = flagType;
        this.severity = severity;
        this.description = description;
        this.drugA = drugA;
        this.drugB = drugB;
    }

    public static Flag of(String flagType, CheckVerdict.Severity severity, String description, String drugA) {
        return new Flag(flagType, severity, description, drugA, null);
    }

    /** An interaction — the only flag with two parties. */
    public static Flag pair(String flagType, CheckVerdict.Severity severity, String description, String drugA, String drugB) {
        return new Flag(flagType, severity, description, drugA, drugB);
    }

    /**
     * A flag that names no drug. engine.js's paediatric flag is the case: the finding is about the
     * PATIENT, not the medicine, so inventing a drug_a to fill the field would assert something the
     * engine does not.
     */
    public static Flag patient(String flagType, CheckVerdict.Severity severity, String description) {
        return new Flag(flagType, severity, description, null, null);
    }

    @Override
    public String toString() { return flagType + (drugA == null ? "" : " [" + drugA + (drugB == null ? "" : " + " + drugB) + "]"); }
}
