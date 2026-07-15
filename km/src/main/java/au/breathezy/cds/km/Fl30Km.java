package au.breathezy.cds.km;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.opencds.hooks.engine.api.CdsHooksEvaluationContext;
import org.opencds.hooks.engine.api.CdsHooksExecutionEngine;
import org.opencds.hooks.model.context.HookContext;
import org.opencds.hooks.model.request.CdsRequest;
import org.opencds.hooks.model.response.Card;
import org.opencds.hooks.model.response.CdsResponse;
import org.opencds.hooks.model.response.Indicator;

/**
 * Shared base for every Breath-Ezy knowledge module.
 *
 * <p>A KM's job is narrow on purpose: run ONE check against the signed knowledge base and report it.
 * It does not compose an overall verdict, it does not decide continuation, and it never emits a dose
 * (the dose KM emits an explicitly advisory {@code dose_candidate}, which the client honours only on
 * PASS/WARN). Composition and the firewall live upstream in {@code breath-ezy}, behind a frozen
 * contract that re-applies every hard rule regardless of what this gateway says.
 *
 * <h2>The binding rule</h2>
 * Every subclass mirrors {@code engine.js} <em>exactly</em>, including its fail-safes. The engine is
 * the specification; a KM is a second implementation of it. That is the whole point: when the two
 * agree the result is corroborated, and when they diverge it is a defect signal in one of them. A KM
 * that "improves on" the engine destroys the only property this arrangement exists to provide.
 *
 * <h2>Card encoding (D-B-2)</h2>
 * One card per check: {@code summary} = the check id, {@code indicator} = mapped severity,
 * {@code detail} = the reason, and the structured verdict in an extension so the Phase C shim can map
 * cards → {@code check_verdicts} without parsing prose. Anything the shim cannot map becomes
 * NOT_RUN — never a drop, never a PASS.
 *
 * <h2>Input</h2>
 * Facts arrive in the hook context as the locked JSON the client already validates ({@code drug},
 * {@code resolved_facts}). An absent or unreadable context is NOT_RUN, not an assumption.
 */
public abstract class Fl30Km implements CdsHooksExecutionEngine {

    protected static final Gson GSON = new Gson();

    /** The frozen check_id this module answers for. */
    public abstract String checkId();

    /**
     * The check itself — mirrors the matching block of engine.js. Pure, so it is directly testable.
     *
     * @return the verdict, or {@code null} when the check DOES NOT APPLY to this drug (e.g. a
     *     non-NTI drug, or a drug with no pregnancy record). engine.js emits no check at all in that
     *     case, so the KM emits no card. That is distinct from NOT_RUN, which means "this check
     *     applies but a fact was missing" and blocks upstream. Collapsing the two would either block
     *     every prescription or silently pass a real gap.
     */
    public abstract CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts);

    @Override
    public CdsResponse evaluate(CdsRequest cdsRequest, CdsHooksEvaluationContext ctx) {
        Fl30KnowledgeBase kb = Fl30KnowledgeBase.get();

        // Fail closed. If the knowledge could not be verified, this KM has nothing trustworthy to say,
        // and saying nothing is not an option either — silence reads as "no problem found". So it
        // reports NOT_RUN with the cause, which upstream turns into BLOCKED_NO_PROOF.
        if (kb.failedClosed()) {
            return respond(CheckVerdict.notRun(checkId(),
                    "knowledge base failed verification: " + kb.failureReason(), java.util.List.of("verified_knowledge_base")));
        }

        JsonObject drug, facts;
        try {
            HookContext hc = cdsRequest.getContext();
            drug = readContext(hc, "drug");
            facts = readContext(hc, "resolved_facts");
        } catch (Exception e) {
            return respond(CheckVerdict.notRun(checkId(), "hook context could not be read: " + e, java.util.List.of("resolved_facts")));
        }
        if (drug == null) {
            return respond(CheckVerdict.notRun(checkId(), "no drug supplied in the hook context", java.util.List.of("drug")));
        }
        if (facts == null) facts = new JsonObject();  // no facts is a real state; each check reports its own NOT_RUN

        try {
            return respond(check(kb, drug, facts));
        } catch (RuntimeException e) {
            // A bug in a KM must not become a PASS. Any unexpected failure degrades to NOT_RUN.
            return respond(CheckVerdict.notRun(checkId(), "check failed to execute: " + e, java.util.List.of("check_execution")));
        }
    }

    private static JsonObject readContext(HookContext hc, String key) {
        if (hc == null) return null;
        Object v = hc.get(key, Object.class);
        if (v == null) return null;
        if (v instanceof JsonObject jo) return jo;
        return GSON.toJsonTree(v).getAsJsonObject();
    }

    /** Encode one verdict as one card (D-B-2). A null verdict means the check does not apply → no card. */
    protected CdsResponse respond(CheckVerdict v) {
        CdsResponse response = new CdsResponse();
        if (v == null) return response;  // check not applicable — the engine emits nothing here either
        Card card = new Card();
        card.setSummary(v.checkId);
        card.setIndicator(indicatorFor(v));
        card.setDetail(v.reason == null ? v.status.name() : v.reason);

        // The structured verdict — what the shim actually maps. The prose above is for a human
        // reading the raw CDS Hooks response; it is never the machine-readable path.
        JsonObject ext = new JsonObject();
        JsonObject verdict = new JsonObject();
        verdict.addProperty("check_id", v.checkId);
        verdict.addProperty("status", v.status.name());
        if (v.severity != null) verdict.addProperty("severity", v.severity.name());
        if (v.reason != null) verdict.addProperty("reason", v.reason);
        if (v.flagType != null) verdict.addProperty("flag_type", v.flagType);
        if (!v.missingFactsRequired.isEmpty()) {
            var arr = new com.google.gson.JsonArray();
            v.missingFactsRequired.forEach(arr::add);
            verdict.add("missing_facts_required", arr);
        }
        ext.add("breathezy.verdict", verdict);
        ext.addProperty("km_set", Fl30KnowledgeBase.EXPECTED_KM_SET);
        card.setExtension(ext);

        response.addCard(card);
        return response;
    }

    /**
     * Severity → CDS Hooks indicator. NOT_RUN maps to WARNING, never INFO: a check that could not run
     * is an open question, and INFO reads as reassurance. Over-signalling a missing fact is the cheap
     * error here; under-signalling it is the expensive one.
     */
    private static Indicator indicatorFor(CheckVerdict v) {
        return switch (v.status) {
            case HARD_FAIL -> Indicator.CRITICAL;
            case WARN, NOT_RUN -> Indicator.WARNING;
            case PASS -> Indicator.INFO;
        };
    }

    // ---- fact readers — absent means ABSENT, never a default ------------------------------------

    protected static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    protected static Double num(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber()
                ? o.get(k).getAsDouble() : null;
    }

    protected static Boolean bool(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean()
                ? o.get(k).getAsBoolean() : null;
    }

    protected static java.util.List<String> strList(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) return null;  // null == not supplied
        var out = new java.util.ArrayList<String>();
        for (var e : o.getAsJsonArray(k)) out.add(e.getAsString().toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    /**
     * The drug's key into the KB: the code when a SIGNED sidecar resolves it, else the canonical name
     * the pipeline already settled (B0). Never a canonicalisation performed here.
     */
    protected static String drugKey(Fl30KnowledgeBase kb, JsonObject drug) {
        String byCode = kb.canonicalNameForCode(str(drug, "rxnorm_code"));
        if (byCode != null) return byCode;
        String name = str(drug, "drug_name");
        return name == null ? null : name.toLowerCase(java.util.Locale.ROOT);
    }
}
