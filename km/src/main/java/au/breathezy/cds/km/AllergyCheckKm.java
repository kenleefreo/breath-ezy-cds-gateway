package au.breathezy.cds.km;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * allergy_check — cross-reactivity against documented allergens.
 *
 * <p>Mirrors engine.js §1 exactly:
 * <pre>
 *   allergens not supplied            → NOT_RUN  (missing: allergy_status)
 *   drug's group == an allergen group → HARD_FAIL critical (flag allergy_cross_reactivity)
 *   otherwise                         → PASS
 * </pre>
 *
 * <p>Note what is NOT here: a drug with no allergy-group record PASSES rather than blocking. That
 * mirrors the engine, and it is deliberate — the group registry covers cross-reactivity CLASSES
 * (beta-lactams and the like), so "no group" means "no known cross-reactivity class", not "unknown".
 * The missing-fact case that genuinely blocks is an absent allergy history, which is the NOT_RUN above.
 */
public final class AllergyCheckKm extends Fl30Km {

    @Override
    public String checkId() { return "allergy_check"; }

    @Override
    public CheckVerdict check(Fl30KnowledgeBase kb, JsonObject drug, JsonObject facts) {
        List<String> allergens = strList(facts, "allergens");
        if (allergens == null) {
            return CheckVerdict.notRun(checkId(), "allergy status not provided", List.of("allergy_status"));
        }
        String d = drugKey(kb, drug);
        String group = kb.getAllergyGroup(d);
        if (group != null) {
            for (String a : allergens) {
                if (group.equals(kb.getAllergyGroup(a))) {
                    return CheckVerdict.hardFail(checkId(),
                            "cross-reactivity: " + d + " shares allergy group '" + group + "' with a documented allergen",
                            "allergy_cross_reactivity");
                }
            }
        }
        return CheckVerdict.pass(checkId());
    }
}
