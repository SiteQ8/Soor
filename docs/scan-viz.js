/*
  The live scan map.

  This is not a video and not a mock-up. The scene runs the real Soor engine
  (engine/soor.js) on the observations it discovers as the animation proceeds,
  so every finding on screen is computed the same way the app computes it.

  What it shows, in the order the app actually works:
    1. discovery  a radar sweep finds the devices on the network
    2. probing    each device is knocked on, port by port, and answers
    3. router     the router's forwarded-port table is read
    4. breach     a forwarded port punches through the wall to the internet,
                  which is exactly why such a device shows up on public
                  scanning engines
    5. verdict    the engine ranks what was found

  The rampart across the top is the wall the project is named for: inside it is
  your home, outside it is the internet.
*/
(function () {
  "use strict";

  var host = document.getElementById("scanmap");
  if (!host || typeof Soor === "undefined") return;

  var canvas = host.querySelector("canvas");
  var ctx = canvas.getContext("2d");
  var logEl = host.querySelector("[data-log]");
  var statsEl = host.querySelector("[data-stats]");
  var verdictEl = host.querySelector("[data-verdict]");
  var replayBtn = host.querySelector("[data-replay]");

  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var lang = function () { return document.documentElement.lang === "en" ? "en" : "ar"; };
  var t = function (ar, en) { return lang() === "ar" ? ar : en; };

  /* The devices the scene discovers. Each carries the observation the engine
     will judge, so the verdict is computed rather than written. */
  var DEVICES = [
    { name: { ar: "الراوتر", en: "Router" }, icon: "router", angle: -90, dist: 0.30,
      obs: [{ port: 1900, banner: "UPnP/1.1 IGD rootDevice" },
            { port: 80, httpServer: "Router Webserver", banner: "WWW-Authenticate: Basic realm" }] },
    { name: { ar: "كاميرا", en: "Camera" }, icon: "camera", angle: -150, dist: 0.62,
      obs: [{ port: 554, rtspServer: "Hipcam RealServer/V1.0", noAuth: true, internetExposed: true }] },
    { name: { ar: "تلفاز", en: "TV box" }, icon: "tv", angle: -30, dist: 0.62,
      obs: [{ port: 5555, banner: "host::features", noAuth: true }] },
    { name: { ar: "تخزين", en: "Storage" }, icon: "disk", angle: -110, dist: 0.85,
      obs: [{ port: 445, banner: "Samba 4.10", noAuth: true }] },
    { name: { ar: "حاسوب", en: "Laptop" }, icon: "laptop", angle: -70, dist: 0.85,
      obs: [{ port: 22, banner: "SSH-2.0-OpenSSH_9.6" }] },
    { name: { ar: "هاتف", en: "Phone" }, icon: "phone", angle: -10, dist: 0.88,
      obs: [] }
  ];

  var STEPS = {
    ar: {
      start: "بدء الفحص على الشبكة المحلية",
      sweep: "مسح النطاق بحثًا عن الأجهزة",
      found: "عُثر على جهاز",
      probe: "طرق المنافذ الشائعة",
      banner: "قراءة ما تعلنه الخدمة",
      upnp: "قراءة جدول المنافذ في الراوتر",
      breach: "منفذ ممرَّر إلى الإنترنت",
      judge: "ترتيب النتائج بحسب الخطورة",
      done: "انتهى الفحص"
    },
    en: {
      start: "Starting the scan on the local network",
      sweep: "Sweeping the range for devices",
      found: "Device found",
      probe: "Knocking on common ports",
      banner: "Reading what the service announces",
      upnp: "Reading the router's port table",
      breach: "Port forwarded to the internet",
      judge: "Ranking findings by severity",
      done: "Scan complete"
    }
  };

  var W = 0, H = 0, DPR = 1, cx = 0, cy = 0, radius = 0, wallY = 0;
  var nodes = [], pings = [], probes = [], beams = [];
  var phase = "idle", phaseStart = 0, sweepAngle = 0, started = 0;
  var discovered = 0, portsProbed = 0, observations = [], findings = [];
  var knowledge = null, raf = null, logLines = [];

  /* ---------- geometry ---------- */

  function resize() {
    var rect = host.querySelector(".map").getBoundingClientRect();
    DPR = Math.min(window.devicePixelRatio || 1, 2);
    W = Math.max(280, rect.width);
    H = Math.max(260, Math.min(W * 0.78, 460));
    canvas.width = W * DPR;
    canvas.height = H * DPR;
    canvas.style.width = W + "px";
    canvas.style.height = H + "px";
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);

    wallY = H * 0.17;
    cx = W / 2;
    cy = H * 0.63;
    radius = Math.min(W * 0.40, (H - wallY) * 0.62);
    layout();
  }

  function layout() {
    nodes = DEVICES.map(function (d, i) {
      var a = d.angle * Math.PI / 180;
      var r = radius * d.dist;
      return {
        def: d, i: i,
        x: cx + Math.cos(a) * r,
        y: cy + Math.sin(a) * r * 0.62,
        found: false, appear: 0, probing: 0, probed: false,
        severity: null, pulse: 0
      };
    });
  }

  /* ---------- drawing ---------- */

  /* The canvas cannot inherit CSS, so the palette is read from the page and
     refreshed when the light or dark scheme changes. Without this the map
     washes out on a light background. */
  var COL = {};
  function readPalette() {
    var dark = !window.matchMedia("(prefers-color-scheme: light)").matches;
    COL = dark ? {
      navy: "#0033A0", navyLite: "#4C70BC", pale: "#8CA3D4",
      ink: "#EAF0FB", ink2: "#A7B4D4", gold: "#E8A13A",
      crit: "#FF6B5B", high: "#F0904A", wall: "#C3D2F2", node: "#16224a"
    } : {
      navy: "#0033A0", navyLite: "#2F55A8", pale: "#41609F",
      ink: "#0A1024", ink2: "#4A5578", gold: "#B26B12",
      crit: "#C0362B", high: "#B85C18", wall: "#42599B", node: "#FFFFFF"
    };
  }
  readPalette();
  var schemeQuery = window.matchMedia("(prefers-color-scheme: light)");
  if (schemeQuery.addEventListener) schemeQuery.addEventListener("change", readPalette);

  function sevColour(s) {
    if (s === "critical") return COL.crit;
    if (s === "high") return COL.high;
    if (s === "medium") return "#E8C34A";
    return COL.navyLite;
  }

  function drawWall() {
    /* the rampart: your home below it, the internet above it */
    var merlonW = 14, gap = 10, y = wallY;
    ctx.save();
    ctx.fillStyle = COL.wall;
    ctx.globalAlpha = 0.85;
    for (var x = 8; x < W - 8; x += merlonW + gap) {
      ctx.fillRect(x, y - 9, merlonW, 10);
    }
    ctx.fillRect(8, y, W - 16, 9);
    ctx.restore();

    ctx.save();
    ctx.font = "600 10px ui-monospace, monospace";
    ctx.fillStyle = COL.ink2;
    ctx.textAlign = "center";
    ctx.fillText(t("الإنترنت", "INTERNET").toUpperCase(), cx, y - 18);
    ctx.restore();
  }

  function drawRings() {
    ctx.save();
    ctx.strokeStyle = COL.navyLite;
    for (var k = 1; k <= 3; k++) {
      ctx.globalAlpha = 0.10;
      ctx.beginPath();
      ctx.ellipse(cx, cy, radius * (k / 3), radius * (k / 3) * 0.62, 0, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.restore();
  }

  function drawSweep(now) {
    if (phase !== "discovery") return;
    var grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius);
    grad.addColorStop(0, "rgba(232,161,58,0.16)");
    grad.addColorStop(1, "rgba(232,161,58,0)");
    ctx.save();
    ctx.translate(cx, cy);
    ctx.scale(1, 0.62);
    ctx.rotate(sweepAngle);
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.arc(0, 0, radius, -0.5, 0.06);
    ctx.closePath();
    ctx.fillStyle = grad;
    ctx.fill();
    ctx.restore();
  }

  function iconPath(kind, x, y, s) {
    ctx.beginPath();
    if (kind === "router") {
      ctx.roundRect(x - s, y - s * 0.45, s * 2, s * 0.9, 3);
    } else if (kind === "camera") {
      ctx.roundRect(x - s, y - s * 0.6, s * 1.7, s * 1.2, 3);
    } else if (kind === "tv") {
      ctx.roundRect(x - s, y - s * 0.7, s * 2, s * 1.3, 3);
    } else if (kind === "disk") {
      ctx.roundRect(x - s * 0.8, y - s * 0.8, s * 1.6, s * 1.6, 3);
    } else if (kind === "laptop") {
      ctx.roundRect(x - s, y - s * 0.7, s * 2, s * 1.2, 2);
    } else {
      ctx.roundRect(x - s * 0.55, y - s * 0.85, s * 1.1, s * 1.7, 3);
    }
  }

  function drawNodes(now) {
    nodes.forEach(function (n) {
      if (!n.found) return;
      var app = Math.min(1, n.appear);
      var s = 11 * app;

      /* link back to the centre */
      ctx.save();
      ctx.globalAlpha = 0.30 * app;
      ctx.strokeStyle = COL.navyLite;
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.lineTo(n.x, n.y);
      ctx.stroke();
      ctx.restore();

      /* probing halo */
      if (n.probing > 0) {
        ctx.save();
        ctx.globalAlpha = 0.5 * n.probing;
        ctx.strokeStyle = COL.gold;
        ctx.lineWidth = 1.2;
        ctx.beginPath();
        ctx.arc(n.x, n.y, 16 + (1 - n.probing) * 12, 0, Math.PI * 2);
        ctx.stroke();
        ctx.restore();
      }

      /* the device */
      var col = n.severity ? sevColour(n.severity) : COL.pale;
      ctx.save();
      ctx.globalAlpha = app;
      if (n.severity) {
        ctx.shadowColor = col;
        ctx.shadowBlur = 12 + Math.sin(now / 260) * 5;
      }
      ctx.fillStyle = n.severity ? col : COL.node;
      ctx.strokeStyle = col;
      ctx.lineWidth = 1.6;
      iconPath(n.def.icon, n.x, n.y, s);
      ctx.fill();
      ctx.stroke();
      ctx.restore();

      /* label */
      ctx.save();
      ctx.globalAlpha = app * 0.9;
      ctx.fillStyle = COL.ink2;
      ctx.font = "500 10px 'Readex Pro', system-ui, sans-serif";
      ctx.textAlign = "center";
      ctx.fillText(n.def.name[lang()], n.x, n.y + s + 13);
      ctx.restore();
    });
  }

  function drawCentre(now) {
    ctx.save();
    ctx.fillStyle = COL.navy;
    ctx.strokeStyle = COL.pale;
    ctx.lineWidth = 1.6;
    ctx.beginPath();
    ctx.arc(cx, cy, 9, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.restore();
  }

  function drawPings(now) {
    pings = pings.filter(function (p) { return p.life > 0; });
    pings.forEach(function (p) {
      p.life -= 0.02;
      ctx.save();
      ctx.globalAlpha = Math.max(0, p.life) * 0.7;
      ctx.strokeStyle = COL.gold;
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.arc(p.x, p.y, (1 - p.life) * 26, 0, Math.PI * 2);
      ctx.stroke();
      ctx.restore();
    });
  }

  function drawProbes() {
    probes = probes.filter(function (p) { return p.tt < 1; });
    probes.forEach(function (p) {
      p.tt += 0.035;
      var e = p.tt < 0.5 ? p.tt * 2 : (1 - p.tt) * 2;
      var x = cx + (p.x - cx) * Math.min(1, p.tt * 1.6);
      var y = cy + (p.y - cy) * Math.min(1, p.tt * 1.6);
      ctx.save();
      ctx.globalAlpha = Math.max(0, e);
      ctx.fillStyle = COL.gold;
      ctx.beginPath();
      ctx.arc(x, y, 2.2, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    });
  }

  function drawBeams(now) {
    /* a forwarded port punching through the wall */
    beams.forEach(function (b) {
      b.tt = Math.min(1, b.tt + 0.02);
      var n = b.node;
      var topY = wallY + 4;
      var progress = b.tt;
      ctx.save();
      ctx.globalAlpha = 0.85;
      ctx.strokeStyle = COL.crit;
      ctx.lineWidth = 2;
      ctx.setLineDash([5, 5]);
      ctx.lineDashOffset = -now / 40;
      ctx.beginPath();
      ctx.moveTo(n.x, n.y);
      ctx.lineTo(n.x, n.y + (topY - n.y) * progress);
      ctx.stroke();
      ctx.restore();

      if (progress >= 1) {
        /* the breach in the wall */
        ctx.save();
        ctx.globalAlpha = 0.9;
        ctx.fillStyle = COL.node;
        ctx.fillRect(n.x - 9, wallY - 10, 18, 20);
        ctx.strokeStyle = COL.crit;
        ctx.lineWidth = 1.5;
        ctx.strokeRect(n.x - 9, wallY - 10, 18, 20);
        ctx.shadowColor = COL.crit;
        ctx.shadowBlur = 14;
        ctx.beginPath();
        ctx.arc(n.x, wallY - 22, 3.4, 0, Math.PI * 2);
        ctx.fillStyle = COL.crit;
        ctx.fill();
        ctx.restore();
      }
    });
  }

  function render(now) {
    ctx.clearRect(0, 0, W, H);
    drawRings();
    drawSweep(now);
    drawWall();
    drawBeams(now);
    drawNodes(now);
    drawCentre(now);
    drawPings(now);
    drawProbes();
  }

  /* ---------- the run ---------- */

  function log(key, extra) {
    var line = STEPS[lang()][key] + (extra ? " " + extra : "");
    logLines.push(line);
    if (logLines.length > 4) logLines.shift();
    logEl.innerHTML = logLines.map(function (l, i) {
      var dim = i < logLines.length - 1 ? ' style="opacity:.45"' : "";
      return "<div" + dim + ">" + l + "</div>";
    }).join("");
  }

  function stats() {
    var counts = Soor.summarise(findings);
    statsEl.innerHTML =
      stat(t("جهاز", "devices"), discovered) +
      stat(t("منفذ", "ports"), portsProbed) +
      stat(t("اكتشاف", "findings"), findings.length);
  }

  function stat(label, value) {
    return '<span class="s"><b>' + value + "</b> " + label + "</span>";
  }

  function judge() {
    findings = Soor.analyse({
      observations: observations,
      services: knowledge.services,
      cameras: knowledge.cameras
    });
    /* colour each device by its worst finding, matched on its address */
    findings.forEach(function (f) {
      var m = /192\.168\.1\.(\d+)/.exec(f.host || "");
      if (!m) return;
      var n = nodes[parseInt(m[1], 10) - 10];
      if (n && !n.severity) n.severity = f.severity;
    });
    stats();
    renderVerdict();
  }

  function renderVerdict() {
    if (!findings.length) { verdictEl.innerHTML = ""; return; }
    verdictEl.innerHTML = findings.slice(0, 3).map(function (f) {
      var r = SoorReport.render(f, knowledge, lang());
      var col = sevColour(f.severity);
      var sevLabel = r.severityLabel ||
        (SoorReport.SEVERITY[f.severity] ? SoorReport.SEVERITY[f.severity][lang()] : f.severity);
      return '<div class="v" style="border-inline-start-color:' + col + '">' +
             '<span class="vs" style="color:' + col + '">' + sevLabel + "</span>" +
             '<span class="vt">' + r.title + "</span></div>";
    }).join("");
  }

  /* the scene as a sequence of timed acts */
  function schedule() {
    var at = 0;
    var T = function (ms, fn) { at += ms; setTimeout(fn, at); };

    log("start");
    phase = "discovery";

    /* discovery: each device is found in turn */
    nodes.forEach(function (n, i) {
      T(reduce ? 60 : 520, function () {
        n.found = true;
        discovered++;
        pings.push({ x: n.x, y: n.y, life: 1 });
        log("found", n.def.name[lang()]);
        stats();
      });
    });

    /* probing: knock on ports, collect what answers */
    T(400, function () { phase = "probing"; log("probe"); });
    nodes.forEach(function (n) {
      T(reduce ? 40 : 360, function () {
        n.probing = 1;
        n.def.obs.forEach(function (o) {
          portsProbed += 1;
          probes.push({ x: n.x, y: n.y, tt: 0 });
          var obs = Object.assign({ host: "192.168.1." + (10 + n.i) }, o);
          obs.hostIndex = n.i;
          observations.push(obs);
        });
        if (n.def.obs.length) log("banner", n.def.name[lang()]);
        stats();
        setTimeout(function () { n.probing = 0; n.probed = true; }, reduce ? 50 : 700);
      });
    });

    /* the router's forwarded-port table, then the breach */
    T(reduce ? 80 : 620, function () {
      phase = "router";
      log("upnp");
      nodes[0].pulse = 1;
    });
    T(reduce ? 80 : 700, function () {
      var cam = nodes[1];
      beams.push({ node: cam, tt: 0 });
      log("breach", cam.def.name[lang()] + " :554");
    });

    /* verdict, computed by the real engine */
    T(reduce ? 80 : 900, function () {
      phase = "verdict";
      log("judge");
      /* map hostIndex so findings can colour their device */
      observations.forEach(function (o) { o.host = "192.168.1." + (10 + o.hostIndex); });
      judge();
    });
    T(500, function () { log("done"); phase = "done"; replayBtn.hidden = false; });
  }

  function loop(now) {
    if (phase === "discovery") sweepAngle += 0.045;
    /* ease each discovered node into view */
    nodes.forEach(function (n) {
      if (n.found && n.appear < 1) n.appear = Math.min(1, n.appear + 0.06);
    });
    render(now || 0);
    raf = requestAnimationFrame(loop);
  }

  function reset() {
    pings = []; probes = []; beams = []; logLines = [];
    discovered = 0; portsProbed = 0; observations = []; findings = [];
    verdictEl.innerHTML = ""; statsEl.innerHTML = "";
    phase = "idle"; sweepAngle = 0;
    layout();
  }

  function run() {
    reset();
    replayBtn.hidden = true;
    started = performance.now();
    schedule();
  }

  /* ---------- wire up ---------- */

  function boot(k) {
    knowledge = k;
    resize();
    loop(0);
    var seen = false;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting && !seen) { seen = true; run(); }
      });
    }, { threshold: 0.35 });
    io.observe(host);

    replayBtn.addEventListener("click", run);
    window.addEventListener("resize", function () { resize(); });
    document.addEventListener("soor:lang", function () {
      stats();
      renderVerdict();
    });
  }

  fetch("data/services.json").then(function (r) { return r.json(); }).then(function (svc) {
    return fetch("data/cameras.json").then(function (r) { return r.json(); }).then(function (cam) {
      boot({ services: svc.services, cameras: cam.vendors });
    });
  }).catch(function () { host.hidden = true; });
})();
