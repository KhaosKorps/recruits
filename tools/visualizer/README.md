# Recruit pathfinding visualiser

A web page that replays recorded GameTest runs over the test world's real blocks. Four views:

- **Runs**: every recorded test, how its recruits ended, and what its searching cost.
- **Explore a run**: one map with its controls above it. Layers: where every recruit walked; the
  followed recruit's current route (the route its search found dashed, the part edge care kept
  solid); every route it found so far; the detour planner's stepping stones (reached, being tried,
  or why one failed); where all searches looked. Beside it: the recruits (click one to follow it),
  where the followed one is, what the planner is doing, and its searches near the chosen tick. Under
  it: each recruit's distance to its owner over the run; click it to jump there.
- **One search**: a search point by point, with its numbers where they were kept, the blocks around
  any column, and a 3D view.
- **The code**: the methods that run for one re-plan, copied whole from `src/main`.

## How it's built

`gradlew runGameTestServer` builds it after every run (the `buildVisualizer` task in
`gradle/tests.gradle`) into `build/visualizer/`, then checks it (`checkVisualizerData`). It needs
Python 3 and Node on the PATH.

| File | What it is |
|---|---|
| `build_visualizer.py` | Writes `index.html` from the recordings, the test log and the source |
| `vizdata.js` | The page's data layer: turns a recording into what the page draws; no page code |
| `viz.js` | The page's views and drawing |
| `test_vizdata.js` | Checks `vizdata.js` on every recording, then runs `test_page.js` |
| `test_page.js` | Loads both scripts under Node with a stand-in page and plays every recording |

The recordings come from `GroupRecorder` (in `src/gametest`), one per recorded test, in
`build/gametest-run/runs/*.json.gz`. The page fetches them from `build/visualizer/runs/`.

The long group tests on the captured ravine run only when asked:

```
gradlew runGameTestServer -PgroupTests=pilot
gradlew runGameTestServer -PgroupTests=all
```

## Opening it

The page loads each run with a web request, which browsers refuse for a page opened straight from
disk. Serve the folder on this machine only, then open http://127.0.0.1:8000:

```
python -m http.server 8000 --bind 127.0.0.1 --directory build/visualizer
```

## Data and its lifetime

- Each test run starts by deleting the last run's recordings, so they never pile up: about 7 MB
  compressed for the normal run, and up to 10 MB uncompressed for the largest group run.
- The builder stops with an error if any recording passes 16 MB, the most a published page's file
  may hold.
- Everything is under `build/`, which git ignores and `gradlew clean` deletes. Nothing recorded is
  committed. A page published for sharing is a copy; it doesn't change when the tests run again.
