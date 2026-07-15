package au.breathezy.cds.km;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The FL-30 knowledge base, loaded from the classpath and VERIFIED before a single check runs.
 *
 * <p>This class is the gateway's whole claim to trustworthiness. OpenCDS supplies execution; the
 * knowledge is clinician-signed and exported from {@code breath-ezy} by {@code tools/export-fl30-kb.mjs}.
 * If the bytes we execute are not the bytes a clinician signed, every verdict downstream is
 * unfounded — so the load either verifies completely or the KM refuses to run at all.
 *
 * <h2>Fail-closed, not fail-safe-ish</h2>
 * A checksum mismatch, a missing file, a malformed manifest or an unexpected {@code km_set} all put
 * this instance into a permanent {@link #failedClosed()} state. Every KM checks that first and emits
 * {@code NOT_RUN} for its check. It never degrades to a default PASS: a PASS the clinician believes
 * came from signed knowledge, but didn't, is the worst output this system can produce — strictly
 * worse than no answer, because it is an answer that will be trusted.
 *
 * <h2>Why the TRANSPORT layer is sha256-over-bytes</h2>
 * The export verifies {@code records_checksum} in Node using breath-ezy's own canonical-JSON
 * function. Java deliberately does NOT re-derive that form — a second implementation of a canonical
 * JSON encoding agrees until the day it doesn't, and then a real tamper reads as a false alarm (or a
 * broken seal reads as intact). So Java verifies a plain sha256 over the exported bytes, which is
 * unambiguous and has exactly one correct answer.
 *
 * <h2>Identity is NOT resolved here</h2>
 * The KB is keyed by canonical name, exactly as signed. When the pipeline sends an {@code rxnorm_code}
 * this class maps it to a canonical name via the sidecar and then looks the name up. That is a
 * key-match, not a resolution: the gateway must never canonicalise a drug name itself, because a
 * second, divergent canonicaliser is precisely the defect the single upstream identity boundary
 * exists to prevent. The sidecar is empty while the drug vocabulary is unsigned, so today the name
 * is the key — and the pipeline canonicalises before both executors, which is what makes that safe.
 */
public class Fl30KnowledgeBase {   // non-final ONLY so tests can stub an accessor; nothing overrides it in production

    /** The KM set this build answers to. A bundle stamped anything else is refused. */
    public static final String EXPECTED_KM_SET = "fl30-kb:v1";

    private static final Gson GSON = new Gson();
    private static volatile Fl30KnowledgeBase instance;

    private final String failure;                       // non-null == failed closed
    private final Map<String, JsonArray> records = new HashMap<>();
    private final Map<String, String> rxcuiToName = new HashMap<>();
    private boolean rxcuiActive;

    // Indexes, built once from the signed records. Keyed lowercase — the export writes canonical
    // (lowercase) names, and matching must not hinge on case.
    private final Map<String, String> allergyGroupByMember = new HashMap<>();
    private final Map<String, List<JsonObject>> interactionsByDrug = new HashMap<>();
    private final Map<String, JsonObject> renalByDrug = new HashMap<>();
    private final Map<String, JsonObject> ntiByDrug = new HashMap<>();
    private final Map<String, JsonObject> scheduleByDrug = new HashMap<>();
    private final Map<String, JsonObject> pregnancyByDrug = new HashMap<>();
    private final Map<String, JsonObject> hepaticByDrug = new HashMap<>();
    private final Map<String, JsonObject> doseByDrug = new HashMap<>();

    public static Fl30KnowledgeBase get() {
        Fl30KnowledgeBase local = instance;
        if (local == null) {
            synchronized (Fl30KnowledgeBase.class) {
                local = instance;
                if (local == null) instance = local = new Fl30KnowledgeBase(Fl30KnowledgeBase.class.getClassLoader());
            }
        }
        return local;
    }

