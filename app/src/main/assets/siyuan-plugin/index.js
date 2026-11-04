/*
 * Plugin de SiYuan: dibuja sobre los PDF las anotaciones de la app SiYuan Ink.
 * Mismo formato y mismo cálculo que app/src/main/assets/siyuan-ink.js.
 */
const { Plugin } = require("siyuan");

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

// Misma clave que usa la app: el título de la pestaña del PDF.
function pdfKeyOf(viewer) {
  if (viewer.__inkKey) return viewer.__inkKey;
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
  const key = name.trim().replace(/[^\w.\-]+/g, "_").slice(0, 120) || "pdf";
  viewer.__inkKey = key;
  return key;
}

module.exports = class SiYuanInkPlugin extends Plugin {
  onload() {
    this.cache = {}; // clave -> { data, text, loadedAt, loading }
    if (!document.getElementById(STYLE_ID)) {
      const st = document.createElement("style");
      st.id = STYLE_ID;
      st.textContent =
        ".siyuan-ink-layer{position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:6}" +
        ".siyuan-ink-hide .siyuan-ink-layer{display:none!important}";
      document.head.appendChild(st);
    }
    this.timer = setInterval(() => this.tick(), 1500);
  }

  onunload() {
    clearInterval(this.timer);
    if (!window.__siyuanInk) {
      document.querySelectorAll("svg.siyuan-ink-layer").forEach((e) => e.remove());
    }
  }

  tick() {
    // Dentro de la app de la Boox ya se encarga su propio script.
    if (window.__siyuanInk) return;
    document.querySelectorAll(".pdfViewer").forEach((viewer) => {
      if (viewer.offsetParent === null) return;
      const key = pdfKeyOf(viewer);
      const entry = this.cache[key];
      if (!entry || (!entry.loading && Date.now() - entry.loadedAt > RELOAD_MS)) this.load(key);
      if (entry && entry.data) this.render(viewer, entry);
    });
  }

  load(key) {
    const entry = this.cache[key] || (this.cache[key] = { data: null, text: "", loadedAt: 0, ver: 0 });
    entry.loading = true;
    fetch("/api/file/getFile", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path: "/data/storage/siyuan-ink/pdf/" + key + ".json" }),
    })
      .then((r) => r.text())
      .then((t) => {
        let d = null;
        try { d = JSON.parse(t); } catch (e) { /* sin anotaciones */ }
        if (!d || !d.pages) d = { pages: {} };
        if (t !== entry.text) {
          entry.text = t;
          entry.data = d;
          entry.ver++;
        }
      })
      .catch(() => {})
      .finally(() => {
        entry.loading = false;
        entry.loadedAt = Date.now();
      });
  }

  render(viewer, entry) {
    const ver = "p" + entry.ver;
    viewer.querySelectorAll(".page[data-page-number]").forEach((pg) => {
      const strokes = entry.data.pages[pg.getAttribute("data-page-number")];
      let layer = pg.querySelector(":scope > svg.siyuan-ink-layer");
      if (!strokes || !strokes.length) {
        if (layer) layer.remove();
        return;
      }
      if (layer && layer.getAttribute("data-ver") === ver) return;
      if (pg.clientWidth < 1) return;
      const h = ((pg.clientHeight / pg.clientWidth) * 1000).toFixed(1);
      let html = '<g fill="#000" stroke="none">';
      for (const s of strokes) html += '<path d="' + strokePath(s.p, s.w) + '"/>';
      html += "</g>";
      if (!layer) {
        layer = document.createElementNS(SVGNS, "svg");
        layer.setAttribute("class", "siyuan-ink-layer");
        pg.appendChild(layer);
      }
      layer.setAttribute("viewBox", "0 0 1000 " + h);
      layer.setAttribute("preserveAspectRatio", "none");
      layer.setAttribute("data-ver", ver);
      layer.innerHTML = html;
    });
  }
};
