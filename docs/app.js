/*
  The language switch. Arabic is the default; the choice is remembered on this
  device only, and every part of the page that draws its own text listens for
  the soor:lang event to redraw in the new language.
*/
(function () {
  "use strict";

  var btn = document.getElementById("lang");
  if (!btn) return;

  function setLang(next) {
    document.documentElement.lang = next;
    document.documentElement.dir = next === "ar" ? "rtl" : "ltr";
    btn.textContent = next === "ar" ? "English" : "العربية";
    try { localStorage.setItem("soor.lang", next); } catch (e) {}
    document.dispatchEvent(new CustomEvent("soor:lang", { detail: next }));
  }

  btn.addEventListener("click", function () {
    setLang(document.documentElement.lang === "en" ? "ar" : "en");
  });

  try {
    var saved = localStorage.getItem("soor.lang");
    if (saved && saved !== document.documentElement.lang) setLang(saved);
  } catch (e) {}
})();
