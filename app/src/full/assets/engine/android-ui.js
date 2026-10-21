/*
 * DeepSeek Android: the official harness UI (dsh web + dsh-web-mobile), made clean for a phone. Injected by
 * EngineWebView into the harness page only (http://127.0.0.1:<port>). It changes presentation, never features:
 *   1. no wallpaper, no glass: every surface is opaque Material-style (the harness's own --dsw tokens), no backdrop blur;
 *   2. semi-transparent overlays (header, sheets, menus, palettes, previews, dialogs, chips) are flattened onto the
 *      page background so nothing shows through; full-screen dark scrims stay scrims;
 *   3. the session stats row is hidden unless "Session stats" is on in the overflow menu;
 *   4. the no-root "Host desktop information" banner is hidden;
 *   5. Settings > Plugins gets an "Installed plugins" section (install from GitHub/npm/file/folder, enable/disable,
 *      update, remove) backed by the app (window.DsAndroid, every call carries this injection's nonce).
 * Idempotent: running it twice on one document does nothing the second time.
 */
(function (nonce, options) {
  "use strict";
  if (window.__dsaInstalled === nonce) return;
  window.__dsaInstalled = nonce;
  var bridge = window.DsAndroid;

  var CSS = [
    "html, body { background: var(--dsw-alias-bg-base, Canvas) !important; background-image: none !important; }",
    "*, *::before, *::after { -webkit-backdrop-filter: none !important; backdrop-filter: none !important; }",
    "body, body * { --dsw-menu-backdrop-filter: none !important;",
    "  --dsw-menu-surface-fill: var(--dsw-alias-bg-base) !important;",
    "  --dsw-specific-menu: var(--dsw-alias-bg-base) !important;",
    "  --dsw-alias-menu-group-header-fill: var(--dsw-alias-bg-base) !important; }",
    "[data-menu-material], [role=menu], [role=listbox], [role=dialog], [aria-modal=true] {",
    "  background-color: var(--dsw-alias-bg-base) !important; background-image: none !important; }",
    "video[autoplay][loop][muted], [class*=wallpaper i], [class*=skin-background i] { display: none !important; }",
    "html.dsa-hide-stats [data-mobile-nav=stats], html.dsa-hide-stats [data-mobile-nav=stats-ring-reserve],",
    "html.dsa-hide-stats [data-mobile-nav=stats-tps-reserve] { display: none !important; }",
    "[data-dsa-hidden] { display: none !important; }",
    "#dsa-user-plugins { margin: 8px 0 20px; padding: 16px; border-radius: 12px;",
    "  background: var(--dsw-alias-bg-layer-1, var(--dsw-alias-bg-base)); border: 1px solid var(--dsw-alias-border-l1, rgba(127,127,127,.25));",
    "  font: inherit; color: inherit; }",
    "#dsa-user-plugins h3 { margin: 0 0 4px; font-size: 15px; font-weight: 600; }",
    "#dsa-user-plugins p { margin: 0 0 12px; font-size: 13px; opacity: .75; }",
    "#dsa-user-plugins .dsa-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }",
    "#dsa-user-plugins input[type=text] { flex: 1 1 200px; min-height: 40px; padding: 0 12px; border-radius: 10px; font: inherit;",
    "  color: inherit; background: var(--dsw-alias-bg-base); border: 1px solid var(--dsw-alias-border-l2, rgba(127,127,127,.4)); }",
    "#dsa-user-plugins button { min-height: 40px; padding: 0 14px; border-radius: 20px; font: inherit; cursor: pointer; color: inherit;",
    "  background: var(--dsw-alias-bg-base); border: 1px solid var(--dsw-alias-border-l2, rgba(127,127,127,.4)); }",
    "#dsa-user-plugins button.dsa-primary { background: var(--dsw-alias-brand-primary, #4d6bfe); color: #fff; border-color: transparent; }",
    "#dsa-user-plugins button:disabled { opacity: .5; }",
    "#dsa-user-plugins .dsa-error { color: var(--dsw-alias-label-danger, #d93025); font-size: 13px; margin: 8px 0 0; min-height: 0; }",
    "#dsa-user-plugins .dsa-status { font-size: 13px; margin: 8px 0 0; opacity: .8; }",
    "#dsa-user-plugins ul { list-style: none; margin: 12px 0 0; padding: 0; }",
    "#dsa-user-plugins li { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 10px 0;",
    "  border-top: 1px solid var(--dsw-alias-border-l1, rgba(127,127,127,.2)); }",
    "#dsa-user-plugins li .dsa-name { flex: 1 1 160px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }",
    "#dsa-user-plugins li .dsa-kind { font-size: 12px; opacity: .7; margin-left: 6px; }"
  ].join("\n");

  function addStyle() {
    if (document.getElementById("dsa-style")) return;
    var style = document.createElement("style");
    style.id = "dsa-style";
    style.textContent = CSS;
    (document.head || document.documentElement).appendChild(style);
  }

  function setStats(show) {
    document.documentElement.classList.toggle("dsa-hide-stats", !show);
  }

  // ---------------------------------------------------------------- opacity

  function parseColor(value) {
    var m = /rgba?\(([^)]+)\)/.exec(value || "");
    if (!m) return null;
    var p = m[1].split(/[ ,\/]+/).filter(Boolean).map(parseFloat);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  }

  var base = null;
  function baseColor() {
    var c = parseColor(getComputedStyle(document.body || document.documentElement).backgroundColor);
    if (!c || c.a < 1) {
      var dark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
      c = dark ? { r: 22, g: 23, b: 26, a: 1 } : { r: 255, g: 255, b: 255, a: 1 };
    }
    return c;
  }

  function flatten(el, c) {
    var b = base;
    var r = Math.round(c.r * c.a + b.r * (1 - c.a));
    var g = Math.round(c.g * c.a + b.g * (1 - c.a));
    var bl = Math.round(c.b * c.a + b.b * (1 - c.a));
    el.style.setProperty("background-color", "rgb(" + r + "," + g + "," + bl + ")", "important");
    el.style.setProperty("background-image", "none", "important");
    el.setAttribute("data-dsa-opaque", "");
  }

  function isScrim(el, rect, c) {
    var full = rect.width >= window.innerWidth * 0.95 && rect.height >= window.innerHeight * 0.9;
    var dark = c.r + c.g + c.b < 150;
    return full && dark && c.a <= 0.7 && (el.textContent || "").trim().length === 0;
  }

  /** Overlay-like surfaces (fixed/sticky/absolute layers, dialogs, menus) with a see-through fill become opaque. */
  function opacify() {
    if (!document.body) return;
    base = baseColor();
    var nodes = document.body.querySelectorAll("*");
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.hasAttribute("data-dsa-opaque") || el.id === "dsa-user-plugins") continue;
      var cs = getComputedStyle(el);
      var pos = cs.position;
      var layered = pos === "fixed" || pos === "sticky" || (pos === "absolute" && cs.zIndex !== "auto" && parseInt(cs.zIndex, 10) > 0)
        || el.hasAttribute("data-menu-material") || /^(dialog|menu|listbox|tooltip)$/.test(el.getAttribute("role") || "");
      if (!layered) continue;
      var c = parseColor(cs.backgroundColor);
      if (!c || c.a === 0 || c.a >= 1) continue;
      var rect = el.getBoundingClientRect();
      if (rect.width < 24 || rect.height < 16) continue;
      if (isScrim(el, rect, c)) continue;
      flatten(el, c);
    }
    hideHostBanner();
  }

  /** Without root there is no host desktop to read: its inline error banner is noise on a phone. */
  function hideHostBanner() {
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var n;
    while ((n = walker.nextNode())) {
      if (/Could not read the Host desktop information|无法读取主机桌面信息/.test(n.nodeValue || "")) {
        var row = n.parentElement;
        for (var up = 0; up < 4 && row && row.parentElement && row.parentElement.textContent.length < 160; up++) row = row.parentElement;
        if (row) row.setAttribute("data-dsa-hidden", "");
      }
    }
  }

  // ---------------------------------------------------------------- installed plugins

  function el(tag, attrs, text) {
    var e = document.createElement(tag);
    for (var k in attrs || {}) e.setAttribute(k, attrs[k]);
    if (text != null) e.textContent = text;
    return e;
  }

  function call(method) {
    if (!bridge || typeof bridge[method] !== "function") return null;
    var args = [nonce].concat(Array.prototype.slice.call(arguments, 1));
    return bridge[method].apply(bridge, args);
  }

  var section = null;
  function render(list, message, error) {
    if (!section) return;
    var ul = section.querySelector("ul");
    ul.textContent = "";
    (list || []).forEach(function (p) {
      var li = el("li", { "data-dsa-plugin": p.name });
      var name = el("span", { "class": "dsa-name" }, p.name);
      name.appendChild(el("span", { "class": "dsa-kind" }, p.kind === "skill" ? "skill" : "plugin" + (p.version ? " " + p.version : "")));
      li.appendChild(name);
      var toggle = el("button", { "data-dsa-action": "toggle" }, p.enabled ? "Disable" : "Enable");
      toggle.onclick = function () { busy(); call("setEnabled", p.name, !p.enabled); };
      var update = el("button", { "data-dsa-action": "update" }, "Update");
      update.onclick = function () { busy("Updating " + p.name + "…"); call("update", p.name); };
      var remove = el("button", { "data-dsa-action": "remove" }, "Remove");
      remove.onclick = function () { if (confirm("Remove " + p.name + "?")) { busy(); call("remove", p.name); } };
      li.appendChild(toggle);
      li.appendChild(update);
      li.appendChild(remove);
      ul.appendChild(li);
    });
    if (!list || list.length === 0) ul.appendChild(el("li", { "class": "dsa-empty" }, "No plugins installed yet."));
    section.querySelector(".dsa-error").textContent = error || "";
    section.querySelector(".dsa-status").textContent = message || "";
    section.querySelectorAll("button, input").forEach(function (b) { b.disabled = false; });
  }

  function busy(text) {
    if (!section) return;
    section.querySelector(".dsa-error").textContent = "";
    section.querySelector(".dsa-status").textContent = text || "Working…";
    section.querySelectorAll("button, input").forEach(function (b) { b.disabled = true; });
  }

  /** Called by the app when an install/update/remove/toggle finishes: {list, message, error}. */
  window.__dsaPluginsDone = function (result) {
    render(result.list, result.message, result.error);
  };

  function buildSection() {
    var s = el("section", { id: "dsa-user-plugins" });
    s.appendChild(el("h3", null, "Installed plugins"));
    s.appendChild(el("p", null, "Everything in DeepSeek Harness is a plugin, and skills install as plugins too. "
      + "Install from a GitHub URL or npm package name, or pick a .tgz/.zip file or a folder."));
    var row = el("div", { "class": "dsa-row" });
    var input = el("input", { type: "text", placeholder: "https://github.com/owner/repo or npm name", "aria-label": "Plugin source",
      autocapitalize: "off", autocomplete: "off", spellcheck: "false" });
    var install = el("button", { "class": "dsa-primary", "data-dsa-action": "install" }, "Install");
    install.onclick = function () { busy("Installing…"); call("install", input.value); };
    input.addEventListener("keydown", function (e) { if (e.key === "Enter") install.click(); });
    var file = el("button", { "data-dsa-action": "pick-file" }, "Choose file");
    file.onclick = function () { call("pick", "file"); };
    var folder = el("button", { "data-dsa-action": "pick-folder" }, "Choose folder");
    folder.onclick = function () { call("pick", "folder"); };
    row.appendChild(input);
    row.appendChild(install);
    row.appendChild(file);
    row.appendChild(folder);
    s.appendChild(row);
    s.appendChild(el("div", { "class": "dsa-error", role: "alert" }));
    s.appendChild(el("div", { "class": "dsa-status", "aria-live": "polite" }));
    s.appendChild(el("ul"));
    return s;
  }

  /**
   * Anchor: the "Built-in plugins" heading of Settings > Plugins (English or Chinese UI). Headings first; the settings
   * tab with the same label (a button or role=tab) is never an anchor.
   */
  var LABEL = /^(Built-in plugins|内置插件)(\s*[(（]?\d+[)）]?)?$/;
  function findHeading() {
    var groups = ["h1, h2, h3, h4, h5, h6, [role=heading]", "div, span, p, summary"];
    for (var g = 0; g < groups.length; g++) {
      var candidates = document.querySelectorAll(groups[g]);
      for (var i = 0; i < candidates.length; i++) {
        var c = candidates[i];
        if (c.children.length > 1 || c.closest("button, [role=tab], [role=tablist], nav")) continue;
        if (LABEL.test((c.textContent || "").trim())) return c;
      }
    }
    return null;
  }

  function mountPlugins() {
    if (document.getElementById("dsa-user-plugins")) return;
    var heading = findHeading();
    if (!heading) {
      section = null;
      return;
    }
    var anchor = heading;
    while (anchor.parentElement && anchor.parentElement.children.length === 1) anchor = anchor.parentElement;
    section = buildSection();
    anchor.parentElement.insertBefore(section, anchor);
    var listed = call("plugins");
    render(listed ? JSON.parse(listed) : [], "", bridge ? "" : "The app bridge is unavailable.");
  }

  // ---------------------------------------------------------------- downloads

  /** Exports are blob:/data: links clicked from script; the WebView cannot save those itself, so the app does. */
  var originalClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var href = this.href || "";
    if (bridge && (href.indexOf("blob:") === 0 || href.indexOf("data:") === 0)) {
      var name = this.getAttribute("download") || "download";
      fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {
        var reader = new FileReader();
        reader.onload = function () {
          var data = String(reader.result);
          call("saveDownload", name, blob.type || "application/octet-stream", data.substring(data.indexOf(",") + 1));
        };
        reader.readAsDataURL(blob);
      });
      return;
    }
    return originalClick.apply(this, arguments);
  };

  // ---------------------------------------------------------------- wiring

  // Streaming answers mutate the page many times a second: one pass at most every 400 ms keeps the scan cheap.
  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(function () {
      scheduled = false;
      try { opacify(); } catch (e) { console.error("[dsa] opacify", e); }
      try { mountPlugins(); } catch (e) { console.error("[dsa] plugins", e); }
    }, 400);
  }

  window.__dsaSetStats = setStats;
  addStyle();
  setStats(!!options.showStats);
  schedule();
  new MutationObserver(function () { addStyle(); schedule(); })
    .observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ["class", "style", "data-state", "open"] });
})("__DSA_NONCE__", __DSA_OPTIONS__);
