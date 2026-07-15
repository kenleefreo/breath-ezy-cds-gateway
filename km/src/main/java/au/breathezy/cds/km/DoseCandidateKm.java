package au.breathezy.cds.km;

import com.google.gson.JsonObject;
import org.opencds.hooks.engine.api.CdsHooksEvaluationContext;
import org.opencds.hooks.model.context.HookContext;
import org.opencds.hooks.model.request.CdsRequest;
import org.opencds.hooks.model.response.Card;
import org.opencds.hooks.model.response.CdsResponse;
import org.opencds.hooks.model.response.Indicator;

/**
 * The dose KM — emits an ADVISORY {@code dose_candidate} from the 451 clinician-attested AU records.
 *
 * <h2>Read this before changing anything here</h2>
 * This is the only module in the gateway that emits a dose, so it carries the tightest constraints in
 * the repo. The invariant is <b>no autonomous prescription</b>: a dose reaching a clinician must come
 * from a clinician-signed record, never from an engine's inference and never from a foreign label.
 *
 * <h2>What this is FOR — and it is NOT "a second dose"</h2>
 * The gateway executes the SAME signed records the in-process engine does. So agreement is
 * <b>corroboration</b>, and divergence is a <b>defect signal in one of the two executors</b> — which
 * is the only way to find out whether the gateway executes our knowledge faithfully. That is exactly
 * what Phase D's A/B parity tests, and dosing is the highest-stakes thing the gateway would execute,
 * so leaving it out would leave the most important thing unmeasured.
 *
 * <h2>Advisory, structurally</h2>
 * The output is rendered beside the AU dose as a second independent executor's opinion. It is
 * <b>never</b> {@code PharmCheck.dose_guidance} — that field stays the clinician-signed AU record,
 * and {@code assertNoAdvisoryInDose()} in breath-ezy throws if this ever lands there. Three
 * independent things have to hold, and they are deliberately not the same thing said three times:
 * <ol>
 *   <li>THE CLIENT drops {@code dose_candidate} unless the composed verdict is PASS/WARN. This KM
 *       cannot see the composite verdict — it is one module among nine, and composition is the
 *       client's job behind the frozen contract. So a dose can never survive a blocked firewall,
 *       whatever this class does.</li>
 *   <li>THIS KM refuses on paediatric or unknown age, mirroring engine.js's {@code !paediatric}
 *       guard. Defence in depth: the client's gate is the one that must hold, but a module that
 *       would hand a child an adult dose if the client ever changed is not one I want to write.</li>
 *   <li>THE RECORD is the only source. A drug with no signed dose yields NO dose — never a
 *       substitute, never a neighbouring record, never an inference. Missing proof → nothing.</li>
 * </ol>
 *
 * <h2>Why an unknown age refuses</h2>
 * There are no paediatric dosing tables in this system at all. If age is unknown, this dataset —
 * which is adult-only by construction (the 232 paediatric rows were deliberately excluded from the
 * attestation) — could be handed to a child. Unknown age is not "probably an adult".
 */
public final class DoseCandidateKm extends Fl30Km {

    /**
     * The frozen dose keys, mirroring engine.js's DOSE_KEYS pick. Copying the WHOLE record would drag
     * provenance and attestation fields onto the wire, where the client's strict contract would
     * reject them — and, worse, would put the clinician's signature block into a field that is
     * explicitly advisory. Only the dose itself travels.
     */
    private static final String[] DOSE_KEYS = {
        "safe_dose_range", "adjustment_required", "adjustment_reason", "monitoring_required",
        "duration_guidance", "pbs_authority_required", "pbs_item_code",
    };

    @Override
    public String checkId() { return null; }  // not a check — this KM emits no verdict

    @Override
    public CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        return null;  // never a verdict; see doseCandidate()
    }

    /**
     * The dose, or null. Pure, so the refusals are directly testable.
     *
     * @return the picked dose object, or {@code null} when there is no signed dose for this drug, or
     *     when age is paediatric/unknown.
     */
    public JsonObject doseCandidate(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        Double age = num(facts, "patient_age_years");
        if (age == null || age < 18) return null;  // paediatric or unconfirmed → no dose, ever

        String d = drugKey(kb, drug);
        JsonObject rec = kb.getDoseGuidance(d);
        if (rec == null) return null;              // no signed dose → no dose. Never a substitute.

        JsonObject out = new JsonObject();
        for (String k : DOSE_KEYS) if (rec.has(k)) out.add(k, rec.get(k));
        return out.size() == 0 ? null : out;       // a record with no dose content is not a dose
    }

    @Override
    public CdsResponse evaluate(CdsRequest cdsRequest, CdsHooksEvaluationContext ctx) {
        Fl30KnowledgeBase kb = Fl30KnowledgeBase.get();
        CdsResponse response = new CdsResponse();

        // Fail closed. Unverified knowledge yields NO dose — and silence here is safe, because a
        // missing dose_candidate simply means the client has none to render. That is the opposite of
        // a check, where silence would read as "nothing wrong".
        if (kb.failedClosed()) return response;

        JsonObject drug, facts;
        try {
            HookContext hc = cdsRequest.getContext();
            drug = hc == null ? null : GSON.toJsonTree(hc.get("drug", Object.class)).getAsJsonObject();
            Object rf = hc == null ? null : hc.get("resolved_facts", Object.class);
            facts = rf == null ? new JsonObject() : GSON.toJsonTree(rf).getAsJsonObject();
        } catch (Exception e) {
            return response;   // unreadable context → no dose
        }
        if (drug == null) return response;

        JsonObject dose;
        try {
            dose = doseCandidate(kb, drug, facts);
        } catch (RuntimeException e) {
            return response;   // a bug here must never become a dose
        }
        if (dose == null) return response;

        Card card = new Card();
        card.setSummary("dose_candidate");
        card.setIndicator(Indicator.INFO);
        card.setDetail("Advisory AU dose candidate from the clinician-signed FL-30 record, executed independently by OpenCDS. "
                + "This is a second executor's reading of the same signed knowledge — it corroborates the in-process engine, "
                + "and is never the authoritative dose.");

        JsonObject ext = new JsonObject();
        ext.add("breathezy.dose_candidate", dose);
        ext.addProperty("advisory", true);
        ext.addProperty("km_set", Fl30KnowledgeBase.EXPECTED_KM_SET);
        card.setExtension(ext);

        response.addCard(card);
        return response;
    }
}
