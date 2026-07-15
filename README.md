# breath-ezy-cds-gateway

Deployed OpenCDS execution gateway for the **Breath-Ezy AI Doctor** — the open-source
(`AU_OSS_CDS`) route for the pharmacology `cds-adapter` slot (FL-34, Track A).

This repo holds **deployed infrastructure**, not application logic. It is deliberately
separate from `kenleefreo/breath-ezy` because OpenCDS is Java / Maven / Tomcat, whereas
`breath-ezy` is Node 20 ESM with no build step. The frozen wire contract, the fail-closed
client, and the firewall fold all live in `breath-ezy`; this repo only builds and runs the
OpenCDS engine that sits behind that contract.

## Safety posture (read first)

OpenCDS supplies **execution + standards packaging** over the clinician-signed FL-30
knowledge base — it never introduces new clinical knowledge. Deploying this image does
**not** open a patient-facing path:

- The `breath-ezy` `cds-adapter` slot stays **EMPTY → HARD_FAIL** until an endpoint is
  wired **and** staging-validated (Phase E / A4).
- The client re-validates every gateway response fail-closed and re-applies the hard rules
  (no dose on HARD_FAIL / paediatric / BLOCKED).
- Receipts stay `mode=mock` until regulatory sign-off (FL-50) — never mock-as-live.

## Build phases

| Phase | What | Status |
|---|---|---|
| A | Pinned reproducible build (`build.sh` + `Dockerfile`) | done |
| B1 | FL-30 KB export + checksummed bundle (`tools/`, `kb/`) | **done** |
| B2–B4 | The 9 knowledge modules (Java KMs) | **this repo, now** |
| C | Translation shim (Node sidecar: locked JSON ↔ CDS Hooks R4) | **done** — `shim/`, in this image |
| D | Local A/B parity validation vs the in-process engine | **done** — breath-ezy `test/parity-opencds-gateway.js` (env-gated) |
| E | Staging deploy (App Runner) + A4 validation | gated on FL-12 |

## Phase B1 — the knowledge bundle

`kb/` is a **committed, versioned release artifact** (`km_set = fl30-kb:v2`), exported from a
clinician-signed `breath-ezy` datastore by `tools/export-fl30-kb.mjs`. It is committed rather
than built at image-build time so the image builds reproducibly from this repo alone, and so a
knowledge change must be a **deliberate re-export to a new `km_set`** rather than something that
silently rides along.

```bash
# Re-export (read-only against a breath-ezy checkout). Requires Node 20+; no dependencies.
node tools/export-fl30-kb.mjs --datastore ../breath-ezy [--dry-run]

# The filter's contract tests — every gate below is proven here, including the aborts.
node --test tools/*.test.mjs
```

**Four gates, in order — the allowlist runs FIRST, and sign-off is never sufficient:**

1. **Allowlist (F5).** Only the 8 capabilities an `engine.js` accessor actually reads may become
   executable. Everything else is excluded **regardless of attestation state** — `international-dose-guidance`
   (US/EU labels) is excluded today only because it happens to be unsigned, and its own attestation
   says clinical sign-off is not required. Australian-context is a hard limit; it does not get to
   depend on an accident.
2. **Sign-off.** Dataset-level `clinical_sign_off === true`. Necessary, never sufficient.
3. **Provenance.** `records_checksum` re-computed with breath-ezy's own function — drift **aborts
   the export**, because a broken seal means the clinician's signature no longer covers the bytes.
4. **Per-record.** `provenance.review_status === "approved"`, which is authoritative over the
   dataset flag.

Every exclusion is recorded in `manifest.excluded[]` — a silent drop is indistinguishable from a bug.

**Current bundle (`fl30-kb:v2`):** 8 capabilities · 1776 signed + approved records · 17 capabilities
excluded · identity sidecar **522 codes**, `rxcui_active: true` · 415 name-only subjects.

### v1 → v2 (2026-07-15) — why the version moved

v1 was exported while the drug vocabulary was **unsigned**, so the checkout's own `identityCode()`
returned null for every drug: the sidecar was empty and the KB matched by name. A clinician signed
the vocabulary, and the same export now yields 522 codes.

That changes **how a KM resolves which drug a request is about** — a knowledge change — so it gets a
new `km_set` rather than silently riding along inside v1. The version is what makes the transition
safe: the client cross-checks it on every response, so a gateway still serving v1 to a v2 client (or
the reverse) returns **`BLOCKED_NO_PROOF`** instead of answering from a knowledge set nobody asked
for. **Both directions fail safe**, which is why the bump is safe to make before any gateway is
deployed. Bumping means editing all three pins deliberately:

| Pin | Where |
|---|---|
| `KM_SET` | `tools/export-fl30-kb.mjs` |
| `EXPECTED_KM_SET` | `km/…/Fl30KnowledgeBase.java` |
| `DEFAULT_KM_SET` | `breath-ezy` `cds-adapter/opencds-client.js` |

The 415 name-only subjects are not a gap to close: they are combination products
(`trimethoprim with sulfamethoxazole`) and classes (`oestrogens`, `ferrous salts`) that RxNorm models
as multi-ingredient concepts a single-name lookup does not resolve. They carry real signed knowledge —
which is exactly why a **code-only** contract would fail and the name must still ride.

**The code is a key, not a second identity.** The KM resolves a code the pipeline already settled;
it never canonicalises a name itself (a second, divergent canonicaliser is the defect the single
upstream identity boundary exists to prevent). And if a code and a name **disagree**, the KM
**refuses** — it cannot know which of the two is wrong, so a check degrades to `NOT_RUN` and the dose
KM emits nothing.

## Phase A — build & run

**Requirements:** git, JDK 17, Maven 3.9+ (local build); Docker (image build).

```bash
# Local build — clones the 7 OpenCDS repos at the pinned commits and builds the WAR.
./build.sh

# Or build the container image (build + runtime, one command):
docker build -t breath-ezy-cds-gateway .
docker run --rm -p 8080:8080 breath-ezy-cds-gateway
```

**Smoke test** (once the container logs `Server startup in [...] milliseconds`):

```bash
# Discovery — lists the loaded knowledge modules as CDS services.
curl http://localhost:8080/opencds/r4/hooks/cds-services

# Evaluate — POST a patient-view hook (prefetch values are BARE FHIR resources).
curl -X POST http://localhost:8080/opencds/r4/hooks/cds-services/example-knowledge-module-r4 \
  -H 'Content-Type: application/json' \
  -d '{"hook":"patient-view","hookInstance":"x","context":{"userId":"Practitioner/x","patientId":"1"},
       "prefetch":{"Patient":{"resourceType":"Patient","id":"1","active":true}}}'
```

Two endpoint quirks confirmed on the 2026-07-14 proof-of-deploy and baked into the client:
the CDS-Hooks path is `/<context>/r4/hooks/cds-services`, and prefetch values are **bare
FHIR resources** (not wrapped in a `{response,resource}` envelope).

## The pin

`pinned-commits.env` records the exact commit hash of each of the 7 OpenCDS repos that
produced a working build on 2026-07-14 (OpenCDS 7.1.0-SNAPSHOT). OpenCDS ships
SNAPSHOT-only with no tagged releases, so an exact SHA is the only stable pin. Bump only
under an approved plan (`breath-ezy` charter `<standards_pins>`).

## No secrets here

Real endpoints and credentials are injected at deploy time from a secrets manager. The env
templates use placeholder values by design; nothing real is ever committed.
