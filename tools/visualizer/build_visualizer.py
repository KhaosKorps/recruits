"""Builds the recruit pathfinding visualiser page from the fork's source files and one GameTest run's recordings.

Every code block is copied from the source as it is on disk, whole methods with their comments, with
their real line numbers. The page reads the recorded runs from a runs/ folder written beside it, so it
has to be served over HTTP: a browser won't let a page opened straight from disk read them.

Run: python -I build_visualizer.py --clone <recruits clone> --trace <single-search trace .json>
     --viz <viz.js> --out <page .html> --runs <folder of run .json.gz> --log <GameTest server log>
     [--notes <HTML fragment for the findings section>]
"""
import argparse
import html
import json
import math
import pathlib
import re
import subprocess
import sys

parser = argparse.ArgumentParser(description="Builds the recruit pathfinding visualiser page.")
parser.add_argument("--clone", required=True, help="the Recruits fork's checkout")
parser.add_argument("--trace", required=True, help="the single-search trace written by PathTraceGameTests")
parser.add_argument("--viz", required=True, help="the page's script, viz.js")
parser.add_argument("--out", required=True, help="the page to write; its runs/ folder goes beside it")
parser.add_argument("--runs", required=True, help="the folder of run recordings (.json.gz) written by RunRecorder")
parser.add_argument("--log", required=True, help="the GameTest server's log, for the pass and fail counts")
parser.add_argument("--notes", help="an HTML fragment to show under the findings, such as what a set of recordings showed")
ARGS = parser.parse_args()

CLONE = pathlib.Path(ARGS.clone)
TRACE = pathlib.Path(ARGS.trace)
VIZ = pathlib.Path(ARGS.viz)
OUT = pathlib.Path(ARGS.out)
RUNS = pathlib.Path(ARGS.runs)
LOG = pathlib.Path(ARGS.log)
for needed in (TRACE, VIZ, LOG):
    if not needed.is_file():
        sys.exit(f"build_visualizer: {needed} not found; run the GameTests first (gradlew runGameTestServer)")
NOTES = pathlib.Path(ARGS.notes).read_text(encoding="utf-8") if ARGS.notes else ""
JAVA = CLONE / "src/main/java/com/talhanation/recruits"
COMMIT = subprocess.run(["git", "-C", str(CLONE), "log", "-1", "--format=%h"], capture_output=True, text=True).stdout.strip()

KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue default do double else enum
extends final finally float for goto if implements import instanceof int interface long native new package private
protected public return short static strictfp super switch synchronized this throw throws transient try void volatile
while var record true false null""".split())
TOKEN = re.compile(r'(?P<c>//[^\n]*|/\*.*?\*/)|(?P<s>"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])\')|(?P<n>\b\d+(?:\.\d+)?[FfDdL]?\b)|(?P<w>[A-Za-z_][A-Za-z0-9_]*)|(?P<o>[^A-Za-z0-9_"\'/]+|/)', re.S)


def highlight(code):
    out = []
    for m in TOKEN.finditer(code):
        kind = m.lastgroup
        text = m.group()
        if kind == "w":
            cls = "k" if text in KEYWORDS else ("t" if text[0].isupper() else None)
        elif kind == "c":
            cls = "fk" if "KhaosKorps fork" in text else "c"
        elif kind == "o":
            cls = None
        else:
            cls = kind
        pieces = text.split("\n")
        for i, piece in enumerate(pieces):
            if i:
                out.append("\n")
            if piece:
                esc = html.escape(piece, quote=False)
                out.append(f'<span class="{cls}">{esc}</span>' if cls else esc)
    return "".join(out)


def body_end(text, start):
    """Index just past the brace that closes the first '{' at or after start, skipping strings and comments."""
    i = text.index("{", start)
    depth = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if text.startswith("//", i):
            i = text.index("\n", i)
            continue
        if text.startswith("/*", i):
            i = text.index("*/", i) + 2
            continue
        if ch == '"' or ch == "'":
            j = i + 1
            while text[j] != ch:
                j += 2 if text[j] == "\\" else 1
            i = j + 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("unbalanced")


def method(rel, pattern):
    """A whole method, with the comments and annotations right above it, and its first line number."""
    path = JAVA / rel
    lines = path.read_text(encoding="utf-8").replace("\r\n", "\n").split("\n")
    hits = [i for i, line in enumerate(lines) if re.search(pattern, line)]
    if len(hits) != 1:
        raise SystemExit(f"{rel}: {len(hits)} matches for {pattern}")
    decl = hits[0]
    first = decl
    while first > 0:
        prev = lines[first - 1].strip()
        if prev.startswith(("@", "*", "/**", "/*", "//")) or prev.endswith("*/"):
            first -= 1
        else:
            break
    text = "\n".join(lines)
    offset = sum(len(l) + 1 for l in lines[:decl])
    end = body_end(text, offset)
    start_offset = sum(len(l) + 1 for l in lines[:first])
    chunk = text[start_offset:end]
    chunk_lines = chunk.split("\n")
    indent = min((len(l) - len(l.lstrip()) for l in chunk_lines if l.strip()), default=0)
    chunk = "\n".join(l[indent:] for l in chunk_lines)
    return chunk, first + 1, lines[decl].strip().rstrip("{").split("//")[0].strip()


def whole_class(rel):
    path = JAVA / rel
    lines = path.read_text(encoding="utf-8").replace("\r\n", "\n").split("\n")
    start = next(i for i, l in enumerate(lines) if l.startswith("/**"))
    return "\n".join(lines[start:]).rstrip() + "\n", start + 1, "class " + path.stem + " (the whole file)"


def code_block(rel, pattern=None, label=None):
    chunk, line, decl = whole_class(rel) if pattern is None else method(rel, pattern)
    numbered = []
    for i, l in enumerate(highlight(chunk).split("\n")):
        numbered.append(f'<span class="ln" data-n="{line + i}">{l}</span>')
    name = label or decl
    summary = f'<code>{html.escape(name)}</code> <span class="where">{html.escape(rel)}:{line}</span>'
    return f'<details open><summary>{summary}</summary><pre class="code">{"".join(n + chr(10) for n in numbered)}</pre></details>'


STEPS = [
    ("The follow goal decides to move", "server thread",
     """<p>Minecraft runs each recruit's goals from its goal selector. <code>canUse</code> is checked every
     <code>FollowStartCheckTicks</code> (10): the owner must be at least the start distance away (3 blocks) and the recruit in Follow Me.
     Once running, <code>tick</code> counts down <code>FollowRepathTicks</code> (10 game ticks) between re-plans. Each re-plan puts the
     edge care on the navigation, asks the detour where to aim, and calls the navigation. <code>canContinueToUse</code> stops the goal
     when the navigation has nothing left to walk or the recruit is within the stop distance.</p>""",
     [("entities/ai/RecruitFollowOwnerGoal.java", r"public boolean canUse\(\)"),
      ("entities/ai/RecruitFollowOwnerGoal.java", r"public boolean canContinueToUse\(\)"),
      ("entities/ai/RecruitFollowOwnerGoal.java", r"public void start\(\)"),
      ("entities/ai/RecruitFollowOwnerGoal.java", r"public void tick\(\)")]),
    ("The detour decides where to aim", "server thread",
     """<p>Normally <code>waypoint</code> returns null and the recruit aims straight at its owner. A recruit that has made no progress for
     3 seconds, whose own last follow path didn't reach, asks <code>DetourRoutes</code> for a plan; once a plan is found, every follower
     whose path fails walks its waypoints one by one, checking for a direct path at each.</p>""",
     [("khaoskorps/DetourFollow.java", r"public BlockPos waypoint\("),
      ("khaoskorps/DetourFollow.java", r"private BlockPos alongRoute\("),
      ("khaoskorps/DetourFollow.java", r"private void noteProgress\(")]),
    ("The navigation decides whether to search", "server thread",
     """<p><code>moveTo(Entity, speed, range)</code> sets the search range (the follow path length; 0 means the follow range, 32 blocks).
     Paper's throttle then refuses a move after 10 failures in a row for 2 seconds. <code>createPath</code> reuses a path when it can: one still
     being searched for the same target, or one not finished that already aims there. Otherwise it cuts out a cube of the world around
     the mob, <code>range + 16</code> blocks each way, sets the budget (16 points per block of range: 512), and asks the path finder.</p>""",
     [("pathfinding/AsyncPathNavigation.java", r"public boolean moveTo\(@NotNull Entity target, double speed, float range\)"),
      ("pathfinding/AsyncPathNavigation.java", r"public boolean moveTo\(@NotNull Entity p_26532_"),
      ("pathfinding/AsyncPathNavigation.java", r"protected Path createPath\(Set<BlockPos> p_148223_"),
      ("pathfinding/AsyncPathNavigation.java", r"public boolean moveTo\(@Nullable Path p_26537_")]),
    ("The search is set up and handed to the worker", "server thread → path worker",
     """<p><code>findPath</code> takes an evaluator from a pool, calls <code>prepare</code> (which reads the settings: edge margin, fall
     distance, dirt-path mode) and <code>getStart</code> on the server thread, then returns an empty <code>AsyncPath</code> at once. The
     <code>AsyncPath</code> constructor queues itself on the worker pool; when the worker has run <code>processPath</code>, <code>process</code>
     fills in the nodes, marks it processed, and the navigation's callback runs on the server thread through <code>server.execute</code>.</p>""",
     [("pathfinding/AsyncPathfinder.java", r"public Path findPath\("),
      ("entities/ai/navigation/RecruitsPathNodeEvaluator.java", r"public void prepare\(PathNavigationRegion region, Mob mob\)"),
      ("pathfinding/AsyncPath.java", r"public AsyncPath\(@NotNull List"),
      ("pathfinding/AsyncPath.java", r"public synchronized void process\(\)"),
      ("pathfinding/AsyncPathProcessor.java", r"protected static void queue\("),
      ("pathfinding/AsyncPathProcessor.java", r"public static void awaitProcessing\(")]),
    ("The A* search", "path worker",
     """<p>This loop is what the playback above shows, step by step. It takes the open point with the lowest <code>f = g + h</code> off a
     binary heap and closes it, stops if it is within reach of a target, and otherwise asks the evaluator for its neighbours. For each, the
     cost to get there is <code>g + step length + costMalus</code> (step length is <code>Node.distanceTo</code>, the 3D distance, from vanilla's
     <code>PathFinder.distance</code>); if that beats what the neighbour had and the walked distance stays under the range, the neighbour takes it,
     with <code>h</code> = 1.5 × its straight-line distance to the target. When the budget runs out, or no open point is left within the range,
     the path is rebuilt from the point that got nearest the target, and marked as not reaching it.</p>""",
     [("pathfinding/AsyncPathfinder.java", r"private @NotNull Path processPath\("),
      ("pathfinding/AsyncPathfinder.java", r"private float getBestH\("),
      ("pathfinding/AsyncPathfinder.java", r"private Path reconstructPath\(")]),
    ("Each neighbour: can it be stood on, and what does it cost?", "path worker",
     """<p><code>getNeighbors</code> asks <code>findAcceptedNode</code> about the four sides and the four diagonals (a diagonal only when both
     sides next to it are open). <code>findAcceptedNode</code> finds where a mob would actually stand in that column: a step up of at most one
     block, or a fall down onto ground no deeper than the fall distance; deeper is blocked. Here the fork refuses two kinds of drop of 2 or more:
     one landing beside a deadly drop, and one landing in a dead end. <code>getNode</code> sets the point's <code>costMalus</code>: the block's own
     cost, +2 for each side without a dirt path (again on every look in ORIGINAL mode), and the edge cost near a drop.</p>""",
     [("entities/ai/navigation/RecruitsPathNodeEvaluator.java", r"public int getNeighbors\("),
      ("entities/ai/navigation/RecruitsPathNodeEvaluator.java", r"protected boolean isNeighborValid\("),
      ("entities/ai/navigation/RecruitsPathNodeEvaluator.java", r"protected boolean isDiagonalValid\("),
      ("entities/ai/navigation/RecruitsPathNodeEvaluator.java", r"protected Node findAcceptedNode\("),
      ("entities/ai/navigation/RecruitsPathNodeEvaluator.java", r"protected @NotNull Node getNode\(int x, int y, int z\)")]),
    ("The block checks", "path worker (and server thread)",
     """<p><code>EdgeProbe</code> is the one definition of a dangerous drop used everywhere: the edge cost, the perch rule, careful steps, the
     cut-backs and the crossing check. The ring search in <code>distanceToDangerousDrop</code> is what the ring grid above draws. The dead-end
     check walks the landing area breadth first with the evaluator's own rules.</p>""",
     [("khaoskorps/EdgeProbe.java", None),
      ("khaoskorps/EdgeCost.java", r"public static float malus\("),
      ("entities/ai/navigation/RecruitsPathNodeEvaluator.java", r"private boolean landsInADeadEnd\("),
      ("entities/ai/navigation/RecruitsPathNodeEvaluator.java", r"private boolean noWayOut\("),
      ("entities/ai/navigation/RecruitsPathNodeEvaluator.java", r"private boolean nearAGoal\(")]),
    ("Back on the server thread: the path is applied and walked", "server thread",
     """<p>Every tick the navigation first lets a finished waiting search take over (keep-walking), returns early while its path is still being
     searched (that tick, the recruit gets no walk input), applies the edge care once to a newly finished path, and steers toward the next point,
     slower when that point is right beside a drop.</p>""",
     [("pathfinding/AsyncPathNavigation.java", r"public void tick\(\)"),
      ("pathfinding/AsyncPathNavigation.java", r"private boolean takePendingIfFinished\(\)"),
      ("pathfinding/AsyncPathNavigation.java", r"private void applyEdgeCare\(\)"),
      ("pathfinding/AsyncPathNavigation.java", r"private double edgeSpeedFactor\(\)")]),
    ("The cut-backs", "server thread",
     """<p>Each returns how many of the path's points to keep. Edge care keeps the smallest answer.</p>""",
     [("khaoskorps/PathEdgeTrim.java", r"public static int pointsToKeep\("),
      ("khaoskorps/PathEdgeTrim.java", r"public static int pointsToKeepForDepth\("),
      ("khaoskorps/PathEdgeTrim.java", r"public static int pointsToKeepNearTarget\("),
      ("khaoskorps/PathEdgeTrim.java", r"static boolean dropAhead\("),
      ("khaoskorps/CrossingLocks.java", r"public static int pointsToKeep\("),
      ("khaoskorps/CrossingLocks.java", r"static Found firstSpot\(")]),
]

