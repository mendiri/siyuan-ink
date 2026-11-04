/*
 * SiYuan Ink: puente entre la interfaz web de SiYuan y el lienzo nativo de Boox.
 * Se inyecta en cada carga de página. Usa la API del núcleo de SiYuan con la sesión
 * de la propia página (no hace falta token).
 */
(function () {
  if (window.__siyuanInk) return;
  var Native = window.SiYuanInkNative;
  var S = (window.__siyuanInk = { lastBlockId: null, lastRange: null });

  // --- Ajustes para e-ink: sin animaciones ni transiciones -------------------------------
  try {
    var st = document.createElement('style');
    st.textContent =
      '*,*::before,*::after{transition:none!important;animation:none!important;scroll-behavior:auto!important}' +
      '.protyle-wysiwyg [custom-ink] img{cursor:pointer}' +
      '.siyuan-ink-layer{position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:6}';
    (document.head || document.documentElement).appendChild(st);
  } catch (e) {}

  // --- Seguimiento del bloque donde está el cursor --------------------------------------
  function blockOf(node) {
    while (node && node.nodeType !== 1) node = node.parentNode;
    if (!node || !node.closest) return null;
    var b = node.closest('[data-node-id]');
    return b && b.closest('.protyle-wysiwyg') ? b : null;
  }

  document.addEventListener('selectionchange', function () {
    var s = window.getSelection();
    if (!s || !s.anchorNode) return;
    var b = blockOf(s.anchorNode);
    if (!b) return;
    S.lastBlockId = b.getAttribute('data-node-id');
    S.lastRange = s.rangeCount ? s.getRangeAt(0).cloneRange() : null;
  });

  // Tocar una tinta abre el editor nativo con sus trazos.
  window.addEventListener(
    'click',
    function (e) {
      var t = e.target;
      if (!t || !t.closest) return;
      var b = blockOf(t);
      if (b) S.lastBlockId = b.getAttribute('data-node-id');
      var img = t.closest('img');
      if (!img || !b) return;
      var inkBlock = img.closest('[custom-ink]');
      if (!inkBlock) return;
      e.preventDefault();
      e.stopPropagation();
      editInk(inkBlock);
    },
    true
  );

  // --- API de SiYuan ---------------------------------------------------------------------
  function api(path, body) {
    return fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    })
      .then(function (r) {
        if (r.status === 401) throw new Error('sesión no autorizada (vuelve a introducir el código de acceso)');
        return r.json();
      })
      .then(function (j) {
        if (j.code !== 0) throw new Error(j.msg || 'error ' + j.code);
        return j.data;
      });
  }

  function upload(content, type, name) {
    var fd = new FormData();
    fd.append('assetsDirPath', '/assets/');
    fd.append('file[]', new Blob([content], { type: type }), name);
    return fetch('/api/asset/upload', { method: 'POST', credentials: 'same-origin', body: fd })
      .then(function (r) {
        return r.json();
      })
      .then(function (j) {
        if (j.code !== 0) throw new Error(j.msg || 'error al subir');
        var m = j.data && j.data.succMap;
        var path = m && (m[name] || m[Object.keys(m)[0]]);
        if (!path) throw new Error('SiYuan no devolvió la ruta del archivo');
        return path;
      });
  }

  function currentDocId() {
    var list = document.querySelectorAll('.protyle:not(.fn__none)');
    for (var i = 0; i < list.length; i++) {
      var p = list[i];
      if (p.offsetParent === null) continue;
      var el =
        p.querySelector('.protyle-title[data-node-id]') ||
        p.querySelector('.protyle-background[data-node-id]') ||
        p.querySelector('.protyle-wysiwyg[data-doc-id]');
      if (el) return el.getAttribute('data-node-id') || el.getAttribute('data-doc-id');
    }
    return null;
  }

  function blockExists(id) {
    return !!(id && document.querySelector('.protyle-wysiwyg [data-node-id="' + id + '"]'));
  }

  function escapeMd(t) {
    return String(t).replace(/([\\`*_\[\]#<>|~])/g, '\\$1');
  }

  function newIdFrom(res) {
    try {
      return res[0].doOperations[0].id;
    } catch (e) {
      return null;
    }
  }

  // Inserta markdown tras el bloque actual o, si no hay, al final del documento abierto.
  function insertMarkdown(md) {
    var after = S.lastBlockId;
    var p;
    if (blockExists(after)) {
      p = api('/api/block/insertBlock', { dataType: 'markdown', data: md, previousID: after });
    } else {
      var doc = currentDocId();
      if (!doc) return Promise.reject(new Error('pon el cursor en un documento de SiYuan donde quieras insertarlo'));
      p = api('/api/block/appendBlock', { dataType: 'markdown', data: md, parentID: doc });
    }
    return p.then(function (res) {
      var id = newIdFrom(res);
      if (id) S.lastBlockId = id;
      return id;
    });
  }

  function editInk(block) {
    var path = block.getAttribute('custom-ink');
    var id = block.getAttribute('data-node-id');
    fetch('/' + path.replace(/^\/+/, ''), { credentials: 'same-origin' })
      .then(function (r) {
        if (!r.ok) throw new Error('no se encontró ' + path);
        return r.text();
      })
      .then(function (json) {
        Native.openEditor(id, json);
      })
      .catch(function (e) {
        Native.notify(false, 'no se pudo abrir la tinta: ' + e.message);
      });
  }

  // --- Anotaciones sobre PDF --------------------------------------------------------------
  // Se guardan en /data/storage/siyuan-ink/pdf/<clave>.json (se sincroniza con SiYuan).
  // Coordenadas normalizadas por página: el ancho de la página vale 1000.
  var pdfCache = {};
  var SVGNS = 'http://www.w3.org/2000/svg';

  function visiblePdfViewer() {
    var list = document.querySelectorAll('.pdfViewer');
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (v.offsetParent === null) continue;
      var r = v.getBoundingClientRect();
      if (r.width > 10 && r.bottom > 0 && r.top < window.innerHeight) return v;
    }
    return null;
  }

  function pdfKeyOf(viewer) {
    if (viewer.__inkKey) return viewer.__inkKey;
    var name = '';
    var el = viewer;
    while (el && el.parentElement && !name) {
      el = el.parentElement.closest('[data-id]');
      if (!el) break;
      var li = document.querySelector('li[data-type="tab-header"][data-id="' + el.getAttribute('data-id') + '"]');
      if (li) {
        var txt = li.querySelector('.item__text');
        name = li.getAttribute('aria-label') || (txt ? txt.textContent : li.textContent) || '';
      }
    }
    if (!name) name = document.title || 'pdf';
    var key = name.trim().replace(/[^\w.\-]+/g, '_').slice(0, 120) || 'pdf';
    viewer.__inkKey = key;
    return key;
  }

  function pdfStorePath(key) {
    return '/data/storage/siyuan-ink/pdf/' + key + '.json';
  }

  function loadPdfInk(key) {
    if (pdfCache[key]) return Promise.resolve(pdfCache[key]);
    return fetch('/api/file/getFile', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: pdfStorePath(key) })
    })
      .then(function (r) {
        return r.text();
      })
      .then(function (t) {
        var d = null;
        try {
          d = JSON.parse(t);
        } catch (e) {}
        if (!d || !d.pages) d = { v: 1, pages: {} };
        pdfCache[key] = d;
        return d;
      });
  }

  function pageBox(pg) {
    var r = pg.getBoundingClientRect();
    return { left: r.left + pg.clientLeft, top: r.top + pg.clientTop, width: pg.clientWidth, height: pg.clientHeight };
  }

  function strokePath(pts) {
    if (!pts.length) return '';
    var d = 'M' + pts[0][0] + ' ' + pts[0][1];
    if (pts.length === 1) return d + ' l0.1 0';
    for (var i = 1; i < pts.length; i++) {
      var a = pts[i - 1], b = pts[i];
      d += ' Q' + a[0] + ' ' + a[1] + ' ' + ((a[0] + b[0]) / 2).toFixed(1) + ' ' + ((a[1] + b[1]) / 2).toFixed(1);
    }
    var l = pts[pts.length - 1];
    return d + ' L' + l[0] + ' ' + l[1];
  }

  function renderPdfViewer(viewer, force) {
    var key = pdfKeyOf(viewer);
    var data = pdfCache[key];
    if (!data) {
      loadPdfInk(key).then(function () {
        renderPdfViewer(viewer, true);
      });
      return;
    }
    var ver = String(data.updated || 0);
    var pages = viewer.querySelectorAll('.page[data-page-number]');
    for (var i = 0; i < pages.length; i++) {
      var pg = pages[i];
      var strokes = data.pages[pg.getAttribute('data-page-number')];
      var layer = pg.querySelector(':scope > svg.siyuan-ink-layer');
      if (!strokes || !strokes.length) {
        if (layer) layer.remove();
        continue;
      }
      if (layer && layer.getAttribute('data-ver') === ver && !force) continue;
      if (pg.clientWidth < 1) continue;
      var h = ((pg.clientHeight / pg.clientWidth) * 1000).toFixed(1);
      var html = '<g fill="none" stroke="#000" stroke-linecap="round" stroke-linejoin="round">';
      for (var j = 0; j < strokes.length; j++) {
        html += '<path stroke-width="' + strokes[j].w + '" d="' + strokePath(strokes[j].p) + '"/>';
      }
      html += '</g>';
      if (!layer) {
        layer = document.createElementNS(SVGNS, 'svg');
        layer.setAttribute('class', 'siyuan-ink-layer');
        pg.appendChild(layer);
      }
      layer.setAttribute('viewBox', '0 0 1000 ' + h);
      layer.setAttribute('preserveAspectRatio', 'none');
      layer.setAttribute('data-ver', ver);
      layer.innerHTML = html;
    }
  }

  // El visor de PDF regenera las páginas al hacer zoom o desplazarse: repintamos las anotaciones.
  setInterval(function () {
    var list = document.querySelectorAll('.pdfViewer');
    for (var i = 0; i < list.length; i++) {
      if (list[i].offsetParent !== null) renderPdfViewer(list[i], false);
    }
  }, 1500);

  S.pdfInk = function (viewer) {
    var key = pdfKeyOf(viewer);
    loadPdfInk(key)
      .then(function (d) {
        var pages = [];
        var list = viewer.querySelectorAll('.page[data-page-number]');
        for (var i = 0; i < list.length; i++) {
          var b = pageBox(list[i]);
          if (b.width < 1 || b.top + b.height < 0 || b.top > window.innerHeight) continue;
          var n = list[i].getAttribute('data-page-number');
          pages.push({ n: n, left: b.left, top: b.top, width: b.width, height: b.height, strokes: d.pages[n] || [] });
        }
        if (!pages.length) throw new Error('no hay páginas del PDF a la vista');
        Native.openPdfInk(JSON.stringify({ key: key, pages: pages }));
      })
      .catch(function (e) {
        Native.notify(false, e.message || String(e));
      });
  };

  S.savePdf = function (p) {
    loadPdfInk(p.key)
      .then(function (d) {
        Object.keys(p.pages).forEach(function (n) {
          if (p.pages[n].length) d.pages[n] = p.pages[n];
          else delete d.pages[n];
        });
        d.updated = Date.now();
        var fd = new FormData();
        fd.append('path', pdfStorePath(p.key));
        fd.append('isDir', 'false');
        fd.append('modTime', String(Date.now()));
        fd.append('file', new Blob([JSON.stringify(d)], { type: 'application/json' }), p.key + '.json');
        return fetch('/api/file/putFile', { method: 'POST', credentials: 'same-origin', body: fd })
          .then(function (r) {
            return r.json();
          })
          .then(function (j) {
            if (j.code !== 0) throw new Error(j.msg || 'error al guardar');
          });
      })
      .then(function () {
        var v = visiblePdfViewer();
        if (v) renderPdfViewer(v, true);
        Native.notify(true, 'Anotación guardada');
      })
      .catch(function (e) {
        Native.notify(false, e.message || String(e));
      });
  };

  // --- Llamadas desde Android ------------------------------------------------------------

  // Botón "Tinta": con un PDF a la vista, anota encima de sus páginas.
  // Si el cursor está en una tinta, la edita; si no, crea una nueva.
  S.requestInk = function () {
    var pdf = visiblePdfViewer();
    if (pdf) {
      S.pdfInk(pdf);
      return;
    }
    var id = S.lastBlockId;
    var el = id && document.querySelector('.protyle-wysiwyg [data-node-id="' + id + '"]');
    if (el && el.getAttribute('custom-ink')) {
      editInk(el);
    } else {
      Native.openEditor(blockExists(id) ? id : '', '');
    }
  };

  // Guarda un dibujo: sube SVG + JSON y crea o actualiza el bloque.
  S.save = function (p) {
    var stamp = Date.now();
    var svgPath, jsonPath;
    upload(p.svg, 'image/svg+xml', 'ink-' + stamp + '.svg')
      .then(function (path) {
        svgPath = path;
        return upload(p.json, 'application/json', 'ink-' + stamp + '.json');
      })
      .then(function (path) {
        jsonPath = path;
        var alt = (p.text || '').replace(/\s+/g, ' ').trim() || 'tinta';
        var md = '![' + escapeMd(alt) + '](' + svgPath + ')';
        if (p.editing && blockExists(p.blockId)) {
          return api('/api/block/updateBlock', { id: p.blockId, dataType: 'markdown', data: md }).then(function () {
            return p.blockId;
          });
        }
        if (p.blockId) S.lastBlockId = p.blockId;
        return insertMarkdown(md);
      })
      .then(function (id) {
        if (!id) throw new Error('no se pudo crear el bloque');
        return api('/api/attr/setBlockAttrs', {
          id: id,
          attrs: { 'custom-ink': jsonPath, 'custom-ink-text': p.text || '' }
        });
      })
      .then(function () {
        Native.notify(true, 'Tinta guardada');
      })
      .catch(function (e) {
        Native.notify(false, e.message || String(e));
      });
  };

  // Inserta texto reconocido donde estaba el cursor.
  S.insertText = function (text) {
    try {
      if (text.indexOf('\n') === -1) {
        var id = S.lastBlockId;
        var el = id && document.querySelector('.protyle-wysiwyg [data-node-id="' + id + '"]');
        var editable = el && (el.matches('[contenteditable="true"]') ? el : el.querySelector('[contenteditable="true"]'));
        if (editable) {
          editable.focus();
          var s = window.getSelection();
          var r = S.lastRange;
          if (!r || !editable.contains(r.startContainer)) {
            r = document.createRange();
            r.selectNodeContents(editable);
            r.collapse(false);
          }
          s.removeAllRanges();
          s.addRange(r);
          if (document.execCommand('insertText', false, text)) {
            Native.notify(true, 'Texto insertado');
            return;
          }
        }
      }
    } catch (e) {}
    // Varias líneas (o no hay cursor): cada renglón como un párrafo nuevo.
    var md = text
      .split('\n')
      .map(function (l) {
        return escapeMd(l.trim());
      })
      .filter(Boolean)
      .join('\n\n');
    insertMarkdown(md)
      .then(function () {
        Native.notify(true, 'Texto insertado');
      })
      .catch(function (e) {
        Native.notify(false, e.message || String(e));
      });
  };
})();
