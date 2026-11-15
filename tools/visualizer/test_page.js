/*
 * Loads the visualiser's scripts (vizdata.js, viz.js) under node with a stand-in page, opens every run
 * in a built page folder, moves through its ticks, follows each recruit, and inspects a search of each
 * kind (its own points, a repeat, counts only), so an error the page would hit while running shows
 * here. Drawing goes to canvases that do nothing; what it checks is that nothing throws.
 * Used by test_vizdata.js: smoke(<built page folder>) returns how many checks failed.
 */
'use strict';
var fs = require('fs');
var path = require('path');
var vm = require('vm');

function element(id) {
  var listeners = {};
  var el = {
    id: id, textContent: '', innerHTML: '', hidden: false, value: '0', max: '0', checked: false, style: {},
    width: 0, height: 0, parentElement: { clientWidth: 900 }, children: [],
    addEventListener: function (type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
    setAttribute: function () {}, getAttribute: function () { return null; }, appendChild: function (c) { this.children.push(c); },
    classList: { toggle: function () {}, add: function () {}, remove: function () {} },
    closest: function () { return null; }, getBoundingClientRect: function () { return { left: 0, top: 0, width: 900, height: 600 }; },
    getContext: function () { return context(); }, setPointerCapture: function () {}
  };
  return el;
}
function context() {
  var target = {
    createImageData: function (w, h) { return { data: new Uint8ClampedArray(w * h * 4) }; },
    measureText: function () { return { width: 10 }; }
  };
  return new Proxy(target, {
    get: function (t, k) { return k in t ? t[k] : function () {}; },
    set: function (t, k, v) { t[k] = v; return true; }
  });
}

function smoke(pageDir) {
  var failed = 0;
  var runsDir = path.join(pageDir, 'runs');
  var files = fs.readdirSync(runsDir).filter(function (f) { return f.endsWith('.json'); }).sort();
  var elements = {};
  function get(id) { return elements[id] || (elements[id] = element(id)); }
  var windowListeners = {};
  var index = { runs: files.map(function (f) { return { label: f, file: 'runs/' + f }; }) };
  get('index-data').textContent = JSON.stringify(index);
  var document = {
    getElementById: get, documentElement: {},
    querySelector: function () { return element('q'); }, querySelectorAll: function () { return []; },
    createElement: function () { return element('made'); }, addEventListener: function () {}
  };
  var window = {
    location: { hash: '' }, devicePixelRatio: 1,
    addEventListener: function (type, fn) { (windowListeners[type] = windowListeners[type] || []).push(fn); },
    matchMedia: function () { return { addEventListener: function () {} }; }
  };
  var context = {
    window: window, document: document, console: console, Math: Math, JSON: JSON, Promise: Promise, Error: Error,
    Int16Array: Int16Array, Uint8Array: Uint8Array, Float32Array: Float32Array, Float64Array: Float64Array, Uint8ClampedArray: Uint8ClampedArray,
    getComputedStyle: function () { return { getPropertyValue: function () { return '#808080'; } }; },
    setInterval: function () { return 1; }, clearInterval: function () {}, location: window.location,
    fetch: function (file) {
      var body = fs.readFileSync(path.join(pageDir, file), 'utf8');
      return Promise.resolve({ ok: true, status: 200, json: function () { return Promise.resolve(JSON.parse(body)); } });
    }
  };
  window.getComputedStyle = context.getComputedStyle;
  vm.createContext(context);
  context.globalThis = context;
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'vizdata.js'), 'utf8'), context, { filename: 'vizdata.js' });
  window.VizData = context.VizData || context.window.VizData;
  vm.runInContext(fs.readFileSync(path.join(__dirname, 'viz.js'), 'utf8'), context, { filename: 'viz.js' });
  var api = window.__viz;
  function go(hash) { window.location.hash = hash; (windowListeners.hashchange || []).forEach(function (fn) { fn(); }); }

  return files.reduce(function (chain, f, i) {
    return chain.then(function () {
      api.openRun(i, true);
      return new Promise(function (r) { setImmediate(r); }).then(function () { return new Promise(function (r) { setImmediate(r); }); });
    }).then(function () {
      var what = [];
      try {
        var M = api.model();
        if (!M || M.name === undefined) throw new Error('the run did not load');
        go('#explore');
        var last = require('./vizdata.js').lastTick(M);
        api.state.layers = { walked: true, searches: true, routes: true, planner: true, heat: true };
        [0, last >> 2, last >> 1, last].forEach(function (t) { api.state.t = t; api.render(); });
        what.push('explore at 4 ticks with every layer');
        for (var r = 0; r < Math.min(M.recruits.length, 3); r++) { api.state.r = r; api.render(); }
        what.push('followed ' + Math.min(M.recruits.length, 3) + ' recruits');
        var own = M.searches.find(function (s) { return s.own && s.detail; }) || M.searches.find(function (s) { return s.own; });
        var rep = M.searches.find(function (s) { return s.sameAs !== undefined; });
        var counts = M.searches.find(function (s) { return !s.own && s.sameAs === undefined; });
        [own, rep, counts].forEach(function (s) {
          if (!s) return;
          api.state.s = s.id; api.state.k = 0; go('#search');
          api.state.k = 1e9; api.render();
          what.push('search #' + s.id + (s.own ? ' (own points)' : s.sameAs !== undefined ? ' (repeat)' : ' (counts only)'));
          go('#explore');
        });
        console.log('  pass the page runs ' + f + ': ' + what.join(', '));
      } catch (e) {
        failed++;
        console.log('  FAIL the page runs ' + f + ': ' + (e && e.stack ? e.stack.split('\n').slice(0, 3).join(' | ') : e));
      }
    });
  }, Promise.resolve()).then(function () { return failed; });
}

module.exports = { smoke: smoke };
