/*
 * Tests the visualiser's data layer (vizdata.js) on real run recordings.
 * Run: node tools/visualizer/test_vizdata.js build/gametest-run/runs
 * Prints every check per recording, and exits with 1 if any failed.
 */
'use strict';
var fs = require('fs');
var path = require('path');
var zlib = require('zlib');
var V = require('./vizdata.js');

var dir = process.argv[2];
if (!dir) { console.error('usage: node test_vizdata.js <folder of run .json.gz>'); process.exit(2); }
var files = fs.readdirSync(dir).filter(function (f) { return /\.json(\.gz)?$/.test(f); }).sort();
if (!files.length) { console.error('no recordings in ' + dir); process.exit(2); }

var failed = 0;
function check(file, what, ok, detail) {
  console.log((ok ? '  pass ' : '  FAIL ') + what + (detail ? ': ' + detail : ''));
  if (!ok) failed++;
}
function same(a, b) { return a && b && a[0] === b[0] && a[1] === b[1] && a[2] === b[2]; }

files.forEach(function (f) {
  var raw = fs.readFileSync(path.join(dir, f));
  var data = JSON.parse((f.endsWith('.gz') ? zlib.gunzipSync(raw) : raw).toString('utf8'));
  var m = V.load(data);
  console.log(f + ' (' + m.recruits.length + ' recruits, ' + m.searches.length + ' searches, ' + m.routes.length + ' routes, ' + m.steps.length + ' planner steps)');

  var noTicks = m.recruits.filter(function (r) { return !r.ticks.length; }).length;
  check(f, 'every recruit has recorded ticks', noTicks === 0, noTicks + ' without');

  var withRoute = 0, badStart = [], badKept = [];
  m.searches.forEach(function (s) {
    var r = V.route(m, s);
    if (!r.length) return;
    withRoute++;
    if (s.start && !same(r[0], s.start)) badStart.push(s.id);
    if (s.kept >= 0 && s.kept > r.length) badKept.push(s.id);
  });
  check(f, 'every found route starts at its search\'s start', badStart.length === 0, withRoute + ' routes, wrong: ' + badStart.slice(0, 5));
  check(f, 'no search kept more points than its found route has', badKept.length === 0, badKept.slice(0, 5).join(', ') || 'none');
  var notStart = m.searches.filter(function (s) { return s.keptIsStart === false; }).length;
  var walked = m.searches.filter(function (s) { return s.keptIsStart === true || s.keptIsStart === false; }).length;
  check(f, 'every walked path is the start of its found route', notStart === 0, (walked - notStart) + ' of ' + walked);

  var badSame = m.searches.filter(function (s) { return s.sameAs !== undefined && !V.pointsSource(m, s); }).map(function (s) { return s.id; });
  check(f, 'every repeated search points at one with points', badSame.length === 0, badSame.slice(0, 5).join(', ') || 'all resolve');

  var outside = 0, decoded = 0;
  m.searches.forEach(function (s) {
    if (!s.own) return;
    V.points(m, s).forEach(function (p) {
      decoded++;
      if (p.p[0] < -1 || p.p[2] < -1 || p.p[0] > m.SX || p.p[2] > m.SZ) outside++;
    });
  });
  check(f, 'every recorded point lies in the arena', outside === 0, decoded + ' points, ' + outside + ' outside');

  var last = V.lastTick(m), probes = [0, last >> 1, last], emptyNear = [];
  m.recruits.forEach(function (r, i) {
    if (!r.searchIds.length) return;
    probes.forEach(function (t) { if (!V.searchesNear(m, i, t, 12).length) emptyNear.push(i + '@' + t); });
  });
  check(f, 'searches near any tick are found for every recruit that searched', emptyNear.length === 0, emptyNear.slice(0, 5).join(', ') || 'start, middle and end');

  var wrongOwner = 0, walkedTicks = 0;
  m.recruits.forEach(function (r, i) {
    for (var t = 0; t < r.ticks.length; t += 50) {
      var w = V.walkedAt(m, i, t);
      if (!w) continue;
      walkedTicks++;
      if (w.r !== i) wrongOwner++;
    }
  });
  check(f, 'the search a recruit walks is one of its own', wrongOwner === 0, walkedTicks + ' ticks sampled, ' + wrongOwner + ' wrong');

  var series = V.distanceSeries(m, 0, 10);
  check(f, 'the distance series covers recruit 0 from tick 0', series.length > 0 && series[0][0] === 0, series.length + ' samples');

  var unknown = m.steps.filter(function (s) { return !V.STEP_WORDS[s.event]; }).map(function (s) { return s.event; });
  check(f, 'every planner step has a plain-words meaning', unknown.length === 0, unknown.slice(0, 5).join(', ') || m.steps.length + ' steps');
  if (m.steps.length) check(f, 'the planner\'s steps are found at the run\'s end', V.planStepsAt(m, last).length > 0, V.planStepsAt(m, last).length + ' steps');

  var shifted = m.searches.filter(function (s) { return s.tick < -5 || s.tick > last + 5; }).length;
  check(f, 'search ticks line up with the run\'s ticks', shifted === 0, shifted + ' of ' + m.searches.length + ' outside 0..' + last);
});

var pageDir = process.argv[3];
if (!pageDir) {
  console.log(failed ? failed + ' check(s) failed' : 'all checks passed');
  process.exit(failed ? 1 : 0);
}
console.log('The page, run under node with a stand-in page (' + pageDir + '):');
require('./test_page.js').smoke(pageDir).then(function (pageFailed) {
  failed += pageFailed;
  console.log(failed ? failed + ' check(s) failed' : 'all checks passed');
  process.exit(failed ? 1 : 0);
}, function (e) {
  console.log('  FAIL the page could not be run: ' + e);
  process.exit(1);
});
