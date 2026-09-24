/*
  Runs the JS engine against the shared vectors and checks the knowledge base.
  The same vectors drive the Swift and Kotlin tests, so passing here is one leg
  of a three-legged guarantee that a finding is the same on every platform.
*/
"use strict";
var fs = require("fs");
var path = require("path");

var Soor = require("../docs/engine/soor.js");

function readJson(p) { return JSON.parse(fs.readFileSync(path.join(__dirname, p), "utf8")); }

var services = readJson("../docs/data/services.json").services;
var cameras = readJson("../docs/data/cameras.json").vendors;
var vectors = readJson("./vectors.json").cases;

var pass = 0, fail = 0;
function ok(cond, msg) { if (cond) { pass++; } else { fail++; console.error("  FAIL: " + msg); } }

/* 1. Every vector produces the expected top finding for its host. */
console.log("Engine vectors:");
vectors.forEach(function (c) {
  var findings = Soor.analyse({ observations: [c.observation], services: services, cameras: cameras });
  var host = c.observation.host;
  var got = findings.filter(function (f) { return f.host === host; })[0];
  var e = c.expect;
  if (!got) { fail++; console.error("  FAIL: " + c.name + " -> no finding"); return; }
  var good = got.kind === e.kind && got.severity === e.severity &&
    (e.service === undefined || got.service === e.service) &&
    (e.vendor === undefined || got.vendor === e.vendor) &&
    (e.exposed === undefined || got.exposed === e.exposed);
  ok(good, c.name + " -> got " + JSON.stringify({ kind: got.kind, severity: got.severity, service: got.service, vendor: got.vendor, exposed: got.exposed }));
});

/* 2. Knowledge base integrity: every service and vendor entry is bilingual. */
console.log("Knowledge base bilingual completeness:");
services.forEach(function (s) {
  ["name", "what", "fix"].forEach(function (k) {
    ok(s[k] && s[k].ar && s[k].en, "service " + s.id + " missing " + k + " ar/en");
  });
  ok(Array.isArray(s.ports) && s.ports.length > 0, "service " + s.id + " has no ports");
  ok(SEVERITY_OK(s.severity), "service " + s.id + " bad severity " + s.severity);
});
cameras.forEach(function (v) {
  ok(v.notes && v.notes.ar && v.notes.en, "vendor " + v.id + " missing notes ar/en");
  ok(Array.isArray(v.default_credentials) && v.default_credentials.length > 0, "vendor " + v.id + " has no default_credentials");
  ok(v.fingerprints && Object.keys(v.fingerprints).length > 0, "vendor " + v.id + " has no fingerprints");
});

function SEVERITY_OK(s) { return ["critical", "high", "medium", "low", "info"].indexOf(s) >= 0; }

/* 3. The engine never invents a finding for an empty scan. */
console.log("Empty scan:");
ok(Soor.analyse({ observations: [], services: services, cameras: cameras }).length === 0, "empty scan produced findings");

/* 4. Determinism: analysing twice gives identical output. */
console.log("Determinism:");
var obs = vectors.map(function (c) { return c.observation; });
var a = JSON.stringify(Soor.analyse({ observations: obs, services: services, cameras: cameras }));
var b = JSON.stringify(Soor.analyse({ observations: obs, services: services, cameras: cameras }));
ok(a === b, "engine output not deterministic");

/* 5. shipsWeakCredentials logic: first-boot-only vendors are not flagged. */
console.log("Credential logic:");
var axis = cameras.filter(function (v) { return v.id === "axis"; })[0];
ok(Soor.shipsWeakCredentials(axis) === false, "axis (first-boot only) should not be flagged as shipping weak creds");
var foscam = cameras.filter(function (v) { return v.id === "foscam"; })[0];
ok(Soor.shipsWeakCredentials(foscam) === true, "foscam (empty password) should be flagged");

console.log("\n" + pass + " passed, " + fail + " failed");
process.exit(fail === 0 ? 0 : 1);
