/*
  Soor lab: scan a home, then fix it.

  A web page cannot scan your network, so the lab scans a hypothetical home,
  and it does so the way the app does. It discovers the devices, knocks on
  their ports, reads the router's forwarded-port table, and hands everything it
  observed to the real engine in engine/soor.js. Every verdict on screen is the
  engine's own, not text written for the page.

  Then it lets you act. Tap a device to see the ports it opens and why they
  matter, apply the fix, and the engine judges the home again from the changed
  observations, exactly as a re-scan would. Close a forwarded port and the
  breach in the wall seals in front of you.

  The wall across the top is the rampart the project is named for: your home
  below it, the internet above it, where public scanners roam.
*/
(function () {
  "use strict";

  var root = document.getElementById("soorlab");
  if (!root || typeof Soor === "undefined" || typeof SoorReport === "undefined") return;

  var canvas = root.querySelector("canvas");
  var ctx = canvas.getContext("2d");
  var ui = {
    stage: root.querySelector(".lab-stage"),
    homes: root.querySelector("[data-homes]"),
    rescan: root.querySelector("[data-rescan]"),
    reset: root.querySelector("[data-reset]"),
    log: root.querySelector("[data-log]"),
    status: root.querySelector("[data-status]"),
    pills: root.querySelector("[data-pills]"),
    inspector: root.querySelector("[data-inspector]"),
    devices: root.querySelector("[data-devices]")
  };

  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  function lang() { return document.documentElement.lang === "en" ? "en" : "ar"; }
  function L(o) { return typeof o === "string" ? o : (o[lang()] || o.ar); }
  function T(ar, en) { return lang() === "ar" ? ar : en; }
  function clone(o) { return JSON.parse(JSON.stringify(o)); }

  /* ---------- what a person can do about a finding ----------
     Each fix changes the observations the way the real change would show up
     on a re-scan, and nothing more. The engine then judges again. */

  function has(d, fn) { return !d.removed && d.obs.some(fn); }

  var FIXES = {
    "upnp-off": {
      label: { ar: "عطّل UPnP في الراوتر", en: "Turn off UPnP on the router" },
      applies: function (d) { return has(d, function (o) { return o.port === 1900; }); },
      apply: function (d, h) {
        d.obs = d.obs.filter(function (o) { return o.port !== 1900; });
        /* with UPnP off, every port it forwarded is closed to the internet */
        h.devices.forEach(function (x) { x.obs.forEach(function (o) { o.internetExposed = false; }); });
      }
    },
    "admin-https": {
      label: { ar: "فعّل HTTPS في لوحة الإدارة", en: "Turn on HTTPS for the admin panel" },
      applies: function (d) { return has(d, function (o) { return o.port === 80; }); },
      apply: function (d) {
        d.obs = d.obs.filter(function (o) { return o.port !== 80; });
        d.obs.push({ port: 443, banner: "self-signed certificate" });
      }
    },
    "close-forward": {
      label: { ar: "أغلق تمرير المنفذ في الراوتر", en: "Close the port forward on the router" },
      applies: function (d) { return has(d, function (o) { return o.internetExposed; }); },
      apply: function (d) { d.obs.forEach(function (o) { o.internetExposed = false; }); }
    },
    "stream-auth": {
      label: { ar: "اجعل البث يطلب كلمة مرور", en: "Make the stream require a password" },
      applies: function (d) { return has(d, function (o) { return o.noAuth; }); },
      apply: function (d) { d.obs.forEach(function (o) { o.noAuth = false; }); }
    },
    "share-auth": {
      label: { ar: "اشترط كلمة مرور وأوقف دخول الضيف", en: "Require a password and turn off guest access" },
      applies: function (d) { return has(d, function (o) { return o.noAuth; }); },
      apply: function (d) { d.obs.forEach(function (o) { o.noAuth = false; }); }
    },
    "adb-off": {
      label: { ar: "عطّل تصحيح الأخطاء عبر الشبكة", en: "Turn off network debugging" },
      applies: function (d) { return has(d, function (o) { return o.port === 5555; }); },
      apply: function (d) { d.obs = d.obs.filter(function (o) { return o.port !== 5555; }); }
    },
    "telnet-off": {
      label: { ar: "عطّل Telnet في الجهاز", en: "Turn off Telnet on the device" },
      applies: function (d) { return has(d, function (o) { return o.port === 23 || o.port === 2323; }); },
      apply: function (d) { d.obs = d.obs.filter(function (o) { return o.port !== 23 && o.port !== 2323; }); }
    },
    "replace-camera": {
      label: { ar: "افصلها واستبدلها بطراز موثوق", en: "Unplug it and replace it with a trusted model" },
      applies: function (d) { return !d.removed; },
      apply: function (d) { d.removed = true; }
    },
    "known-device": {
      label: { ar: "أعرف هذا الجهاز", en: "I know this device" },
      applies: function (d) { return has(d, function (o) { return o.isNew; }); },
      apply: function (d) { d.obs.forEach(function (o) { o.isNew = false; }); }
    },
    "unknown-device": {
      label: { ar: "لا أعرفه، فافصله عن الشبكة", en: "I don't, disconnect it" },
      applies: function (d) { return !d.removed; },
      apply: function (d) { d.removed = true; }
    }
  };

  /* ---------- the homes ----------
     Named by the state of the network, on one axis from worst to best, never by
     who lives there or what gadgets it has, since any home can be both. Each
     name matches the status the scan ends on: exposed to the internet (a breach
     in the wall), weak spots inside (the wall holds, some devices inside do not),
     and nearly fortified (only things worth reviewing). */

  var ROUTER_WEB = { port: 80, httpServer: "Router Webserver", banner: "WWW-Authenticate: Basic realm" };
  var UPNP = { port: 1900, banner: "UPnP/1.1 IGD rootDevice" };

  var HOMES = [
    {
      id: "exposed",
      name: { ar: "بيت مكشوف للإنترنت", en: "Exposed to the internet" },
      devices: [
        { id: "router", kind: "router", hub: true, ip: "192.168.1.1",
          name: { ar: "الراوتر", en: "Router" },
          obs: [UPNP, ROUTER_WEB], fixes: ["upnp-off", "admin-https"] },
        { id: "camera", kind: "camera", ip: "192.168.1.64",
          name: { ar: "كاميرا المدخل", en: "Door camera" },
          obs: [{ port: 554, rtspServer: "Hipcam RealServer/V1.0", noAuth: true, internetExposed: true }],
          fixes: ["close-forward", "stream-auth"], note: "password" },
        { id: "nas", kind: "nas", ip: "192.168.1.20",
          name: { ar: "جهاز التخزين", en: "Home storage" },
          obs: [{ port: 445, banner: "Samba 4.10", noAuth: true }], fixes: ["share-auth"] },
        { id: "box", kind: "tv", ip: "192.168.1.90",
          name: { ar: "جهاز البث", en: "Streaming box" },
          obs: [{ port: 5555, banner: "host::features", noAuth: true }], fixes: ["adb-off"] },
        { id: "laptop", kind: "laptop", ip: "192.168.1.10",
          name: { ar: "الحاسوب", en: "Laptop" },
          obs: [{ port: 22, banner: "SSH-2.0-OpenSSH_9.6" }], fixes: [] },
        { id: "phone", kind: "phone", ip: "192.168.1.42",
          name: { ar: "الهاتف", en: "Phone" }, obs: [], fixes: [] },
        { id: "tablet", kind: "tablet", ip: "192.168.1.43",
          name: { ar: "جهاز الأطفال", en: "Kids' tablet" }, obs: [], fixes: [] }
      ]
    },
    {
      id: "inside",
      name: { ar: "بيت فيه ثغرات داخلية", en: "Weak spots inside" },
      devices: [
        { id: "router", kind: "router", hub: true, ip: "192.168.1.1",
          name: { ar: "الراوتر", en: "Router" },
          obs: [ROUTER_WEB], fixes: ["admin-https"] },
        { id: "cam", kind: "camera", ip: "192.168.1.70",
          name: { ar: "كاميرا الحديقة", en: "Garden camera" },
          obs: [{ port: 34567, httpServer: "uc-httpd 1.0.0", httpTitle: "NETSurveillance WEB", noAuth: true }],
          fixes: ["replace-camera"] },
        { id: "hub", kind: "iot", ip: "192.168.1.30",
          name: { ar: "مركز التحكم المنزلي", en: "Smart hub" },
          obs: [{ port: 2323, banner: "BusyBox login:" }], fixes: ["telnet-off"] },
        { id: "box", kind: "tv", ip: "192.168.1.90",
          name: { ar: "جهاز البث", en: "Streaming box" },
          obs: [{ port: 5555, banner: "host::features", noAuth: true }], fixes: ["adb-off"] },
        { id: "mystery", kind: "unknown", ip: "192.168.1.150",
          name: { ar: "جهاز مجهول", en: "Unknown device" },
          obs: [{ port: 0, isNew: true }], fixes: ["known-device", "unknown-device"] },
        { id: "laptop", kind: "laptop", ip: "192.168.1.10",
          name: { ar: "الحاسوب", en: "Laptop" },
          obs: [{ port: 22, banner: "SSH-2.0-OpenSSH_9.6" }], fixes: [] }
      ]
    },
    {
      id: "nearly",
      name: { ar: "بيت شبه محصّن", en: "Nearly fortified" },
      devices: [
        { id: "router", kind: "router", hub: true, ip: "192.168.1.1",
          name: { ar: "الراوتر", en: "Router" },
          obs: [UPNP, ROUTER_WEB], fixes: ["upnp-off", "admin-https"] },
        { id: "laptop", kind: "laptop", ip: "192.168.1.10",
          name: { ar: "الحاسوب", en: "Laptop" },
          obs: [{ port: 22, banner: "SSH-2.0-OpenSSH_9.6" }], fixes: [] },
        { id: "tv", kind: "tv", ip: "192.168.1.50",
          name: { ar: "التلفاز", en: "TV" }, obs: [], fixes: [] },
        { id: "phone", kind: "phone", ip: "192.168.1.42",
          name: { ar: "الهاتف", en: "Phone" }, obs: [], fixes: [] }
      ]
    }
  ];

  var NOTES = {
    password: {
      ar: "سُور لا يجرّب كلمات المرور أبدًا، لذا لا يستطيع أن يتأكد أنك غيّرت كلمة مرور المصنع، فيبقى هذا التذكير قائمًا حتى تتأكد بنفسك.",
      en: "Soor never tries passwords, so it cannot confirm you changed the factory password. This reminder stays until you check it yourself."
    },
    review: {
      ar: "لا خطر عاجل هنا، والخدمة تستحق المراجعة من وقت لآخر فقط.",
      en: "Nothing urgent here, the service is just worth reviewing now and then."
    },
    info: {
      ar: "لا شيء يستحق القلق، وما بقي هنا للعلم فقط.",
      en: "Nothing to worry about, what remains is for your information only."
    },
    clean: {
      ar: "لا شيء في هذا الجهاز يستحق القلق.",
      en: "Nothing on this device needs worrying about."
    }
  };

  var STATUS = {
    scanning: { ar: "جارٍ الفحص", en: "Scanning" },
    exposed: { ar: "في السور ثغرة، وبيتك مرئي من الإنترنت", en: "There is a breach in the wall, your home is visible from the internet" },
    urgent: { ar: "السور سليم، لكنّ في الداخل ما يحتاج معالجة الآن", en: "The wall holds, but something inside needs attention now" },
    review: { ar: "السور سليم، وبقيت أمور تستحق المراجعة", en: "The wall holds, a few things are worth reviewing" },
    clean: { ar: "السور سليم ولا شيء يستحق القلق", en: "The wall holds and nothing needs worrying about" }
  };

  var LOG = {
    start: { ar: "بدأ الفحص في الشبكة المحلية", en: "Scan started on the local network" },
    probe: { ar: "فحص المنافذ الشائعة في كل جهاز", en: "Checking common ports on each device" },
    router: { ar: "قراءة جدول المنافذ في الراوتر", en: "Reading the router's port table" },
    judge: { ar: "المحرك يرتّب النتائج بحسب الخطورة", en: "The engine ranks the findings by severity" },
    done: { ar: "انتهى الفحص، فاضغط أي جهاز", en: "Scan complete, tap any device" },
    fixed: { ar: "طُبّق الحل وأعاد المحرك حكمه", en: "Fix applied, the engine judged again" },
    sealed: { ar: "رُمّم السور", en: "The wall is sealed" }
  };

  /* ---------- state ---------- */

  var SEV_ORDER = { critical: 0, high: 1, medium: 2, low: 3, info: 4 };
  var SWEEP_FROM = -196 * Math.PI / 180, SWEEP_TO = 16 * Math.PI / 180;

  var knowledge = null, home = null, homeId = "exposed";
  var nodes = [], findings = [], breaches = [], particles = [];
  var selected = null, hovered = null, autoPicked = null, lastChange = null;
  var phase = "idle", sweep = 0;
  var crawler = { x: 30, dir: 1, pause: 0, idx: 0, spot: null };
  var W = 0, H = 0, DPR = 1, R = 18, wallY = 0, hubX = 0, hubY = 0, rx = 0, ry = 0;
  var visible = false, raf = 0, last = 0, timers = [], logItems = [];
  var C = {};

  /* ---------- palette, read from the page so both themes stay right ---------- */

  function cssVar(name, fallback) {
    var v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return v || fallback;
  }
  function readPalette() {
    C.dark = !window.matchMedia("(prefers-color-scheme: light)").matches;
    C.bg = cssVar("--bg-2", "#0A1024");
    C.panel = cssVar("--panel", "#0F1730");
    C.ink = cssVar("--ink", "#EAF0FB");
    C.ink2 = cssVar("--ink-2", "#A7B4D4");
    C.navy = cssVar("--navy", "#0033A0");
    C.lite = cssVar("--navy-lite", "#4C70BC");
    C.gold = cssVar("--signal", "#E8A13A");
    C.crit = cssVar("--crit", "#FF6B5B");
    C.high = cssVar("--high", "#F0904A");
    C.med = cssVar("--med", "#E8C34A");
    C.ok = cssVar("--ok", "#52C48D");
    C.info = cssVar("--info", "#6FA8E8");
    C.wall = C.dark ? "#C9D6F2" : "#3F5A9E";
    C.wallShade = C.dark ? "#8FA3CF" : "#2C437F";
  }
  function rgba(col, a) {
    col = (col || "").trim();
    if (col.charAt(0) === "#") {
      var h = col.slice(1);
      if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
      var n = parseInt(h, 16);
      return "rgba(" + ((n >> 16) & 255) + "," + ((n >> 8) & 255) + "," + (n & 255) + "," + a + ")";
    }
    var m = col.match(/rgba?\(([^)]+)\)/);
    if (m) return "rgba(" + m[1].split(",").slice(0, 3).join(",") + "," + a + ")";
    return col;
  }
  function sevColor(s) {
    if (s === "critical") return C.crit;
    if (s === "high") return C.high;
    if (s === "medium") return C.med;
    if (s === "info" || s === "low") return C.info;
    return C.ok;
  }
  function sevVar(s) {
    if (s === "critical") return "var(--crit)";
    if (s === "high") return "var(--high)";
    if (s === "medium") return "var(--med)";
    if (s === "info" || s === "low") return "var(--info)";
    return "var(--ok)";
  }

  /* ---------- home and layout ---------- */

  function loadHome(id) {
    homeId = id;
    var src = HOMES.filter(function (h) { return h.id === id; })[0] || HOMES[0];
    home = clone(src);
    nodes = home.devices.map(function (d) {
      return { id: d.id, dev: d, hub: !!d.hub, x: 0, y: 0, ang: 0,
               found: false, appear: 0, fade: 1, probe: 0, sev: null, bump: 0 };
    });
    place();
  }

  function place() {
    var ring = nodes.filter(function (n) { return !n.hub; });
    var a0 = -166 * Math.PI / 180, a1 = -14 * Math.PI / 180;
    ring.forEach(function (n, i) {
      var t = ring.length === 1 ? 0.5 : i / (ring.length - 1);
      var a = a0 + (a1 - a0) * t;
      var k = (i % 2 === 0) ? 1 : 0.62;
      n.ang = a;
      n.x = hubX + Math.cos(a) * rx * k;
      n.y = hubY + Math.sin(a) * ry * k;
    });
    nodes.forEach(function (n) { if (n.hub) { n.x = hubX; n.y = hubY; n.ang = -Math.PI / 2; } });
    breaches.forEach(function (b) { b.x = clampX(b.node.x); });
  }

  function clampX(x) { return Math.max(22, Math.min(W - 22, x)); }

  function resize() {
    var box = ui.stage.getBoundingClientRect();
    DPR = Math.min(window.devicePixelRatio || 1, 2);
    W = Math.max(280, Math.floor(box.width));
    var narrow = W < 540;
    H = narrow ? Math.round(W * 1.02) : Math.round(Math.min(W * 0.74, 520));
    R = narrow ? 15 : 18;
    canvas.width = Math.round(W * DPR);
    canvas.height = Math.round(H * DPR);
    canvas.style.height = H + "px";
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    wallY = Math.round(H * 0.18);
    hubX = W / 2;
    hubY = Math.round(H * 0.84);
    rx = Math.min(W * 0.41, 380);
    ry = (hubY - wallY - 44) * 0.95;
    place();
    kick();
  }

  /* ---------- the engine, the source of every verdict ---------- */

  function observed() {
    var out = [];
    home.devices.forEach(function (d) {
      if (d.removed) return;
      d.obs.forEach(function (o) { var c = clone(o); c.host = d.ip; out.push(c); });
    });
    return out;
  }
  function judge() {
    findings = Soor.analyse({ observations: observed(), services: knowledge.services, cameras: knowledge.cameras });
    nodes.forEach(function (n) { n.sev = n.dev.removed ? null : worstSev(forHost(n.dev.ip)); });
    root.dataset.findings = String(findings.length);
  }
  function forHost(ip) { return findings.filter(function (f) { return f.host === ip; }); }
  function worstOf(list) {
    var best = null;
    list.forEach(function (f) { if (!best || SEV_ORDER[f.severity] < SEV_ORDER[best.severity]) best = f; });
    return best;
  }
  function worstSev(list) { var w = worstOf(list); return w ? w.severity : null; }
  function byId(id) { return nodes.filter(function (n) { return n.id === id; })[0] || null; }
  function exposedNodes() {
    return nodes.filter(function (n) {
      return !n.dev.removed && n.dev.obs.some(function (o) { return o.internetExposed; });
    });
  }
  function worstNode() {
    var best = null;
    nodes.forEach(function (n) {
      if (n.dev.removed || !n.sev) return;
      if (!best || SEV_ORDER[n.sev] < SEV_ORDER[best.sev]) best = n;
    });
    return best;
  }
  function statusOf() {
    var exposed = findings.some(function (f) { return f.exposed; });
    var urgent = findings.some(function (f) {
      return f.severity === "critical" || f.kind === "open-auth" || f.reason === "no-auth";
    });
    var review = findings.some(function (f) { return f.severity === "high" || f.severity === "medium"; });
    if (exposed) return { tone: "crit", text: STATUS.exposed };
    if (urgent) return { tone: "high", text: STATUS.urgent };
    if (review) return { tone: "med", text: STATUS.review };
    return { tone: "ok", text: STATUS.clean };
  }

  /* ---------- the scan, act by act ---------- */

  function later(ms, fn) { timers.push(setTimeout(fn, ms)); }
  function stopTimers() { timers.forEach(clearTimeout); timers = []; }

  function setPhase(p) {
    phase = p;
    root.dataset.phase = p;
    ui.rescan.disabled = p !== "ready";
  }

  function scan() {
    stopTimers();
    particles = []; breaches = []; findings = []; logItems = [];
    selected = null; autoPicked = null; lastChange = null; hovered = null;
    crawler.spot = null; crawler.pause = 0;
    nodes.forEach(function (n) {
      n.found = n.hub; n.appear = n.hub ? 1 : 0; n.sev = null; n.probe = 0;
      n.fade = n.dev.removed ? 0 : 1;
    });
    root.dataset.breaches = "0";
    setPhase("discover");
    log([LOG.start]);
    renderAll();
    if (reduce) { instant(); return; }
    sweep = SWEEP_FROM;
    kick();
  }

  function instant() {
    nodes.forEach(function (n) { if (!n.dev.removed) { n.found = true; n.appear = 1; } });
    exposedNodes().forEach(function (n) {
      breaches.push({ node: n, x: clampX(n.x), open: 1, seal: 0, state: "open", spotted: true });
    });
    verdict();
  }

  function startProbe() {
    setPhase("probe");
    log([LOG.probe]);
    var list = nodes.filter(function (n) { return !n.dev.removed; });
    list.sort(function (a, b) { return (b.hub ? 1 : 0) - (a.hub ? 1 : 0); });
    list.forEach(function (n, i) {
      later(i * 300, function () {
        n.probe = 1;
        if (!n.hub) for (var k = 0; k < 3; k++) particles.push({ t: "packet", node: n, p: -k * 0.2 });
        var ps = n.dev.obs.filter(function (o) { return o.port > 0; }).map(function (o) { return o.port; })
          .sort(function (a, b) { return a - b; });
        if (ps.length) log([{
          ar: (ps.length > 1 ? "منافذ مفتوحة في " : "منفذ مفتوح في ") + n.dev.name.ar + ": " + ps.join(" و"),
          en: "Open " + (ps.length > 1 ? "ports" : "port") + " on " + n.dev.name.en + ": " + ps.join(", ")
        }]);
      });
    });
    later(list.length * 300 + 450, readRouter);
  }

  function readRouter() {
    setPhase("router");
    log([LOG.router]);
    particles.push({ t: "ring", x: hubX, y: hubY, life: 1 });
    var ex = exposedNodes();
    later(650, function () {
      ex.forEach(function (n, i) {
        later(i * 260, function () {
          breaches.push({ node: n, x: clampX(n.x), open: 0, seal: 0, state: "forming", spotted: false });
          log([{ ar: "منفذ ممرَّر يكشف " + n.dev.name.ar + " للإنترنت", en: n.dev.name.en + " exposed to the internet by a forwarded port" }]);
          syncBreachCount();
        });
      });
      later(ex.length ? 1200 + ex.length * 260 : 260, verdict);
    });
  }

  function verdict() {
    judge();
    setPhase("ready");
    log([LOG.judge]);
    var w = worstNode();
    if (w) { selected = w.id; autoPicked = w.id; }
    syncBreachCount();
    renderAll();
    later(reduce ? 0 : 500, function () { log([LOG.done]); });
    kick();
  }

  /* ---------- acting on a finding ---------- */

  function applyFix(fixId, n) {
    var fix = FIXES[fixId];
    if (!fix || phase !== "ready" || !n || !fix.applies(n.dev)) return;
    var before = worstOf(forHost(n.dev.ip));
    var exposedBefore = exposedNodes().map(function (x) { return x.id; });

    fix.apply(n.dev, home);
    judge();

    var exposedNow = exposedNodes().map(function (x) { return x.id; });
    exposedBefore.forEach(function (id) { if (exposedNow.indexOf(id) < 0) seal(id); });

    var after = n.dev.removed ? "gone" : worstOf(forHost(n.dev.ip));
    lastChange = { id: n.id, name: n.dev.name, before: before, after: after };
    autoPicked = null;
    n.bump = 1;
    if (!reduce) particles.push({ t: "ring", x: n.x, y: n.y, life: 1, gold: true });
    if (n.dev.removed && selected === n.id) selected = null;

    log([LOG.fixed]);
    ui.reset.hidden = false;
    syncBreachCount();
    renderAll();
    kick();

    /* the payoff is watching the wall seal, so make sure it is on screen */
    var sealing = breaches.some(function (b) { return b.state === "sealing"; });
    var top = canvas.getBoundingClientRect().top;
    if (sealing && (top < 0 || top > window.innerHeight * 0.5)) {
      ui.stage.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
    }
  }

  function seal(id) {
    breaches.forEach(function (b) {
      if (b.node.id !== id || b.state === "sealing") return;
      b.state = "sealing";
      if (reduce) b.seal = 1;
    });
  }

  function syncBreachCount() {
    var open = breaches.filter(function (b) { return b.state === "open" || b.state === "forming"; }).length;
    root.dataset.breaches = String(open);
  }

  function select(id) {
    if (phase !== "ready") return;
    var n = byId(id);
    if (!n || n.dev.removed) return;
    if (selected !== id) { lastChange = null; autoPicked = null; }
    selected = id;
    n.bump = Math.max(n.bump, 0.5);
    renderInspector();
    renderDevices();
    kick();
  }

  /* ---------- the log ---------- */

  function log(parts) {
    logItems.push(parts);
    if (logItems.length > 3) logItems.shift();
    renderLog();
  }
  function renderLog() {
    ui.log.innerHTML = logItems.map(function (parts, i) {
      var text = parts.map(function (p) { return L(p); }).join("");
      var dim = i < logItems.length - 1 ? " dim" : "";
      return '<div class="lab-line' + dim + '">' + text + "</div>";
    }).join("");
  }

  /* ---------- the panel ---------- */

  function renderTabs() {
    ui.homes.innerHTML = HOMES.map(function (h) {
      return '<button type="button" role="tab" data-home="' + h.id + '" aria-selected="' +
        (h.id === homeId) + '">' + L(h.name) + "</button>";
    }).join("");
    ui.rescan.textContent = T("افحص من جديد", "Scan again");
    ui.reset.textContent = T("أعد البيت كما كان", "Restore the home");
  }

  function renderStatus() {
    if (phase !== "ready") {
      ui.status.className = "lab-status tone-wait";
      ui.status.innerHTML = '<span class="st-dot"></span><span>' + L(STATUS.scanning) + "</span>";
      ui.pills.innerHTML = "";
      root.dataset.tone = "wait";
      return;
    }
    var s = statusOf();
    root.dataset.tone = s.tone;
    ui.status.className = "lab-status tone-" + s.tone;
    ui.status.innerHTML = '<span class="st-dot"></span><span>' + L(s.text) + "</span>";
    var counts = Soor.summarise(findings);
    ui.pills.innerHTML = ["critical", "high", "medium", "info"].filter(function (k) { return counts[k]; })
      .map(function (k) {
        return '<span class="lab-pill" style="--c:' + sevVar(k) + '"><b>' + counts[k] + "</b> " +
          L(SoorReport.SEVERITY[k]) + "</span>";
      }).join("");
  }

  function noteFor(n, fs) {
    if (!fs.length) return NOTES.clean;
    if (n.dev.note === "password" && fs.some(function (f) { return f.kind === "default-credentials"; })) return NOTES.password;
    if (fs.every(function (f) { return f.severity === "info" || f.severity === "low"; })) return NOTES.info;
    return NOTES.review;
  }

  function findingLine(f) {
    var r = SoorReport.render(f, knowledge, lang());
    return '<span class="chg-sev" style="--c:' + sevVar(f.severity) + '">' + r.severity + "</span>" +
      '<span class="chg-title">' + r.title + "</span>";
  }

  function changeHtml(c) {
    var before = c.before ? findingLine(c.before)
      : '<span class="chg-title">' + T("لا شيء", "Nothing") + "</span>";
    var after;
    if (c.after === "gone") {
      after = '<span class="chg-sev" style="--c:var(--ok)">' + T("فُصل", "Removed") + "</span>" +
        '<span class="chg-title">' + T("خرج الجهاز من الشبكة", "The device left the network") + "</span>";
    } else if (!c.after) {
      after = '<span class="chg-sev" style="--c:var(--ok)">' + T("أُغلق", "Closed") + "</span>" +
        '<span class="chg-title">' + T("لا شيء يستحق القلق هنا", "Nothing worth worrying about here") + "</span>";
    } else if (c.before && c.before.kind === c.after.kind && c.before.severity === c.after.severity) {
      /* same verdict, but the reason behind it may have improved */
      var better = c.before.reason === "no-auth" && c.after.reason !== "no-auth";
      after = '<span class="chg-sev" style="--c:' + (better ? "var(--ok)" : sevVar(c.after.severity)) + '">' +
        (better ? T("تحسّن", "Better") : L(SoorReport.SEVERITY[c.after.severity])) + "</span>" +
        '<span class="chg-title">' + (better
          ? T("لم يعد يستجيب دون كلمة مرور", "No longer answers without a password")
          : T("لم يتغيّر الحكم", "The verdict did not change")) + "</span>";
    } else {
      after = findingLine(c.after);
    }
    return '<div class="insp-change" role="status">' +
      '<div class="chg-row"><span class="chg-k">' + T("كان", "Before") + "</span>" + before + "</div>" +
      '<div class="chg-row"><span class="chg-k">' + T("صار", "Now") + "</span>" + after + "</div>" +
      '<div class="chg-foot">' + T("أعاد محرك سُور حكمه على البيت من جديد", "The Soor engine judged the home again") + "</div>" +
      "</div>";
  }

  /* split a report paragraph into sentences, in either language */
  function splitSentences(text) {
    /* a plain loop rather than a regex lookbehind, which older iPhones cannot
       parse, and a parse error would take the whole lab down with it */
    if (!text) return [];
    var out = [], buf = "";
    for (var i = 0; i < text.length; i++) {
      var ch = text.charAt(i);
      buf += ch;
      if ((ch === "." || ch === "?" || ch === "\u061F" || ch === "!") && (i + 1 >= text.length || text.charAt(i + 1) === " ")) {
        if (buf.trim()) out.push(buf.trim());
        buf = "";
      }
    }
    if (buf.trim()) out.push(buf.trim());
    return out;
  }

  function renderInspector() {
    if (phase !== "ready") {
      ui.inspector.innerHTML = '<p class="insp-hint">' +
        T("انتظر حتى ينتهي الفحص، ثم اضغط أي جهاز", "Wait for the scan to finish, then tap any device") + "</p>";
      return;
    }
    var n = byId(selected);
    var html = "";

    if (!n || n.dev.removed) {
      if (lastChange && lastChange.after === "gone") {
        html += '<div class="insp-head"><span class="insp-dot" style="background:var(--ok)"></span>' +
          '<div class="insp-name"><b>' + L(lastChange.name) + "</b></div></div>" + changeHtml(lastChange);
      }
      html += '<p class="insp-hint">' +
        T("اضغط أي جهاز على الخريطة أو في القائمة لترى منافذه وحكم سُور عليه", "Tap any device on the map or in the list to see its ports and Soor's verdict") + "</p>";
      ui.inspector.innerHTML = html;
      return;
    }

    var d = n.dev, fs = forHost(d.ip);
    var fixes = (d.fixes || []).filter(function (id) { return FIXES[id] && FIXES[id].applies(d); });

    html += '<div class="insp-head">' +
      '<span class="insp-dot" style="background:' + sevVar(n.sev) + '"></span>' +
      '<div class="insp-name"><b>' + L(d.name) + '</b><span class="insp-ip">' + d.ip + "</span></div>" +
      (autoPicked === n.id && fixes.length ? '<span class="insp-tag">' + T("ابدأ من هنا", "Start here") + "</span>" : "") +
      "</div>";

    var ports = d.obs.filter(function (o) { return o.port > 0; });
    if (ports.length) {
      html += '<div class="insp-ports">' + ports.map(function (o) {
        var svc = Soor.matchService(o, knowledge.services);
        return '<span class="insp-port"><b>' + o.port + "</b>" + (svc ? "<span>" + L(svc.name) + "</span>" : "") + "</span>";
      }).join("") + "</div>";
    }

    if (lastChange && lastChange.id === n.id) html += changeHtml(lastChange);

    fs.forEach(function (f) {
      var r = SoorReport.render(f, knowledge, lang());
      var parts = splitSentences(r.detail);
      var lead = parts.shift() || "";
      html += '<div class="insp-f" style="--c:' + sevVar(f.severity) + '">' +
        '<div class="insp-ft"><span class="insp-sev">' + r.severity + "</span><span>" + r.title + "</span></div>" +
        (lead ? '<p class="insp-detail">' + lead + "</p>" : "") +
        ((parts.length || r.fix) ? '<details class="insp-more"><summary>' + T("التفاصيل وخطوات الحل", "Details and how to fix it") + "</summary>" +
          (parts.length ? '<p class="insp-detail">' + parts.join(" ") + "</p>" : "") +
          (r.fix ? '<div class="insp-fix"><b>' + T("الحل", "The fix") + "</b><span>" + r.fix + "</span></div>" : "") +
          "</details>" : "") +
        "</div>";
    });

    if (fixes.length) {
      html += '<div class="insp-actions"><div class="insp-label">' +
        T("طبّق الحل وشاهد ما يتغيّر", "Apply the fix and watch what changes") + "</div>" +
        fixes.map(function (id) {
          return '<button type="button" class="fixbtn" data-fix="' + id + '">' + L(FIXES[id].label) + "</button>";
        }).join("") + "</div>";
    } else {
      html += '<p class="insp-note">' + L(noteFor(n, fs)) + "</p>";
    }

    ui.inspector.innerHTML = html;
  }

  function renderDevices() {
    ui.devices.innerHTML = nodes.filter(function (n) { return n.found && !n.dev.removed; }).map(function (n) {
      var c = phase === "ready" ? sevVar(n.sev) : "var(--rule)";
      return '<button type="button" class="devbtn" data-dev="' + n.id + '" aria-pressed="' + (selected === n.id) + '"' +
        (phase !== "ready" ? " disabled" : "") + '><span class="devdot" style="background:' + c + '"></span>' +
        L(n.dev.name) + "</button>";
    }).join("");
  }

  function renderAll() {
    renderTabs();
    renderStatus();
    renderInspector();
    renderDevices();
    renderLog();
  }

  /* ---------- simulation ---------- */

  function update(dt) {
    if (phase === "discover") {
      sweep += dt * 1.75;
      nodes.forEach(function (n) {
        if (!n.found && !n.dev.removed && sweep >= n.ang) {
          n.found = true; n.appear = 0;
          particles.push({ t: "ping", x: n.x, y: n.y, life: 1 });
          log([{ ar: "عُثر على " + n.dev.name.ar, en: "Found: " + n.dev.name.en }]);
          renderDevices();
        }
      });
      if (sweep >= SWEEP_TO) startProbe();
    }

    nodes.forEach(function (n) {
      if (n.found && n.appear < 1) n.appear = Math.min(1, n.appear + dt * 3.2);
      if (n.probe > 0) n.probe = Math.max(0, n.probe - dt * 1.2);
      if (n.bump > 0) n.bump = Math.max(0, n.bump - dt * 2.2);
      if (n.dev.removed && n.fade > 0) n.fade = Math.max(0, n.fade - dt * 1.8);
    });

    breaches.forEach(function (b) {
      if (b.state === "forming") {
        b.open = Math.min(1, b.open + dt * 1.8);
        if (b.open >= 1) b.state = "open";
      } else if (b.state === "sealing") {
        b.seal = Math.min(1, b.seal + dt * 1.25);
        if (!reduce && Math.random() < 0.7) {
          particles.push({ t: "spark", x: b.x + (Math.random() - 0.5) * 22, y: wallY + 2,
                           vx: (Math.random() - 0.5) * 90, vy: -60 - Math.random() * 90, life: 1 });
        }
        if (b.seal >= 1) { b.state = "sealed"; log([LOG.sealed]); }
      } else if (b.state === "open" && !reduce && Math.random() < dt * 5) {
        particles.push({ t: "escape", x: b.x + (Math.random() - 0.5) * 10, y: wallY - 10, life: 1 });
      }
    });
    breaches = breaches.filter(function (b) { return b.state !== "sealed"; });

    if (!reduce && (phase === "router" || phase === "ready")) {
      var open = breaches.filter(function (b) { return b.state === "open"; });
      if (crawler.pause > 0) {
        crawler.pause -= dt;
        if (crawler.pause <= 0) crawler.spot = null;
      } else if (open.length) {
        var tgt = open[crawler.idx % open.length];
        var dx = tgt.x - crawler.x, step = 150 * dt;
        if (Math.abs(dx) <= step) {
          crawler.x = tgt.x; crawler.pause = 1.9; crawler.spot = tgt; crawler.idx++;
          if (!tgt.spotted) { tgt.spotted = true; log([{ ar: "ماسح عام يرصد " + tgt.node.dev.name.ar, en: "Spotted by a public scanner: " + tgt.node.dev.name.en }]); }
        } else {
          crawler.dir = dx > 0 ? 1 : -1;
          crawler.x += crawler.dir * step;
        }
      } else {
        crawler.spot = null;
        crawler.x += crawler.dir * 60 * dt;
        if (crawler.x > W - 26) { crawler.x = W - 26; crawler.dir = -1; }
        if (crawler.x < 26) { crawler.x = 26; crawler.dir = 1; }
      }
    }

    particles.forEach(function (p) {
      if (p.t === "ping") p.life -= dt * 1.1;
      else if (p.t === "ring") p.life -= dt * 1.3;
      else if (p.t === "packet") p.p += dt * 1.5;
      else if (p.t === "spark") { p.x += p.vx * dt; p.y += p.vy * dt; p.vy += 260 * dt; p.life -= dt * 1.4; }
      else if (p.t === "escape") { p.y -= 34 * dt; p.life -= dt * 0.9; }
    });
    particles = particles.filter(function (p) { return p.t === "packet" ? p.p < 2 : p.life > 0; });
  }

  /* ---------- drawing ---------- */

  function rr(x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function glyph(kind, s) {
    ctx.beginPath();
    if (kind === "router") {
      rr(-s, -s * 0.15, 2 * s, s * 0.8, s * 0.2);
      ctx.moveTo(-s * 0.55, -s * 0.15); ctx.lineTo(-s * 0.8, -s * 0.9);
      ctx.moveTo(s * 0.55, -s * 0.15); ctx.lineTo(s * 0.8, -s * 0.9);
    } else if (kind === "camera") {
      rr(-s, -s * 0.5, s * 1.45, s, s * 0.22);
      ctx.moveTo(s * 0.45, -s * 0.2); ctx.lineTo(s, -s * 0.48); ctx.lineTo(s, s * 0.48); ctx.lineTo(s * 0.45, s * 0.2);
    } else if (kind === "tv") {
      rr(-s, -s * 0.72, 2 * s, s * 1.22, s * 0.14);
      ctx.moveTo(-s * 0.4, s * 0.82); ctx.lineTo(s * 0.4, s * 0.82);
    } else if (kind === "nas") {
      rr(-s * 0.75, -s * 0.92, s * 1.5, s * 0.82, s * 0.14);
      rr(-s * 0.75, s * 0.1, s * 1.5, s * 0.82, s * 0.14);
    } else if (kind === "laptop") {
      rr(-s * 0.78, -s * 0.78, s * 1.56, s * 1.08, s * 0.1);
      ctx.moveTo(-s * 1.05, s * 0.55); ctx.lineTo(s * 1.05, s * 0.55);
    } else if (kind === "phone") {
      rr(-s * 0.5, -s * 0.95, s, s * 1.9, s * 0.22);
    } else if (kind === "tablet") {
      rr(-s * 0.74, -s * 0.95, s * 1.48, s * 1.9, s * 0.18);
    } else if (kind === "iot") {
      for (var i = 0; i < 6; i++) {
        var a = Math.PI / 3 * i - Math.PI / 6;
        if (i === 0) ctx.moveTo(Math.cos(a) * s, Math.sin(a) * s); else ctx.lineTo(Math.cos(a) * s, Math.sin(a) * s);
      }
      ctx.closePath();
    } else {
      ctx.arc(0, 0, s * 0.85, 0, Math.PI * 2);
    }
    ctx.stroke();

    /* small details that make each device readable */
    ctx.beginPath();
    if (kind === "router") {
      ctx.arc(-s * 0.45, s * 0.25, s * 0.1, 0, Math.PI * 2);
      ctx.arc(0, s * 0.25, s * 0.1, 0, Math.PI * 2);
      ctx.arc(s * 0.45, s * 0.25, s * 0.1, 0, Math.PI * 2);
      ctx.fill();
    } else if (kind === "camera") {
      ctx.arc(-s * 0.3, 0, s * 0.26, 0, Math.PI * 2); ctx.stroke();
    } else if (kind === "nas") {
      ctx.arc(s * 0.4, -s * 0.5, s * 0.09, 0, Math.PI * 2);
      ctx.arc(s * 0.4, s * 0.52, s * 0.09, 0, Math.PI * 2);
      ctx.fill();
    } else if (kind === "phone" || kind === "tablet") {
      ctx.arc(0, s * 0.7, s * 0.09, 0, Math.PI * 2); ctx.fill();
    } else if (kind === "iot") {
      ctx.arc(0, 0, s * 0.3, 0, Math.PI * 2); ctx.fill();
    } else if (kind === "unknown") {
      ctx.font = "700 " + Math.round(s * 1.3) + "px 'Readex Pro', system-ui, sans-serif";
      ctx.textAlign = "center"; ctx.textBaseline = "middle";
      ctx.fillText("?", 0, s * 0.08);
    }
  }

  function easeOutBack(t) { var c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); }

  function curvePoint(n, t) {
    var mx = (hubX + n.x) / 2, my = (hubY + n.y) / 2 - 20;
    var u = 1 - t;
    return { x: u * u * hubX + 2 * u * t * mx + t * t * n.x, y: u * u * hubY + 2 * u * t * my + t * t * n.y };
  }

  function drawBackground() {
    var g = ctx.createLinearGradient(0, 0, 0, wallY + 14);
    g.addColorStop(0, rgba(C.navy, C.dark ? 0.42 : 0.12));
    g.addColorStop(1, rgba(C.navy, 0));
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, W, wallY + 14);

    ctx.fillStyle = rgba(C.ink2, C.dark ? 0.14 : 0.22);
    for (var x = 14; x < W; x += 22) for (var y = 12; y < wallY - 16; y += 15) ctx.fillRect(x, y, 1.3, 1.3);

    var hg = ctx.createRadialGradient(hubX, hubY, 10, hubX, hubY, Math.max(rx, ry) * 1.15);
    hg.addColorStop(0, rgba(C.lite, C.dark ? 0.18 : 0.12));
    hg.addColorStop(1, rgba(C.lite, 0));
    ctx.fillStyle = hg;
    ctx.fillRect(0, wallY, W, H - wallY);

    ctx.strokeStyle = rgba(C.lite, C.dark ? 0.16 : 0.2);
    ctx.lineWidth = 1;
    [0.36, 0.66, 1].forEach(function (k) {
      ctx.beginPath();
      ctx.ellipse(hubX, hubY, rx * k, ry * k, 0, Math.PI, Math.PI * 2);
      ctx.stroke();
    });

    /* where each side of the wall is */
    var rtl = lang() === "ar";
    ctx.font = "600 10px 'Readex Pro', system-ui, sans-serif";
    ctx.textBaseline = "alphabetic";
    ctx.textAlign = rtl ? "right" : "left";
    ctx.fillStyle = rgba(C.ink2, 0.9);
    ctx.fillText(T("الإنترنت", "THE INTERNET"), rtl ? W - 12 : 12, 15);
    ctx.fillText(T("بيتك", "YOUR HOME"), rtl ? W - 12 : 12, H - 10);
  }

  function drawSweep() {
    if (phase !== "discover") return;
    var len = rx * 1.08, k = ry / rx;
    ctx.save();
    ctx.translate(hubX, hubY);
    ctx.scale(1, k);
    var g = ctx.createRadialGradient(0, 0, 0, 0, 0, len);
    g.addColorStop(0, rgba(C.gold, 0.30));
    g.addColorStop(1, rgba(C.gold, 0));
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.arc(0, 0, len, sweep - 0.6, sweep);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
    ctx.strokeStyle = rgba(C.gold, 0.85);
    ctx.lineWidth = 1.6;
    ctx.beginPath();
    ctx.moveTo(hubX, hubY);
    ctx.lineTo(hubX + Math.cos(sweep) * len, hubY + Math.sin(sweep) * len * k);
    ctx.stroke();
  }

  function drawLinks() {
    nodes.forEach(function (n) {
      if (n.hub || !n.found || n.fade <= 0) return;
      var a = Math.min(1, n.appear) * n.fade;
      var col = phase === "ready" && n.sev ? sevColor(n.sev) : C.lite;
      ctx.strokeStyle = rgba(col, 0.32 * a);
      ctx.lineWidth = 1.3;
      ctx.beginPath();
      ctx.moveTo(hubX, hubY);
      ctx.quadraticCurveTo((hubX + n.x) / 2, (hubY + n.y) / 2 - 20, n.x, n.y);
      ctx.stroke();
    });
  }

  function drawWall(now) {
    var body = 12, mh = 9, mw = 13, gap = 9;
    ctx.fillStyle = C.wall;
    ctx.fillRect(0, wallY, W, body);
    ctx.beginPath();
    for (var x = 4; x < W; x += mw + gap) rr(x, wallY - mh, mw, mh + 1, 2);
    ctx.fill();
    ctx.fillStyle = C.wallShade;
    ctx.fillRect(0, wallY + body - 3, W, 3);

    breaches.forEach(function (b) {
      var open = b.state === "forming" ? b.open : b.state === "sealing" ? 1 - b.seal : 1;
      var gw = 26 * open;
      var x0 = b.x - 13, x1 = b.x + 13, y0 = wallY - mh - 1, y1 = wallY + body + 1;

      if (b.state === "sealing") {
        /* the stones going back in, lit gold as they set */
        ctx.fillStyle = rgba(C.gold, 0.9 * (1 - b.seal));
        ctx.fillRect(x0, y0 + 2, x1 - x0, y1 - y0 - 2);
      }
      if (gw < 0.6) return;

      var gx0 = b.x - gw / 2, gx1 = b.x + gw / 2, hh = y1 - y0;
      ctx.save();
      ctx.shadowColor = C.crit;
      ctx.shadowBlur = 18 * open;
      ctx.fillStyle = C.bg;
      ctx.beginPath();
      ctx.moveTo(gx0, y0);
      ctx.lineTo(gx0 + gw * 0.22, y0 + hh * 0.3);
      ctx.lineTo(gx0 - 2, y0 + hh * 0.56);
      ctx.lineTo(gx0 + gw * 0.14, y1);
      ctx.lineTo(gx1 - gw * 0.1, y1);
      ctx.lineTo(gx1 + 2, y0 + hh * 0.6);
      ctx.lineTo(gx1 - gw * 0.2, y0 + hh * 0.34);
      ctx.lineTo(gx1, y0);
      ctx.closePath();
      ctx.fill();
      ctx.restore();
      ctx.fillStyle = rgba(C.crit, 0.22 * open);
      ctx.fill();
      ctx.strokeStyle = rgba(C.crit, 0.95 * open);
      ctx.lineWidth = 1.5;
      ctx.stroke();
    });
  }

  function drawBeams(now) {
    breaches.forEach(function (b) {
      var n = b.node;
      var p = b.state === "forming" ? b.open : b.state === "sealing" ? 1 - b.seal : 1;
      if (p <= 0.01) return;
      var bottom = n.y - R - 3, top = wallY + 12;
      var yEnd = bottom + (top - bottom) * p;
      ctx.save();
      ctx.strokeStyle = rgba(C.crit, 0.92);
      ctx.lineWidth = 2;
      ctx.setLineDash([6, 5]);
      ctx.lineDashOffset = -now / 32;
      ctx.beginPath();
      ctx.moveTo(n.x, bottom);
      ctx.lineTo(b.x, yEnd);
      ctx.stroke();
      ctx.restore();
    });
  }

  function drawCrawler() {
    if (phase !== "router" && phase !== "ready") return;
    var y = Math.max(22, wallY * 0.5), x = crawler.x;
    var hot = !!crawler.spot;
    if (hot) {
      var b = crawler.spot;
      ctx.fillStyle = rgba(C.crit, 0.2);
      ctx.beginPath();
      ctx.moveTo(x, y + 5);
      ctx.lineTo(b.x - 12, wallY - 9);
      ctx.lineTo(b.x + 12, wallY - 9);
      ctx.closePath();
      ctx.fill();
      ctx.font = "600 10px 'Readex Pro', system-ui, sans-serif";
      ctx.textAlign = "center";
      ctx.fillStyle = C.crit;
      var label = T("ماسح عام رصد الثغرة", "A public scanner found the gap");
      var half = ctx.measureText(label).width / 2 + 4;
      ctx.fillText(label, Math.max(half, Math.min(W - half, x)), y - 11);
    }
    ctx.strokeStyle = hot ? C.crit : rgba(C.ink2, 0.95);
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.ellipse(x, y, 9, 5.5, 0, 0, Math.PI * 2);
    ctx.stroke();
    ctx.fillStyle = hot ? C.crit : C.ink2;
    ctx.beginPath();
    ctx.arc(x + crawler.dir * 1.6, y, 2.4, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawNode(n, now) {
    if (!n.found || n.fade <= 0) return;
    var t = Math.min(1, n.appear);
    var pop = n.hub ? 1 : easeOutBack(t);
    var alpha = Math.min(1, t * 1.5) * n.fade;
    var base = n.hub ? R * 1.25 : R;
    var r = base * Math.max(0.2, pop) * (1 + 0.14 * n.bump) * (hovered === n.id ? 1.08 : 1);
    var ready = phase === "ready";
    var ring = ready ? sevColor(n.sev) : C.lite;

    ctx.save();
    ctx.globalAlpha = alpha;

    if (selected === n.id && ready) {
      var pulse = 0.5 + 0.5 * Math.sin(now / 280);
      ctx.strokeStyle = rgba(C.gold, 0.55 + 0.35 * pulse);
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 6 + pulse * 2, 0, Math.PI * 2);
      ctx.stroke();
    }
    if (n.probe > 0) {
      ctx.strokeStyle = rgba(C.gold, 0.6 * n.probe);
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 4 + (1 - n.probe) * 14, 0, Math.PI * 2);
      ctx.stroke();
    }

    if (ready && (n.sev === "critical" || n.sev === "high")) {
      ctx.shadowColor = ring;
      ctx.shadowBlur = 14 + Math.sin(now / 300) * 5;
    }
    ctx.fillStyle = C.panel;
    ctx.beginPath();
    ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.strokeStyle = ring;
    ctx.lineWidth = 2;
    ctx.stroke();

    ctx.translate(n.x, n.y);
    ctx.strokeStyle = C.ink;
    ctx.fillStyle = C.ink;
    ctx.lineWidth = 1.4;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    glyph(n.dev.kind, r * 0.5);
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);

    var fs = W < 540 ? 10 : 11;
    ctx.font = "500 " + fs + "px 'Readex Pro', system-ui, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "alphabetic";
    var label = L(n.dev.name);
    var half = ctx.measureText(label).width / 2 + 3;
    ctx.fillStyle = selected === n.id && ready ? C.ink : C.ink2;
    ctx.fillText(label, Math.max(half, Math.min(W - half, n.x)), n.y + r + fs + 4);
    ctx.restore();
  }

  function drawParticles() {
    particles.forEach(function (p) {
      if (p.t === "ping") {
        ctx.strokeStyle = rgba(C.gold, Math.max(0, p.life) * 0.75);
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.arc(p.x, p.y, (1 - p.life) * 32 + 6, 0, Math.PI * 2);
        ctx.stroke();
      } else if (p.t === "ring") {
        ctx.strokeStyle = rgba(p.gold ? C.gold : C.lite, Math.max(0, p.life) * 0.7);
        ctx.lineWidth = 1.6;
        ctx.beginPath();
        ctx.arc(p.x, p.y, (1 - p.life) * 46 + 8, 0, Math.PI * 2);
        ctx.stroke();
      } else if (p.t === "packet") {
        if (p.p < 0) return;
        var tt = p.p < 1 ? p.p : 2 - p.p;
        var pt = curvePoint(p.node, tt);
        ctx.fillStyle = rgba(C.gold, 0.95);
        ctx.beginPath();
        ctx.arc(pt.x, pt.y, 2.4, 0, Math.PI * 2);
        ctx.fill();
      } else if (p.t === "spark") {
        ctx.fillStyle = rgba(C.gold, Math.max(0, p.life));
        ctx.beginPath();
        ctx.arc(p.x, p.y, 1.8, 0, Math.PI * 2);
        ctx.fill();
      } else if (p.t === "escape") {
        ctx.fillStyle = rgba(C.crit, Math.max(0, p.life) * 0.8);
        ctx.beginPath();
        ctx.arc(p.x, p.y, 1.6, 0, Math.PI * 2);
        ctx.fill();
      }
    });
  }

  function draw(now) {
    try { ctx.direction = lang() === "ar" ? "rtl" : "ltr"; } catch (e) {}
    ctx.clearRect(0, 0, W, H);
    drawBackground();
    drawSweep();
    drawLinks();
    drawBeams(now);
    drawWall(now);
    drawCrawler();
    nodes.forEach(function (n) { if (!n.hub) drawNode(n, now); });
    nodes.forEach(function (n) { if (n.hub) drawNode(n, now); });
    drawParticles();
  }

  /* ---------- the loop, paused whenever nobody is looking ---------- */

  function frame(now) {
    raf = 0;
    if (!visible || document.hidden) return;
    var dt = last ? Math.min(0.05, (now - last) / 1000) : 0.016;
    last = now;
    update(dt);
    draw(now);
    if (!reduce || phase !== "ready") raf = requestAnimationFrame(frame);
  }
  function kick() {
    if (!raf && visible && !document.hidden && W) { last = 0; raf = requestAnimationFrame(frame); }
  }

  /* ---------- input ---------- */

  function pick(ev) {
    var r = canvas.getBoundingClientRect();
    var x = ev.clientX - r.left, y = ev.clientY - r.top;
    var best = null, bd = 1e9;
    nodes.forEach(function (n) {
      if (!n.found || n.dev.removed) return;
      var reach = (n.hub ? R * 1.25 : R) + 12;
      var d = Math.sqrt((n.x - x) * (n.x - x) + (n.y - y) * (n.y - y));
      if (d < reach && d < bd) { best = n; bd = d; }
    });
    return best;
  }

  canvas.addEventListener("click", function (ev) {
    if (phase !== "ready") return;
    var n = pick(ev);
    if (n) select(n.id);
  });
  canvas.addEventListener("pointermove", function (ev) {
    var n = phase === "ready" ? pick(ev) : null;
    var id = n ? n.id : null;
    if (id !== hovered) { hovered = id; kick(); }
    canvas.style.cursor = n ? "pointer" : "default";
  });
  canvas.addEventListener("pointerleave", function () { hovered = null; });

  ui.devices.addEventListener("click", function (ev) {
    var b = ev.target.closest("[data-dev]");
    if (b) select(b.getAttribute("data-dev"));
  });
  ui.inspector.addEventListener("click", function (ev) {
    var b = ev.target.closest("[data-fix]");
    if (b) applyFix(b.getAttribute("data-fix"), byId(selected));
  });
  ui.homes.addEventListener("click", function (ev) {
    var b = ev.target.closest("[data-home]");
    if (!b) return;
    ui.reset.hidden = true;
    loadHome(b.getAttribute("data-home"));
    scan();
  });
  ui.rescan.addEventListener("click", function () { if (phase === "ready") scan(); });
  ui.reset.addEventListener("click", function () {
    ui.reset.hidden = true;
    loadHome(homeId);
    scan();
  });
  root.addEventListener("keydown", function (ev) {
    if (ev.key === "Escape" && selected) { selected = null; lastChange = null; renderInspector(); renderDevices(); }
  });

  document.addEventListener("soor:lang", function () { renderAll(); kick(); });
  document.addEventListener("visibilitychange", kick);
  var scheme = window.matchMedia("(prefers-color-scheme: light)");
  if (scheme.addEventListener) scheme.addEventListener("change", function () { readPalette(); kick(); });

  /* ---------- boot ---------- */

  function boot(k) {
    knowledge = k;
    readPalette();
    loadHome(homeId);
    resize();
    renderAll();
    if (window.ResizeObserver) new ResizeObserver(function () { resize(); }).observe(ui.stage);
    else window.addEventListener("resize", resize);

    new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        visible = e.isIntersecting;
        if (visible) {
          if (phase === "idle") scan();
          kick();
        }
      });
    }, { threshold: 0.2 }).observe(root);
  }

  fetch("data/services.json").then(function (r) { return r.json(); }).then(function (svc) {
    return fetch("data/cameras.json").then(function (r) { return r.json(); }).then(function (cam) {
      boot({ services: svc.services, cameras: cam.vendors });
    });
  }).catch(function () { root.hidden = true; });
})();
