/*
 * The visualiser's data layer: turns one run recording (written by GroupRecorder) into what the page
 * shows, with no drawing and no page access, so it runs under node too (test_vizdata.js tests it on
 * real recordings). viz.js draws from it.
 *
 * Ticks: a recruit's positions, the detour plans and the planner's steps are counted in test ticks
 * from the test's start; a search's tick is the world's clock, so load() moves it by the recording's
 * tickOffset onto the same count.
 */
(function (root) {
  'use strict';

  var DIR = { n: 'north', s: 'south', e: 'east', w: 'west', u: 'up', d: 'down' };
  var RES = { a: 'accepted', r: 'refused', b: 'blocked' };

  /** What each planner step means, in plain words. */
  var STEP_WORDS = {
    'direct': 'checks for a walkable path from here to the owner',
    'direct-reached': 'found a walkable path to the owner: the plan is found',
    'direct-failed': 'no walkable path to the owner from here',
    'direct-no-start': 'could not start a search from here',
    'unloaded': 'the spot\'s chunk is not loaded',
    'no-ground': 'nothing to stand on near this spot',
    'near-drop': 'every spot here is near a deadly drop',
    'visited': 'too close to a spot already visited',
    'over-budget': 'past the detour\'s distance limit',
    'trying': 'searching for a way to this spot',
    'reached': 'reached this spot: it becomes a stepping stone',
    'failed': 'no way to this spot found',
    'no-start': 'could not start a search to this spot',
    'side-done': 'this side has no spots left to try'
  };

  /** Loads a recording into the model the page draws from. */
  function load(data) {
    var SX = data.size[0], SY = data.size[1], SZ = data.size[2];
    var m = {
      name: data.test, outcome: data.outcome, message: data.message || '', seed: data.seed, SX: SX, SY: SY, SZ: SZ,
      blocks: data.blocks, owner: data.owner, offset: data.tickOffset || 0, maxFall: data.fallDistance, margin: data.margin,
      range: data.range, budget: data.budget, routes: data.routes || [], heat: data.heat || null, queue: data.queue || null,
      threads: data.threads, dirtPath: data.dirtPath, setup: data.setup || null, raw: data.searches || []
    };
    m.stand = new Int16Array(SX * SZ);
    m.wet = new Uint8Array(SX * SZ);
    m.leaf = new Uint8Array(SX * SZ);
    m.minStand = 1e9; m.maxStand = -1e9;
    for (var x = 0; x < SX; x++) for (var z = 0; z < SZ; z++) {
      var s = 0, w = 0, f = 0;
      for (var y = SY - 1; y >= 0; y--) {
        var c = m.blocks.charAt((y * SZ + z) * SX + x);
        if (c === 'f') { f = 1; continue; }  // leaves are foliage over the ground, not ground
        if (c === 'w') w = 1;
        if (c === 'l') w = 2;
        if (c === '#') { s = y + 1; break; }
      }
      m.stand[z * SX + x] = s; m.wet[z * SX + x] = w; m.leaf[z * SX + x] = f;
      if (!isEdge(m, x, z)) { m.minStand = Math.min(m.minStand, s); m.maxStand = Math.max(m.maxStand, s); }
    }
    var list = data.recruits && data.recruits.length ? data.recruits
      : [{ id: 0, ticks: data.ticks || [], outcome: data.outcome, reachedTick: -1, closest: -1, death: null, spawn: null }];
    m.recruits = list.map(function (r, i) {
      return { id: i, outcome: r.outcome, ticks: r.ticks || [], reachedTick: r.reachedTick, closest: r.closest, death: r.death,
        spawn: r.spawn, searchIds: [] };
    });
    m.searches = m.raw.map(function (s, i) {
      return {
        id: i, r: s.r === undefined ? 0 : s.r, kind: s.kind, tick: s.tick - m.offset, start: s.start, target: s.target,
        reached: s.reached, n: s.n || 0, expanded: s.x, behind: s.behind, server: !!s.server, done: s.done, noStart: s.noStart,
        routeIndex: s.found === undefined ? -1 : s.found, kept: s.kept === undefined ? -1 : s.kept, keptIsStart: s.keptIsStart,
        detail: !!s.detail, sameAs: s.same, own: Array.isArray(s.pops)
      };
    });
    m.searches.forEach(function (s) { if (m.recruits[s.r]) m.recruits[s.r].searchIds.push(s.id); });
    m.plans = (data.plans || []).map(function (p) { return { tick: p[0], status: p[1], searches: p[2], route: p[3] }; });
    m.steps = (data.planSteps || []).map(function (r) { return { tick: r[0], plan: r[1], side: r[2], event: r[3], from: r[4], spot: r[5] }; });
    return m;
  }

  function isEdge(m, x, z) { return x === 0 || z === 0 || x === m.SX - 1 || z === m.SZ - 1; }
  function blockAt(m, x, y, z) {
    if (x < 0 || x >= m.SX || y < 0 || y >= m.SY || z < 0 || z >= m.SZ) return '#';
    return m.blocks.charAt((y * m.SZ + z) * m.SX + x);
  }
  function standAt(m, x, z) { return (x < 0 || z < 0 || x >= m.SX || z >= m.SZ) ? 0 : m.stand[z * m.SX + x]; }

  /** The route a search found, start first; empty when none was recorded. */
  function route(m, s) { return s && s.routeIndex >= 0 ? m.routes[s.routeIndex] : []; }

  /** The search whose points are shown for {@code s}: itself, the earlier identical one, or null when only counts were kept. */
  function pointsSource(m, s) {
    if (!s) return null;
    if (s.own) return s;
    if (s.sameAs !== undefined && m.searches[s.sameAs] && m.searches[s.sameAs].own) return m.searches[s.sameAs];
    return null;
  }

  /** The points a search took off its open set, in order, decoded once. */
  function points(m, s) {
    var src = pointsSource(m, s);
    if (!src) return [];
    if (src._points) return src._points;
    var raw = m.raw[src.id].pops;
    src._points = raw.map(function (a) {
      if (a.length >= 12) {
        return { p: [a[0], a[1], a[2]], g: a[3], h: a[4], f: a[5], m: a[6], ed: a[7], ring: a[8], expanded: a[8] !== '-', from: a[9],
          ec: a[7] > 0 && m.margin > 0 ? 8 * (m.margin - a[7] + 1) / m.margin : 0,
          cand: a[10].map(function (c) { return { d: DIR[c[0]] || c[0], r: RES[c[1]] || c[1], ask: [c[2], c[3], c[4]], at: [c[2], c[5], c[4]], m: c[6], de: c[7] }; }),
          nb: a[11].map(function (n) { return { p: [n[0], n[1], n[2]], m: n[3], g0: n[4], open: n[5] === 1, g: n[6], h: n[7], f: n[8], upd: n[9] === 1 }; }) };
      }
      if (a.length === 5) return { p: [a[0], a[1], a[2]], expanded: a[3] === 1, from: a[4] };
      return { p: [a[0], a[1], a[2]], expanded: a.length === 3, from: -1 };
    });
    return src._points;
  }

  /** The last tick any recruit was recorded at. */
  function lastTick(m) {
    return m.recruits.reduce(function (most, r) { return Math.max(most, r.ticks.length - 1); }, 0);
  }

  function tickAt(ticks, t) { return ticks.length ? ticks[Math.min(Math.max(0, t), ticks.length - 1)] : null; }

  function flatDistance(a, b) { var dx = a[0] - b[0], dz = a[2] - b[2]; return Math.sqrt(dx * dx + dz * dz); }

  /** A recruit's distance to the owner (on the ground plane, block centres) every {@code every} ticks, as [tick, distance]. */
  function distanceSeries(m, r, every) {
    var ticks = m.recruits[r].ticks, owner = [m.owner[0] + 0.5, m.owner[1], m.owner[2] + 0.5], out = [];
    for (var i = 0; i < ticks.length; i += every) out.push([i, flatDistance(ticks[i], owner)]);
    if (ticks.length && (ticks.length - 1) % every) out.push([ticks.length - 1, flatDistance(ticks[ticks.length - 1], owner)]);
    return out;
  }

  /** The search a recruit was walking at tick t, or null. */
  function walkedAt(m, r, t) {
    var here = tickAt(m.recruits[r].ticks, t);
    return here && here[3] >= 0 ? m.searches[here[3]] : null;
  }

  /** Up to {@code count} of a recruit's searches nearest tick t, in tick order. */
  function searchesNear(m, r, t, count) {
    var ids = m.recruits[r] ? m.recruits[r].searchIds.slice() : [];
    ids.sort(function (a, b) { return Math.abs(m.searches[a].tick - t) - Math.abs(m.searches[b].tick - t) || a - b; });
    return ids.slice(0, count).sort(function (a, b) { return a - b; }).map(function (id) { return m.searches[id]; });
  }

  /** The detour plan's latest state at tick t: status, searches so far and route; null before the first. */
  function planAt(m, t) {
    var plan = null;
    m.plans.forEach(function (p) { if (p.tick <= t) plan = p; });
    return plan;
  }

  /** The steps, up to tick t, of the latest plan that had begun by then; empty when none had. */
  function planStepsAt(m, t) {
    var current = -1;
    m.steps.forEach(function (s) { if (s.tick <= t) current = s.plan; });
    return current < 0 ? [] : m.steps.filter(function (s) { return s.plan === current && s.tick <= t; });
  }

  /** The distinct routes a recruit's follow searches found up to tick t, as route indexes. */
  function routesUntil(m, r, t) {
    var seen = {}, out = [];
    (m.recruits[r] ? m.recruits[r].searchIds : []).forEach(function (id) {
      var s = m.searches[id];
      if (s.kind !== 'follow' || s.tick > t || s.routeIndex < 0 || seen[s.routeIndex]) return;
      seen[s.routeIndex] = true; out.push(s.routeIndex);
    });
    return out;
  }

  /** How many recruits ended each way. */
  function outcomeCounts(m) {
    var counts = {};
    m.recruits.forEach(function (r) { counts[r.outcome] = (counts[r.outcome] || 0) + 1; });
    return counts;
  }

  /** What the run's searching cost: searches, points, shares behind the start and repeated, searches on the server thread. */
  function cost(m) {
    var c = { searches: m.searches.length, points: 0, behind: 0, repeats: 0, server: 0, countsOnly: 0 };
    m.searches.forEach(function (s) {
      c.points += s.n; c.behind += s.behind || 0;
      if (s.sameAs !== undefined) c.repeats++;
      else if (!s.own) c.countsOnly++;
      if (s.server) c.server++;
    });
    return c;
  }

  /**
   * How far recruit r walked, along the ground: up to tick t and over the whole run; and the tick from
   * which it stayed within a block of where it ended, or -1 when it was still moving in the last 100 ticks.
   */
  function walkSummary(m, r, t) {
    var ticks = m.recruits[r].ticks, soFar = 0, total = 0, end = ticks.length - 1, still = -1;
    for (var i = 1; i < ticks.length; i++) {
      var d = flatDistance(ticks[i - 1], ticks[i]);
      total += d;
      if (i <= t) soFar += d;
    }
    if (end >= 0) {
      var i0 = end;
      while (i0 > 0 && flatDistance(ticks[i0 - 1], ticks[end]) <= 1.0) i0--;
      if (i0 < end - 100) still = i0;
    }
    return { soFar: soFar, total: total, stillSince: still };
  }

  /**
   * Plans that repeat an earlier plan exactly: each side takes the same steps to the same spots, so the
   * same result. The two sides' steps interleave in whatever order their searches finish, so each side
   * is compared on its own. A map from a plan's id to the first plan it repeats.
   */
  function planRepeats(m) {
    var sides = {}, sig = {}, first = {}, out = {};
    m.steps.forEach(function (s) {
      var k = s.plan + '/' + s.side;
      sides[k] = (sides[k] || '') + s.event + ':' + (s.spot ? s.spot.join(',') : '') + ';';
    });
    Object.keys(sides).forEach(function (k) {
      var parts = k.split('/'), p = parts[0];
      sig[p] = sig[p] || {};
      sig[p][parts[1]] = sides[k];
    });
    Object.keys(sig).forEach(function (p) { sig[p] = (sig[p]['1'] || '') + '|' + (sig[p]['-1'] || ''); });
    Object.keys(sig).map(Number).sort(function (a, b) { return a - b; }).forEach(function (p) {
      if (first[sig[p]] === undefined) first[sig[p]] = p; else out[p] = first[sig[p]];
    });
    return out;
  }

  /** How many plans the run's planner steps belong to. */
  function planCount(m) {
    var ids = {};
    m.steps.forEach(function (s) { ids[s.plan] = true; });
    return Object.keys(ids).length;
  }

  root.VizData = {
    STEP_WORDS: STEP_WORDS, load: load, blockAt: blockAt, standAt: standAt, isEdge: isEdge, route: route,
    pointsSource: pointsSource, points: points, lastTick: lastTick, tickAt: tickAt, flatDistance: flatDistance,
    distanceSeries: distanceSeries, walkedAt: walkedAt, searchesNear: searchesNear, planAt: planAt,
    planStepsAt: planStepsAt, routesUntil: routesUntil, outcomeCounts: outcomeCounts, cost: cost,
    walkSummary: walkSummary, planRepeats: planRepeats, planCount: planCount
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = root.VizData;
})(typeof window !== 'undefined' ? window : globalThis);