    /** Visible for tests: load from an arbitrary classloader so a tampered bundle can be exercised. */
    Fl30KnowledgeBase(ClassLoader loader) {
        String err = null;
        try {
            byte[] manifestBytes = read(loader, "kb/manifest.json");
            JsonObject manifest = GSON.fromJson(new String(manifestBytes, StandardCharsets.UTF_8), JsonObject.class);

            String kmSet = manifest.get("km_set").getAsString();
            if (!EXPECTED_KM_SET.equals(kmSet)) {
                throw new IllegalStateException("km_set mismatch: bundle is '" + kmSet + "', this build executes '"
                        + EXPECTED_KM_SET + "'. Executing knowledge the client did not ask for is not a recoverable condition.");
            }

            for (var el : manifest.getAsJsonArray("capabilities")) {
                JsonObject cap = el.getAsJsonObject();
                String file = cap.get("file").getAsString();
                byte[] bytes = read(loader, "kb/" + file);
                String expected = cap.get("file_sha256").getAsString();
                String actual = sha256(bytes);
                if (!expected.equals(actual)) {
                    throw new IllegalStateException("checksum mismatch on " + file + ": manifest says " + expected
                            + ", bytes hash to " + actual + ". These are not the bytes a clinician signed.");
                }
                JsonObject body = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
                records.put(cap.get("capability").getAsString(), body.getAsJsonArray("records"));
            }

            // The identity sidecar. Verified like any other file, then honoured ONLY if the manifest
            // says a signed source populated it. An unsigned identity map may BLOCK, never STEER.
            JsonObject sidecar = manifest.getAsJsonObject("identity_sidecar");
            byte[] sidecarBytes = read(loader, "kb/" + sidecar.get("file").getAsString());
            if (!sidecar.get("file_sha256").getAsString().equals(sha256(sidecarBytes))) {
                throw new IllegalStateException("checksum mismatch on the identity sidecar");
            }
            rxcuiActive = manifest.getAsJsonObject("key_policy").get("rxcui_active").getAsBoolean();
            if (rxcuiActive) {
                JsonObject map = GSON.fromJson(new String(sidecarBytes, StandardCharsets.UTF_8), JsonObject.class)
                        .getAsJsonObject("rxcui_to_canonical_name");
                for (String code : map.keySet()) rxcuiToName.put(code, map.get(code).getAsString().toLowerCase(Locale.ROOT));
            }

            index();
        } catch (Exception e) {
            err = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        this.failure = err;
    }

    private void index() {
        for (var el : capability("allergy")) {
            JsonObject r = el.getAsJsonObject();
            String group = r.get("group").getAsString();
            for (var m : r.getAsJsonArray("members")) allergyGroupByMember.put(lower(m.getAsString()), group);
        }
        // engine.js filters getInteractions(drug) on (a==drug && meds has b) || (b==drug && meds has a),
        // so both directions must be reachable from either party's name.
        for (var el : capability("interactions")) {
            JsonObject r = el.getAsJsonObject();
            for (String side : new String[]{"subject", "object"}) {
                if (!r.has(side)) continue;
                interactionsByDrug.computeIfAbsent(lower(r.get(side).getAsString()), k -> new ArrayList<>()).add(r);
            }
        }
        byIngredient("renal", renalByDrug);
        byIngredient("nti", ntiByDrug);
        byIngredient("scheduling", scheduleByDrug);
        byIngredient("hepatic", hepaticByDrug);
        byIngredient("dose_guidance", doseByDrug);
        for (var el : capability("pregnancy_risk")) {
            JsonObject r = el.getAsJsonObject();
            if (r.has("subject")) pregnancyByDrug.put(lower(r.get("subject").getAsString()), r);
        }
    }

    private void byIngredient(String capability, Map<String, JsonObject> into) {
        for (var el : capability(capability)) {
            JsonObject r = el.getAsJsonObject();
            if (r.has("ingredient")) into.put(lower(r.get("ingredient").getAsString()), r);
        }
    }

    private JsonArray capability(String name) {
        JsonArray a = records.get(name);
        return a == null ? new JsonArray() : a;
    }

    private static byte[] read(ClassLoader loader, String path) throws Exception {
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing knowledge file on the classpath: " + path);
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

    // ---- state ---------------------------------------------------------------------------------

    /** True when the bundle could not be verified. Every KM must consult this and emit NOT_RUN. */
    public boolean failedClosed() { return failure != null; }

    /** Why the load failed — surfaced on the NOT_RUN card so an operator sees the cause, not a silence. */
    public String failureReason() { return failure; }

    public boolean rxcuiActive() { return rxcuiActive; }

    /**
     * Map an incoming code to the canonical name the records are keyed on. NOT a canonicaliser: it
     * only resolves a code the pipeline already settled, and only from a signed sidecar. Returns null
     * when the code is unknown or the sidecar is unsigned — the caller then keys on the name, which
     * the pipeline has already canonicalised (B0).
     */
    public String canonicalNameForCode(String rxcui) {
        if (!rxcuiActive || rxcui == null) return null;
        return rxcuiToName.get(rxcui);
    }

    // ---- accessors — one per engine.js accessor, same names, same semantics ---------------------

    public String getAllergyGroup(String drug) { return allergyGroupByMember.get(lower(drug)); }

    public List<JsonObject> getInteractions(String drug) {
        return interactionsByDrug.getOrDefault(lower(drug), List.of());
    }

    public JsonObject getRenalRule(String drug) { return renalByDrug.get(lower(drug)); }
    public JsonObject getNti(String drug) { return ntiByDrug.get(lower(drug)); }
    public JsonObject getPregnancyRisk(String drug) { return pregnancyByDrug.get(lower(drug)); }
    public JsonObject getHepatic(String drug) { return hepaticByDrug.get(lower(drug)); }
    public JsonObject getDoseGuidance(String drug) { return doseByDrug.get(lower(drug)); }

    /**
     * engine.js: getSchedule(drug) returns the schedule string, or the literal {@code "unknown"} when
     * the drug is absent from the map — NOT null. Mirrored exactly: {@code knownDrug()} upstream keys
     * off {@code !== "unknown"}, so returning null here would be a quiet semantic drift between the
     * two executors, which is the one thing a parity harness cannot see past.
     */
    public String getSchedule(String drug) {
        JsonObject r = scheduleByDrug.get(lower(drug));
        return r == null || !r.has("schedule") ? "unknown" : r.get("schedule").getAsString();
    }
}
