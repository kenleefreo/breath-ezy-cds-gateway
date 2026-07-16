/**
 * Auth contract for the shim's optional bearer token (SHIM_TOKEN).
 *
 * The token is an EXPOSURE control for a public staging URL, not a safety boundary —
 * the breath-ezy client is fail-closed regardless (a 401 is a transport failure →
 * BLOCKED_NO_PROOF). What this test pins down:
 *   - unset token → open (local dev / docker-compose keep working unchanged);
 *   - set token → only an exact `Authorization: Bearer <token>` passes;
 *   - the comparison refuses prefixes, suffixes, empty and malformed headers
 *     (a length mismatch must short-circuit BEFORE timingSafeEqual, which throws on it).
 *
 * Run: node --test shim/auth.test.mjs
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { isAuthorized } from "./server.mjs";

const T = "0123456789abcdef0123456789abcdef";

test("no token configured → open", () => {
  assert.equal(isAuthorized(undefined, ""), true);
  assert.equal(isAuthorized("Bearer anything", ""), true);
});

test("token configured → exact bearer match passes", () => {
  assert.equal(isAuthorized(`Bearer ${T}`, T), true);
  assert.equal(isAuthorized(`  Bearer ${T}  `, T), true); // header whitespace tolerated
});

test("token configured → everything else refused", () => {
  assert.equal(isAuthorized(undefined, T), false); // no header
  assert.equal(isAuthorized("", T), false); // empty header
  assert.equal(isAuthorized(T, T), false); // raw token without the Bearer scheme
  assert.equal(isAuthorized(`Basic ${T}`, T), false); // wrong scheme
  assert.equal(isAuthorized(`Bearer ${T.slice(0, -1)}`, T), false); // shorter (length mismatch path)
  assert.equal(isAuthorized(`Bearer ${T}0`, T), false); // longer (length mismatch path)
  assert.equal(isAuthorized(`Bearer ${T.slice(0, -1)}X`, T), false); // same length, wrong byte
  assert.equal(isAuthorized("Bearer ", T), false); // scheme with no credential
});
