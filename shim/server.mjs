#!/usr/bin/env node
/**
 * The Phase C shim — the Node sidecar that lets the fail-closed `breath-ezy` client talk to OpenCDS.
 *
 * `POST /pharm-check`  (the locked JSON contract the client already speaks)
 *      → fan out, in PARALLEL, one CDS Hooks R4 call per requested check + the dose KM
 *      → merge the cards back into one locked response
 *
 * ALL of the decisions live in `map.mjs` as pure functions. This file is the IO edge and nothing more:
 * it opens sockets, sets timeouts, and hands bytes to the mapper. That split is deliberate — every
 * fail-safe worth proving is provable without a container, and the parts that need a container
 * (sockets, timeouts) are the parts with no judgement in them.
 *
 * NO DEPENDENCIES. `node:http` only. A shim that translates a safety contract is the last place to
 * inherit somebody else's supply chain.
 *
 * Usage:
 *   node shim/server.mjs                       # defaults: :8081 → http://localhost:8080/opencds
 *   SHIM_PORT=8081 OPENCDS_BASE=... node shim/server.mjs
 */
import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { buildHookRequest, mergeResults, SERVICE_FOR, DOSE_SERVICE } from "./map.mjs";

const PORT = Number(process.env.SHIM_PORT || 8081);
const BASE = (process.env.OPENCDS_BASE || "http://localhost:8080/opencds").replace(/\/+$/, "");
/** Per-call. A KM that hangs must not hang the request — it becomes NOT_RUN like any other failure.
 *  Deploy note: a cold JVM's first evaluations exceed 5s (observed on App Runner, 2026-07-16, as
 *  NOT_RUN → BLOCKED_NO_PROOF on a fresh instance) — deployed services set SHIM_TIMEOUT_MS=15000. */
const TIMEOUT_MS = Number(process.env.SHIM_TIMEOUT_MS || 5000);
/** Optional shared bearer token. The gateway serves clinician-signed KNOWLEDGE (never patient data)
 *  and the client re-validates fail-closed — the token is an exposure control for a public staging
 *  URL, not a safety boundary. Unset → open (local dev/containers); deployed services set SHIM_TOKEN. */
const TOKEN = (process.env.SHIM_TOKEN || "").trim();

/** Constant-time bearer check. Exported for the auth test. `/healthz` is never gated — a health
 *  check that needs a secret is a health check the platform cannot run. */
export function isAuthorized(authHeader, token = TOKEN) {
  if (!token) return true;
  const m = /^Bearer[ \t]+(.+)$/.exec(String(authHeader || "").trim());
  if (!m) return false;
  const presented = Buffer.from(m[1]);
  const expected = Buffer.from(token);
  return presented.length === expected.length && timingSafeEqual(presented, expected);
}

/** POST one CDS Hooks request. NEVER throws: a failure is a RESULT, because every failure mode here
 *  has the same safe answer (NOT_RUN) and a thrown error would take the other checks down with it. */
async function callService(serviceId, body) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(`${BASE}/r4/hooks/cds-services/${serviceId}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
      signal: ac.signal,
    });
    if (!res.ok) return { ok: false, error: `HTTP ${res.status}` };
    const json = await res.json();
    return { ok: true, cards: Array.isArray(json?.cards) ? json.cards : [] };
  } catch (e) {
    return { ok: false, error: e.name === "AbortError" ? `timeout after ${TIMEOUT_MS}ms` : String(e.message || e) };
  } finally {
    clearTimeout(timer);
  }
}

/** The fan-out. PARALLEL (D-C-3): the checks are independent, and 9 sequential round-trips would make
 *  latency the reason someone turns the gateway off. */
export async function evaluate(request, call = callService) {
  const checks = request.checks_requested || [];
  const hook = buildHookRequest(request, request.request_id);

  const [results, doseResult] = await Promise.all([
    Promise.all(checks.map(async (check_id) => {
      const service = SERVICE_FOR[check_id];
      if (!service) return { check_id, ok: true, cards: [] }; // no KM mirrors it — mergeResults says NOT_RUN
      return { check_id, ...(await call(service, hook)) };
    })),
    // Unconditional (D-C-4). A shim that decided WHEN a dose is allowed would be composing, which is
    // exactly what it must not do. The client gates it on PASS/WARN.
    call(DOSE_SERVICE, hook),
  ]);

  return mergeResults(request, results, doseResult);
}

const send = (res, code, body) => {
  const s = JSON.stringify(body);
  res.writeHead(code, { "content-type": "application/json", "content-length": Buffer.byteLength(s) });
  res.end(s);
};

const server = createServer((req, res) => {
  if (req.method === "GET" && req.url === "/healthz") return send(res, 200, { ok: true, upstream: BASE });
  if (!isAuthorized(req.headers.authorization)) return send(res, 401, { error: "unauthorized" });
  if (req.method !== "POST" || req.url !== "/pharm-check") return send(res, 404, { error: "not found" });

  let raw = "";
  req.on("data", (c) => {
    raw += c;
    if (raw.length > 1_000_000) { req.destroy(); }   // a safety shim is not a general-purpose upload endpoint
  });
  req.on("end", async () => {
    let request;
    try {
      request = JSON.parse(raw);
    } catch {
      return send(res, 400, { error: "malformed JSON" });
    }
    // The shim does NOT re-validate the locked contract: the client validates on the way out and
    // fail-closed on the way back, and a second, divergent validator here would be one more thing to
    // drift. It only needs enough to fan out.
    if (!request?.request_id || !request?.drug || !Array.isArray(request?.checks_requested)) {
      return send(res, 400, { error: "request_id, drug and checks_requested are required" });
    }
    try {
      send(res, 200, await evaluate(request));
    } catch (e) {
      // A bug in the shim must not become a verdict. No response at all → the client's transport
      // failure → BLOCKED_NO_PROOF, which is where an unknown state belongs.
      send(res, 500, { error: `shim failure: ${e.message}` });
    }
  });
});

if (import.meta.url === `file://${process.argv[1]}`) {
  server.listen(PORT, () => console.log(`fl30-shim: :${PORT} → ${BASE} (timeout ${TIMEOUT_MS}ms, auth ${TOKEN ? "bearer-token" : "OPEN — set SHIM_TOKEN on any public deployment"})`));
}
