"""Builds the recruit pathfinding visualiser page from the fork's source files and one GameTest run's recordings.

Every code block is copied from the source as it is on disk, whole methods with their comments, with
their real line numbers. The page reads the recorded runs from a runs/ folder written beside it, so it
has to be served over HTTP: a browser won't let a page opened straight from disk read them.

Run: python -I build_visualizer.py --clone <recruits clone> --viz <viz.js> --data <vizdata.js> --out <page .html> --runs <folder of run .json.gz> --log <GameTest server log>
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
parser.add_argument("--viz", required=True, help="the page's script, viz.js")
parser.add_argument("--data", required=True, help="the page's data layer, vizdata.js")
parser.add_argument("--out", required=True, help="the page to write; its runs/ folder goes beside it")
parser.add_argument("--runs", required=True, help="the folder of run recordings (.json.gz) written by RunRecorder")
parser.add_argument("--log", required=True, help="the GameTest server's log, for the pass and fail counts")
parser.add_argument("--notes", help="an HTML fragment to show under the findings, such as what a set of recordings showed")
ARGS = parser.parse_args()

CLONE = pathlib.Path(ARGS.clone)
VIZ = pathlib.Path(ARGS.viz)
DATA = pathlib.Path(ARGS.data)
OUT = pathlib.Path(ARGS.out)
RUNS = pathlib.Path(ARGS.runs)
LOG = pathlib.Path(ARGS.log)
for needed in (VIZ, DATA, LOG):
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

steps_html = []
for n, (title, thread, text, blocks) in enumerate(STEPS, 1):
    codes = "".join(code_block(rel, pat) for rel, pat in blocks)
    steps_html.append(f'<section class="step" id="step{n}"><div class="eyebrow">Step {n} · {html.escape(thread)}</div><h3>{html.escape(title)}</h3>{text}{codes}</section>')

viz = VIZ.read_text(encoding="utf-8")
vizdata = DATA.read_text(encoding="utf-8")

# The recorded runs: copied beside the page uncompressed (the page fetches them), and indexed.
import gzip
LIMIT = 16_000_000
runs_out = OUT.parent / "runs"
runs_out.mkdir(parents=True, exist_ok=True)
for old in runs_out.glob("*.json"):
    old.unlink()  # a test removed since the last build leaves no recording behind
runs = []
too_big = []
for f in sorted(RUNS.glob("*.json.gz")):
    body = gzip.decompress(f.read_bytes())
    target = runs_out / f.name.replace(".json.gz", ".json")
    target.write_bytes(body)
    if len(body) > LIMIT:
        too_big.append(f"{target.name} ({len(body) / 1e6:.1f} MB)")
    d = json.loads(body)
    group = d.get("recruits") or []
    outcomes = {}
    for member in group:
        outcomes[member["outcome"]] = outcomes.get(member["outcome"], 0) + 1
    searches = d["searches"]
    runs.append({"test": d["test"], "file": "runs/" + target.name, "outcome": d["outcome"], "message": d["message"],
                 "recruits": len(group) or 1, "outcomes": outcomes, "fall": d["fallDistance"],
                 "ticks": max([len(m["ticks"]) for m in group] or [len(d.get("ticks", []))]),
                 "searches": len(searches), "points": sum(s.get("n", 0) for s in searches),
                 "behind": sum(s.get("behind", 0) for s in searches), "same": sum(1 for s in searches if "same" in s),
                 "server": sum(1 for s in searches if s.get("server")), "queue": d.get("queue"),
                 "plans": sum(1 for p in d.get("plans", []) if p[1] in ("FOUND", "FAILED")),
                 "found": sum(1 for p in d.get("plans", []) if p[1] == "FOUND")})
if too_big:
    sys.exit("build_visualizer: recordings over the 16 MB a published page file may hold: " + ", ".join(too_big))


def short_name(test):
    return (test.replace("aFollowerGoesRoundARoughRavineAtFallDistance", "rough ravine, fall ")
            .replace("aGroupFollowsAcrossTheCapturedRavine_", "your ravine, ").replace("_seed", ", seed ")
            .replace("AtoB_", "A to B, group of ").replace("BtoA_", "B to A, group of "))


# Group runs on the captured ravine first, then the rough ravines.
runs.sort(key=lambda r: (0 if "CapturedRavine" in r["test"] else 1, r["test"]))
for r in runs:
    counts = ", ".join(f"{k} {v}" for k, v in sorted(r["outcomes"].items())) if r["outcomes"] else r["outcome"]
    r["label"] = f'{short_name(r["test"])}: {counts}'

log = LOG.read_text(encoding="utf-8", errors="replace")
req = re.search(r"All (\d+) required tests passed", log)
reqfail = re.search(r"(\d+) required tests failed", log)
opt = re.search(r"(\d+) optional tests failed", log)
summary_line = (f'{req.group(1)} required tests passed' if req else (f'{reqfail.group(1)} required tests failed' if reqfail else 'no summary found')) + (f', {opt.group(1)} optional tests failed' if opt else '')
failures = {}
for m in re.finditer(r"\]: (\S+) failed! (.*)", log):
    failures[m.group(1)] = m.group(2)[:300]
other_fail = "".join(f'<li><code>{html.escape(n)}</code>: {html.escape(m)}</li>' for n, m in failures.items()
                     if "roughravine" not in n.lower() and "capturedravine_" not in n.lower())

CHIP = {"reached": "--good", "dead": "--bad", "pit": "--warn", "edge": "--warn", "water": "--warn"}


def chips(r):
    if not r["outcomes"]:
        return html.escape(r["outcome"])
    return " ".join(f'<span class="chip" style="--chip:var({CHIP.get(k, "--muted")})">{html.escape(k)} {v}</span>' for k, v in sorted(r["outcomes"].items()))


run_rows = "".join(
    f'<tr><td>{html.escape(short_name(r["test"]))}</td><td class="num">{r["recruits"]}</td><td>{chips(r)}</td><td class="num">{r["fall"]}</td>'
    f'<td class="num">{r["ticks"]}</td><td class="num">{r["found"]} of {r["plans"]}</td>'
    f'<td><button type="button" data-explore="{i}">Explore</button></td></tr>' for i, r in enumerate(runs))
cost_rows = "".join(
    f'<tr><td>{html.escape(short_name(r["test"]))}</td><td class="num">{r["searches"]}</td><td class="num">{r["points"]}</td>'
    f'<td class="num">{round(r["points"] / r["searches"]) if r["searches"] else 0}</td>'
    f'<td class="num">{round(100 * r["behind"] / r["points"]) if r["points"] else 0}%</td>'
    f'<td class="num">{round(100 * r["same"] / r["searches"]) if r["searches"] else 0}%</td><td class="num">{r["server"]}</td>'
    f'<td class="num">{(r["queue"] or {}).get("fullTicks", "")}</td></tr>' for r in runs)
index = json.dumps({"runs": [{"label": r["label"], "file": r["file"]} for r in runs]}, separators=(",", ":")).replace("</", "<\\/")

page = f"""<title>Recruit Pathfinding Visualizer</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;600;700&family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap">
<style>
/* Layout: tabs over one column; Explore puts the map beside its side panel, stacking on narrow screens. */
:root {{
  --bg: #f3f4f1; --surface: #ffffff; --fg: #1b201c; --muted: #5d675f; --line: #d8dcd4; --accent: #2f7a4f;
  --good: #2f7a4f; --bad: #b8332b; --warn: #c06a12;
  --t-low: #3b4a40; --t-high: #c9d3c2; --t-wall: #2a2f2b; --water: #3f7fc0; --lava: #d4552b;
  --pop-a: #f2d16b; --pop-b: #b0367a; --open: #1f8a8a; --found: #1659d6; --walk: #7c3aed; --trail: #0f8f5f; --plan: #c2410c;
  --recruit: #e11d48; --heat: #ea580c; --marker: #ffffff; --marker-fg: #111111; --drop-line: #e07a1f; --step-line: rgba(0,0,0,0.18);
  --stone: #8a918a; --air: #eef1ec; --code-bg: #f0f2ee; --k: #7a3b8f; --t: #1f6f8b; --s: #8a5a12; --c: #6b776d; --n: #b0442a; --fk: #2f7a4f;
  --display: "Space Grotesk", "Segoe UI", system-ui, sans-serif; --body: "Inter", "Segoe UI", system-ui, sans-serif; --mono: "JetBrains Mono", Consolas, monospace;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --bg: #121512; --surface: #1a1e1b; --fg: #e3e8e2; --muted: #9aa69c; --line: #2c342e; --accent: #6cc28f;
  --good: #6cc28f; --bad: #f0857a; --warn: #f0a24a;
  --t-low: #26302a; --t-high: #a7b4a0; --t-wall: #0c0e0c; --water: #3a78bd; --lava: #e0602f;
  --pop-a: #f2d16b; --pop-b: #d14a95; --open: #45c4c4; --found: #6ea0ff; --walk: #b18cff; --trail: #34d399; --plan: #fb923c;
  --recruit: #fb7185; --heat: #fb923c; --marker: #ffffff; --marker-fg: #111111; --drop-line: #ff9a3c; --step-line: rgba(255,255,255,0.16);
  --stone: #5d645e; --air: #1f2420; --code-bg: #0f120f; --k: #d4a2e6; --t: #7cc6de; --s: #e2b86a; --c: #7f8c81; --n: #f0937a; --fk: #8fd8aa; color-scheme: dark; }} }}
:root[data-theme="dark"] {{
  --bg: #121512; --surface: #1a1e1b; --fg: #e3e8e2; --muted: #9aa69c; --line: #2c342e; --accent: #6cc28f;
  --good: #6cc28f; --bad: #f0857a; --warn: #f0a24a;
  --t-low: #26302a; --t-high: #a7b4a0; --t-wall: #0c0e0c; --water: #3a78bd; --lava: #e0602f;
  --pop-a: #f2d16b; --pop-b: #d14a95; --open: #45c4c4; --found: #6ea0ff; --walk: #b18cff; --trail: #34d399; --plan: #fb923c;
  --recruit: #fb7185; --heat: #fb923c; --marker: #ffffff; --marker-fg: #111111; --drop-line: #ff9a3c; --step-line: rgba(255,255,255,0.16);
  --stone: #5d645e; --air: #1f2420; --code-bg: #0f120f; --k: #d4a2e6; --t: #7cc6de; --s: #e2b86a; --c: #7f8c81; --n: #f0937a; --fk: #8fd8aa; color-scheme: dark; }}
body {{ background: var(--bg); color: var(--fg); font-family: var(--body); font-size: 15px; line-height: 1.55;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); padding-block: 24px 64px; }}
main {{ max-width: 1240px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }}
section {{ display: flex; flex-direction: column; gap: 14px; min-width: 0; }}
h1, h2, h3, h4 {{ font-family: var(--display); margin: 0; line-height: 1.2; text-wrap: balance; }}
h1 {{ font-size: 1.8rem; }} h2 {{ font-size: 1.3rem; }} h3 {{ font-size: 1.08rem; }} h4 {{ font-size: 0.92rem; margin-top: 4px; }}
p {{ margin: 0; max-width: 80ch; }}
code {{ font-family: var(--mono); font-size: 0.86em; background: var(--code-bg); padding: 1px 5px; border-radius: 4px; }}
.eyebrow {{ font-family: var(--mono); font-size: 0.74rem; letter-spacing: 0.08em; text-transform: uppercase; color: var(--accent); }}
.dim {{ color: var(--muted); }} .good {{ color: var(--good); font-weight: 600; }} .bad {{ color: var(--bad); font-weight: 600; }}
.scroll {{ overflow-x: auto; min-width: 0; }}
table {{ border-collapse: collapse; font-size: 0.86rem; width: 100%; }}
th, td {{ text-align: left; padding: 5px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }}
th {{ font-family: var(--display); color: var(--muted); font-weight: 600; font-size: 0.78rem; }}
.num, table.math td {{ font-variant-numeric: tabular-nums; }}
table.math td {{ font-family: var(--mono); font-size: 0.76rem; white-space: nowrap; }}
nav.tabs {{ display: flex; flex-wrap: wrap; gap: 4px; border-bottom: 1px solid var(--line); }}
nav.tabs a {{ padding: 8px 14px; color: var(--muted); text-decoration: none; font-family: var(--display); font-weight: 600; border-bottom: 2px solid transparent; }}
nav.tabs a[aria-current="page"] {{ color: var(--fg); border-bottom-color: var(--accent); }}
.bar {{ display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: center; background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; }}
.bar label {{ display: flex; gap: 6px; align-items: center; font-size: 0.88rem; }}
select, button, input[type=range] {{ font: inherit; font-size: 0.88rem; }}
select {{ max-width: 100%; padding: 4px 6px; border-radius: 6px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); }}
button {{ padding: 4px 12px; border-radius: 6px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); cursor: pointer; }}
button:hover {{ border-color: var(--accent); }}
button.link {{ border: 0; padding: 0; background: none; color: var(--accent); text-decoration: underline; font-weight: 600; }}
#tick, #s-step {{ flex: 1 1 260px; min-width: 160px; }}
.chip {{ display: inline-block; padding: 1px 8px; border-radius: 999px; font-size: 0.78rem; font-weight: 600; color: var(--chip); border: 1px solid var(--chip); }}
.legend {{ display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 0.8rem; color: var(--muted); }}
.legend i {{ display: inline-block; width: 18px; height: 0; border-top: 3px solid; vertical-align: middle; margin-right: 4px; }}
.legend i.box {{ width: 11px; height: 11px; border: 0; border-radius: 2px; }}
.explore {{ display: grid; grid-template-columns: minmax(0, 1fr) minmax(260px, 340px); gap: 16px; align-items: start; }}
@media (max-width: 960px) {{ .explore {{ grid-template-columns: minmax(0, 1fr); }} }}
.mapbox {{ display: flex; flex-direction: column; gap: 6px; min-width: 0; }}
#map, #s-map {{ border-radius: 6px; display: block; max-width: 100%; }}
#s-map {{ cursor: crosshair; }}
#hover, #tickno, #s-stepno {{ font-family: var(--mono); font-size: 0.78rem; color: var(--muted); min-height: 1.2em; }}
.side {{ display: flex; flex-direction: column; gap: 12px; min-width: 0; font-size: 0.88rem; }}
.side .card {{ background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 6px; min-width: 0; }}
#recruits {{ max-height: 260px; overflow: auto; }}
#recruits tr[data-follow] {{ cursor: pointer; }}
#recruits tr.on td {{ background: var(--code-bg); font-weight: 600; }}
ol.steps, ul.near, ul.cands {{ margin: 0; padding-left: 1.2em; display: flex; flex-direction: column; gap: 3px; font-size: 0.84rem; }}
#timeline {{ display: block; cursor: pointer; border-radius: 8px; border: 1px solid var(--line); }}
.searchgrid {{ display: grid; grid-template-columns: minmax(0, 660px) minmax(0, 1fr); gap: 16px; align-items: start; }}
@media (max-width: 960px) {{ .searchgrid {{ grid-template-columns: minmax(0, 1fr); }} }}
#s-panel {{ display: flex; flex-direction: column; gap: 10px; min-width: 0; font-size: 0.9rem; }}
.kv {{ display: grid; grid-template-columns: auto auto auto auto; gap: 2px 10px; justify-content: start; font-size: 0.85rem; }}
.kv span {{ color: var(--muted); }}
.eq {{ font-family: var(--mono); font-size: 0.82rem; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }}
.note {{ background: var(--surface); border: 1px solid var(--line); border-left: 3px solid var(--accent); border-radius: 6px; padding: 8px 10px; }}
.checks {{ display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 18px; align-items: start; }}
@media (max-width: 760px) {{ .checks {{ grid-template-columns: minmax(0, 1fr); }} }}
.sec {{ display: flex; flex-direction: column; gap: 4px; min-width: 0; }}
.sec span {{ font-size: 0.82rem; color: var(--muted); }}
#view3d {{ background: var(--surface); border: 1px solid var(--line); border-radius: 10px; overflow: hidden; min-height: 300px; touch-action: none; }}
#view3d canvas {{ display: block; cursor: grab; }}
details {{ background: var(--surface); border: 1px solid var(--line); border-radius: 8px; min-width: 0; }}
summary {{ padding: 8px 12px; cursor: pointer; font-size: 0.88rem; display: flex; flex-wrap: wrap; gap: 4px 12px; align-items: baseline; }}
summary .where {{ font-family: var(--mono); font-size: 0.75rem; color: var(--muted); }}
pre.code {{ margin: 0; padding: 10px 0; background: var(--code-bg); overflow-x: auto; font-family: var(--mono); font-size: 0.76rem; line-height: 1.5; border-top: 1px solid var(--line); }}
.ln {{ display: block; padding: 0 14px 0 0; white-space: pre; }}
.ln::before {{ content: attr(data-n); display: inline-block; width: 3.6em; padding-right: 1em; text-align: right; color: var(--muted); opacity: 0.6; user-select: none; }}
.k {{ color: var(--k); }} .t {{ color: var(--t); }} .s {{ color: var(--s); }} .c {{ color: var(--c); font-style: italic; }} .n {{ color: var(--n); }} .fk {{ color: var(--fk); font-weight: 600; }}
:focus-visible {{ outline: 2px solid var(--accent); outline-offset: 2px; }}
@media (prefers-reduced-motion: reduce) {{ * {{ scroll-behavior: auto !important; }} }}
</style>
<main>
<header style="display:flex;flex-direction:column;gap:8px">
  <div class="eyebrow">Villager Recruits fork · commit {COMMIT} · {html.escape(summary_line)}</div>
  <h1>Recruit Pathfinding Visualizer</h1>
  <p class="dim">Recorded GameTest runs replayed over the test world's real blocks: where every recruit walked, what each search found, what the detour planner tried, and the math behind any single search.</p>
</header>
<nav class="tabs" aria-label="Views"><a href="#runs">Runs</a><a href="#explore">Explore a run</a><a href="#search">One search</a><a href="#code">The code</a></nav>

<section id="v-runs">
  <h2>Runs</h2>
  <p>Every recorded test from the last test run. <b>Explore</b> opens one: its map, every recruit's route, the planner's stepping stones and a timeline.</p>
  <div class="scroll"><table>
    <thead><tr><th>Test</th><th>Recruits</th><th>How they ended</th><th>Fall distance</th><th>Ticks</th><th>Detour plans found</th><th></th></tr></thead>
    <tbody>{run_rows}</tbody>
  </table></div>
  <h3>What the searching cost</h3>
  <p class="dim">Points: the spots each search took off its open set, the work it did. Behind the start: points on the far side of a search's start from its target. Repeats: searches identical to an earlier one (same start, target, points and route). A search runs on the server thread when the path worker's queue is full, slowing the whole server.</p>
  <div class="scroll"><table>
    <thead><tr><th>Test</th><th>Searches</th><th>Points</th><th>Points a search</th><th>Behind the start</th><th>Repeats</th><th>On the server thread</th><th>Queue full (ticks)</th></tr></thead>
    <tbody>{cost_rows}</tbody>
  </table></div>
  <details><summary>Other tests that failed in this run</summary><ul style="margin:8px 0;padding:0 16px 0 32px">{other_fail or '<li>None.</li>'}</ul></details>
  {NOTES}
</section>

<section id="v-explore" hidden>
  <div class="bar">
    <label>Run <select id="run-select" aria-label="Run"></select></label><span id="run-status" class="dim"></span>
  </div>
  <h2 id="run-title"></h2>
  <p id="run-summary"></p>
  <div class="bar">
    <button id="play" type="button">Play</button>
    <input id="tick" type="range" min="0" max="0" value="0" aria-label="Tick">
    <span id="tickno"></span>
    <label>Speed <select id="speed"><option value="2">slow</option><option value="10" selected>normal</option><option value="40">fast</option></select></label>
  </div>
  <div class="bar">
    <label><input id="ly-walked" type="checkbox"> where recruits walked</label>
    <label><input id="ly-searches" type="checkbox"> the followed recruit's current route</label>
    <label><input id="ly-routes" type="checkbox"> every route it found so far</label>
    <label><input id="ly-planner" type="checkbox"> planner's stepping stones</label>
    <label><input id="ly-heat" type="checkbox"> where all searches looked</label>
  </div>
  <div class="legend">
    <span><i style="border-color:var(--trail)"></i>followed recruit's walk</span>
    <span><i style="border-color:var(--good)"></i>others: reached</span><span><i style="border-color:var(--warn)"></i>pit, edge or water</span><span><i style="border-color:var(--bad)"></i>dead</span><span><i style="border-color:var(--muted)"></i>stuck</span>
    <span><i style="border-color:var(--found);border-top-style:dashed"></i>route a search found</span><span><i style="border-color:var(--walk)"></i>part kept after edge care</span>
    <span><i class="box" style="background:var(--good)"></i>stepping stone reached</span><span><i class="box" style="background:var(--accent)"></i>being tried</span>
    <span>✕ <span style="color:var(--bad)">no way there</span> · <span style="color:var(--warn)">near a deadly drop</span> · nothing to stand on</span>
    <span><i class="box" style="background:var(--heat)"></i>searched here</span><span><i style="border-color:var(--drop-line)"></i>drop deeper than the fall distance</span>
  </div>
  <div class="explore">
    <div class="mapbox">
      <canvas id="map" aria-label="Map of the run: the ground, the recruits' routes, searches and stepping stones"></canvas>
      <div id="hover">Point at the map for a column's height.</div>
      <h3>Distance to the owner over the run</h3>
      <p class="dim">One line per recruit, the followed one bold; green and red lines mark detour plans found and failed. Click to jump to that tick.</p>
      <canvas id="timeline" aria-label="Each recruit's distance to its owner over the run"></canvas>
    </div>
    <div class="side">
      <div class="card"><h4>Recruits</h4><p class="dim">Click one to follow it.</p><div id="recruits"></div></div>
      <div class="card"><h4>Followed recruit now</h4><div id="now"></div></div>
      <div class="card"><h4>Detour planner</h4><div id="planner"></div></div>
      <div class="card"><h4>Its searches near this tick</h4><div id="near"></div></div>
    </div>
  </div>
</section>

<section id="v-search" hidden>
  <div class="bar"><button id="s-back" type="button">← Back to the run</button></div>
  <div id="s-head"></div>
  <div class="bar">
    <button id="s-prev" type="button">◀ Point</button><button id="s-play" type="button">Play</button><button id="s-next" type="button">Point ▶</button><button id="s-end" type="button">End</button>
    <input id="s-step" type="range" min="0" max="0" value="0" aria-label="Point">
    <span id="s-stepno"></span>
    <label>Speed <select id="s-speed"><option value="1">1</option><option value="4" selected>4</option><option value="16">16</option></select></label>
    <label>Colour by <select id="s-color"><option value="order">order taken</option><option value="f">f</option><option value="g">g</option><option value="m">cost</option></select></label>
  </div>
  <div class="searchgrid">
    <div class="mapbox">
      <canvas id="s-map" aria-label="The search's points, in the order taken"></canvas>
      <div class="legend"><span><i class="box" style="background:var(--pop-a)"></i>→<i class="box" style="background:var(--pop-b)"></i>points taken, first to last</span><span><i class="box" style="border:2px solid var(--open)"></i>this point's neighbours</span><span><i style="border-color:var(--found);border-top-style:dashed"></i>route found</span><span><i style="border-color:var(--walk)"></i>part kept</span></div>
    </div>
    <div id="s-panel"></div>
  </div>
  <h3>The blocks around the selected column</h3>
  <div class="checks">
    <canvas id="column" aria-label="The blocks in the selected column"></canvas>
    <div id="colinfo"></div>
  </div>
  <div class="sec"><span id="secx-label"></span><div class="scroll"><canvas id="secx" aria-label="Side view along x"></canvas></div></div>
  <div class="sec"><span id="secz-label"></span><div class="scroll"><canvas id="secz" aria-label="Side view along z"></canvas></div></div>
  <h3>In 3D</h3>
  <p class="dim">The test world with the search's points and the route it found. Drag to turn, scroll to zoom.</p>
  <label style="font-size:0.9rem"><input id="cut" type="checkbox"> Cut the world away east of the selected column</label>
  <div id="view3d"></div>
</section>

<section id="v-code" hidden>
  <h2>How the code runs</h2>
  <p>In the order it runs for one re-plan, each method whole, with its comments and its real line numbers. Fork lines carry the <span class="fk">KhaosKorps fork</span> marker.</p>
  {"".join(steps_html)}
</section>
</main>
<script type="application/json" id="index-data">{index}</script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js" integrity="sha384-CI3ELBVUz9XQO+97x6nwMDPosPR5XvsxW2ua7N1Xeygeh1IxtgqtCkGfQY9WWdHu" crossorigin="anonymous"></script>
<script>{vizdata}</script>
<script>{viz}</script>
"""
OUT.write_text(page, encoding="utf-8")
print("wrote", OUT, len(page), "characters,", len(runs), "runs")
