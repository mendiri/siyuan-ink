/*
 * Plugin de SiYuan: dibuja sobre los PDF las anotaciones de la app SiYuan Ink.
 * Mismo formato y mismo cálculo que app/src/main/assets/siyuan-ink.js.
 */
const siyuan = require("siyuan");
const { Plugin } = siyuan;

const SVGNS = "http://www.w3.org/2000/svg";
const STYLE_ID = "siyuan-ink-plugin-style";
const RELOAD_MS = 15000;

function widthFor(base, p) {
  if (typeof p !== "number") p = 0.5;
  p = Math.max(0, Math.min(1, p));
  return base * (0.35 + 1.3 * p);
}

function circle(cx, cy, r) {
  return "M" + (cx - r).toFixed(1) + " " + cy.toFixed(1) +
    " a" + r.toFixed(2) + " " + r.toFixed(2) + " 0 1 0 " + (2 * r).toFixed(2) + " 0" +
    " a" + r.toFixed(2) + " " + r.toFixed(2) + " 0 1 0 " + (-2 * r).toFixed(2) + " 0Z ";
}

function strokePath(pts, base) {
  const n = pts.length;
  if (!n) return "";
  const w = [];
  for (let i = 0; i < n; i++) {
    let sum = 0, c = 0;
    for (let j = Math.max(0, i - 2); j <= Math.min(n - 1, i + 2); j++) {
      sum += typeof pts[j][2] === "number" ? pts[j][2] : 0.5;
      c++;
    }
    w.push(widthFor(base, sum / c));
  }
  let d = circle(pts[0][0], pts[0][1], w[0] / 2);
  if (n === 1) return d;
  d += circle(pts[n - 1][0], pts[n - 1][1], w[n - 1] / 2);
  const left = [], right = [];
  for (let k = 0; k < n; k++) {
    const a = pts[Math.max(0, k - 1)], b = pts[Math.min(n - 1, k + 1)];
    let dx = b[0] - a[0], dy = b[1] - a[1];
    const len = Math.sqrt(dx * dx + dy * dy);
    if (len < 0.0001) { dx = 1; dy = 0; } else { dx /= len; dy /= len; }
    const h = w[k] / 2;
    left.push((pts[k][0] - dy * h).toFixed(1) + " " + (pts[k][1] + dx * h).toFixed(1));
    right.push((pts[k][0] + dy * h).toFixed(1) + " " + (pts[k][1] - dx * h).toFixed(1));
  }
  return d + "M" + left.join(" L") + " L" + right.reverse().join(" L") + "Z";
}

function sanitizeKey(name) {
  return String(name).trim().replace(/[^\w.\-]+/g, "_").slice(0, 150) || "pdf";
}

// Ruta del PDF dentro de SiYuan ("assets/….pdf"): misma clave que usa la app de la Boox.
function assetPathOf(viewer) {
  let found = null;
  try {
    if (typeof siyuan.getAllModels === "function") {
      const assets = siyuan.getAllModels().asset || [];
      for (const m of assets) {
        if (m && typeof m.path === "string" && m.element && m.element.contains(viewer)) {
          found = m.path;
          break;
        }
      }
    }
  } catch (e) { /* seguimos con el recorrido manual */ }
  if (!found) {
    try {
      const seen = new Set();
      const walk = (o, depth) => {
        if (!o || typeof o !== "object" || found || depth > 14 || seen.has(o)) return;
        seen.add(o);
        if (typeof o.path === "string" && /\.pdf$/i.test(o.path) && o.element && o.element.contains && o.element.contains(viewer)) {
          found = o.path;
          return;
        }
        if (Array.isArray(o.children)) o.children.forEach((c) => walk(c, depth + 1));
        if (o.model) walk(o.model, depth + 1);
        if (o.layout) walk(o.layout, depth + 1);
        if (o.centerLayout) walk(o.centerLayout, depth + 1);
      };
      walk(window.siyuan && window.siyuan.layout, 0);
    } catch (e) { /* nada */ }
  }
  if (!found) return null;
  return found.replace(/^https?:\/\/[^/]+\//, "").replace(/^\/+/, "");
}

// Clave antigua (app ≤ 0.4): título de la pestaña.
function titleKeyOf(viewer) {
  let name = "";
  let el = viewer;
  while (el && el.parentElement && !name) {
    el = el.parentElement.closest("[data-id]");
    if (!el) break;
    const li = document.querySelector('li[data-type="tab-header"][data-id="' + el.getAttribute("data-id") + '"]');
    if (li) {
      const txt = li.querySelector(".item__text");
      name = li.getAttribute("aria-label") || (txt ? txt.textContent : li.textContent) || "";
    }
  }
  if (!name) name = document.title || "pdf";
  return sanitizeKey(name).slice(0, 120);
}

function keysOf(viewer) {
  if (!viewer.__inkKey) {
    const legacy = titleKeyOf(viewer);
    const path = assetPathOf(viewer);
    if (!path) return { key: legacy, legacy: legacy };
    viewer.__inkKey = sanitizeKey(path);
    viewer.__inkLegacy = legacy;
  }
  return { key: viewer.__inkKey, legacy: viewer.__inkLegacy };
}

function fetchInk(key) {
  return fetch("/api/file/getFile", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path: "/data/storage/siyuan-ink/pdf/" + key + ".json" }),
  })
    .then((r) => r.text())
    .then((t) => {
      let d = null;
      try { d = JSON.parse(t); } catch (e) { /* no existe */ }
      return d && d.pages ? { d, t } : null;
    })
    .catch(() => null);
}

