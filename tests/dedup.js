/* Focused check: one camera on several ports collapses to one finding. */
"use strict";
var fs = require("fs"), path = require("path");
var Soor = require("../docs/engine/soor.js");
function readJson(p) { return JSON.parse(fs.readFileSync(path.join(__dirname, p), "utf8")); }
var services = readJson("../docs/data/services.json").services;
var cameras = readJson("../docs/data/cameras.json").vendors;

var obs = [
  { host: "192.168.1.64", port: 554, rtspServer: "Hipcam RealServer/V1.0", noAuth: true, internetExposed: true },
  { host: "192.168.1.64", port: 80, httpServer: "App-webs", httpTitle: "", internetExposed: true }
];
var findings = Soor.analyse({ observations: obs, services: services, cameras: cameras });
var cam = findings.filter(function (f) { return f.host === "192.168.1.64" && f.vendor === "hikvision"; });

var fail = 0;
if (cam.length !== 1) { fail++; console.error("FAIL: expected 1 camera finding, got " + cam.length); }
if (cam[0] && cam[0].kind !== "exposed-camera-default") { fail++; console.error("FAIL: expected exposed-camera-default, got " + cam[0].kind); }

/* Two different cameras stay two findings. */
var obs2 = [
  { host: "192.168.1.64", port: 554, rtspServer: "Hipcam RealServer/V1.0", noAuth: true, internetExposed: true },
  { host: "192.168.1.65", port: 80, banner: "Dahua", internetExposed: false }
];
var two = Soor.analyse({ observations: obs2, services: services, cameras: cameras })
  .filter(function (f) { return f.vendor; });
if (two.length !== 2) { fail++; console.error("FAIL: two distinct cameras should give 2 findings, got " + two.length); }

console.log(fail === 0 ? "dedup: 3 passed, 0 failed" : "dedup: FAILED");
process.exit(fail === 0 ? 0 : 1);
