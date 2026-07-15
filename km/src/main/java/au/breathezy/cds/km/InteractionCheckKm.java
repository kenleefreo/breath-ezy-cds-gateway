package au.breathezy.cds.km;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * interaction_check — drug-drug interactions against the current medication list.
 *
 * <p>Mirrors engine.js §2 exactly:
 * <pre>
 *   current_medications not supplied     → NOT_RUN (missing: current_medications)
 *   any hit with severity "critical"     → HARD_FAIL critical (flag interaction_severe)
 *   any other hit                        → WARN moderate     (flag interaction_moderate)
 *   no hits                              → PASS
 * </pre>
 *
 * <p>The engine matches a record when {@code (a == drug && meds contains b) || (b == drug && meds
 * contains a)} — i.e. the pair is unordered, and BOTH parties must be identified. The KB indexes
 * interactions under both sides so either direction is reachable.
 *
 * <p><b>Identity is why this check is the one that bites.</b> E6 found a dose being emitted under an
 * alias while this check silently passed, because the dose lookup resolved the alias and the
 * interaction lookup did not. Consistency across accessors is the safety property — which is why
 * identity is settled once, upstream, before either executor runs, and never inside a check.
 */
public final class InteractionCheckKm extends Fl30Km {

    @Override
    public String checkId() { return "interaction_check"; }

    @Override
    public CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        List<String> meds = strList(facts, "current_medications");
        if (meds == null) {
            return CheckVerdict.notRun(checkId(), "current medications not provided", List.of("current_medications"));
        }
        String d = drugKey(kb, drug);
        List<JsonObject> hits = new ArrayList<>();
        for (JsonObject ix : kb.getInteractions(d)) {
            String a = lower(str(ix, "subject"));
            String b = lower(str(ix, "object"));
            if ((d.equals(a) && b != null && meds.contains(b)) || (d.equals(b) && a != null && meds.contains(a))) hits.add(ix);
        }
        if (hits.isEmpty()) return CheckVerdict.pass(checkId());

        // A FLAG PER HIT — engine.js: `hits.forEach((h, i) => flags.push({...}))` alongside ONE
        // interaction_check. This is the whole reason a verdict carries a LIST: warfarin with amiodarone
        // AND aspirin is one verdict and TWO findings, and the client filters flags[] to build the
        // interaction list a clinician actually reads. Collapsing them would show ONE interaction where
        // there are two — and Phase D would read our own modelling gap as knowledge divergence.
        //
        // Per-hit severity, not the check's: engine.js flags each hit at ITS OWN severity, so a moderate
        // hit stays moderate on the card even when a critical sibling drives the verdict to HARD_FAIL.
        // Rolling them up would overstate the moderate one — a flag is a finding, not a verdict.
        List<Flag> flags = new ArrayList<>();
        for (JsonObject ix : hits) {
            boolean crit = "critical".equals(str(ix, "severity"));
            String a = lower(str(ix, "subject"));
            String b = lower(str(ix, "object"));
            // engine.js description: `${a} + ${b}: ${note}` where note is the mechanism_class it maps in.
            String note = str(ix, "mechanism_class");
            flags.add(Flag.pair(crit ? "interaction_severe" : "interaction_moderate",
                    crit ? CheckVerdict.Severity.critical : CheckVerdict.Severity.moderate,
                    a + " + " + b + (note == null ? "" : ": " + note), a, b));
        }

        boolean critical = hits.stream().anyMatch(h -> "critical".equals(str(h, "severity")));
        return critical
                ? CheckVerdict.hardFail(checkId(), "interaction(s) detected", flags)
                : CheckVerdict.warn(checkId(), CheckVerdict.Severity.moderate, "interaction(s) detected", flags);
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }
}