trace = json.loads(TRACE.read_text(encoding="utf-8"))
rows = []
for s in trace["searches"]:
    start, target = s["start"], s["target"]
    far = max(math.hypot(p["p"][0] - start[0], p["p"][2] - start[2]) for p in s["pops"])
    end = s["path"][-1] if s["path"] else start
    short = math.dist(end, target)
    rows.append(f'<tr><td>{html.escape(s["name"])}</td><td class="num">{len(s["pops"])}</td><td class="num">{far:.1f}</td>'
                f'<td class="num">{max(p["m"] for p in s["pops"]):.0f}</td><td>{"yes" if s["reached"] else "no"}</td><td class="num">{short:.1f}</td></tr>')

steps_html = []
for n, (title, thread, text, blocks) in enumerate(STEPS, 1):
    codes = "".join(code_block(rel, pat) for rel, pat in blocks)
    steps_html.append(f'<section class="step" id="step{n}"><div class="eyebrow">Step {n} · {html.escape(thread)}</div><h3>{html.escape(title)}</h3>{text}{codes}</section>')

data = json.dumps(trace, separators=(",", ":")).replace("</", "<\\/")
viz = VIZ.read_text(encoding="utf-8")

# The recorded runs, and the last test run's results.
import gzip
import shutil
runs_out = OUT.parent / "runs"
runs_out.mkdir(parents=True, exist_ok=True)
for old in runs_out.glob("*.json"):
    old.unlink()  # a test removed since the last build leaves no recording behind
