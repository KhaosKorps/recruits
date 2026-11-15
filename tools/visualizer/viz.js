/*
 * The visualiser's page: four views (#runs, #explore, #search, #code) drawn from vizdata.js's model.
 * Explore shows one run on one map: every recruit's walked route, the followed recruit's searches (the
 * route each found dashed, the part kept after edge care solid), the detour planner's stepping stones,
 * and where all searches looked, with a timeline of each recruit's distance to its owner. Search plays
 * one search point by point with its numbers, block checks and a 3D view.
 */
(function () {
  'use strict';
  var V = window.VizData;
  var INDEX = JSON.parse(document.getElementById('index-data').textContent);

  // ---------- small helpers ----------
  function $(id) { return document.getElementById(id); }
  function css(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }
  function hexRgb(h) {
    h = h.replace('#', '');
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    var n = parseInt(h, 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function mix(a, b, t) { return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]; }
  function shade(c, f) { return [Math.min(255, c[0] * f), Math.min(255, c[1] * f), Math.min(255, c[2] * f)]; }
  function rgb(c, a) { return 'rgba(' + Math.round(c[0]) + ',' + Math.round(c[1]) + ',' + Math.round(c[2]) + ',' + (a === undefined ? 1 : a) + ')'; }
  function fmt(v) { return (Math.round(v * 100) / 100).toFixed(2); }
  function esc(t) { return String(t).replace(/[&<>"']/g, function (ch) { return '&#' + ch.charCodeAt(0) + ';'; }); }
  function xyz(p) { return p ? '(' + p[0] + ', ' + p[1] + ', ' + p[2] + ')' : '(none)'; }
  function dist3(a, b) { var dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2]; return Math.sqrt(dx * dx + dy * dy + dz * dz); }
  function outcomeVar(o) { return o === 'reached' ? '--good' : o === 'dead' ? '--bad' : o === 'pit' || o === 'edge' || o === 'water' ? '--warn' : '--muted'; }
  function sizeCanvas(cv, w, h) {
    var dpr = window.devicePixelRatio || 1;
    cv.style.width = w + 'px'; cv.style.height = h + 'px'; cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr);
    var c = cv.getContext('2d'); c.setTransform(dpr, 0, 0, dpr, 0, 0); return c;
  }

  // ---------- state ----------
  var M = null;
  var st = { run: -1, t: 0, r: 0, s: -1, k: 0, color: 'order', cut: false, sel: null, playT: null, playS: null,
    layers: { walked: true, searches: true, routes: false, planner: true, heat: false } };

  // ---------- views ----------
  var VIEWS = ['runs', 'explore', 'search', 'code'];
  // The page opens on Explore. A view that needs a run loads the first one if none is loaded yet.
  var loading = false;
  function showView() {
    var name = (location.hash || '#explore').slice(1);
    if (VIEWS.indexOf(name) < 0) name = 'explore';
    VIEWS.forEach(function (v) {
      $('v-' + v).hidden = v !== name;
      var tab = document.querySelector('nav.tabs a[href="#' + v + '"]');
      if (tab) tab.setAttribute('aria-current', v === name ? 'page' : 'false');
    });
    stopPlay();
    if ((name === 'explore' || name === 'search') && !M) {
      if (!loading && INDEX.runs.length) openRun(0, false);
      return;
    }
    if (name === 'explore') layoutExplore();
    if (name === 'search') layoutSearch();
  }
  window.addEventListener('hashchange', showView);

  // ---------- loading a run ----------
  var runSel = $('run-select');
  INDEX.runs.forEach(function (r, i) {
    var o = document.createElement('option'); o.value = i; o.textContent = r.label; runSel.appendChild(o);
  });
  runSel.addEventListener('change', function () { openRun(+runSel.value, false); });
  document.querySelectorAll('[data-explore]').forEach(function (b) {
    b.addEventListener('click', function () { openRun(+b.getAttribute('data-explore'), true); });
  });
  function openRun(i, go) {
    stopPlay();
    var r = INDEX.runs[i];
    runSel.value = i;
    loading = true;
    $('run-status').textContent = 'Loading ' + r.label + ' ...';
    $('s-head').textContent = 'Loading ' + r.label + ' ...';
    fetch(r.file).then(function (res) {
      if (!res.ok) throw new Error('the page could not fetch ' + r.file + ' (HTTP ' + res.status + ')');
      return res.json();
    }).then(function (data) {
      M = V.load(data); M.repeats = V.planRepeats(M); st.run = i; st.r = 0; st.s = -1; st.k = 0; st.sel = null; st.t = V.lastTick(M);
      terrain = null; world3d = null; loading = false;
      $('run-status').textContent = '';
      buildRecruitList();
      if (go && location.hash !== '#explore') location.hash = '#explore'; else showView();
    }).catch(function (e) {
      loading = false;
      var why = 'Could not open this run: ' + e.message + '.';
      $('run-status').textContent = why; $('s-head').textContent = why;
    });
  }

  // ---------- the map, shared drawing ----------
  var terrain = null;
  function terrainImage() {
    var off = document.createElement('canvas'); off.width = M.SX; off.height = M.SZ;
    var c = off.getContext('2d'), img = c.createImageData(M.SX, M.SZ);
    var lo = hexRgb(css('--t-low')), hi = hexRgb(css('--t-high')), wall = hexRgb(css('--t-wall')), water = hexRgb(css('--water')), lava = hexRgb(css('--lava')), leaf = hexRgb(css('--leaf'));
    for (var x = 0; x < M.SX; x++) for (var z = 0; z < M.SZ; z++) {
      var col, s = M.stand[z * M.SX + x];
      if (V.isEdge(M, x, z)) col = wall;
      else {
        col = mix(lo, hi, (s - M.minStand) / Math.max(1, M.maxStand - M.minStand));
        var nw = V.isEdge(M, x - 1, z - 1) ? s : V.standAt(M, x - 1, z - 1);
        col = shade(col, Math.max(0.55, Math.min(1.3, 1 - 0.09 * (nw - s))));
        if (M.wet[z * M.SX + x] === 1) col = mix(col, water, 0.72);
        if (M.wet[z * M.SX + x] === 2) col = mix(col, lava, 0.8);
        if (M.leaf[z * M.SX + x]) col = mix(col, leaf, 0.4);
      }
      var i = (z * M.SX + x) * 4; img.data[i] = col[0]; img.data[i + 1] = col[1]; img.data[i + 2] = col[2]; img.data[i + 3] = 255;
    }
    c.putImageData(img, 0, 0); return off;
  }
  /** A canvas with its scale: cell is the size of one block in pixels. */
  function Plot(canvas, maxWidth) {
    var w = Math.min(canvas.parentElement.clientWidth || maxWidth, maxWidth);
    this.cell = Math.max(3, Math.floor(w / M.SX));
    this.ctx = sizeCanvas(canvas, this.cell * M.SX, this.cell * M.SZ);
  }
  Plot.prototype.ground = function () {
    var c = this.ctx, cell = this.cell;
    if (!terrain) terrain = terrainImage();
    c.imageSmoothingEnabled = false;
    c.drawImage(terrain, 0, 0, M.SX * cell, M.SZ * cell);
    // a thin line at each one-block step, a thick one where a drop is deeper than the fall distance
    for (var x = 1; x < M.SX - 1; x++) for (var z = 1; z < M.SZ - 1; z++) {
      var here = V.standAt(M, x, z);
      [[V.standAt(M, x + 1, z), x + 1, z, x + 1, z + 1, x + 1 < M.SX - 1], [V.standAt(M, x, z + 1), x, z + 1, x + 1, z + 1, z + 1 < M.SZ - 1]].forEach(function (d) {
        if (!d[5] || d[0] === here) return;
        var deep = Math.abs(d[0] - here) > M.maxFall;
        c.strokeStyle = deep ? css('--drop-line') : css('--step-line'); c.lineWidth = deep ? 1.5 : 0.5;
        c.beginPath(); c.moveTo(d[1] * cell, d[2] * cell); c.lineTo(d[3] * cell, d[4] * cell); c.stroke();
      });
    }
  };
  Plot.prototype.line = function (points, color, width, dash, centred) {
    if (!points || points.length < 2) return;
    var c = this.ctx, cell = this.cell, off = centred === false ? 0 : 0.5;
    c.strokeStyle = color; c.lineWidth = width; c.setLineDash(dash || []); c.lineJoin = 'round';
    c.beginPath();
    points.forEach(function (q, j) { var px = (q[0] + off) * cell, pz = (q[2] + off) * cell; if (j) c.lineTo(px, pz); else c.moveTo(px, pz); });
    c.stroke(); c.setLineDash([]);
  };
  Plot.prototype.marker = function (p, label, colorVar, centred) {
    var c = this.ctx, r = Math.max(5, this.cell * 0.75), off = centred === false ? 0 : 0.5;
    var x = (p[0] + off) * this.cell, z = (p[2] + off) * this.cell;
    c.fillStyle = css(colorVar); c.beginPath(); c.arc(x, z, r, 0, Math.PI * 2); c.fill();
    c.strokeStyle = css('--marker-fg'); c.lineWidth = 1; c.stroke();
    c.fillStyle = css('--marker-fg'); c.font = '600 ' + Math.round(r * 1.15) + 'px Inter, sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle';
    c.fillText(label, x, z + 0.5);
  };
  Plot.prototype.cross = function (p, color) {
    var c = this.ctx, cell = this.cell, x = p[0] * cell, z = p[2] * cell, m = -Math.max(1, cell * 0.15);
    c.strokeStyle = color; c.lineWidth = 2.4;
    c.beginPath(); c.moveTo(x + m, z + m); c.lineTo(x + cell - m, z + cell - m); c.moveTo(x + cell - m, z + m); c.lineTo(x + m, z + cell - m); c.stroke();
  };
  Plot.prototype.ring = function (p, color, size) {
    var c = this.ctx;
    c.strokeStyle = color; c.lineWidth = 2;
    c.beginPath(); c.arc((p[0] + 0.5) * this.cell, (p[2] + 0.5) * this.cell, size, 0, Math.PI * 2); c.stroke();
  };
  Plot.prototype.dot = function (p, color, size, centred) {
    var c = this.ctx, off = centred === false ? 0 : 0.5;
    c.fillStyle = color; c.beginPath(); c.arc((p[0] + off) * this.cell, (p[2] + off) * this.cell, size, 0, Math.PI * 2); c.fill();
  };
  /** A search's route: the found route dashed, the part kept after edge care solid over it. */
  Plot.prototype.searchRoute = function (s, width) {
    var r = V.route(M, s);
    if (!r.length) return;
    this.line(r, css('--found'), width, [5, 4]);
    if (s.kept > 0) this.line(r.slice(0, s.kept), css('--walk'), width + 1);
  };
  function trail(ticks, t, every) {
    var out = [];
    for (var i = 0; i <= Math.min(t, ticks.length - 1); i += every) out.push(ticks[i]);
    if (ticks.length) out.push(ticks[Math.min(t, ticks.length - 1)]);
    return out;
  }

  // ---------- explore: the map ----------
  var explorePlot = null;
  var STEP_STYLE = {
    'reached': '--stepstone', 'trying': '--stepstone', 'failed': '--bad', 'no-ground': '--muted', 'near-drop': '--warn',
    'visited': '--muted', 'over-budget': '--muted', 'unloaded': '--muted', 'no-start': '--bad',
    'no-way-back': '--bad', 'checking-back': '--muted', 'shifted': '--warn'
  };
  function drawExplore() {
    var p = explorePlot, t = st.t, R = M.recruits;
    p.ground();
    if (st.layers.heat && M.heat) {
      var n = M.SX * M.SZ, sum = new Float64Array(n), max = 1;
      for (var i = 0; i < n; i++) { sum[i] = (M.heat.follow[i] || 0) + (M.heat.detour[i] || 0); max = Math.max(max, sum[i]); }
      var heat = hexRgb(css('--heat')), lm = Math.log(1 + max);
      for (var j = 0; j < n; j++) {
        if (!sum[j]) continue;
        p.ctx.fillStyle = rgb(heat, 0.12 + 0.7 * Math.log(1 + sum[j]) / lm);
        p.ctx.fillRect((j % M.SX) * p.cell, Math.floor(j / M.SX) * p.cell, p.cell, p.cell);
      }
    }
    if (st.layers.routes) {
      var faint = hexRgb(css('--found'));
      V.routesUntil(M, st.r, t).forEach(function (ri) { p.line(M.routes[ri], rgb(faint, 0.35), 1.2); });
    }
    if (st.layers.walked) {
      // Each walk is drawn whole and faint for the run, and bold up to this tick, so where it went is always in view.
      R.forEach(function (r, i) {
        if (i === st.r || !r.ticks.length) return;
        var c = hexRgb(css(outcomeVar(r.outcome)));
        p.line(trail(r.ticks, r.ticks.length, 4), rgb(c, 0.3), 1, null, false);
        p.line(trail(r.ticks, t, 4), rgb(c, 0.9), 1.2, null, false);
        if (r.death && t >= r.ticks.length) return;
        p.dot(V.tickAt(r.ticks, t), css(outcomeVar(r.outcome)), Math.max(2, p.cell * 0.35), false);
      });
      var mine = R[st.r].ticks;
      if (mine.length) {
        var tc = hexRgb(css('--trail'));
        p.line(trail(mine, mine.length, 2), rgb(tc, 0.35), 4, null, false);
        p.line(trail(mine, t, 2), css('--trail'), 3, null, false);
        p.marker(mine[0], 'S', '--trail', false);
        var last = mine[mine.length - 1];
        if (V.flatDistance(last, mine[0]) > 1.5) p.marker(last, 'E', '--trail', false);
      }
    }
    if (st.layers.searches) {
      var walked = V.walkedAt(M, st.r, t);
      if (walked) p.searchRoute(walked, 2.5);
    }
    if (st.layers.planner) {
      var steps = V.planStepsAt(M, t), chain = { '1': [], '-1': [] };
      steps.forEach(function (s, i) {
        if (i === 0 || s.event === 'direct') chain[s.side] = chain[s.side].length ? chain[s.side] : [s.from];
        // A side that backs up leaves its last stepping stone; the chain drawn is the way it kept.
        if (s.event === 'backtrack') { chain[s.side].pop(); return; }
        if (!s.spot || s.event.indexOf('direct') === 0 || s.event === 'side-done') return;
        if (s.event === 'reached') { chain[s.side].push(s.spot); p.dot(s.spot, css('--stepstone'), Math.max(4, p.cell * 0.85)); }
        else if (s.event === 'trying') p.ring(s.spot, css('--stepstone'), Math.max(3, p.cell * 0.7));
        else p.cross(s.spot, css(STEP_STYLE[s.event] || '--muted'));
      });
      ['1', '-1'].forEach(function (k) { p.line(chain[k], css('--stepstone'), 2.2, [5, 3]); });
      // The plan's start is where the stuck recruit stood: a gold square round that block, so R stays visible.
      if (steps.length) {
        p.ctx.strokeStyle = css('--stepstone'); p.ctx.lineWidth = 2.5;
        p.ctx.strokeRect((steps[0].from[0] - 0.6) * p.cell, (steps[0].from[2] - 0.6) * p.cell, p.cell * 2.2, p.cell * 2.2);
      }
    }
    var me = V.tickAt(R[st.r].ticks, t);
    if (me) p.marker(me, 'R', '--recruit', false);
    p.marker(M.owner, 'O', '--marker');
  }

  // ---------- explore: the side panel ----------
  function buildRecruitList() {
    var h = ['<table><thead><tr><th>Recruit</th><th>Ended</th><th>Reached at</th><th>Closest</th></tr></thead><tbody>'];
    M.recruits.forEach(function (r, i) {
      h.push('<tr data-follow="' + i + '" tabindex="0"><td>' + i + '</td><td><span class="chip" style="--chip:var(' + outcomeVar(r.outcome) + ')">' + esc(r.outcome)
        + '</span>' + (r.death ? ' ' + esc(r.death.cause) : '') + '</td><td class="num">' + (r.reachedTick >= 0 ? 'tick ' + r.reachedTick : '') + '</td><td class="num">'
        + (r.closest >= 0 ? fmt(r.closest) : '') + '</td></tr>');
    });
    h.push('</tbody></table>');
    $('recruits').innerHTML = h.join('');
    var counts = V.outcomeCounts(M), c = V.cost(M);
    $('run-summary').innerHTML = Object.keys(counts).map(function (k) {
      return '<span class="chip" style="--chip:var(' + outcomeVar(k) + ')">' + esc(k) + ' ' + counts[k] + '</span>';
    }).join(' ') + ' <span class="dim">' + c.searches + ' searches, ' + c.points + ' points taken, '
      + (c.points ? Math.round(100 * c.behind / c.points) : 0) + '% of them behind the search\'s start; ' + c.repeats + ' searches repeated an earlier one exactly'
      + (c.server ? '; ' + c.server + ' ran on the server thread' : '') + '.</span>';
  }
  $('recruits').addEventListener('click', function (e) {
    var row = e.target.closest('[data-follow]'); if (!row) return;
    st.r = +row.getAttribute('data-follow'); renderExplore();
  });
  $('recruits').addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    var row = e.target.closest('[data-follow]'); if (!row) return;
    st.r = +row.getAttribute('data-follow'); renderExplore();
  });
  function sidePanel() {
    var t = st.t, r = M.recruits[st.r], here = V.tickAt(r.ticks, t), h = [];
    document.querySelectorAll('#recruits [data-follow]').forEach(function (row) { row.classList.toggle('on', +row.getAttribute('data-follow') === st.r); });
    if (!here) { $('now').innerHTML = '<p class="dim">No positions were recorded for this recruit.</p>'; return; }
    var gone = t >= r.ticks.length, f = here[4], states = [];
    if (gone) states.push(r.death ? '<span class="bad">dead since tick ' + r.death.tick + ' (' + esc(r.death.cause) + ')</span>' : 'no longer recorded');
    else {
      states.push(f & 1 ? 'following' : 'not following');
      if (f & 32) states.push('has a target to fight');
      if (f & 4) states.push('<span class="bad">waiting for its path to be searched</span>');
      if (f & 8) states.push('<span class="bad">in water</span>');
    }
    var d = V.flatDistance(here, [M.owner[0] + 0.5, 0, M.owner[2] + 0.5]);
    h.push('<p><b>Recruit ' + st.r + '</b> at ' + xyz(here.slice(0, 3).map(function (v) { return Math.round(v * 10) / 10; })) + ', '
      + fmt(d) + ' blocks from its owner: ' + states.join('; ') + '.</p>');
    var ws = V.walkSummary(M, st.r, t);
    h.push('<p>Walked ' + fmt(ws.soFar) + ' blocks by this tick, ' + fmt(ws.total) + ' in the whole run (the bold line, S to E)'
      + (ws.stillSince >= 0 && t >= ws.stillSince ? '; <span class="bad">it has not moved since tick ' + ws.stillSince + '</span>' : '') + '.</p>');
    var w = V.walkedAt(M, st.r, t);
    if (w) {
      var rl = V.route(M, w).length, link = '<button type="button" class="link" data-inspect="' + w.id + '">#' + w.id + '</button>';
      h.push(rl ? '<p>Walking the route search ' + link + ' found: ' + rl + ' points' + (w.kept >= 0 && w.kept < rl ? ', of which edge care kept the first ' + w.kept : '')
        + '; ' + (w.reached ? 'it reaches the owner' : 'it does not reach the owner') + '.</p>'
        : '<p>Its path, search ' + link + ', was still being searched when the run ended.</p>');
    }
    $('now').innerHTML = h.join('');
    // the planner
    var plan = V.planAt(M, t), steps = V.planStepsAt(M, t), counts = {};
    steps.forEach(function (s) { counts[s.event] = (counts[s.event] || 0) + 1; });
    var pid = steps.length ? steps[0].plan : -1, repeatOf = pid >= 0 && M.repeats ? M.repeats[pid] : undefined;
    var ph = ['<p>' + (plan ? 'Detour plan' + (pid >= 0 ? ' ' + (pid + 1) + ' of ' + V.planCount(M) : '') + ' for the owner: <b>' + esc(plan.status) + '</b>, ' + plan.searches + ' searches so far'
      + (plan.route && plan.route.length ? ', ' + plan.route.length + ' stepping stones' : '') : 'No detour plan yet') + '.'
      + (repeatOf !== undefined ? ' <span class="bad">Identical to plan ' + (repeatOf + 1) + ': the same spots tried, with the same result.</span>' : '') + '</p>'];
    if (steps.length) {
      ph.push('<p class="dim">This plan\'s steps so far: ' + Object.keys(counts).map(function (k) { return esc(k) + ' ' + counts[k]; }).join(', ') + '.</p><ol class="steps">');
      steps.slice(-8).forEach(function (s) {
        ph.push('<li><span class="dim">tick ' + s.tick + ', ' + (s.side === 1 ? 'left' : 'right') + ' side:</span> ' + esc(V.STEP_WORDS[s.event] || s.event)
          + (s.spot && s.event.indexOf('direct') !== 0 ? ' ' + xyz(s.spot) : '') + '</li>');
      });
      ph.push('</ol>');
    }
    $('planner').innerHTML = ph.join('');
    // searches near this tick
    // The searches nearest this tick that kept their points (so they can be opened point by point), then the nearest others.
    var around = V.searchesNear(M, st.r, t, 400);
    var opened = around.filter(function (s) { return V.pointsSource(M, s); });
    opened.sort(function (a, b) { return Math.abs(a.tick - t) - Math.abs(b.tick - t); });
    var rest = around.filter(function (s) { return !V.pointsSource(M, s); });
    rest.sort(function (a, b) { return Math.abs(a.tick - t) - Math.abs(b.tick - t); });
    var near = opened.slice(0, 6).concat(rest.slice(0, 4)).sort(function (a, b) { return a.id - b.id; }), nh = ['<ul class="near">'];
    near.forEach(function (s) {
      var kind = s.own ? (s.detail ? 'every number' : 'points') : s.sameAs !== undefined ? 'same as #' + s.sameAs : 'counts only';
      nh.push('<li><button type="button" class="link" data-inspect="' + s.id + '">#' + s.id + '</button> tick ' + s.tick + ', ' + esc(s.kind) + ', '
        + (s.reached ? '<span class="good">reached</span>' : 'not reached') + ', ' + s.n + ' points <span class="dim">(' + kind + ')</span></li>');
    });
    $('near').innerHTML = near.length ? nh.join('') + '</ul>' : '<p class="dim">This recruit made no searches.</p>';
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-inspect]'); if (!b) return;
    st.s = +b.getAttribute('data-inspect'); st.k = 0; st.sel = null;
    if (location.hash !== '#search') location.hash = '#search'; else layoutSearch();
  });

  // ---------- explore: the timeline ----------
  var tl = null;
  function drawTimeline() {
    var cv = $('timeline'), w = cv.parentElement.clientWidth || 600, h = 170, padL = 34, padB = 18, last = Math.max(1, V.lastTick(M));
    var c = sizeCanvas(cv, w, h), series = [], max = 1;
    M.recruits.forEach(function (r, i) { var s = V.distanceSeries(M, i, Math.max(1, Math.round(last / 400))); series.push(s); s.forEach(function (q) { max = Math.max(max, q[1]); }); });
    var sx = function (t) { return padL + (w - padL - 6) * t / last; }, sy = function (d) { return 6 + (h - padB - 10) * (1 - d / max); };
    tl = { sx: sx, padL: padL, w: w, last: last };
    c.fillStyle = css('--surface'); c.fillRect(0, 0, w, h);
    c.strokeStyle = css('--line'); c.lineWidth = 1; c.font = '10px JetBrains Mono, monospace'; c.fillStyle = css('--muted'); c.textBaseline = 'middle'; c.textAlign = 'right';
    [0, max / 2, max].forEach(function (d) { c.beginPath(); c.moveTo(padL, sy(d)); c.lineTo(w - 6, sy(d)); c.stroke(); c.fillText(Math.round(d) + '', padL - 4, sy(d)); });
    c.textAlign = 'center'; c.textBaseline = 'top';
    [0, Math.round(last / 2), last].forEach(function (t) { c.fillText('tick ' + t, Math.min(w - 30, Math.max(padL + 20, sx(t))), h - padB + 4); });
    M.plans.forEach(function (p) {
      if (p.status !== 'FOUND' && p.status !== 'FAILED') return;
      c.strokeStyle = css(p.status === 'FOUND' ? '--good' : '--bad'); c.globalAlpha = 0.5;
      c.beginPath(); c.moveTo(sx(p.tick), 6); c.lineTo(sx(p.tick), h - padB); c.stroke(); c.globalAlpha = 1;
    });
    series.forEach(function (s, i) {
      if (i === st.r || !s.length) return;
      c.strokeStyle = css(outcomeVar(M.recruits[i].outcome)); c.lineWidth = 1; c.globalAlpha = 0.55;
      c.beginPath(); s.forEach(function (q, j) { if (j) c.lineTo(sx(q[0]), sy(q[1])); else c.moveTo(sx(q[0]), sy(q[1])); }); c.stroke(); c.globalAlpha = 1;
    });
    var mine = series[st.r];
    if (mine && mine.length) {
      c.strokeStyle = css('--trail'); c.lineWidth = 2.2;
      c.beginPath(); mine.forEach(function (q, j) { if (j) c.lineTo(sx(q[0]), sy(q[1])); else c.moveTo(sx(q[0]), sy(q[1])); }); c.stroke();
    }
    c.strokeStyle = css('--fg'); c.lineWidth = 1.5; c.beginPath(); c.moveTo(sx(st.t), 4); c.lineTo(sx(st.t), h - padB); c.stroke();
  }
  $('timeline').addEventListener('click', function (e) {
    if (!tl) return;
    var r = e.currentTarget.getBoundingClientRect(), x = e.clientX - r.left;
    st.t = Math.max(0, Math.min(tl.last, Math.round((x - tl.padL) / (tl.w - tl.padL - 6) * tl.last)));
    renderExplore();
  });

  // ---------- explore: controls ----------
  ['walked', 'searches', 'routes', 'planner', 'heat'].forEach(function (k) {
    var box = $('ly-' + k); box.checked = st.layers[k];
    box.addEventListener('change', function () { st.layers[k] = box.checked; renderExplore(); });
  });
  $('tick').addEventListener('input', function (e) { st.t = +e.target.value; renderExplore(); });
  $('play').addEventListener('click', function () { togglePlay('T'); });
  $('map').addEventListener('mousemove', function (e) {
    if (!explorePlot) return;
    var r = e.currentTarget.getBoundingClientRect(), x = Math.floor((e.clientX - r.left) / explorePlot.cell), z = Math.floor((e.clientY - r.top) / explorePlot.cell);
    if (x < 0 || z < 0 || x >= M.SX || z >= M.SZ) return;
    var heat = M.heat ? (M.heat.follow[z * M.SX + x] || 0) + (M.heat.detour[z * M.SX + x] || 0) : 0;
    $('hover').textContent = 'x ' + x + ', z ' + z + ': ground at height ' + V.standAt(M, x, z) + (M.wet[z * M.SX + x] === 1 ? ', water' : M.wet[z * M.SX + x] === 2 ? ', lava' : '')
      + (heat ? '; searches took a point here ' + heat + ' times' : '');
  });
  function layoutExplore() {
    if (!M) return;
    explorePlot = new Plot($('map'), 900);
    $('tick').max = V.lastTick(M); $('tick').value = st.t;
    renderExplore();
  }
  function renderExplore() {
    if (!M || $('v-explore').hidden) return;
    $('tick').value = st.t;
    $('tickno').textContent = 'tick ' + st.t + ' of ' + V.lastTick(M);
    $('run-title').textContent = INDEX.runs[st.run].label;
    drawExplore(); sidePanel(); drawTimeline();
  }

  // ---------- playing ----------
  function stopPlay() {
    ['T', 'S'].forEach(function (w) { if (st['play' + w]) { clearInterval(st['play' + w]); st['play' + w] = null; } });
    $('play').textContent = 'Play'; $('s-play').textContent = 'Play';
  }
  function togglePlay(which) {
    var btn = which === 'T' ? $('play') : $('s-play');
    if (st['play' + which]) { clearInterval(st['play' + which]); st['play' + which] = null; btn.textContent = 'Play'; return; }
    btn.textContent = 'Pause';
    st['play' + which] = setInterval(function () {
      if (which === 'T') {
        var last = V.lastTick(M);
        if (st.t >= last) { togglePlay('T'); return; }
        st.t = Math.min(last, st.t + +$('speed').value); renderExplore();
      } else {
        var n = V.points(M, M.searches[st.s]).length;
        if (st.k >= n - 1) { togglePlay('S'); return; }
        st.k = Math.min(n - 1, st.k + +$('s-speed').value); renderSearch();
      }
    }, 60);
  }

  // ---------- search: one search, point by point ----------
  var searchPlot = null;
  function S() { return M.searches[st.s]; }
  function PTS() { return V.points(M, S()); }
  function layoutSearch() {
    if (!M) return;
    if (st.s < 0 || !M.searches[st.s]) {
      // Open on the followed recruit's search nearest this tick that kept its points, else the one it walks.
      var t = st.t, withPoints = V.searchesNear(M, st.r, t, 400).filter(function (s) { return V.pointsSource(M, s); });
      withPoints.sort(function (a, b) { return Math.abs(a.tick - t) - Math.abs(b.tick - t); });
      var w = withPoints[0] || V.walkedAt(M, st.r, t) || V.searchesNear(M, st.r, t, 1)[0];
      st.s = w ? w.id : 0; st.k = 0;
    }
    searchPlot = new Plot($('s-map'), 640);
    var n = PTS().length;
    st.k = Math.min(st.k, Math.max(0, n - 1));
    $('s-step').max = Math.max(0, n - 1); $('s-step').value = st.k;
    renderSearch(); rebuild3d();
  }
  $('s-step').addEventListener('input', function (e) { st.k = +e.target.value; renderSearch(); });
  $('s-prev').addEventListener('click', function () { st.k = Math.max(0, st.k - 1); renderSearch(); });
  $('s-next').addEventListener('click', function () { st.k = Math.min(Math.max(0, PTS().length - 1), st.k + 1); renderSearch(); });
  $('s-end').addEventListener('click', function () { st.k = Math.max(0, PTS().length - 1); renderSearch(); });
  $('s-play').addEventListener('click', function () { togglePlay('S'); });
  $('s-color').addEventListener('change', function (e) { st.color = e.target.value; renderSearch(); });
  $('s-back').addEventListener('click', function () { location.hash = '#explore'; });
  $('s-map').addEventListener('click', function (e) {
    var r = e.currentTarget.getBoundingClientRect(), x = Math.floor((e.clientX - r.left) / searchPlot.cell), z = Math.floor((e.clientY - r.top) / searchPlot.cell);
    if (x < 0 || z < 0 || x >= M.SX || z >= M.SZ) return;
    st.sel = [x, z]; renderSearch(); update3dCut();
  });
  function pointColor(i, p, pts) {
    var a = hexRgb(css('--pop-a')), b = hexRgb(css('--pop-b')), t = i / Math.max(1, pts.length - 1);
    if (st.color !== 'order' && p.f !== undefined) {
      var key = st.color, max = 1;
      pts.forEach(function (q) { max = Math.max(max, q[key]); });
      t = p[key] / max;
    }
    return rgb(mix(a, b, t));
  }
  function drawSearch() {
    var p = searchPlot, c = p.ctx, cell = p.cell, s = S(), pts = PTS(), k = Math.min(st.k, pts.length - 1), inset = Math.max(1, cell * 0.18);
    p.ground();
    for (var i = 0; i <= k; i++) {
      c.fillStyle = pointColor(i, pts[i], pts);
      c.fillRect(pts[i].p[0] * cell + inset, pts[i].p[2] * cell + inset, cell - 2 * inset, cell - 2 * inset);
    }
    var cur = k >= 0 ? pts[k] : null;
    if (cur && cur.nb) {
      c.strokeStyle = css('--open'); c.lineWidth = 1;
      cur.nb.forEach(function (n) { c.strokeRect(n.p[0] * cell + 1.5, n.p[2] * cell + 1.5, cell - 3, cell - 3); });
      (cur.cand || []).forEach(function (cd) { if (cd.r !== 'accepted') p.cross(cd.ask, css('--bad')); });
    }
    if (cur) { c.strokeStyle = css('--fg'); c.lineWidth = 2.2; c.strokeRect(cur.p[0] * cell, cur.p[2] * cell, cell, cell); }
    p.searchRoute(s, 2.5);
    if (s.start) p.marker(s.start, 'S', '--marker');
    if (s.target) p.marker(s.target, s.kind === 'detour' ? 'D' : 'T', '--marker');
    if (st.sel) { c.setLineDash([3, 2]); c.strokeStyle = css('--fg'); c.lineWidth = 1.5; c.strokeRect(st.sel[0] * cell, st.sel[1] * cell, cell, cell); c.setLineDash([]); }
  }
  function searchPanel() {
    var s = S(), pts = PTS(), k = Math.min(st.k, pts.length - 1), p = k >= 0 ? pts[k] : null, rl = V.route(M, s).length, h = [];
    h.push('<p><b>Search #' + s.id + '</b>, recruit ' + s.r + ', tick ' + s.tick + ', ' + esc(s.kind) + ' search from ' + xyz(s.start) + ' to ' + xyz(s.target) + ': '
      + (s.reached ? '<span class="good">reached its target</span>' : '<span class="bad">did not reach its target</span>') + '. It took ' + s.n + ' points off its open set'
      + (s.n ? ', ' + Math.round(100 * (s.behind || 0) / s.n) + '% of them behind its start' : '') + '. Its found route has ' + rl + ' points'
      + (s.kept >= 0 ? (s.kept < rl ? '; edge care kept the first ' + s.kept : '; edge care kept all of it') : '; no recruit walked it') + '.</p>');
    if (!s.own && s.sameAs !== undefined) h.push('<p class="note">This search is identical to search <button type="button" class="link" data-inspect="' + s.sameAs + '">#' + s.sameAs
      + '</button>: same start, target, points and route. Its points are the ones shown.</p>');
    if (!pts.length) h.push('<p class="note">Only this search\'s counts were kept, to keep long runs small: its found route is drawn, but not the points it looked at. Searches marked "points" or "every number" in the list keep them.</p>');
    $('s-head').innerHTML = h.join('');
    var m = [];
    if (!p) { $('s-panel').innerHTML = ''; return; }
    m.push('<div class="kv"><span>Point</span><b>' + (k + 1) + ' of ' + pts.length + '</b><span>At</span><b>' + xyz(p.p) + '</b></div>');
    if (p.f === undefined) {
      m.push('<p class="explain">This search kept where each point was, not its numbers. ' + (p.expanded ? 'The search took this point off its open set and asked for its neighbours.'
        : 'This point was closed without asking for its neighbours: it reached the target, or lay beyond the 32-block limit.') + '</p>');
      $('s-panel').innerHTML = m.join(''); return;
    }
    m.push('<div class="eq">f = g + h = <b>' + fmt(p.g) + '</b> + <b>' + fmt(p.h) + '</b> = <b>' + fmt(p.f) + '</b><br><span class="dim">g: what walking here cost; h: 1.5 × the straight distance left to the target'
      + (k === 0 ? ' (the start uses the plain distance)' : '') + '. The search always takes the open point with the lowest f next.</span></div>');
    m.push('<div class="eq">cost of this spot (costMalus) = <b>' + fmt(p.m) + '</b><br><span class="dim">edge part: ' + (p.ed ? 'a deadly drop ' + p.ed + ' block' + (p.ed > 1 ? 's' : '') + ' away → 8 × ('
      + M.margin + ' − ' + p.ed + ' + 1) / ' + M.margin + ' = ' + fmt(p.ec) : 'no deadly drop within ' + M.margin + ' → 0') + '; the rest is the block\'s own cost and the dirt-path cost</span></div>');
    if (!p.expanded) {
      m.push('<p class="explain">Closed without asking for neighbours: it is within a block of the target, or beyond the 32-block limit.</p>');
      $('s-panel').innerHTML = m.join(''); return;
    }
    m.push('<h4>Its neighbours</h4><div class="scroll"><table class="math"><thead><tr><th>Spot</th><th>step</th><th>cost</th><th>g + step + cost</th><th>result</th></tr></thead><tbody>');
    p.nb.forEach(function (n) {
      var step = dist3(p.p, n.p), g = p.g + step + n.m, res;
      if (n.upd && !n.open) res = '<span class="good">added</span>';
      else if (n.upd) res = '<span class="good">cheaper way found</span>';
      else if (n.open) res = 'kept its cheaper ' + fmt(n.g0);
      else res = '<span class="bad">not added: beyond 32 blocks of walking</span>';
      m.push('<tr><td>' + xyz(n.p) + '</td><td>' + fmt(step) + '</td><td>' + fmt(n.m) + '</td><td>' + fmt(p.g) + ' + ' + fmt(step) + ' + ' + fmt(n.m) + ' = <b>' + fmt(g) + '</b></td><td>' + res + '</td></tr>');
    });
    m.push('</tbody></table></div><h4>Steps it asked about</h4><ul class="cands">');
    p.cand.forEach(function (cd) {
      var dy = cd.at[1] - cd.ask[1], what;
      if (cd.r === 'refused') what = '<span class="bad">refused</span>: a drop of 2 or more onto a spot beside a deadly drop, or into a dead end';
      else if (cd.r === 'blocked') what = '<span class="bad">blocked</span>: a wall, a fall deeper than ' + M.maxFall + ', or a block it may not enter';
      else what = 'accepted at ' + xyz(cd.at) + (dy > 0 ? ', a step up' : dy < 0 ? ', a fall of ' + (-dy) : '') + ', cost ' + fmt(cd.m);
      m.push('<li><b>' + esc(cd.d) + '</b> ' + xyz(cd.ask) + ': ' + what + (cd.de ? '; the dead-end check walked ' + cd.de + ' spots' : '') + '</li>');
    });
    m.push('</ul>');
    $('s-panel').innerHTML = m.join('');
  }
  function renderSearch() {
    if (!M || $('v-search').hidden) return;
    $('s-step').value = st.k;
    var n = PTS().length;
    $('s-stepno').textContent = n ? 'point ' + (Math.min(st.k, n - 1) + 1) + ' of ' + n : 'no points kept';
    drawSearch(); searchPanel(); drawColumn(); drawSections(); update3d();
  }

  // ---------- search: block checks ----------
  function selected() { if (st.sel) return st.sel; var pts = PTS(), p = pts[Math.min(st.k, pts.length - 1)]; return p ? [p.p[0], p.p[2]] : [M.SX >> 1, M.SZ >> 1]; }
  function blockColor(c) { return c === '#' ? css('--stone') : c === 'f' ? css('--leaf') : c === 'w' ? css('--water') : c === 'l' ? css('--lava') : css('--air'); }
  function drawColumn() {
    var at = selected(), x = at[0], z = at[1], u = Math.max(4, Math.min(14, Math.floor(560 / M.SY)));
    var c = sizeCanvas($('column'), 150, M.SY * u + 4), pts = PTS(), p = null;
    pts.forEach(function (q, i) { if (i <= st.k && q.p[0] === x && q.p[2] === z) p = q; });
    c.font = '10px JetBrains Mono, monospace'; c.textBaseline = 'middle';
    for (var y = 0; y < M.SY; y++) {
      var top = (M.SY - 1 - y) * u + 2;
      c.fillStyle = blockColor(V.blockAt(M, x, y, z)); c.fillRect(40, top, 40, u - 1);
      if (y % 4 === 0) { c.fillStyle = css('--muted'); c.textAlign = 'right'; c.fillText(String(y), 34, top + u / 2); }
    }
    if (p) {
      var ft = (M.SY - 1 - p.p[1]) * u + 2;
      c.strokeStyle = css('--fg'); c.lineWidth = 2; c.strokeRect(40, ft, 40, u - 1);
      var from = (M.SY - p.p[1]) * u + 2, to = (M.SY - p.p[1] + M.maxFall + 1) * u + 2;
      c.strokeStyle = css('--drop-line'); c.lineWidth = 1.5; c.beginPath(); c.moveTo(84, from); c.lineTo(90, from); c.lineTo(90, to); c.lineTo(84, to); c.stroke();
      c.fillStyle = css('--fg'); c.textAlign = 'left'; c.fillText('drop check', 94, (from + to) / 2);
    }
    $('colinfo').innerHTML = '<p><b>Column x ' + x + ', z ' + z + '</b>: ground at height ' + V.standAt(M, x, z) + (M.wet[z * M.SX + x] ? ', water or lava on it' : '') + '.</p>'
      + (p ? '<p>The search stood here with feet at y ' + p.p[1] + '. The bracket is what the drop check looks at: up to ' + (M.maxFall + 1) + ' blocks below the feet.</p>'
        : '<p class="dim">Click a column on the map to see its blocks; the search took no point here up to this step.</p>');
  }
  function drawSection(cv, along, fixed) {
    var n = along === 'x' ? M.SX : M.SZ, w = Math.min(cv.parentElement.clientWidth || 600, 900), u = Math.max(3, Math.floor(w / n));
    var c = sizeCanvas(cv, u * n, u * M.SY), pts = PTS(), s = S();
    for (var i = 0; i < n; i++) for (var y = 0; y < M.SY; y++) {
      c.fillStyle = blockColor(along === 'x' ? V.blockAt(M, i, y, fixed) : V.blockAt(M, fixed, y, i)); c.fillRect(i * u, (M.SY - 1 - y) * u, u, u);
    }
    for (var k = 0; k <= Math.min(st.k, pts.length - 1); k++) {
      var p = pts[k];
      if (along === 'x' ? p.p[2] !== fixed : p.p[0] !== fixed) continue;
      c.fillStyle = pointColor(k, p, pts); c.fillRect((along === 'x' ? p.p[0] : p.p[2]) * u + u * 0.2, (M.SY - 1 - p.p[1]) * u + u * 0.2, u * 0.6, u * 0.6);
    }
    c.fillStyle = css('--found');
    V.route(M, s).forEach(function (q) {
      if (along === 'x' ? q[2] !== fixed : q[0] !== fixed) return;
      c.beginPath(); c.arc(((along === 'x' ? q[0] : q[2]) + 0.5) * u, (M.SY - 1 - q[1] + 0.5) * u, u * 0.35, 0, Math.PI * 2); c.fill();
    });
    var at = selected(), mark = along === 'x' ? at[0] : at[1];
    c.strokeStyle = css('--fg'); c.setLineDash([3, 2]); c.strokeRect(mark * u, 0, u, M.SY * u); c.setLineDash([]);
  }
  function drawSections() {
    var at = selected();
    $('secx-label').textContent = 'Side view along x, at z ' + at[1] + ' (west on the left)';
    $('secz-label').textContent = 'Side view along z, at x ' + at[0] + ' (north on the left)';
    drawSection($('secx'), 'x', at[1]); drawSection($('secz'), 'z', at[0]);
  }

  // ---------- search: 3D ----------
  var three = null, world3d = null;
  function init3d() {
    var host = $('view3d');
    if (!window.THREE) { host.innerHTML = '<p class="dim" style="padding:12px">The 3D view needs three.js, which did not load.</p>'; return; }
    var THREE = window.THREE, renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.localClippingEnabled = true;
    host.appendChild(renderer.domElement);
    var scene = new THREE.Scene();
    scene.add(new THREE.HemisphereLight(0xffffff, 0x445544, 0.85));
    var sun = new THREE.DirectionalLight(0xffffff, 0.55); sun.position.set(40, 120, 20); scene.add(sun);
    three = { THREE: THREE, renderer: renderer, scene: scene, camera: new THREE.PerspectiveCamera(45, 1, 0.5, 800), host: host,
      clip: new THREE.Plane(new THREE.Vector3(-1, 0, 0), 9999), world: new THREE.Group(), search: new THREE.Group(),
      yaw: -0.7, pitch: 0.75, dist: 100, target: new THREE.Vector3(32, 8, 32) };
    scene.add(three.world); scene.add(three.search);
    var drag = null, el = renderer.domElement;
    el.addEventListener('pointerdown', function (e) { drag = [e.clientX, e.clientY]; el.setPointerCapture(e.pointerId); });
    el.addEventListener('pointermove', function (e) {
      if (!drag) return;
      three.yaw -= (e.clientX - drag[0]) * 0.008; three.pitch = Math.max(0.08, Math.min(1.45, three.pitch + (e.clientY - drag[1]) * 0.006));
      drag = [e.clientX, e.clientY]; draw3d();
    });
    el.addEventListener('pointerup', function () { drag = null; });
    el.addEventListener('wheel', function (e) { e.preventDefault(); three.dist = Math.max(15, Math.min(400, three.dist * (e.deltaY > 0 ? 1.1 : 0.9))); draw3d(); }, { passive: false });
  }
  function clearGroup(g) { while (g.children.length) { var o = g.children.pop(); if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); } }
  function color3(v) { var c = hexRgb(css(v)); return new three.THREE.Color(c[0] / 255, c[1] / 255, c[2] / 255); }
  function rebuild3d() {
    if (!three) return;
    var THREE = three.THREE;
    var w = three.host.clientWidth || 600, h = Math.max(300, Math.min(520, Math.round(w * 0.6)));
    three.renderer.setPixelRatio(window.devicePixelRatio || 1); three.renderer.setSize(w, h);
    three.camera.aspect = w / h; three.camera.updateProjectionMatrix();
    if (world3d !== M) {
      world3d = M; clearGroup(three.world);
      three.target.set(M.SX / 2, (M.minStand + M.maxStand) / 2, M.SZ / 2);
      three.dist = Math.max(60, Math.max(M.SX, M.SZ) * 1.45);
      var solids = [], waters = [], leaves = [];
      for (var x = 1; x < M.SX - 1; x++) for (var z = 1; z < M.SZ - 1; z++) for (var y = 0; y < M.SY; y++) {
        var c = V.blockAt(M, x, y, z);
        if (c === '.') continue;
        var open = V.blockAt(M, x, y + 1, z) !== '#' || V.blockAt(M, x + 1, y, z) !== '#' || V.blockAt(M, x - 1, y, z) !== '#' || V.blockAt(M, x, y, z + 1) !== '#' || V.blockAt(M, x, y, z - 1) !== '#';
        if (open) (c === '#' ? solids : c === 'f' ? leaves : waters).push([x, y, z]);
      }
      var box = new THREE.BoxGeometry(1, 1, 1), mx = new THREE.Matrix4(), col = new THREE.Color(), lo = hexRgb(css('--t-low')), hi = hexRgb(css('--t-high'));
      var mesh = new THREE.InstancedMesh(box, new THREE.MeshLambertMaterial({ clippingPlanes: [three.clip] }), Math.max(1, solids.length));
      solids.forEach(function (b, i) {
        mx.makeTranslation(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5); mesh.setMatrixAt(i, mx);
        var cc = mix(lo, hi, Math.max(0, Math.min(1, (b[1] - (M.minStand - 6)) / Math.max(1, M.maxStand - M.minStand + 6))));
        col.setRGB(cc[0] / 255, cc[1] / 255, cc[2] / 255); mesh.setColorAt(i, col);
      });
      three.world.add(mesh);
      [[waters, '--water', 0.55], [leaves, '--leaf', 0.6]].forEach(function (set) {
        if (!set[0].length) return;
        var im = new THREE.InstancedMesh(box, new THREE.MeshLambertMaterial({ color: color3(set[1]), transparent: true, opacity: set[2], clippingPlanes: [three.clip] }), set[0].length);
        set[0].forEach(function (b, i) { mx.makeTranslation(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5); im.setMatrixAt(i, mx); });
        three.world.add(im);
      });
    }
    clearGroup(three.search);
    var pts = PTS(), pos = new Float32Array(Math.max(1, pts.length) * 3), cols = new Float32Array(Math.max(1, pts.length) * 3), a = hexRgb(css('--pop-a')), b = hexRgb(css('--pop-b'));
    pts.forEach(function (p, i) {
      pos[i * 3] = p.p[0] + 0.5; pos[i * 3 + 1] = p.p[1] + 0.15; pos[i * 3 + 2] = p.p[2] + 0.5;
      var c = mix(a, b, i / Math.max(1, pts.length - 1)); cols[i * 3] = c[0] / 255; cols[i * 3 + 1] = c[1] / 255; cols[i * 3 + 2] = c[2] / 255;
    });
    var g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('color', new THREE.BufferAttribute(cols, 3));
    three.points = new THREE.Points(g, new THREE.PointsMaterial({ size: 0.45, vertexColors: true })); three.search.add(three.points);
    var r = V.route(M, S());
    if (r.length > 1) three.search.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(r.map(function (q) { return new THREE.Vector3(q[0] + 0.5, q[1] + 0.35, q[2] + 0.5); })),
      new THREE.LineBasicMaterial({ color: color3('--found') })));
    three.cursor = new THREE.Mesh(new THREE.BoxGeometry(1.05, 0.2, 1.05), new THREE.MeshBasicMaterial({ color: color3('--fg') }));
    three.search.add(three.cursor);
    update3d();
  }
  function update3d() {
    if (!three || !three.points) return;
    var pts = PTS(); three.points.geometry.setDrawRange(0, Math.min(st.k, pts.length - 1) + 1);
    var p = pts[Math.min(st.k, pts.length - 1)]; if (p) three.cursor.position.set(p.p[0] + 0.5, p.p[1] + 0.1, p.p[2] + 0.5);
    draw3d();
  }
  function update3dCut() { if (!three) return; three.clip.constant = st.cut ? selected()[0] + 1 : 9999; draw3d(); }
  $('cut').addEventListener('change', function (e) { st.cut = e.target.checked; update3dCut(); });
  function draw3d() {
    if (!three) return;
    var t = three.target, d = three.dist;
    three.camera.position.set(t.x + d * Math.cos(three.pitch) * Math.cos(three.yaw), t.y + d * Math.sin(three.pitch), t.z + d * Math.cos(three.pitch) * Math.sin(three.yaw));
    three.camera.lookAt(t); three.scene.background = color3('--surface');
    three.renderer.render(three.scene, three.camera);
  }

  // ---------- start ----------
  window.addEventListener('resize', function () {
    if (!M) return;
    if (!$('v-explore').hidden) layoutExplore();
    if (!$('v-search').hidden) layoutSearch();
  });
  if (window.matchMedia) window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () { terrain = null; world3d = null; showView(); });
  try { init3d(); } catch (e) { $('view3d').innerHTML = '<p class="dim" style="padding:12px">The 3D view could not start here: ' + esc(e.message) + '</p>'; three = null; }
  showView();
  window.__viz = { openRun: openRun, state: st, model: function () { return M; }, render: function () { renderExplore(); renderSearch(); } };
})();
