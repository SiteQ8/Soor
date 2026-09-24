/*
  The demo page. It loads the two knowledge files, runs the real Soor engine on
  a set of realistic scan scenarios, and renders the findings the way the app
  does. Nothing here reaches the network beyond fetching the app's own JSON.
*/
(function () {
  "use strict";

  var lang = document.documentElement.lang === "en" ? "en" : "ar";
  var knowledge = null;

  var SCENARIOS = [
    {
      id: "typical",
      label: { ar: "بيت اعتيادي", en: "A typical home" },
      observations: [
        { host: "192.168.1.1", port: 80, httpServer: "Router Webserver", banner: "WWW-Authenticate: Basic realm", internetExposed: false },
        { host: "192.168.1.1", port: 1900, banner: "UPnP/1.1 IGD rootDevice", internetExposed: false },
        { host: "192.168.1.10", port: 22, banner: "SSH-2.0-OpenSSH_9.6", internetExposed: false },
        { host: "192.168.1.42", port: 0, deviceName: "phone", isNew: false, internetExposed: false }
      ]
    },
    {
      id: "camera",
      label: { ar: "كاميرا مكشوفة", en: "An exposed camera" },
      observations: [
        { host: "192.168.1.64", port: 554, rtspServer: "Hipcam RealServer/V1.0", noAuth: true, internetExposed: true },
        { host: "192.168.1.64", port: 80, httpServer: "App-webs", httpTitle: "", internetExposed: true },
        { host: "192.168.1.1", port: 1900, banner: "UPnP/1.1 IGD rootDevice", internetExposed: false },
        { host: "192.168.1.20", port: 445, banner: "Samba 4.10", noAuth: true, internetExposed: false }
      ]
    },
    {
      id: "androidbox",
      label: { ar: "صندوق بث", en: "A streaming box" },
      observations: [
        { host: "192.168.1.90", port: 5555, banner: "host::features", noAuth: true, internetExposed: false },
        { host: "192.168.1.90", port: 0, deviceName: "unknown box", isNew: true, internetExposed: false },
        { host: "192.168.1.70", port: 34567, httpServer: "uc-httpd 1.0.0", httpTitle: "NETSurveillance WEB", noAuth: true, internetExposed: false }
      ]
    },
    {
      id: "clean",
      label: { ar: "شبكة نظيفة", en: "A clean network" },
      observations: [
        { host: "192.168.1.1", port: 443, banner: "self-signed certificate", internetExposed: false },
        { host: "192.168.1.10", port: 22, banner: "SSH-2.0-OpenSSH_9.6", internetExposed: false }
      ]
    }
  ];

  function t(obj) { return obj[lang] || obj.ar; }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function severityClass(sev) {
    return ["critical", "high", "medium", "low", "info"].indexOf(sev) >= 0 ? sev : "info";
  }

  function render(scenario) {
    var findings = Soor.analyse({
      observations: scenario.observations,
      services: knowledge.services,
      cameras: knowledge.cameras
    });

    var summary = document.getElementById("summary");
    var counts = Soor.summarise(findings);
    summary.innerHTML = "";
    var labels = { critical: { ar: "حرِج", en: "critical" }, high: { ar: "مرتفع", en: "high" }, medium: { ar: "متوسط", en: "medium" }, info: { ar: "للعلم", en: "info" } };
    ["critical", "high", "medium", "info"].forEach(function (sev) {
      if (!counts[sev]) return;
      var pill = el("span", "pill " + sev);
      pill.innerHTML = "<b>" + counts[sev] + "</b> " + t(labels[sev]);
      summary.appendChild(pill);
    });
    if (!findings.length) {
      summary.appendChild(el("span", "pill info", t({ ar: "لا مخاطر ظاهرة", en: "no visible risks" })));
    }

    var box = document.getElementById("findings");
    box.innerHTML = "";
    findings.forEach(function (f) {
      var r = SoorReport.render(f, knowledge, lang);
      var card = el("div", "finding " + severityClass(f.severity));
      var row = el("div", "row");
      row.appendChild(el("span", "sev", r.severity));
      row.appendChild(el("span", "title", r.title));
      row.appendChild(el("span", "addr", f.host + (f.port ? ":" + f.port : "")));
      card.appendChild(row);
      if (r.detail) card.appendChild(el("div", "detail", r.detail));
      if (r.fix) {
        var fix = el("div", "fix");
        fix.innerHTML = "<b>" + t({ ar: "الحل: ", en: "Fix: " }) + "</b>";
        fix.appendChild(document.createTextNode(r.fix));
        card.appendChild(fix);
      }
      box.appendChild(card);
    });
  }

  function buildControls() {
    var wrap = document.getElementById("scenarios");
    wrap.innerHTML = "";
    SCENARIOS.forEach(function (s, i) {
      var b = el("button", null, t(s.label));
      b.addEventListener("click", function () { render(s); });
      wrap.appendChild(b);
      if (i === 0) setTimeout(function () { render(s); }, 0);
    });
  }

  function setLang(next) {
    lang = next;
    document.documentElement.lang = next;
    document.documentElement.dir = next === "ar" ? "rtl" : "ltr";
    document.getElementById("lang").textContent = next === "ar" ? "English" : "العربية";
    document.querySelector('meta[name="theme-color"]');
    if (knowledge) buildControls();
  }

  document.getElementById("lang").addEventListener("click", function () {
    setLang(lang === "ar" ? "en" : "ar");
  });

  fetch("data/services.json").then(function (r) { return r.json(); }).then(function (svc) {
    return fetch("data/cameras.json").then(function (r) { return r.json(); }).then(function (cam) {
      knowledge = { services: svc.services, cameras: cam.vendors };
      buildControls();
    });
  }).catch(function () {
    document.getElementById("findings").textContent =
      lang === "ar" ? "تعذّر تحميل قاعدة المعرفة." : "Could not load the knowledge base.";
  });
})();