runs = []
for f in sorted(RUNS.glob("*.json.gz")):
    d = json.loads(gzip.decompress(f.read_bytes()))
    (runs_out / f.name.replace(".json.gz", ".json")).write_bytes(gzip.decompress(f.read_bytes()))
    kinds, reached = {}, {}
    for srch in d["searches"]:
        kinds[srch["kind"]] = kinds.get(srch["kind"], 0) + 1
        if srch["reached"]:
            reached[srch["kind"]] = reached.get(srch["kind"], 0) + 1
    ends = [pl for pl in d["plans"] if pl[1] in ("FOUND", "FAILED")]
    runs.append({"test": d["test"], "file": "runs/" + f.name.replace(".json.gz", ".json"),"outcome": d["outcome"], "message": d["message"], "seed": d["seed"],
                 "fall": d["fallDistance"], "ticks": len(d["ticks"]), "follow": kinds.get("follow", 0), "detour": kinds.get("detour", 0),
                 "followReached": reached.get("follow", 0), "detourReached": reached.get("detour", 0), "plans": len(ends),
                 "plansFound": sum(1 for pl in ends if pl[1] == "FOUND"), "end": d["ticks"][-1][:3]})
log = LOG.read_text(encoding="utf-8", errors="replace")
req = re.search(r"All (\d+) required tests passed", log)
reqfail = re.search(r"(\d+) required tests failed", log)
opt = re.search(r"(\d+) optional tests failed", log)
failures = {}
for m in re.finditer(r"\]: (\S+) failed! (.*)", log):
    failures[m.group(1)] = m.group(2)[:300]
index = json.dumps({"runs": runs}, separators=(",", ":")).replace("</", "<\\/")
run_rows = "".join(
    f'<tr><td>{html.escape(r["test"].replace("aFollowerGoesRoundARoughRavineAt", ""))}</td><td>{r["seed"]}</td><td>{r["fall"]}</td>'
    f'<td class="{"good" if r["outcome"] == "passed" else "bad"}">{html.escape(r["outcome"])}</td><td class="num">{r["ticks"]}</td>'
    f'<td class="num">{r["follow"]} ({r["followReached"]} reached)</td><td class="num">{r["detour"]} ({r["detourReached"]} reached)</td>'
    f'<td class="num">{r["plansFound"]} of {r["plans"]} found</td><td class="num">({r["end"][0]:.0f}, {r["end"][1]:.0f}, {r["end"][2]:.0f})</td>'
    f'<td><button type="button" data-open-run="{html.escape(r["file"])}">Open</button></td></tr>' for r in runs)
