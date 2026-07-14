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
| A | Pinned reproducible build (`build.sh` + `Dockerfile`) | **this repo, now** |
| B | FL-30 KB → OpenCDS knowledge modules (Java KMs) | planned |
| C | Translation shim (Node sidecar: locked JSON ↔ CDS Hooks R4) | planned |
| D | Local A/B parity validation vs the in-process engine | planned |
| E | Staging deploy (App Runner) + A4 validation | gated on FL-12 |

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
