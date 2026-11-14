(function () {
  'use strict';
  var INDEX = JSON.parse(document.getElementById('index-data').textContent);
  var TRACE = JSON.parse(document.getElementById('trace-data').textContent);
  var DIR = { n: 'north', s: 'south', e: 'east', w: 'west', u: 'up', d: 'down' };
  var RES = { a: 'accepted', r: 'refused', b: 'blocked' };

  // ---------- helpers ----------
  function css(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }
  function hexRgb(h) {
    h = h.replace('#', '');
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    var n = parseInt(h, 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function mix(a, b, t) { return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]; }
  function scale(c, f) { return [Math.min(255, c[0] * f), Math.min(255, c[1] * f), Math.min(255, c[2] * f)]; }
  function rgb(c) { return 'rgb(' + Math.round(c[0]) + ',' + Math.round(c[1]) + ',' + Math.round(c[2]) + ')'; }
  function fmt(v) { return (Math.round(v * 100) / 100).toFixed(2); }
  function esc(t) { return String(t).replace(/[&<>"']/g, function (ch) { return '&#' + ch.charCodeAt(0) + ';'; }); }
  function dist3(a, b) { var dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2]; return Math.sqrt(dx * dx + dy * dy + dz * dz); }
  function key(p) { return p[0] + ',' + p[1] + ',' + p[2]; }
  function xyz(p) { return p ? '(' + p[0] + ', ' + p[1] + ', ' + p[2] + ')' : '(none)'; }
  function $(id) { return document.getElementById(id); }

  // ---------- the world being shown ----------
  var W = null;
  var st = { src: 0, s: 0, k: 0, t: 0, r: 0, heat: false, sel: null, color: 'order', path: true, cut: false, runOverlay: true, playS: null, playT: null };

  // ---------- group runs: one recording, many recruits ----------
  function group() { return W && W.run && W.run.recruits ? W.run.recruits : null; }
  /** The ticks of the recruit being followed: the only one in a single run, the chosen one in a group run. */
  function focusTicks() { var g = group(); return g ? g[st.r].ticks : W.run.ticks; }
  function lastTick() {
    var g = group(); if (!g) return W.run.ticks.length - 1;
    return g.reduce(function (m, r) { return Math.max(m, r.ticks.length - 1); }, 0);
  }
  function tickAt(ticks, t) { return ticks.length ? ticks[Math.min(t, ticks.length - 1)] : null; }
  function outcomeColor(o) { return o === 'reached' ? css('--good') : o === 'dead' ? css('--bad') : o === 'pit' || o === 'edge' ? css('--drop-line') : css('--muted'); }

  function setWorld(data, kind) {
    var size = data.size;
    W = { kind: kind, SX: size[0], SY: size[1], SZ: size[2], B: data.blocks, margin: data.margin,
      maxFall: kind === 'run' ? data.fallDistance : data.maxFall, range: data.range, budget: data.budget, run: kind === 'run' ? data : null };
    W.stand = new Int16Array(W.SX * W.SZ); W.wet = new Uint8Array(W.SX * W.SZ);
    W.minStand = 99; W.maxStand = 0;
    for (var x = 0; x < W.SX; x++) for (var z = 0; z < W.SZ; z++) {
      var s = 0, w = 0;
      for (var y = W.SY - 1; y >= 0; y--) {
        var c = blockAt(x, y, z);
        if (c === 'w') w = 1;
        if (c === 'l') w = 2;
        if (c === '#') { s = y + 1; break; }
      }
      W.stand[z * W.SX + x] = s; W.wet[z * W.SX + x] = w;
      if (!isWall(x, z)) { W.minStand = Math.min(W.minStand, s); W.maxStand = Math.max(W.maxStand, s); }
    }
    W.searches = kind === 'run' ? normRun(data.searches) : data.searches.map(normTrace);
    W.searches.forEach(prep);
  }
  function blockAt(x, y, z) {
    if (x < 0 || x >= W.SX || y < 0 || y >= W.SY || z < 0 || z >= W.SZ) return '#';
    return W.B.charAt((y * W.SZ + z) * W.SX + x);
  }
  function isWall(x, z) { return x === 0 || z === 0 || x === W.SX - 1 || z === W.SZ - 1; }
  function standAt(x, z) { return (x < 0 || z < 0 || x >= W.SX || z >= W.SZ) ? 0 : W.stand[z * W.SX + x]; }

  function normTrace(s) {
    s.kind = 'follow'; s.detail = true; s.margin = TRACE.margin; s.maxFall = TRACE.maxFall; s.n = s.pops.length;
    s.pops.forEach(function (p) { p.expanded = p.ring !== '-'; });
    return s;
  }
  function normRun(list) {
    return list.map(function (s) {
      var src = s.same !== undefined ? list[s.same] : s;
      var o = { id: s.id, kind: s.kind, tick: s.tick, start: s.start || [0, 0, 0], target: s.target || [0, 0, 0], reached: s.reached,
        path: s.path || [], done: s.done, noStart: s.noStart, n: s.n, dirtPath: s.dirtPath, margin: s.margin, maxFall: s.maxFall,
        detail: !!src.detail, sameAs: s.same, r: s.r, expandedCount: s.x, behind: s.behind, server: s.server };
      var raw = src.pops || [];
      o.pops = raw.map(function (a) {
        if (!o.detail) return { p: [a[0], a[1], a[2]], expanded: a.length === 3 };
        var fi = a[9];
        return { p: [a[0], a[1], a[2]], g: a[3], h: a[4], f: a[5], m: a[6], ed: a[7],
          ec: a[7] > 0 && o.margin > 0 ? 8 * (o.margin - a[7] + 1) / o.margin : 0, ring: a[8], expanded: a[8] !== '-',
          from: fi >= 0 ? [raw[fi][0], raw[fi][1], raw[fi][2]] : null,
          cand: a[10].map(function (c) { return { d: DIR[c[0]] || c[0], r: RES[c[1]] || c[1], ask: [c[2], c[3], c[4]], at: [c[2], c[5], c[4]], m: c[6], de: c[7] }; }),
          nb: a[11].map(function (n) { return { p: [n[0], n[1], n[2]], m: n[3], g0: n[4], open: n[5] === 1, g: n[6], h: n[7], f: n[8], upd: n[9] === 1 }; }) };
      });
      o.name = '#' + s.id + (s.r !== undefined ? ' · recruit ' + s.r : '') + ' · tick ' + s.tick + ' · ' + s.kind + ' ' + xyz(o.start) + ' → ' + xyz(o.target) + ' · '
        + (o.reached ? 'reached' : 'not reached') + ' · ' + o.n + ' points' + (s.same !== undefined ? ' · same as #' + s.same : '')
        + (raw.length ? (o.detail ? '' : ' · positions only') : ' · counts only') + (s.server ? ' · ran on the server thread' : '');
      return o;
    });
  }
  function prep(S) {
    S.popAt = new Map(); S.byColumn = new Map(); S.addedAt = new Map(); S.maxF = 1; S.maxG = 1; S.maxM = 1;
    S.pops.forEach(function (p, i) {
      S.popAt.set(key(p.p), i);
      var ck = p.p[0] + ',' + p.p[2];
      if (!S.byColumn.has(ck)) S.byColumn.set(ck, []);
      S.byColumn.get(ck).push(i);
      if (p.f !== undefined) { S.maxF = Math.max(S.maxF, p.f); S.maxG = Math.max(S.maxG, p.g); S.maxM = Math.max(S.maxM, p.m); }
      if (p.nb) p.nb.forEach(function (n) { if (n.upd && !S.addedAt.has(key(n.p))) S.addedAt.set(key(n.p), i); });
    });
  }
  function S() { return W.searches[st.s]; }
  function P() { var s = S(); return s && s.pops.length ? s.pops[Math.min(st.k, s.pops.length - 1)] : null; }

  // ---------- sources ----------
  var srcSel = $('source');
  var sources = [{ label: 'Single searches recorded on ravine seed 1 (3 searches)', kind: 'trace' }];
  INDEX.runs.forEach(function (r) { sources.push({ label: r.test.replace('aFollowerGoesRoundARoughRavineAt', '') + ' · ' + r.outcome, kind: 'run', file: r.file }); });
  sources.forEach(function (s, i) { var o = document.createElement('option'); o.value = i; o.textContent = s.label; srcSel.appendChild(o); });
  srcSel.addEventListener('change', function () { loadSource(+srcSel.value); });
  document.querySelectorAll('[data-open-run]').forEach(function (b) {
    b.addEventListener('click', function () {
      var i = sources.findIndex(function (s) { return s.file === b.getAttribute('data-open-run'); });
      if (i >= 0) { srcSel.value = i; loadSource(i); $('timeline').scrollIntoView({ behavior: 'smooth' }); }
    });
  });
  function loadSource(i) {
    st.src = i; stopPlay();
    var src = sources[i];
    $('loading').textContent = '';
    if (src.kind === 'trace') { setWorld(TRACE, 'trace'); afterLoad(); return; }
    $('loading').textContent = 'Loading ' + src.file + ' ...';
    fetch(src.file).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (data) {
      setWorld(data, 'run'); $('loading').textContent = ''; afterLoad();
    }).catch(function (e) { $('loading').textContent = 'Could not load ' + src.file + ': ' + e.message; });
  }
  function afterLoad() {
    var sel = $('search'); sel.innerHTML = '';
    W.searches.forEach(function (s, i) { var o = document.createElement('option'); o.value = i; o.textContent = s.name; sel.appendChild(o); });
    st.s = 0; st.k = 0; st.r = 0; st.sel = null;
    st.t = W.run ? lastTick() : 0;
    if (W.run) {
      var firstDetail = W.searches.findIndex(function (s) { return s.detail && s.kind === 'detour'; });
      if (firstDetail < 0) firstDetail = W.searches.findIndex(function (s) { return s.pops.length > 0; });
      st.s = firstDetail >= 0 ? firstDetail : 0;
      sel.value = st.s;
    }
    var focus = $('focus'), g = group();
    focus.innerHTML = '';
    if (g) g.forEach(function (r, i) {
      var o = document.createElement('option'); o.value = i;
      o.textContent = 'recruit ' + i + ' · ' + r.outcome + (r.reachedTick >= 0 ? ' at tick ' + r.reachedTick : '') + (r.death ? ' · ' + r.death.cause : '');
      focus.appendChild(o);
    });
    $('focuswrap').hidden = !g; $('heatwrap').hidden = !(W.run && W.run.heat);
    $('runctl').hidden = !W.run; $('runpanel').hidden = !W.run;
    syncSliders(); relayoutAll(true);
  }

  // ---------- controls ----------
  $('search').addEventListener('change', function (e) { st.s = +e.target.value; st.k = 0; syncSliders(); render(); rebuild3dSearch(); });
  $('step').addEventListener('input', function (e) { st.k = +e.target.value; render(); });
  $('prev').addEventListener('click', function () { st.k = Math.max(0, st.k - 1); syncSliders(); render(); });
  $('next').addEventListener('click', function () { st.k = Math.min(S().pops.length - 1, st.k + 1); syncSliders(); render(); });
  $('end').addEventListener('click', function () { st.k = S().pops.length - 1; syncSliders(); render(); });
  $('play').addEventListener('click', function () { togglePlay('S'); });
  $('colorby').addEventListener('change', function (e) { st.color = e.target.value; render(); });
  $('showpath').addEventListener('change', function (e) { st.path = e.target.checked; render(); });
  $('tick').addEventListener('input', function (e) { st.t = +e.target.value; render(); });
  $('runplay').addEventListener('click', function () { togglePlay('T'); });
  $('runoverlay').addEventListener('change', function (e) { st.runOverlay = e.target.checked; render(); });
  $('focus').addEventListener('change', function (e) { st.r = +e.target.value; render(); });
  $('heat').addEventListener('change', function (e) { st.heat = e.target.checked; render(); });
  $('runpanel').addEventListener('click', function (e) {
    var b = e.target.closest('[data-focus]'); if (!b) return;
    st.r = +b.getAttribute('data-focus'); $('focus').value = st.r; render();
  });
  $('inspect').addEventListener('click', function () {
    var here = tickAt(focusTicks(), st.t); if (!here) return;
    var id = here[3];
    if (id >= 0) { st.s = id; st.k = W.searches[id].pops.length - 1; $('search').value = id; syncSliders(); render(); rebuild3dSearch(); }
  });
  $('cut').addEventListener('change', function (e) { st.cut = e.target.checked; update3dCut(); });
  function syncSliders() {
    var s = S(); $('step').max = Math.max(0, s.pops.length - 1); $('step').value = st.k;
    if (W.run) { $('tick').max = lastTick(); $('tick').value = st.t; }
  }
  function stopPlay() {
    ['S', 'T'].forEach(function (w) { if (st['play' + w]) { clearInterval(st['play' + w]); st['play' + w] = null; } });
    $('play').textContent = 'Play'; $('runplay').textContent = 'Play';
  }
  function togglePlay(which) {
    var btn = which === 'S' ? $('play') : $('runplay');
    if (st['play' + which]) { clearInterval(st['play' + which]); st['play' + which] = null; btn.textContent = 'Play'; return; }
    btn.textContent = 'Pause';
    st['play' + which] = setInterval(function () {
      if (which === 'S') {
        if (st.k >= S().pops.length - 1) { togglePlay('S'); return; }
        st.k = Math.min(S().pops.length - 1, st.k + +$('speed').value);
      } else {
        if (st.t >= lastTick()) { togglePlay('T'); return; }
        st.t = Math.min(lastTick(), st.t + +$('runspeed').value);
      }
      syncSliders(); render();
    }, 60);
  }

  // ---------- top-down map ----------
  var map = $('map'), mctx = map.getContext('2d'), cell = 10;
  function sizeMap() {
    var w = Math.min(map.parentElement.clientWidth, 640);
    cell = Math.max(4, Math.floor(w / W.SX));
    var dpr = window.devicePixelRatio || 1;
    map.style.width = cell * W.SX + 'px'; map.style.height = cell * W.SZ + 'px';
    map.width = cell * W.SX * dpr; map.height = cell * W.SZ * dpr;
    mctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  var terrainCache = null;
  function terrainImage() {
    // Height as colour, lit from the north-west: a column lower than its north-west neighbour is shaded.
    var off = document.createElement('canvas'); off.width = W.SX; off.height = W.SZ;
    var c = off.getContext('2d'), img = c.createImageData(W.SX, W.SZ);
    var lo = hexRgb(css('--t-low')), hi = hexRgb(css('--t-high')), wall = hexRgb(css('--t-wall')), water = hexRgb(css('--water')), lava = hexRgb(css('--lava'));
    for (var x = 0; x < W.SX; x++) for (var z = 0; z < W.SZ; z++) {
      var col;
      if (isWall(x, z)) col = wall;
      else {
        var s = W.stand[z * W.SX + x];
        col = mix(lo, hi, (s - W.minStand) / Math.max(1, W.maxStand - W.minStand));
        var nw = isWall(x - 1, z - 1) ? s : standAt(x - 1, z - 1);
        col = scale(col, Math.max(0.55, Math.min(1.3, 1 - 0.09 * (nw - s))));
        if (W.wet[z * W.SX + x] === 1) col = mix(col, water, 0.72);
        if (W.wet[z * W.SX + x] === 2) col = mix(col, lava, 0.8);
      }
      var i = (z * W.SX + x) * 4; img.data[i] = col[0]; img.data[i + 1] = col[1]; img.data[i + 2] = col[2]; img.data[i + 3] = 255;
    }
    c.putImageData(img, 0, 0); return off;
  }
  function popColor(i, p) {
    var a = hexRgb(css('--pop-a')), b = hexRgb(css('--pop-b')), s = S(), t;
    if (st.color === 'order' || p.f === undefined) t = i / Math.max(1, s.pops.length - 1);
    else if (st.color === 'f') t = p.f / s.maxF;
    else if (st.color === 'g') t = p.g / s.maxG;
    else t = p.m / s.maxM;
    return rgb(mix(a, b, t));
  }
  function line(points, color, width, dash) {
    if (points.length < 2) return;
    mctx.strokeStyle = color; mctx.lineWidth = width; mctx.setLineDash(dash || []);
    mctx.beginPath();
    points.forEach(function (q, j) { var px = (q[0] + 0.5) * cell, pz = (q[2] + 0.5) * cell; if (j) mctx.lineTo(px, pz); else mctx.moveTo(px, pz); });
    mctx.stroke(); mctx.setLineDash([]);
  }
  function drawMap() {
    if (!terrainCache) terrainCache = terrainImage();
    mctx.imageSmoothingEnabled = false;
    mctx.drawImage(terrainCache, 0, 0, W.SX * cell, W.SZ * cell);
    if (st.heat && W.run && W.run.heat) drawHeat();
    // a thin line at every one-block step, a thick one where the step is deeper than the fall distance
    for (var x = 1; x < W.SX - 1; x++) for (var z = 1; z < W.SZ - 1; z++) {
      var here = standAt(x, z), e = standAt(x + 1, z), so = standAt(z + 1 < W.SZ - 1 ? x : x, z + 1);
      [[e, (x + 1) * cell, z * cell, (x + 1) * cell, (z + 1) * cell, x + 1 < W.SX - 1], [so, x * cell, (z + 1) * cell, (x + 1) * cell, (z + 1) * cell, z + 1 < W.SZ - 1]].forEach(function (d) {
        if (!d[5] || d[0] === here) return;
        var deep = Math.abs(d[0] - here) > W.maxFall;
        mctx.strokeStyle = deep ? css('--drop-line') : css('--step-line'); mctx.lineWidth = deep ? 1.5 : 0.6;
        mctx.beginPath(); mctx.moveTo(d[1], d[2]); mctx.lineTo(d[3], d[4]); mctx.stroke();
      });
    }
    var s = S(), k = Math.min(st.k, s.pops.length - 1), inset = Math.max(1, cell * 0.2);
    for (var i = 0; i <= k; i++) {
      var p = s.pops[i];
      mctx.fillStyle = popColor(i, p);
      mctx.fillRect(p.p[0] * cell + inset, p.p[2] * cell + inset, cell - 2 * inset, cell - 2 * inset);
    }
    mctx.strokeStyle = css('--open'); mctx.lineWidth = 1;
    s.addedAt.forEach(function (at, kk) {
      if (at > k) return;
      var popped = s.popAt.get(kk);
      if (popped !== undefined && popped <= k) return;
      var q = kk.split(',').map(Number);
      mctx.strokeRect(q[0] * cell + 1.5, q[2] * cell + 1.5, cell - 3, cell - 3);
    });
    var cur = P();
    if (cur && cur.cand) cur.cand.forEach(function (c) {
      if (c.r === 'accepted') return;
      mctx.strokeStyle = css('--refused'); mctx.lineWidth = 1.5;
      var cx = c.ask[0] * cell, cz = c.ask[2] * cell;
      mctx.beginPath(); mctx.moveTo(cx + 2, cz + 2); mctx.lineTo(cx + cell - 2, cz + cell - 2); mctx.moveTo(cx + cell - 2, cz + 2); mctx.lineTo(cx + 2, cz + cell - 2); mctx.stroke();
    });
    if (cur) { mctx.strokeStyle = css('--current'); mctx.lineWidth = 2.5; mctx.strokeRect(cur.p[0] * cell, cur.p[2] * cell, cell, cell); }
    if (st.path || k === s.pops.length - 1) line(s.path, css('--path'), 2.5);
    marker(s.start, 'S', '--marker'); marker(s.target, s.kind === 'detour' ? 'D' : 'T', '--marker');
    if (W.run && st.runOverlay) drawRun();
    if (st.sel) { mctx.setLineDash([3, 2]); mctx.strokeStyle = css('--fg'); mctx.lineWidth = 1.5; mctx.strokeRect(st.sel[0] * cell, st.sel[1] * cell, cell, cell); mctx.setLineDash([]); }
  }
  function planAt(t) {
    var plan = null;
    W.run.plans.forEach(function (p) { if (p[0] <= t) plan = p; });
    return plan;
  }
  /** Every point any search of the run took off its open set, per column: darker orange where more searching happened. */
  function drawHeat() {
    var H = W.run.heat, n = W.SX * W.SZ, max = 1, sum = new Float64Array(n);
    for (var i = 0; i < n; i++) { sum[i] = (H.follow[i] || 0) + (H.detour[i] || 0); max = Math.max(max, sum[i]); }
    var lm = Math.log(1 + max);
    for (var j = 0; j < n; j++) {
      if (!sum[j]) continue;
      mctx.fillStyle = 'rgba(234, 88, 12,' + (0.12 + 0.7 * Math.log(1 + sum[j]) / lm).toFixed(3) + ')';
      mctx.fillRect((j % W.SX) * cell, Math.floor(j / W.SX) * cell, cell, cell);
    }
  }
  function trailOf(ticks, t, every) {
    var trail = [];
    for (var i = 0; i <= Math.min(t, ticks.length - 1); i += every) trail.push([Math.floor(ticks[i][0]), 0, Math.floor(ticks[i][2])]);
    return trail;
  }
  function drawRun() {
    var R = W.run, t = st.t, g = group();
    if (g) g.forEach(function (r, i) {
      if (i === st.r || !r.ticks.length) return;
      line(trailOf(r.ticks, t, 4), outcomeColor(r.outcome), 1);
      var p = tickAt(r.ticks, t), alive = !r.death || t < r.ticks.length;
      mctx.fillStyle = alive ? outcomeColor(r.outcome) : css('--bad');
      mctx.beginPath(); mctx.arc(p[0] * cell, p[2] * cell, Math.max(2, cell * 0.35), 0, Math.PI * 2); mctx.fill();
    });
    var ticks = focusTicks();
    if (!ticks.length) { marker(R.owner, 'O', '--marker'); return; }
    line(trailOf(ticks, t, 2), css('--trail'), 2);
    var plan = planAt(t);
    if (plan && plan[3].length) {
      line(plan[3], css('--plan'), 2, [5, 3]);
      plan[3].forEach(function (q) { mctx.fillStyle = css('--plan'); mctx.beginPath(); mctx.arc((q[0] + 0.5) * cell, (q[2] + 0.5) * cell, cell * 0.45, 0, Math.PI * 2); mctx.fill(); });
    }
    var here = tickAt(ticks, t), id = here[3];
    if (id >= 0 && W.searches[id]) line(W.searches[id].path, css('--walk'), 3);
    marker([Math.floor(here[0]), 0, Math.floor(here[2])], 'R', '--recruit');
    marker(R.owner, 'O', '--marker');
  }
  function marker(p, label, colorVar) {
    var r = Math.max(5, cell * 0.7);
    mctx.fillStyle = css(colorVar); mctx.beginPath(); mctx.arc((p[0] + 0.5) * cell, (p[2] + 0.5) * cell, r, 0, Math.PI * 2); mctx.fill();
    mctx.strokeStyle = css('--marker-fg'); mctx.lineWidth = 1; mctx.stroke();
    mctx.fillStyle = css('--marker-fg'); mctx.font = '600 ' + Math.round(r * 1.2) + 'px Inter, sans-serif'; mctx.textAlign = 'center'; mctx.textBaseline = 'middle';
    mctx.fillText(label, (p[0] + 0.5) * cell, (p[2] + 0.5) * cell + 0.5);
  }
  map.addEventListener('click', function (e) {
    var r = map.getBoundingClientRect(), x = Math.floor((e.clientX - r.left) / cell), z = Math.floor((e.clientY - r.top) / cell);
    if (x < 0 || z < 0 || x >= W.SX || z >= W.SZ) return;
    st.sel = [x, z]; render(); update3dCut();
  });
  map.addEventListener('mousemove', function (e) {
    var r = map.getBoundingClientRect(), x = Math.floor((e.clientX - r.left) / cell), z = Math.floor((e.clientY - r.top) / cell);
    if (x < 0 || z < 0 || x >= W.SX || z >= W.SZ) return;
    var i = S().byColumn.get(x + ',' + z);
    $('hover').textContent = 'x ' + x + ', z ' + z + ': ground (stand height) ' + standAt(x, z) + (W.wet[z * W.SX + x] ? ', water' : '')
      + (i ? ', taken at step ' + i.slice(0, 6).map(function (n) { return n + 1; }).join(', ') : '');
  });

  // ---------- the run panel ----------
  /** For a group run: the counts by outcome, each recruit with a button to follow it, and the run's search cost. */
  function groupPanel(g) {
    var R = W.run, counts = {}, h = [];
    g.forEach(function (r) { counts[r.outcome] = (counts[r.outcome] || 0) + 1; });
    h.push('<p><b>' + g.length + ' recruits</b>: ' + Object.keys(counts).map(function (k) { return '<span style="color:' + outcomeColor(k) + '">' + esc(k) + ' ' + counts[k] + '</span>'; }).join(', ')
      + '. Path queue: at most ' + R.queue.max + ' waiting, full for ' + R.queue.fullTicks + ' ticks (' + R.threads + ' path thread' + (R.threads === 1 ? '' : 's') + ').</p>');
    var all = W.searches, pts = 0, behind = 0, server = 0;
    all.forEach(function (s) { pts += s.n || 0; behind += s.behind || 0; if (s.server) server++; });
    h.push('<p>' + all.length + ' searches took ' + pts + ' points off their open sets (' + (all.length ? Math.round(pts / all.length) : 0) + ' a search); '
      + (pts ? Math.round(100 * behind / pts) : 0) + '% of the points lay behind the search\'s start, away from its target; '
      + server + ' searches ran on the server thread because the path queue was full.</p>');
    h.push('<div class="scroll"><table><thead><tr><th>Recruit</th><th>Ended</th><th>Reached at tick</th><th>Closest to owner</th><th>Death</th><th></th></tr></thead><tbody>');
    g.forEach(function (r, i) {
      h.push('<tr' + (i === st.r ? ' style="font-weight:600"' : '') + '><td>' + i + '</td><td style="color:' + outcomeColor(r.outcome) + '">' + esc(r.outcome) + '</td><td class="num">'
        + (r.reachedTick >= 0 ? r.reachedTick : '') + '</td><td class="num">' + fmt(r.closest) + '</td><td>' + (r.death ? esc(r.death.cause) + ' at tick ' + r.death.tick : '')
        + '</td><td><button type="button" data-focus="' + i + '">Follow</button></td></tr>');
    });
    h.push('</tbody></table></div>');
    return h.join('');
  }
  function runPanel() {
    if (!W.run) return;
    var R = W.run, g = group(), ticks = focusTicks(), t = st.t;
    if (!ticks.length) { $('runpanel').innerHTML = g ? groupPanel(g) : ''; return; }
    var here = tickAt(ticks, t), f = here[4];
    var states = [];
    if (f & 1) states.push('following'); else states.push('not following (goal stopped)');
    if (f & 4) states.push('<span class="bad">its path is still being searched: no walk input this tick</span>');
    if (f & 2) states.push('a new search waits beside the walked path');
    if (f & 8) states.push('<span class="bad">in water</span>');
    if (f & 16) states.push('on the ground');
    var plan = planAt(t), id = here[3];
    var h = [];
    if (f & 32) states.push('has a target to fight');
    if (g && t >= ticks.length) states = [g[st.r].death ? '<span class="bad">dead since tick ' + g[st.r].death.tick + ' (' + esc(g[st.r].death.cause) + ')</span>' : 'no longer recorded'];
    h.push('<div class="kv"><span>Tick</span><b>' + t + ' of ' + lastTick() + '</b><span>Result</span><b>' + esc(R.outcome) + '</b></div>');
    h.push('<p>Recruit R' + (g ? ' (recruit ' + st.r + ')' : '') + ' at ' + fmt(here[0]) + ', ' + fmt(here[1]) + ', ' + fmt(here[2]) + ': ' + states.join('; ') + '.</p>');
    h.push('<p>Walking: ' + (id >= 0 ? 'the path from search <b>#' + id + '</b> (' + (W.searches[id].reached ? 'reaches its target' : 'does not reach its target') + ', ' + W.searches[id].path.length + ' points after cut-backs were applied later)' : 'no path') + '.</p>');
    h.push('<p>Detour plan for its owner: ' + (plan ? '<b>' + esc(plan[1]) + '</b> after ' + plan[2] + ' searches' + (plan[3].length ? ', ' + plan[3].length + ' waypoints' : '') : 'none') + '.</p>');
    h.push('<p class="dim">' + esc(R.message || '') + '</p>');
    if (g) h.push(groupPanel(g));
    $('runpanel').innerHTML = h.join('');
    $('tickno').textContent = 'tick ' + t;
  }

  // ---------- the step panel: the math of one A* step ----------
  function stepPanel() {
    var s = S(), p = P(), k = Math.min(st.k, s.pops.length - 1), h = [];
    h.push('<div class="kv"><span>Search</span><b>' + esc(s.kind) + (s.id !== undefined ? ' #' + s.id : '') + '</b><span>Step</span><b>' + (k + 1) + ' of ' + s.pops.length + '</b></div>');
    h.push('<p class="explain">From ' + xyz(s.start) + ' to ' + xyz(s.target) + '. ' + (s.reached ? '<span class="good">Reached its target.</span>' : '<span class="bad">Did not reach its target.</span>')
      + (s.noStart ? ' No start point could be found.' : '') + ' Dirt-path mode ' + esc(s.dirtPath) + ', edge margin ' + s.margin + ', fall distance ' + s.maxFall + '.</p>');
    if (!p) { $('panel').innerHTML = h.join(''); return; }
    if (!s.detail) {
      h.push('<p class="explain">Only the points taken off the open set were kept for this search: full detail is kept for the first search from each start to each target' + (s.sameAs !== undefined ? '; this one is identical to search #' + s.sameAs : '') + '. Point ' + xyz(p.p) + '.</p>');
      $('panel').innerHTML = h.join(''); return;
    }
    if (!p.expanded) {
      h.push('<p class="explain">' + xyz(p.p) + ' was taken off the open set and closed, but never expanded: the search checks whether a point is within a block of its target before asking for its neighbours, so this is the point that reached it, or one beyond the search\'s range (it expands only points less than the range from its start).</p>'
        + '<div class="eq">f = g + h = <b>' + fmt(p.g) + '</b> + <b>' + fmt(p.h) + '</b> = <b>' + fmt(p.f) + '</b></div>');
      $('panel').innerHTML = h.join(''); return;
    }
    h.push('<p class="explain">The open set is a heap ordered by <code>f</code>; the point with the lowest <code>f</code> is taken off and closed: <b>' + xyz(p.p) + '</b>' + (p.from ? ', reached from ' + xyz(p.from) : ', the start') + '.</p>');
    h.push('<div class="eq">f = g + h = <b>' + fmt(p.g) + '</b> + <b>' + fmt(p.h) + '</b> = <b>' + fmt(p.f) + '</b><br><span class="dim">' + (k === 0 ? 'the start point: g = 0, h = distance to the target' : 'h = 1.5 × straight-line distance to the target') + '</span></div>');
    h.push('<div class="eq">costMalus of this point = <b>' + fmt(p.m) + '</b><br><span class="dim">edge part: ' + (p.ed ? 'nearest dangerous drop at ring ' + p.ed + ' → 8 × (' + s.margin + ' − ' + p.ed + ' + 1) / ' + s.margin + ' = ' + fmt(p.ec) : 'no dangerous drop within ' + s.margin + ' → 0') + '; the rest is the block\'s cost and the dirt-path cost (' + esc(s.dirtPath) + ')</span></div>');
    h.push('<h4>Neighbours the evaluator returned</h4><div class="scroll"><table class="math"><thead><tr><th>Point</th><th>step</th><th>costMalus</th><th>g + step + costMalus</th><th>before</th><th>result</th><th>after: g, h, f</th></tr></thead><tbody>');
    p.nb.forEach(function (n) {
      var step = dist3(p.p, n.p), tg = p.g + step + n.m, res, ok = '';
      if (n.upd && !n.open) res = '<span class="good">added</span>';
      else if (n.upd) res = '<span class="good">improved</span>';
      else if (n.open) res = 'kept: ' + fmt(n.g0) + ' ≤ ' + fmt(tg);
      else res = '<span class="bad">not added: walked ' + W.range + '+ blocks</span>';
      if (n.upd) ok = Math.abs(n.g - tg) < 0.02 ? ' <span class="good" title="the recorded g equals the computed one">✓</span>' : ' <span class="bad">≠ (drop cost)</span>';
      h.push('<tr><td>' + xyz(n.p) + '</td><td>' + fmt(step) + '</td><td>' + fmt(n.m) + '</td><td>' + fmt(p.g) + ' + ' + fmt(step) + ' + ' + fmt(n.m) + ' = <b>' + fmt(tg) + '</b>' + ok + '</td><td>'
        + (n.open ? 'open, g ' + fmt(n.g0) : 'new') + '</td><td>' + res + '</td><td>' + fmt(n.g) + ', ' + fmt(n.h) + ', ' + fmt(n.f) + '</td></tr>');
    });
    h.push('</tbody></table></div><h4>Steps asked for (' + p.cand.length + ')</h4><ul class="cands">');
    p.cand.forEach(function (c) {
      var dy = c.at[1] - c.ask[1], what;
      if (c.r === 'refused') what = '<span class="bad">refused</span> (a drop of 2+ onto a spot beside a deadly drop, or into a dead end)';
      else if (c.r === 'blocked') what = '<span class="bad">blocked</span> (a wall, a fall deeper than ' + s.maxFall + ', or a block it may not enter)';
      else what = 'accepted at ' + xyz(c.at) + (dy > 0 ? ', a step up' : dy < 0 ? ', a fall of ' + (-dy) : '') + ', costMalus ' + fmt(c.m);
      h.push('<li><b>' + esc(c.d) + '</b> ' + xyz(c.ask) + ': ' + what + (c.de ? '; the dead-end check walked ' + c.de + ' spots' : '') + '</li>');
    });
    h.push('</ul>');
    $('panel').innerHTML = h.join('');
  }

  // ---------- block checks ----------
  function selected() { if (st.sel) return st.sel; var p = P(); return p ? [p.p[0], p.p[2]] : [W.SX >> 1, W.SZ >> 1]; }
  function popInColumn(x, z) {
    var list = S().byColumn.get(x + ',' + z);
    if (!list) return null;
    var best = null;
    list.forEach(function (i) { if (i <= st.k && (best === null || i > best)) best = i; });
    return best === null ? list[0] : best;
  }
  function blockColor(c) { return c === '#' ? css('--stone') : c === 'w' ? css('--water') : c === 'l' ? css('--lava') : css('--air'); }
  function sizeCanvas(cv, w, h) {
    var dpr = window.devicePixelRatio || 1;
    cv.style.width = w + 'px'; cv.style.height = h + 'px'; cv.width = w * dpr; cv.height = h * dpr;
    var c = cv.getContext('2d'); c.setTransform(dpr, 0, 0, dpr, 0, 0); return c;
  }
  function drawColumn() {
    var at = selected(), x = at[0], z = at[1], u = Math.max(5, Math.min(14, Math.floor(640 / W.SY)));
    var c = sizeCanvas($('column'), 160, W.SY * u + 4);
    var pi = popInColumn(x, z), p = pi === null ? null : S().pops[pi];
    c.font = '10px JetBrains Mono, monospace'; c.textBaseline = 'middle';
    for (var y = 0; y < W.SY; y++) {
      var top = (W.SY - 1 - y) * u + 2;
      c.fillStyle = blockColor(blockAt(x, y, z)); c.fillRect(40, top, 40, u - 1);
      c.fillStyle = css('--muted'); c.textAlign = 'right'; c.fillText(String(y), 34, top + u / 2);
    }
    if (p) {
      var feet = p.p[1], ft = (W.SY - 1 - feet) * u + 2;
      c.strokeStyle = css('--current'); c.lineWidth = 2; c.strokeRect(40, ft, 40, u - 1);
      c.fillStyle = css('--fg'); c.textAlign = 'left'; c.fillText('feet', 86, ft + u / 2);
      var from = (W.SY - feet) * u + 2, to = (W.SY - feet + S().maxFall + 1) * u + 2;
      c.strokeStyle = css('--drop-line'); c.lineWidth = 1.5; c.beginPath(); c.moveTo(84, from); c.lineTo(90, from); c.lineTo(90, to); c.lineTo(84, to); c.stroke();
      c.fillText('drop check', 94, (from + to) / 2);
    }
    var info = ['<p><b>Column x ' + x + ', z ' + z + '</b>: ground (stand height) ' + standAt(x, z) + (W.wet[z * W.SX + x] ? ', water on it' : '') + '.</p>'];
    if (p && p.ed !== undefined && p.expanded) info.push('<p>Taken off the open set at step ' + (pi + 1) + ', feet at y ' + p.p[1] + '. Nearest dangerous drop: ' + (p.ed ? 'ring ' + p.ed + ', edge cost ' + fmt(p.ec) : 'none within ' + S().margin + ' blocks, edge cost 0') + '.</p>');
    else if (p) info.push('<p>Taken off the open set at step ' + (pi + 1) + ', feet at y ' + p.p[1] + '.</p>');
    else info.push('<p class="dim">This search never took a point in this column off its open set.</p>');
    $('colinfo').innerHTML = info.join('');
    drawRing(p, x, z);
  }
  function drawRing(p, x, z) {
    var margin = Math.max(1, S().margin), n = 2 * margin + 1, u = 30;
    var c = sizeCanvas($('ring'), n * u, n * u), grid = {}, idx = 0, has = p && p.ring && p.ring !== '-' && p.ring.length;
    if (has) for (var r = 1; r <= margin; r++) for (var dx = -r; dx <= r; dx++) for (var dz = -r; dz <= r; dz++) {
      if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
      grid[dx + ',' + dz] = p.ring.charAt(idx++);
    }
    for (var gx = -margin; gx <= margin; gx++) for (var gz = -margin; gz <= margin; gz++) {
      var px = (gx + margin) * u, pz = (gz + margin) * u, ch = grid[gx + ',' + gz];
      c.fillStyle = gx === 0 && gz === 0 ? css('--current') : ch === 'D' ? css('--refused') : ch === '.' ? css('--safe') : css('--air');
      c.fillRect(px + 1, pz + 1, u - 2, u - 2);
      if (p) {
        var d = standAt(x + gx, z + gz) - p.p[1];
        c.fillStyle = gx === 0 && gz === 0 ? css('--bg') : css('--on-cell'); c.font = '600 11px JetBrains Mono, monospace'; c.textAlign = 'center'; c.textBaseline = 'middle';
        c.fillText(gx === 0 && gz === 0 ? 'here' : (d > 0 ? '+' : '') + d, px + u / 2, pz + u / 2);
      }
    }
    $('ringinfo').textContent = has
      ? 'Each square is a column around this point, rings 1 to ' + margin + '. Red: walking there in a straight line meets a dangerous drop (open below for more than ' + (S().maxFall + 1) + ' blocks, or lava). Green: safe. The number is that column\'s ground height minus these feet.'
      : 'The ring check is kept for fully recorded points with edge care on. The numbers are ground height minus these feet.';
  }
  function drawSection(cv, along, fixed) {
    var n = along === 'x' ? W.SX : W.SZ;
    var w = Math.min(cv.parentElement.clientWidth, 640), u = Math.max(4, Math.floor(w / n));
    var c = sizeCanvas(cv, u * n, u * W.SY), s = S();
    for (var i = 0; i < n; i++) for (var y = 0; y < W.SY; y++) {
      c.fillStyle = blockColor(along === 'x' ? blockAt(i, y, fixed) : blockAt(fixed, y, i)); c.fillRect(i * u, (W.SY - 1 - y) * u, u, u);
    }
    for (var k = 0; k <= Math.min(st.k, s.pops.length - 1); k++) {
      var p = s.pops[k];
      if (along === 'x' ? p.p[2] !== fixed : p.p[0] !== fixed) continue;
      var a = along === 'x' ? p.p[0] : p.p[2];
      c.fillStyle = popColor(k, p); c.fillRect(a * u + u * 0.2, (W.SY - 1 - p.p[1]) * u + u * 0.2, u * 0.6, u * 0.6);
    }
    c.fillStyle = css('--path');
    s.path.forEach(function (q) {
      if (along === 'x' ? q[2] !== fixed : q[0] !== fixed) return;
      var a = along === 'x' ? q[0] : q[2];
      c.beginPath(); c.arc((a + 0.5) * u, (W.SY - 1 - q[1] + 0.5) * u, u * 0.35, 0, Math.PI * 2); c.fill();
    });
    var here = W.run && st.runOverlay ? tickAt(focusTicks(), st.t) : null;
    if (here) {
      if (Math.floor(along === 'x' ? here[2] : here[0]) === fixed) {
        var a2 = along === 'x' ? here[0] : here[2];
        c.fillStyle = css('--recruit'); c.fillRect((a2 - 0.3) * u, (W.SY - here[1] - 1.8) * u, u * 0.6, u * 1.8);
      }
    }
    var at = selected(), mark = along === 'x' ? at[0] : at[1];
    c.strokeStyle = css('--fg'); c.setLineDash([3, 2]); c.strokeRect(mark * u, 0, u, W.SY * u); c.setLineDash([]);
  }
  function drawSections() {
    var at = selected();
    $('secx-label').textContent = 'Side view along x, at z ' + at[1] + ' (west on the left)';
    $('secz-label').textContent = 'Side view along z, at x ' + at[0] + ' (north on the left)';
    drawSection($('secx'), 'x', at[1]); drawSection($('secz'), 'z', at[0]);
  }

  // ---------- 3D ----------
  var three = null;
  function init3d() {
    var host = $('view3d');
    if (!window.THREE) { host.innerHTML = '<p class="dim" style="padding:12px">The 3D view needs three.js, which did not load.</p>'; return; }
    var THREE = window.THREE;
    var renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.localClippingEnabled = true;
    host.appendChild(renderer.domElement);
    var scene = new THREE.Scene();
    scene.add(new THREE.HemisphereLight(0xffffff, 0x445544, 0.85));
    var sun = new THREE.DirectionalLight(0xffffff, 0.55); sun.position.set(40, 80, 20); scene.add(sun);
    three = { THREE: THREE, renderer: renderer, scene: scene, camera: new THREE.PerspectiveCamera(45, 1, 0.5, 500), host: host,
      clip: new THREE.Plane(new THREE.Vector3(-1, 0, 0), 999), world: new THREE.Group(), search: new THREE.Group(), run: new THREE.Group(),
      yaw: -0.7, pitch: 0.75, dist: 95, target: new THREE.Vector3(32, 8, 32) };
    scene.add(three.world); scene.add(three.search); scene.add(three.run);
    var drag = null, el = renderer.domElement;
    el.addEventListener('pointerdown', function (e) { drag = [e.clientX, e.clientY]; el.setPointerCapture(e.pointerId); });
    el.addEventListener('pointermove', function (e) {
      if (!drag) return;
      three.yaw -= (e.clientX - drag[0]) * 0.008; three.pitch = Math.max(0.08, Math.min(1.45, three.pitch + (e.clientY - drag[1]) * 0.006));
      drag = [e.clientX, e.clientY]; draw3d();
    });
    el.addEventListener('pointerup', function () { drag = null; });
    el.addEventListener('wheel', function (e) { e.preventDefault(); three.dist = Math.max(20, Math.min(220, three.dist * (e.deltaY > 0 ? 1.1 : 0.9))); draw3d(); }, { passive: false });
  }
  function clearGroup(g) { while (g.children.length) { var o = g.children.pop(); if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); } }
  function color3(v) { var c = hexRgb(css(v)); return new three.THREE.Color(c[0] / 255, c[1] / 255, c[2] / 255); }
  function rebuild3dWorld() {
    if (!three) return;
    var THREE = three.THREE; clearGroup(three.world);
    // Aim at the arena's middle, at its ground, from far enough to see all of it.
    three.target.set(W.SX / 2, (W.minStand + W.maxStand) / 2, W.SZ / 2);
    three.dist = Math.max(60, Math.max(W.SX, W.SZ) * 1.45);
    var solids = [], waters = [];
    for (var x = 1; x < W.SX - 1; x++) for (var z = 1; z < W.SZ - 1; z++) for (var y = 0; y < W.SY; y++) {
      var c = blockAt(x, y, z);
      if (c === '.') continue;
      var exposed = blockAt(x, y + 1, z) !== '#' || blockAt(x + 1, y, z) !== '#' || blockAt(x - 1, y, z) !== '#' || blockAt(x, y, z + 1) !== '#' || blockAt(x, y, z - 1) !== '#';
      if (exposed) (c === '#' ? solids : waters).push([x, y, z]);
    }
    var box = new THREE.BoxGeometry(1, 1, 1), m = new THREE.Matrix4(), col = new THREE.Color();
    var lo = hexRgb(css('--t-low')), hi = hexRgb(css('--t-high'));
    var mesh = new THREE.InstancedMesh(box, new THREE.MeshLambertMaterial({ clippingPlanes: [three.clip] }), solids.length);
    solids.forEach(function (b, i) {
      m.makeTranslation(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5); mesh.setMatrixAt(i, m);
      var cc = mix(lo, hi, Math.max(0, Math.min(1, (b[1] - (W.minStand - 6)) / Math.max(1, W.maxStand - W.minStand + 6))));
      col.setRGB(cc[0] / 255, cc[1] / 255, cc[2] / 255); mesh.setColorAt(i, col);
    });
    three.world.add(mesh);
    if (waters.length) {
      var wm = new THREE.InstancedMesh(box, new THREE.MeshLambertMaterial({ color: color3('--water'), transparent: true, opacity: 0.55, clippingPlanes: [three.clip] }), waters.length);
      waters.forEach(function (b, i) { m.makeTranslation(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5); wm.setMatrixAt(i, m); });
      three.world.add(wm);
    }
  }
  function polyline3(points, colorVar, lift) {
    var THREE = three.THREE, v = points.map(function (q) { return new THREE.Vector3(q[0] + 0.5, q[1] + lift, q[2] + 0.5); });
    return new THREE.Line(new THREE.BufferGeometry().setFromPoints(v), new THREE.LineBasicMaterial({ color: color3(colorVar) }));
  }
  function rebuild3dSearch() {
    if (!three) return;
    var THREE = three.THREE, g = three.search, s = S(); clearGroup(g);
    var pts = new Float32Array(s.pops.length * 3), cols = new Float32Array(s.pops.length * 3);
    var a = hexRgb(css('--pop-a')), b = hexRgb(css('--pop-b'));
    s.pops.forEach(function (p, i) {
      pts[i * 3] = p.p[0] + 0.5; pts[i * 3 + 1] = p.p[1] + 0.15; pts[i * 3 + 2] = p.p[2] + 0.5;
      var c = mix(a, b, i / Math.max(1, s.pops.length - 1)); cols[i * 3] = c[0] / 255; cols[i * 3 + 1] = c[1] / 255; cols[i * 3 + 2] = c[2] / 255;
    });
    var pg = new THREE.BufferGeometry();
    pg.setAttribute('position', new THREE.BufferAttribute(pts, 3)); pg.setAttribute('color', new THREE.BufferAttribute(cols, 3));
    three.points = new THREE.Points(pg, new THREE.PointsMaterial({ size: 0.45, vertexColors: true })); g.add(three.points);
    if (s.path.length > 1) g.add(polyline3(s.path, '--path', 0.35));
    [s.start, s.target].forEach(function (q) {
      var mk = new THREE.Mesh(new THREE.SphereGeometry(0.45, 12, 8), new THREE.MeshLambertMaterial({ color: color3('--marker') }));
      mk.position.set(q[0] + 0.5, q[1] + 0.9, q[2] + 0.5); g.add(mk);
    });
    three.cursor = new THREE.Mesh(new THREE.BoxGeometry(1.05, 0.2, 1.05), new THREE.MeshBasicMaterial({ color: color3('--current') }));
    g.add(three.cursor);
    update3d();
  }
  function rebuild3dRun() {
    if (!three) return;
    clearGroup(three.run);
    if (!W.run || !st.runOverlay) return;
    var R = W.run, t = st.t, THREE = three.THREE, trail = [], ticks = focusTicks(), g = group();
    for (var i = 0; i <= Math.min(t, ticks.length - 1); i += 2) trail.push([ticks[i][0] - 0.5, ticks[i][1], ticks[i][2] - 0.5]);
    if (trail.length > 1) three.run.add(polyline3(trail, '--trail', 0.1));
    var plan = planAt(t);
    if (plan && plan[3].length > 1) three.run.add(polyline3(plan[3], '--plan', 0.6));
    (g || [{ ticks: ticks }]).forEach(function (r, j) {
      if (!r.ticks.length || (r.death && t >= r.ticks.length)) return;
      var p = tickAt(r.ticks, t), focused = !g || j === st.r;
      var body = new THREE.Mesh(new THREE.BoxGeometry(0.6, 1.8, 0.6), new THREE.MeshLambertMaterial({ color: focused ? color3('--recruit') : color3(r.outcome === 'reached' ? '--good' : '--muted') }));
      body.position.set(p[0], p[1] + 0.9, p[2]); three.run.add(body);
    });
    var owner = new THREE.Mesh(new THREE.BoxGeometry(0.6, 1.8, 0.6), new THREE.MeshLambertMaterial({ color: color3('--marker') }));
    owner.position.set(R.owner[0] + 0.5, R.owner[1] + 0.9, R.owner[2] + 0.5); three.run.add(owner);
  }
  function size3d() {
    if (!three) return;
    var w = three.host.clientWidth, h = Math.max(320, Math.min(560, Math.round(w * 0.62)));
    three.renderer.setPixelRatio(window.devicePixelRatio || 1); three.renderer.setSize(w, h);
    three.camera.aspect = w / h; three.camera.updateProjectionMatrix();
  }
  function update3d() {
    if (!three || !three.points) return;
    three.points.geometry.setDrawRange(0, Math.min(st.k, S().pops.length - 1) + 1);
    var p = P(); if (p) three.cursor.position.set(p.p[0] + 0.5, p.p[1] + 0.1, p.p[2] + 0.5);
    rebuild3dRun(); draw3d();
  }
  function update3dCut() { if (!three) return; three.clip.constant = st.cut ? selected()[0] + 1 : 999; draw3d(); }
  function draw3d() {
    if (!three) return;
    var t = three.target, d = three.dist;
    three.camera.position.set(t.x + d * Math.cos(three.pitch) * Math.cos(three.yaw), t.y + d * Math.sin(three.pitch), t.z + d * Math.cos(three.pitch) * Math.sin(three.yaw));
    three.camera.lookAt(t); three.scene.background = color3('--surface');
    three.renderer.render(three.scene, three.camera);
  }

  // ---------- render ----------
  function render() {
    drawMap(); stepPanel(); runPanel(); drawColumn(); drawSections(); update3d();
    $('stepno').textContent = 'step ' + (Math.min(st.k, S().pops.length - 1) + 1) + ' / ' + S().pops.length;
  }
  function relayoutAll(newWorld) {
    terrainCache = null; sizeMap(); size3d();
    if (newWorld) { rebuild3dWorld(); rebuild3dSearch(); }
    render();
  }
  window.addEventListener('resize', function () { relayoutAll(false); });
  if (window.matchMedia) window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () { relayoutAll(true); });
  try { init3d(); } catch (e) { $('view3d').innerHTML = '<p class="dim" style="padding:12px">The 3D view could not start here: ' + esc(e.message) + '</p>'; three = null; }
  setWorld(TRACE, 'trace');
  afterLoad();
})();