other_fail = "".join(f'<li><code>{html.escape(n)}</code>: {html.escape(m)}</li>' for n, m in failures.items() if "RoughRavine" not in n)
summary_line = (f'{req.group(1)} required tests passed' if req else (f'{reqfail.group(1)} required tests failed' if reqfail else 'no summary found')) + (f', {opt.group(1)} optional tests failed' if opt else '')

page = f"""<title>Recruit Pathfinding Visualizer</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;600;700&family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap">
<style>
/* Layout: one column; the playback is a map beside its math panel, stacking on narrow screens. */
:root {{
  --bg: #f3f4f1; --surface: #ffffff; --fg: #1b201c; --muted: #5d675f; --line: #d8dcd4; --accent: #2f7a4f;
  --t-low: #3b4a40; --t-high: #c9d3c2; --t-wall: #2a2f2b; --water: #3f7fc0; --lava: #d4552b;
  --pop-a: #f2d16b; --pop-b: #b0367a; --open: #1f8a8a; --refused: #d0342c; --current: #111111; --path: #1659d6;
  --marker: #ffffff; --marker-fg: #111111; --drop-line: #e07a1f; --stone: #8a918a; --air: #eef1ec; --safe: #4caf6e; --on-cell: #111111;
  --step-line: rgba(0,0,0,0.18); --trail: #0f8f5f; --plan: #c2410c; --walk: #7c3aed; --recruit: #e11d48;
  --code-bg: #f0f2ee; --k: #7a3b8f; --t: #1f6f8b; --s: #8a5a12; --c: #6b776d; --n: #b0442a; --fk: #2f7a4f; --good: #2f7a4f; --bad: #b0442a;
  --display: "Space Grotesk", "Segoe UI", system-ui, sans-serif; --body: "Inter", "Segoe UI", system-ui, sans-serif; --mono: "JetBrains Mono", Consolas, monospace;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --bg: #121512; --surface: #1a1e1b; --fg: #e3e8e2; --muted: #9aa69c; --line: #2c342e; --accent: #6cc28f;
  --t-low: #26302a; --t-high: #a7b4a0; --t-wall: #0c0e0c; --water: #3a78bd; --lava: #e0602f;
  --pop-a: #f2d16b; --pop-b: #d14a95; --open: #45c4c4; --refused: #ff5a4f; --current: #ffffff; --path: #5b93ff;
  --marker: #ffffff; --marker-fg: #111111; --drop-line: #ff9a3c; --stone: #5d645e; --air: #1f2420; --safe: #3f9b5d; --on-cell: #ffffff;
  --step-line: rgba(255,255,255,0.16); --trail: #34d399; --plan: #fb923c; --walk: #a78bfa; --recruit: #fb7185;
  --code-bg: #0f120f; --k: #d4a2e6; --t: #7cc6de; --s: #e2b86a; --c: #7f8c81; --n: #f0937a; --fk: #8fd8aa; --good: #6cc28f; --bad: #f0937a; color-scheme: dark; }} }}
:root[data-theme="dark"] {{
  --bg: #121512; --surface: #1a1e1b; --fg: #e3e8e2; --muted: #9aa69c; --line: #2c342e; --accent: #6cc28f;
  --t-low: #26302a; --t-high: #a7b4a0; --t-wall: #0c0e0c; --water: #3a78bd; --lava: #e0602f;
  --pop-a: #f2d16b; --pop-b: #d14a95; --open: #45c4c4; --refused: #ff5a4f; --current: #ffffff; --path: #5b93ff;
  --marker: #ffffff; --marker-fg: #111111; --drop-line: #ff9a3c; --stone: #5d645e; --air: #1f2420; --safe: #3f9b5d; --on-cell: #ffffff;
  --step-line: rgba(255,255,255,0.16); --trail: #34d399; --plan: #fb923c; --walk: #a78bfa; --recruit: #fb7185;
  --code-bg: #0f120f; --k: #d4a2e6; --t: #7cc6de; --s: #e2b86a; --c: #7f8c81; --n: #f0937a; --fk: #8fd8aa; --good: #6cc28f; --bad: #f0937a; color-scheme: dark; }}
body {{ background: var(--bg); color: var(--fg); font-family: var(--body); font-size: 15px; line-height: 1.55;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); padding-block: 28px 64px; }}
main {{ max-width: 1180px; margin: 0 auto; display: flex; flex-direction: column; gap: 48px; }}
section {{ display: flex; flex-direction: column; gap: 14px; min-width: 0; }}
h1, h2, h3, h4 {{ font-family: var(--display); margin: 0; line-height: 1.2; text-wrap: balance; }}
h1 {{ font-size: 2rem; }} h2 {{ font-size: 1.4rem; padding-top: 8px; border-top: 1px solid var(--line); }} h3 {{ font-size: 1.15rem; }} h4 {{ font-size: 0.95rem; margin-top: 6px; }}
p {{ margin: 0; max-width: 78ch; }}
code {{ font-family: var(--mono); font-size: 0.86em; background: var(--code-bg); padding: 1px 5px; border-radius: 4px; }}
.eyebrow {{ font-family: var(--mono); font-size: 0.75rem; letter-spacing: 0.08em; text-transform: uppercase; color: var(--accent); }}
.lede {{ color: var(--muted); font-size: 1.03rem; max-width: 80ch; }}
.dim {{ color: var(--muted); }}
.good {{ color: var(--good); font-weight: 600; }} .bad {{ color: var(--bad); font-weight: 600; }}
.scroll {{ overflow-x: auto; min-width: 0; }}
table {{ border-collapse: collapse; font-size: 0.86rem; width: 100%; }}
th, td {{ text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }}
th {{ font-family: var(--display); color: var(--muted); font-weight: 600; font-size: 0.8rem; }}
.num, table.math td {{ font-variant-numeric: tabular-nums; }}
table.math td {{ font-family: var(--mono); font-size: 0.78rem; white-space: nowrap; }}
.controls {{ display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: center; background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; }}
.controls label {{ display: flex; gap: 6px; align-items: center; font-size: 0.88rem; }}
select, button, input[type=range] {{ font: inherit; font-size: 0.88rem; }}
select {{ max-width: 100%; padding: 4px 6px; border-radius: 6px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); }}
button {{ padding: 4px 12px; border-radius: 6px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); cursor: pointer; }}
button:hover {{ border-color: var(--accent); }}
#step {{ flex: 1 1 220px; min-width: 160px; }}
.play {{ display: grid; grid-template-columns: minmax(0, 660px) minmax(0, 1fr); gap: 18px; align-items: start; }}
@media (max-width: 900px) {{ .play {{ grid-template-columns: minmax(0, 1fr); }} }}
.mapbox {{ display: flex; flex-direction: column; gap: 6px; min-width: 0; }}
#map {{ cursor: crosshair; border-radius: 6px; display: block; }}
#hover, #stepno {{ font-family: var(--mono); font-size: 0.78rem; color: var(--muted); min-height: 1.2em; }}
.legend {{ display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 0.8rem; color: var(--muted); }}
.legend i {{ display: inline-block; width: 12px; height: 12px; border-radius: 2px; vertical-align: -2px; margin-right: 4px; }}
.runpanel {{ background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 10px 14px; display: flex; flex-direction: column; gap: 6px; font-size: 0.9rem; }}
.runpanel[hidden] {{ display: none; }}
#panel {{ display: flex; flex-direction: column; gap: 10px; min-width: 0; font-size: 0.9rem; }}
.kv {{ display: grid; grid-template-columns: auto auto auto auto; gap: 2px 10px; justify-content: start; font-size: 0.85rem; }}
.kv span {{ color: var(--muted); }}
.eq {{ font-family: var(--mono); font-size: 0.85rem; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }}
.explain {{ font-size: 0.9rem; }}
ul.cands {{ margin: 0; padding-left: 1.2em; font-size: 0.82rem; display: flex; flex-direction: column; gap: 3px; }}
.checks {{ display: grid; grid-template-columns: auto auto minmax(0, 1fr); gap: 18px; align-items: start; }}
@media (max-width: 760px) {{ .checks {{ grid-template-columns: minmax(0, 1fr); }} }}
.checks figure {{ margin: 0; display: flex; flex-direction: column; gap: 6px; min-width: 0; }}
figcaption {{ font-size: 0.82rem; color: var(--muted); max-width: 46ch; }}
.sec {{ display: flex; flex-direction: column; gap: 4px; min-width: 0; }}
.sec span {{ font-size: 0.82rem; color: var(--muted); }}
#view3d {{ background: var(--surface); border: 1px solid var(--line); border-radius: 10px; overflow: hidden; min-height: 320px; touch-action: none; }}
#view3d canvas {{ display: block; cursor: grab; }}
.step {{ gap: 10px; }}
details {{ background: var(--surface); border: 1px solid var(--line); border-radius: 8px; min-width: 0; }}
summary {{ padding: 8px 12px; cursor: pointer; font-size: 0.88rem; display: flex; flex-wrap: wrap; gap: 4px 12px; align-items: baseline; }}
summary .where {{ font-family: var(--mono); font-size: 0.75rem; color: var(--muted); }}
pre.code {{ margin: 0; padding: 10px 0; background: var(--code-bg); overflow-x: auto; font-family: var(--mono); font-size: 0.76rem; line-height: 1.5; border-top: 1px solid var(--line); }}
.ln {{ display: block; padding: 0 14px 0 0; white-space: pre; }}
.ln::before {{ content: attr(data-n); display: inline-block; width: 3.6em; padding-right: 1em; text-align: right; color: var(--muted); opacity: 0.6; user-select: none; }}
.k {{ color: var(--k); }} .t {{ color: var(--t); }} .s {{ color: var(--s); }} .c {{ color: var(--c); font-style: italic; }} .n {{ color: var(--n); }} .fk {{ color: var(--fk); font-weight: 600; }}
nav.toc {{ display: flex; flex-wrap: wrap; gap: 6px 16px; font-size: 0.88rem; }}
nav.toc a {{ color: var(--accent); }}
:focus-visible {{ outline: 2px solid var(--accent); outline-offset: 2px; }}
</style>
<main>
<header style="display:flex;flex-direction:column;gap:12px">
  <div class="eyebrow">Villager Recruits fork · commit {COMMIT} · rough ravine seed {trace["seed"]} · fall distance {trace["maxFall"]}</div>
  <h1>Recruit Pathfinding Visualizer</h1>
  <p class="lede">Real searches, recorded from the mod's own code running in the headless test server, played back over the test world's real blocks. Step through the A* one point at a time with every number it computed, look at the block checks behind each cost, see it all in 3D, then read the code that produced it, start to finish.</p>
  <nav class="toc"><a href="#results">Test results</a><a href="#findings">What the recordings show</a><a href="#timeline">A run, tick by tick</a><a href="#playback">A search, step by step</a><a href="#checks">The block checks</a><a href="#world3d">In 3D</a><a href="#code">How the code runs</a></nav>
</header>

<section id="results">
  <h2>Test results</h2>
  <p>The last full test run, on a fresh flat test world, commit {COMMIT}: <b>{summary_line}</b>. Every rough-ravine test is recorded whole; open one to replay it below.</p>
  <div class="scroll"><table>
    <thead><tr><th>Rough-ravine test</th><th>Seed</th><th>Fall distance</th><th>Result</th><th>Ticks</th><th>Follow searches</th><th>Detour searches</th><th>Detour plans</th><th>Where it ended</th><th></th></tr></thead>
    <tbody>{run_rows}</tbody>
  </table></div>
  <details><summary>The other optional test that failed</summary><ul style="margin:8px 0;padding:0 16px 0 32px">{other_fail}</ul></details>
</section>

<section id="findings">
  <h2>What the recordings show</h2>
  <p>The world: a {trace["size"][0]} × {trace["size"][2]} arena with ground rolling by up to two blocks, a ravine 7 to 15 blocks wide running from the west wall to x 40 to 48, a floor of varying depth with water, ledges on its walls and a staircase of 3-block drops at its east end. The way round is past the east end. The table below is the three single searches recorded on seed 1; the runs are in the results above.</p>
  <div class="scroll"><table>
    <thead><tr><th>Recorded search</th><th>Points taken off the open set</th><th>Furthest from the start (blocks)</th><th>Highest costMalus</th><th>Reached the owner</th><th>Path ends this far from the owner</th></tr></thead>
    <tbody>{"".join(rows)}</tbody>
  </table></div>
  <p class="dim">Budget: the most points one search may take off its open set before it gives up, {trace["budget"]} here ({trace["range"]} blocks of walking × 16 points a block). A search that runs out ends at the point that got nearest its target.</p>
  {NOTES}
</section>

<section id="timeline">
  <h2>A run, tick by tick</h2>
  <div class="controls">
    <label>Recording <select id="source" aria-label="Recording"></select></label><span id="loading" class="dim"></span>
  </div>
  <div class="controls" id="runctl" hidden>
    <button id="runplay" type="button">Play</button>
    <input id="tick" type="range" min="0" max="0" value="0" aria-label="Tick" style="flex:1 1 220px;min-width:160px">
    <span id="tickno" class="dim"></span>
    <label>Speed <select id="runspeed"><option value="1">1</option><option value="5" selected>5</option><option value="20">20</option></select></label>
    <label><input id="runoverlay" type="checkbox" checked> trail, plan and walked path</label>
    <button id="inspect" type="button">Open the search walked now</button>
  </div>
  <div id="runpanel" class="runpanel" hidden aria-live="polite"></div>
  <p class="dim">On the map below: R is the recruit and O its owner. The green line is where it has walked, the purple line the path it walks at this tick, the orange dashes and dots the detour plan's waypoints.</p>
</section>

<section id="playback">
  <h2>A search, step by step</h2>
  <div class="controls">
    <label style="flex:1 1 100%">Search <select id="search" aria-label="Recorded search" style="flex:1;min-width:0"></select></label>
    <button id="prev" type="button">◀ Step</button><button id="play" type="button">Play</button><button id="next" type="button">Step ▶</button><button id="end" type="button">End</button>
    <input id="step" type="range" min="0" max="0" value="0" aria-label="Step">
    <label>Speed <select id="speed"><option value="1">1</option><option value="4" selected>4</option><option value="16">16</option></select></label>
    <label>Colour by <select id="colorby"><option value="order">order taken</option><option value="f">f</option><option value="g">g</option><option value="m">costMalus</option></select></label>
    <label><input id="showpath" type="checkbox" checked> final path</label>
  </div>
  <div class="play">
    <div class="mapbox">
      <canvas id="map" width="640" height="640" aria-label="Top-down map of the test world with the search drawn on it"></canvas>
      <div id="stepno"></div><div id="hover">Point at a column for its height; click it for its block checks.</div>
      <div class="legend"><span><i style="background:var(--t-low)"></i>low ground</span><span><i style="background:var(--t-high)"></i>high ground (lit from the north-west; a thin line marks each one-block step)</span><span><i style="background:var(--water)"></i>water</span><span><i style="background:var(--drop-line)"></i>height change deeper than the fall distance</span><span><i style="background:var(--pop-a)"></i>→<i style="background:var(--pop-b)"></i>closed points</span><span><i style="border:2px solid var(--open)"></i>open points</span><span><i style="border:2px solid var(--current)"></i>this step</span><span><i style="background:var(--refused)"></i>refused or blocked step</span><span><i style="background:var(--path)"></i>final path</span></div>
    </div>
    <div id="panel" aria-live="polite"></div>
  </div>
</section>

<section id="checks">
  <h2>The block checks</h2>
  <p>For the column you clicked (or this step's point): the blocks in it, the drop check below the feet, the edge ring around it, and two side views through it with the search's points at their real heights.</p>
  <div class="checks">
    <figure><canvas id="column" aria-label="The blocks in the selected column"></canvas><figcaption>The column, y 0 at the bottom. The bracket is what <code>isDangerousDrop</code> looks at: fall distance + 1 blocks below the feet.</figcaption></figure>
    <figure><canvas id="ring" aria-label="The edge ring around the selected point"></canvas><figcaption id="ringinfo"></figcaption></figure>
    <div id="colinfo"></div>
  </div>
  <div class="sec"><span id="secx-label"></span><div class="scroll"><canvas id="secx" aria-label="Side view along x"></canvas></div></div>
  <div class="sec"><span id="secz-label"></span><div class="scroll"><canvas id="secz" aria-label="Side view along z"></canvas></div></div>
</section>

<section id="world3d">
  <h2>In 3D</h2>
  <p>The test world as recorded, with the barrier walls left out. Drag to turn, scroll to zoom. Points are coloured in the order the search took them, up to the current step; the line is the path it returned; the white bar is this step's point.</p>
  <label style="font-size:0.9rem"><input id="cut" type="checkbox"> Cut the world away east of the selected column, to see into the ravine</label>
  <div id="view3d"></div>
</section>

<section id="code">
  <h2>How the code runs</h2>
  <p>In the order it runs for one re-plan, each method whole, with its comments and its real line numbers. Fork lines carry the <span class="fk">KhaosKorps fork</span> marker.</p>
  {"".join(steps_html)}
</section>
</main>
<script type="application/json" id="index-data">{index}</script>
<script type="application/json" id="trace-data">{data}</script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js" integrity="sha384-CI3ELBVUz9XQO+97x6nwMDPosPR5XvsxW2ua7N1Xeygeh1IxtgqtCkGfQY9WWdHu" crossorigin="anonymous"></script>
<script>{viz}</script>
"""
OUT.write_text(page, encoding="utf-8")
print("wrote", OUT, len(page), "characters")
