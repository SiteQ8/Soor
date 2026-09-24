/*
  Soor engine, run on the device.

  It takes what a scan already found on the local network, an observation per
  host and port, and turns it into findings a person can act on. It reaches no
  network of its own and keeps no state: give it observations and the two
  knowledge files, get back findings. The iOS and Android ports carry the same
  logic, checked against the same vectors, so a finding is the same on every
  platform.

  The kinds of finding, worst first:
    exposed-camera-default   a camera whose model ships with known factory
                             credentials AND whose port the router forwarded to
                             the internet. This is why cameras appear on public
                             scanning engines, so it is raised the loudest.
    exposed-service          any risky service the router forwarded to the
                             internet.
    default-credentials      a device on a model known to ship with factory
                             credentials, still on the local network.
    open-auth                a service that answered with no authentication at
                             all, read from the device itself.
    weak-service             a risky service seen on the local network.
    new-device               a device not seen in the previous scan.

  Soor never tries a password on any device. Default-credential findings come
  from the model fingerprint and from what the service announces about itself,
  never from an attempted login.
*/
(function (root) {
  "use strict";

  var SEVERITY_ORDER = { critical: 0, high: 1, medium: 2, low: 3, info: 4 };

  function lower(s) { return String(s == null ? "" : s).toLowerCase(); }

  function anyMatch(haystack, needles) {
    if (!needles || !needles.length) return false;
    var h = lower(haystack);
    for (var i = 0; i < needles.length; i++) {
      var n = lower(needles[i]);
      if (n && h.indexOf(n) >= 0) return true;
    }
    return false;
  }

  /*
    A host's banner text is everything a scan could read from it without logging
    in: HTTP Server header and title, RTSP Server line, raw TCP banner. The
    caller passes whichever it has.
  */
  function serviceBanner(obs) {
    return [obs.httpServer, obs.httpTitle, obs.rtspServer, obs.banner, obs.raw]
      .filter(Boolean)
      .join(" \n ");
  }

  /* Match an observed port and banner against the services knowledge base. */
  function matchService(obs, services) {
    var banner = serviceBanner(obs);
    var byPort = null;
    for (var i = 0; i < services.length; i++) {
      var svc = services[i];
      var portHit = (svc.ports || []).indexOf(obs.port) >= 0;
      var bannerHit = anyMatch(banner, svc.match);
      if (bannerHit) return svc;          /* banner is stronger than port */
      if (portHit && !byPort) byPort = svc;
    }
    return byPort;
  }

  /* Identify the camera vendor from a host's banner fingerprints. */
  function matchCamera(obs, vendors) {
    var banner = serviceBanner(obs);
    for (var i = 0; i < vendors.length; i++) {
      var v = vendors[i];
      var fp = v.fingerprints || {};
      if (anyMatch(obs.httpServer, fp.http_server) ||
          anyMatch(obs.httpTitle, fp.http_title) ||
          anyMatch(obs.rtspServer, fp.rtsp_server) ||
          anyMatch(banner, fp.banner)) {
        return v;
      }
    }
    return null;
  }

  /*
    Does this vendor ship with credentials a person is likely to have left
    unchanged? A vendor that forces a password on first boot is only a concern
    when the device also announces that no password is set.
  */
  function shipsWeakCredentials(vendor) {
    if (!vendor || !vendor.default_credentials) return false;
    return vendor.default_credentials.some(function (c) {
      var pass = lower(c.pass);
      return pass === "" || (pass.indexOf("set on first boot") < 0 &&
                             pass.indexOf("account based") < 0);
    });
  }

  function finding(kind, severity, obs, extra) {
    var f = {
      kind: kind,
      severity: severity,
      host: obs.host,
      port: obs.port,
      exposed: !!obs.internetExposed
    };
    if (extra) Object.keys(extra).forEach(function (k) { f[k] = extra[k]; });
    return f;
  }

  /*
    analyse(input)
      input.observations   array of { host, port, proto, httpServer, httpTitle,
                           rtspServer, banner, raw, noAuth, internetExposed,
                           deviceName, isNew }
      input.services       the services knowledge base (services array)
      input.cameras        the cameras knowledge base (vendors array)

    noAuth is set by the caller when the service answered a request with no
    authentication, something the device itself reveals. internetExposed is set
    when the router forwarded this port to the internet (read from the router's
    UPnP or port-forward table). Neither is ever obtained by trying a password.

    Returns findings sorted worst first. Each finding names its knowledge-base
    entry so the caller can render the bilingual text; the engine stays free of
    display strings.
  */
  function analyse(input) {
    var obsList = input.observations || [];
    var services = input.services || [];
    var vendors = input.cameras || [];
    var findings = [];

    obsList.forEach(function (obs) {
      var svc = matchService(obs, services);
      var cam = matchCamera(obs, vendors);
      var isCamera = cam || (svc && svc.category === "camera");

      if (isCamera && cam && shipsWeakCredentials(cam)) {
        if (obs.internetExposed) {
          findings.push(finding("exposed-camera-default", "critical", obs, {
            vendor: cam.id, service: svc ? svc.id : "rtsp",
            reason: obs.noAuth ? "no-auth" : "known-default"
          }));
        } else {
          findings.push(finding("default-credentials", "high", obs, {
            vendor: cam.id, service: svc ? svc.id : null,
            reason: obs.noAuth ? "no-auth" : "known-default"
          }));
        }
        return;
      }

      if (svc && obs.internetExposed && svc.severity !== "low") {
        findings.push(finding("exposed-service", "critical", obs, { service: svc.id }));
        return;
      }

      if (svc && obs.noAuth && svc.category !== "info") {
        findings.push(finding("open-auth", "high", obs, { service: svc.id }));
        return;
      }

      if (svc) {
        var sev = svc.severity === "low" ? "info" : svc.severity;
        findings.push(finding("weak-service", sev, obs, { service: svc.id }));
        return;
      }

      if (obs.isNew) {
        findings.push(finding("new-device", "info", obs, {}));
      }
    });

    /* One camera can answer on several ports (RTSP and HTTP, say), producing the
       same vendor finding more than once. Keep only the worst per host+vendor so
       a person sees one finding per exposed camera, not one per port. */
    var cameraKinds = { "exposed-camera-default": true, "default-credentials": true };
    var bestCamera = {};
    findings = findings.filter(function (f) {
      if (!cameraKinds[f.kind] || !f.vendor) return true;
      var key = f.host + "|" + f.vendor;
      var prev = bestCamera[key];
      if (!prev) { bestCamera[key] = f; return true; }
      /* keep whichever is more severe; drop this one */
      if (SEVERITY_ORDER[f.severity] < SEVERITY_ORDER[prev.severity]) {
        prev._drop = true; bestCamera[key] = f; return true;
      }
      return false;
    }).filter(function (f) { return !f._drop; });

    /* A device newly on the network is worth noting even when a service matched,
       but only once per host, and never duplicating a louder finding. */
    var hostsWithFinding = {};
    findings.forEach(function (f) { hostsWithFinding[f.host] = true; });
    obsList.forEach(function (obs) {
      if (obs.isNew && !hostsWithFinding[obs.host]) {
        findings.push(finding("new-device", "info", obs, {}));
        hostsWithFinding[obs.host] = true;
      }
    });

    findings.sort(function (a, b) {
      var d = SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity];
      if (d !== 0) return d;
      if (a.host !== b.host) return a.host < b.host ? -1 : 1;
      return a.port - b.port;
    });

    return findings;
  }

  /* Group findings for a summary: counts per severity. */
  function summarise(findings) {
    var counts = { critical: 0, high: 0, medium: 0, low: 0, info: 0 };
    findings.forEach(function (f) { counts[f.severity] = (counts[f.severity] || 0) + 1; });
    return counts;
  }

  var api = {
    analyse: analyse,
    summarise: summarise,
    matchService: matchService,
    matchCamera: matchCamera,
    shipsWeakCredentials: shipsWeakCredentials
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.Soor = api;
})(typeof window !== "undefined" ? window : globalThis);
