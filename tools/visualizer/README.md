# Recruit pathfinding visualiser

A web page that replays what the recruits' pathfinding did in a GameTest run: every rough-ravine
test tick by tick, any recorded search step by step with the numbers it computed, the block checks
behind each cost, the test world in 3D, and the code that ran, copied whole from `src/main`.

## How it's built

`gradlew runGameTestServer` builds it after every run (the `buildVisualizer` task in
`gradle/tests.gradle`), into `build/visualizer/`:

- `index.html`: the page, with the single-search trace and the code embedded.
- `runs/*.json`: one recording per rough-ravine test, loaded when you open that run.

It needs Python 3 on the PATH. The inputs come from the test run:

| Input | Written by | Where |
|---|---|---|
| Run recordings, one per rough-ravine test | `RunRecorder` | `build/gametest-run/runs/*.json.gz` |
| Three single searches on ravine seed 1 | `PathTraceGameTests` | `build/gametest-run/pathtrace/ravine-seed1.json` |
| Pass and fail counts | the GameTest server | `build/gametest-run/logs/latest.log` |

To build it by hand, or with a findings note under the findings section:

```
python -I tools/visualizer/build_visualizer.py --clone . --trace build/gametest-run/pathtrace/ravine-seed1.json --viz tools/visualizer/viz.js --out build/visualizer/index.html --runs build/gametest-run/runs --log build/gametest-run/logs/latest.log --notes notes.html
```

## Opening it

The page loads each run with a web request, which browsers refuse for a page opened straight from
disk. Serve the folder on this machine only, then open http://127.0.0.1:8000:

```
python -m http.server 8000 --bind 127.0.0.1 --directory build/visualizer
```

## Data and its lifetime

- Each test run starts by deleting the last run's recordings, so they never pile up. About 7 MB
  compressed for the 12 rough-ravine tests, and the page's own uncompressed copy about 45 MB.
- Everything is under `build/`, which git ignores and `gradlew clean` deletes. Nothing recorded is
  committed.
- A page published for sharing is a copy; it doesn't change when the tests run again.
