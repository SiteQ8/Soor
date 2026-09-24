/*
  Turns findings from the engine into the words a person reads, in Arabic and
  English. The engine stays free of display strings; this is where a finding
  becomes a sentence. The iOS and Android ports carry their own copy of this
  wording, drawn from the same knowledge files.
*/
(function (root) {
  "use strict";

  var KIND = {
    "exposed-camera-default": {
      ar: "كاميرا مكشوفة للإنترنت بكلمة مرور مصنع",
      en: "Camera exposed to the internet with a factory password"
    },
    "exposed-service": {
      ar: "خدمة خطرة مكشوفة للإنترنت",
      en: "Risky service exposed to the internet"
    },
    "default-credentials": {
      ar: "جهاز على طراز يُشحن ببيانات دخول معروفة",
      en: "Device on a model that ships with known login details"
    },
    "open-auth": {
      ar: "خدمة تستجيب دون كلمة مرور",
      en: "Service that answers with no password"
    },
    "weak-service": {
      ar: "خدمة تستحق المراجعة",
      en: "Service worth reviewing"
    },
    "new-device": {
      ar: "جهاز ظهر على الشبكة لأول مرة",
      en: "A device seen on the network for the first time"
    }
  };

  var SEVERITY = {
    critical: { ar: "حرِج", en: "Critical" },
    high: { ar: "مرتفع", en: "High" },
    medium: { ar: "متوسط", en: "Medium" },
    low: { ar: "منخفض", en: "Low" },
    info: { ar: "للعلم", en: "Info" }
  };

  function byId(list, id) {
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }

  function pick(obj, lang) {
    if (!obj) return "";
    return obj[lang === "ar" ? "ar" : "en"] || "";
  }

  /*
    render(finding, knowledge, lang)
      knowledge.services   services array
      knowledge.cameras    vendors array
      lang                 ar or en
    Returns { severity, title, detail, fix } as plain strings.
  */
  function render(finding, knowledge, lang) {
    var svc = finding.service ? byId(knowledge.services, finding.service) : null;
    var cam = finding.vendor ? byId(knowledge.cameras, finding.vendor) : null;

    var title = pick(KIND[finding.kind], lang);
    var detailParts = [];
    var fixParts = [];

    if (cam) {
      detailParts.push(pick(cam.notes, lang));
    }
    if (svc) {
      detailParts.push(pick(svc.what, lang));
      fixParts.push(pick(svc.fix, lang));
    }

    if (finding.kind === "exposed-camera-default") {
      detailParts.unshift(lang === "ar"
        ? "منفذ هذه الكاميرا مرّره الراوتر إلى الإنترنت، وطرازها يُشحن ببيانات دخول معروفة، فهي قابلة لأن تظهر في محركات الفحص العامة."
        : "This camera's port is forwarded to the internet by the router, and its model ships with known login details, so it can appear on public scanning engines.");
      fixParts.unshift(lang === "ar"
        ? "غيّر كلمة مرور الكاميرا الآن، ثم أغلق تمرير المنفذ في الراوتر أو عطّل UPnP."
        : "Change the camera password now, then close the port forward on the router or disable UPnP.");
    }

    if (finding.kind === "exposed-service") {
      detailParts.unshift(lang === "ar"
        ? "هذا المنفذ مرّره الراوتر إلى الإنترنت، فصار قابلًا للوصول من خارج البيت."
        : "This port is forwarded to the internet by the router, so it is reachable from outside the home.");
      fixParts.unshift(lang === "ar"
        ? "أغلق تمرير المنفذ في الراوتر أو عطّل UPnP، ثم راجع الخدمة نفسها."
        : "Close the port forward on the router or disable UPnP, then review the service itself.");
    }

    if (finding.kind === "default-credentials") {
      fixParts.unshift(lang === "ar"
        ? "غيّر كلمة مرور الجهاز الآن ولا تبقِه على بيانات المصنع."
        : "Change the device password now and do not leave it on factory details.");
    }

    if (finding.reason === "no-auth") {
      detailParts.push(lang === "ar"
        ? "الخدمة استجابت دون طلب كلمة مرور، وهذا مؤكَّد من الجهاز نفسه."
        : "The service answered without asking for a password, confirmed from the device itself.");
    }

    if (finding.kind === "new-device") {
      detailParts.push(lang === "ar"
        ? "تحقّق أنك تعرف هذا الجهاز. إن لم تعرفه فافصله وغيّر كلمة مرور الشبكة."
        : "Check that you recognise this device. If you do not, disconnect it and change the network password.");
    }

    return {
      severity: pick(SEVERITY[finding.severity], lang),
      title: title,
      detail: detailParts.filter(Boolean).join(" "),
      fix: fixParts.filter(Boolean).join(" "),
      host: finding.host,
      port: finding.port
    };
  }

  var api = { render: render, KIND: KIND, SEVERITY: SEVERITY };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.SoorReport = api;
})(typeof window !== "undefined" ? window : globalThis);