let dirCache = null;
let dirAt = 0;

function listInkFiles() {
  if (dirCache && Date.now() - dirAt < 10000) return Promise.resolve(dirCache);
  return fetch("/api/file/readDir", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path: "/data/storage/siyuan-ink/pdf" }),
  })
    .then((r) => r.json())
    .then((j) => {
      dirCache = j && j.code === 0 && Array.isArray(j.data) ? j.data.filter((f) => !f.isDir).map((f) => f.name) : [];
      dirAt = Date.now();
      return dirCache;
    })
    .catch(() => {
      dirCache = [];
      dirAt = Date.now();
      return dirCache;
    });
}

// Identificador único que SiYuan añade a cada recurso: "20260216073344-8kbx1ji".
function assetToken(name) {
  const m = /\d{14}-[a-z0-9]{7}/i.exec(name || "");
  return m ? m[0] : null;
}

// Busca el archivo de anotaciones de este PDF aunque la clave se calculara distinto en la Boox.
function resolveFile(key, legacy, files) {
  if (files.includes(key + ".json")) return key;
  if (legacy && files.includes(legacy + ".json")) return legacy;
  const tok = assetToken(key) || assetToken(legacy);
  if (tok) {
    const f = files.find((n) => n.includes(tok));
    if (f) return f.replace(/\.json$/, "");
  }
  return null;
}

function distToSeg(px, py, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1];
  const l2 = dx * dx + dy * dy;
  let t = l2 ? ((px - a[0]) * dx + (py - a[1]) * dy) / l2 : 0;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (a[0] + t * dx), py - (a[1] + t * dy));
}

function strokeNear(s, x, y, r) {
  const rr = r + s.w * 0.8;
  const p = s.p;
  if (p.length === 1) return Math.hypot(x - p[0][0], y - p[0][1]) <= rr;
  for (let i = 1; i < p.length; i++) if (distToSeg(x, y, p[i - 1], p[i]) <= rr) return true;
  return false;
}

const WIDTHS = [1.5, 2.5, 4];
const WIDTH_NAMES = ["fino", "medio", "grueso"];
const ERASER_RADIUS = 8; // en unidades de página (ancho = 1000)

const BAR_CSS =
  "position:absolute;right:12px;bottom:12px;z-index:20;font-size:12px;display:flex;gap:4px;align-items:center;" +
  "padding:3px 6px;border-radius:6px;background:var(--b3-theme-surface,#fff);color:var(--b3-theme-on-surface,#333);" +
  "border:1px solid var(--b3-border-color,#ccc);box-shadow:0 1px 4px rgba(0,0,0,.2)";
const BTN_CSS =
  "border:1px solid var(--b3-border-color,#ccc);background:transparent;color:inherit;border-radius:4px;" +
  "padding:2px 8px;font-size:12px;cursor:pointer";

module.exports = class SiYuanInkPlugin extends Plugin {
  onload() {
    this.cache = {}; // clave -> { data, text, loadedAt, loading, file }
    this.edit = null; // estado del modo edición
    if (!document.getElementById(STYLE_ID)) {
      const st = document.createElement("style");
      st.id = STYLE_ID;
      st.textContent =
        ".siyuan-ink-layer{position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:6}" +
        ".siyuan-ink-hide .siyuan-ink-layer{display:none!important}" +
        ".siyuan-ink-editing .textLayer,.siyuan-ink-editing .annotationLayer{pointer-events:none!important;user-select:none!important}" +
        ".siyuan-ink-editing .page{cursor:crosshair;touch-action:none;user-select:none}" +
        ".siyuan-ink-editing.siyuan-ink-erasing .page{cursor:cell}";
      document.head.appendChild(st);
    }
    this.onDown = (e) => this.pointerDown(e);
    this.onMove = (e) => this.pointerMove(e);
    this.onUp = (e) => this.pointerUp(e);
    window.addEventListener("pointerdown", this.onDown, true);
    window.addEventListener("pointermove", this.onMove, true);
    window.addEventListener("pointerup", this.onUp, true);
    window.addEventListener("pointercancel", this.onUp, true);
    this.timer = setInterval(() => this.tick(), 1500);
  }

  onunload() {
    clearInterval(this.timer);
    window.removeEventListener("pointerdown", this.onDown, true);
    window.removeEventListener("pointermove", this.onMove, true);
    window.removeEventListener("pointerup", this.onUp, true);
    window.removeEventListener("pointercancel", this.onUp, true);
    if (this.edit) this.stopEdit(false);
    if (!window.__siyuanInk) {
      document.querySelectorAll("svg.siyuan-ink-layer").forEach((e) => e.remove());
    }
    document.querySelectorAll(".siyuan-ink-badge").forEach((e) => e.remove());
  }

  tick() {
    // Dentro de la app de la Boox ya se encarga su propio script.
    if (window.__siyuanInk) return;
    document.querySelectorAll(".pdfViewer").forEach((viewer) => {
      if (viewer.offsetParent === null) return;
      const { key, legacy } = keysOf(viewer);
      const entry = this.cache[key];
      const editingThis = this.edit && this.edit.viewer === viewer;
      // Mientras se edita no se recarga del servidor (se perderían los cambios).
      if (!editingThis && (!entry || (!entry.loading && Date.now() - entry.loadedAt > RELOAD_MS))) this.load(key, legacy);
      if (entry && entry.data) this.render(viewer, entry, editingThis);
      this.bar(viewer, key, entry);
    });
  }

  load(key, legacy) {
    const entry = this.cache[key] || (this.cache[key] = { data: null, text: "", loadedAt: 0, ver: 0, files: 0, file: null });
    entry.loading = true;
    return listInkFiles()
      .then((files) => {
        entry.files = files.length;
        const file = resolveFile(key, legacy, files);
        entry.file = file || key;
        return file ? fetchInk(file) : null;
      })
      .then((res) => {
        const t = res ? res.t : "";
        if (t !== entry.text || !entry.data) {
          entry.text = t;
          entry.data = res ? res.d : { pages: {} };
          entry.ver++;
        }
      })
      .catch(() => {
        if (!entry.data) entry.data = { pages: {} };
      })
      .finally(() => {
        entry.loading = false;
        entry.loadedAt = Date.now();
      });
  }

  // ---- Barra en la esquina del PDF -----------------------------------------------------------

  bar(viewer, key, entry) {
    const host = viewer.closest(".pdf__outer") || (viewer.parentElement && viewer.parentElement.parentElement);
    if (!host) return;
    let b = host.querySelector(":scope > .siyuan-ink-badge");
    if (!b) {
      b = document.createElement("div");
      b.className = "siyuan-ink-badge";
      b.style.cssText = BAR_CSS;
      b.addEventListener("pointerdown", (e) => e.stopPropagation());
      b.addEventListener("click", (e) => {
        const act = e.target && e.target.getAttribute && e.target.getAttribute("data-act");
        if (act) this.action(act, viewer, key);
      });
      if (getComputedStyle(host).position === "static") host.style.position = "relative";
      host.appendChild(b);
    }
    const ed = this.edit && this.edit.viewer === viewer ? this.edit : null;
    let html;
    const btn = (act, label, on) =>
      '<button data-act="' + act + '" style="' + BTN_CSS + (on ? ";font-weight:bold;border-color:currentColor" : "") + '">' + label + "</button>";
    if (ed) {
      html =
        btn("pen", "Pluma", ed.tool === "pen") +
        btn("eraser", "Goma", ed.tool === "eraser") +
        btn("width", "Grosor: " + WIDTH_NAMES[ed.widthIdx]) +
        btn("undo", "↶ Deshacer") +
        btn("cancel", "Cancelar") +
        btn("save", ed.saving ? "Guardando…" : "✓ Guardar", true);
    } else {
      let info = "cargando…";
      if (entry && entry.data) {
        const n = Object.keys(entry.data.pages || {}).filter((k) => (entry.data.pages[k] || []).length).length;
        info = n ? n + (n === 1 ? " página anotada" : " páginas anotadas") : "sin anotaciones";
      }
      html = '<span style="opacity:.75">✎ SiYuan Ink: ' + info + "</span>" + btn("edit", "Editar tinta");
    }
    if (b.__html !== html) {
      b.__html = html;
      b.innerHTML = html;
    }
  }

  action(act, viewer, key) {
    const ed = this.edit;
    if (act === "edit") return this.startEdit(viewer, key);
    if (!ed) return;
    if (act === "pen" || act === "eraser") {
      ed.tool = act;
      viewer.classList.toggle("siyuan-ink-erasing", act === "eraser");
    } else if (act === "width") {
      ed.widthIdx = (ed.widthIdx + 1) % WIDTHS.length;
    } else if (act === "undo") {
      const prev = ed.undo.pop();
      if (prev) {
        ed.entry.data.pages = prev;
        this.bump(ed);
      }
    } else if (act === "cancel") {
      this.stopEdit(false);
    } else if (act === "save") {
      this.save();
    }
    this.bar(viewer, key, this.cache[key]);
  }

  // ---- Edición ---------------------------------------------------------------------------------

  startEdit(viewer, key) {
    if (this.edit) this.stopEdit(false);
    const entry = this.cache[key];
    if (!entry || !entry.data || entry.loading) return;
    this.edit = {
      viewer, key, entry,
      original: JSON.stringify(entry.data),
      tool: "pen", widthIdx: 1, undo: [], stroke: null, saving: false,
    };
    viewer.classList.add("siyuan-ink-editing");
    window.getSelection() && window.getSelection().removeAllRanges();
    this.bar(viewer, key, entry);
  }

  stopEdit(keep) {
    const ed = this.edit;
    if (!ed) return;
    this.edit = null;
    ed.viewer.classList.remove("siyuan-ink-editing", "siyuan-ink-erasing");
    if (!keep) {
      ed.entry.data = JSON.parse(ed.original);
      ed.entry.ver++;
    }
    this.render(ed.viewer, ed.entry, false);
    this.bar(ed.viewer, ed.key, ed.entry);
  }

  bump(ed) {
    ed.entry.ver++;
    this.render(ed.viewer, ed.entry, true);
  }

  save() {
    const ed = this.edit;
    if (!ed || ed.saving) return;
    ed.saving = true;
    this.bar(ed.viewer, ed.key, ed.entry);
    const d = ed.entry.data;
    Object.keys(d.pages).forEach((n) => { if (!d.pages[n] || !d.pages[n].length) delete d.pages[n]; });
    d.updated = Date.now();
    const text = JSON.stringify(d);
    const file = ed.entry.file || ed.key;
    const fd = new FormData();
    fd.append("path", "/data/storage/siyuan-ink/pdf/" + file + ".json");
    fd.append("isDir", "false");
    fd.append("modTime", String(Date.now()));
    fd.append("file", new Blob([text], { type: "application/json" }), file + ".json");
    fetch("/api/file/putFile", { method: "POST", body: fd })
      .then((r) => r.json())
      .then((j) => {
        if (j.code !== 0) throw new Error(j.msg || "error");
        ed.entry.text = text;
        ed.entry.loadedAt = Date.now();
        dirCache = null;
        this.stopEdit(true);
      })
      .catch((e) => {
        ed.saving = false;
        this.bar(ed.viewer, ed.key, ed.entry);
        alert("SiYuan Ink: no se pudo guardar la tinta: " + (e.message || e));
      });
  }

  pageAt(e) {
    const ed = this.edit;
    if (!ed || !e.target || !e.target.closest) return null;
    if (e.target.closest(".siyuan-ink-badge")) return null;
    const pg = e.target.closest(".page[data-page-number]");
    if (!pg || !ed.viewer.contains(pg)) return null;
    return pg;
  }

  toPage(pg, e) {
    const r = pg.getBoundingClientRect();
    const left = r.left + pg.clientLeft;
    const top = r.top + pg.clientTop;
    const k = 1000 / pg.clientWidth;
    return [
      Math.round((e.clientX - left) * k * 10) / 10,
      Math.round((e.clientY - top) * k * 10) / 10,
      // El ratón da 0.5 al pulsar; un lápiz da su presión real.
      Math.round((e.pressure > 0 ? e.pressure : 0.5) * 100) / 100,
    ];
  }

  pointerDown(e) {
    const pg = this.pageAt(e);
    if (!pg) return;
    if (e.pointerType === "mouse" && e.button !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    const ed = this.edit;
    const n = pg.getAttribute("data-page-number");
    ed.undo.push(JSON.parse(JSON.stringify(ed.entry.data.pages)));
    if (ed.undo.length > 50) ed.undo.shift();
    // El botón trasero / goma de una tableta (buttons & 32) también borra.
    const erasing = ed.tool === "eraser" || (e.buttons & 32) !== 0;
    ed.stroke = { pg, n, erasing, pointerId: e.pointerId, s: erasing ? null : { w: WIDTHS[ed.widthIdx], p: [this.toPage(pg, e)] } };
    try { pg.setPointerCapture(e.pointerId); } catch (err) { /* nada */ }
    if (erasing) this.eraseAt(pg, n, e);
    else this.drawLive();
  }

  pointerMove(e) {
    const ed = this.edit;
    if (!ed || !ed.stroke || e.pointerId !== ed.stroke.pointerId) return;
    e.preventDefault();
    e.stopPropagation();
    const st = ed.stroke;
    const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
    if (st.erasing) {
      this.eraseAt(st.pg, st.n, e);
      return;
    }
    for (const ev of evs.length ? evs : [e]) {
      const p = this.toPage(st.pg, ev);
      const last = st.s.p[st.s.p.length - 1];
      if (Math.abs(p[0] - last[0]) + Math.abs(p[1] - last[1]) >= 0.6) st.s.p.push(p);
    }
    this.drawLive();
  }

  pointerUp(e) {
    const ed = this.edit;
    if (!ed || !ed.stroke || e.pointerId !== ed.stroke.pointerId) return;
    e.preventDefault();
    e.stopPropagation();
    const st = ed.stroke;
    ed.stroke = null;
    if (!st.erasing && st.s.p.length) {
      const pages = ed.entry.data.pages;
      (pages[st.n] || (pages[st.n] = [])).push(st.s);
    }
    this.bump(ed);
  }

  eraseAt(pg, n, e) {
    const ed = this.edit;
    const list = ed.entry.data.pages[n];
    if (!list || !list.length) return;
    const p = this.toPage(pg, e);
    const keep = list.filter((s) => !strokeNear(s, p[0], p[1], ERASER_RADIUS));
    if (keep.length !== list.length) {
      ed.entry.data.pages[n] = keep;
      this.bump(ed);
    }
  }

  drawLive() {
    const st = this.edit && this.edit.stroke;
    if (!st || st.erasing) return;
    const layer = this.ensureLayer(st.pg);
    let live = layer.querySelector("path.siyuan-ink-live");
    if (!live) {
      live = document.createElementNS(SVGNS, "path");
      live.setAttribute("class", "siyuan-ink-live");
      live.setAttribute("fill", "#000");
      layer.appendChild(live);
    }
    live.setAttribute("d", strokePath(st.s.p, st.s.w));
  }

  ensureLayer(pg) {
    let layer = pg.querySelector(":scope > svg.siyuan-ink-layer");
    if (!layer) {
      layer = document.createElementNS(SVGNS, "svg");
      layer.setAttribute("class", "siyuan-ink-layer");
      pg.appendChild(layer);
    }
    const h = ((pg.clientHeight / Math.max(1, pg.clientWidth)) * 1000).toFixed(1);
    layer.setAttribute("viewBox", "0 0 1000 " + h);
    layer.setAttribute("preserveAspectRatio", "none");
    return layer;
  }

  render(viewer, entry, keepEmpty) {
    const ver = "p" + entry.ver;
    viewer.querySelectorAll(".page[data-page-number]").forEach((pg) => {
      const strokes = entry.data.pages[pg.getAttribute("data-page-number")];
      let layer = pg.querySelector(":scope > svg.siyuan-ink-layer");
      if ((!strokes || !strokes.length) && !keepEmpty) {
        if (layer) layer.remove();
        return;
      }
      if (layer && layer.getAttribute("data-ver") === ver) return;
      if (pg.clientWidth < 1) return;
      let html = '<g fill="#000" stroke="none">';
      for (const s of strokes || []) html += '<path d="' + strokePath(s.p, s.w) + '"/>';
      html += "</g>";
      layer = this.ensureLayer(pg);
      layer.setAttribute("data-ver", ver);
      layer.innerHTML = html;
    });
  }
};
