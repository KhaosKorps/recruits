package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.DetourPlan;
import com.talhanation.recruits.khaoskorps.DetourRoutes;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import com.talhanation.recruits.pathfinding.AsyncPath;
import com.talhanation.recruits.pathfinding.AsyncPathNavigation;
import com.talhanation.recruits.pathfinding.AsyncPathProcessor;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.zip.GZIPOutputStream;

import static com.talhanation.recruits.khaoskorps.gametest.Recordings.num;
import static com.talhanation.recruits.khaoskorps.gametest.Recordings.quote;
import static com.talhanation.recruits.khaoskorps.gametest.Recordings.xyz;

/**
 * Records a whole group's test run for the pathfinding visualiser: every recruit's position each
 * tick, a summary of every search each recruit's navigation and the detour planner make for it, the
 * points of a sample of those searches, the owner's detour plan, each death and its cause, how full
 * the path worker's queue got, and a heat map of every point any search took off its open set.
 *
 * One per test, for one recruit or many. Every search keeps the route it found. Points are kept for
 * every detour search up to {@link #DETOUR_POINTS}, and for one follow search per recruit every
 * {@code followSampleTicks} (every follow search when 0); the other searches keep their counts only,
 * which is what makes a long run of a large group fit in memory. Full detail (every step asked for and
 * every neighbour's values) is kept for up to {@code detailLimit} searches, the first from each start to
 * each target. The run is written once, to runs/&lt;test name&gt;.json.gz in the test server's folder.
 */
final class GroupRecorder {

    static final int FOLLOW_SAMPLE_TICKS = 200;
    static final int DETOUR_POINTS = 300;

    private final String name;
    private final GameTestHelper helper;
    private final ServerPlayer owner;
    private final BlockPos origin;
    private final int sizeX, sizeY, sizeZ;
    private final String setup;
    private final long seed;
    private final int followSampleTicks;
    private final int detailLimit;
    private final Set<String> detailedKeys = new HashSet<>();
    private int detailed;
    private final List<Member> members = new ArrayList<>();
    private final AtomicIntegerArray heatFollow;
    private final AtomicIntegerArray heatDetour;
    private final AtomicInteger detourKept = new AtomicInteger();
    private final PlanSteps planSteps;
    private final List<String> plans = new ArrayList<>();
    private String lastPlan = "";
    private int maxQueued;
    private int queueFullTicks;
    private int ticks;
    private boolean written;

    /** A group run: one follow search per recruit every {@link #FOLLOW_SAMPLE_TICKS} keeps its points, none keeps full detail. */
    GroupRecorder(String name, GameTestHelper helper, ServerPlayer owner, int sizeX, int sizeY, int sizeZ, String setup) {
        this(name, helper, owner, sizeX, sizeY, sizeZ, setup, 0, FOLLOW_SAMPLE_TICKS, 0);
    }

    /**
     * @param setup             a JSON object describing the test's setup (direction, group size, spawn
     *                          points), written into the run as it is
     * @param seed              the test terrain's seed, 0 for none
     * @param followSampleTicks how often a recruit's follow search keeps its points, in ticks; 0 for every one
     * @param detailLimit       how many searches keep full detail
     */
    GroupRecorder(String name, GameTestHelper helper, ServerPlayer owner, int sizeX, int sizeY, int sizeZ, String setup,
                  long seed, int followSampleTicks, int detailLimit) {
        this.name = name;
        this.helper = helper;
        this.owner = owner;
        this.origin = helper.absolutePos(BlockPos.ZERO);
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.setup = setup;
        this.seed = seed;
        this.followSampleTicks = followSampleTicks;
        this.detailLimit = detailLimit;
        this.heatFollow = new AtomicIntegerArray(sizeX * sizeZ);
        this.heatDetour = new AtomicIntegerArray(sizeX * sizeZ);
        this.planSteps = new PlanSteps(helper);
    }

    /** The detour planner's steps for this run's recruits, so far. */
    PlanSteps planSteps() {
        return this.planSteps;
    }

    /** Starts recording {@code recruit}: its navigation gets a tracing path finder, which runs the real search and records it. */
    Member add(AbstractRecruitEntity recruit) {
        if (!(recruit.getNavigation() instanceof AsyncPathNavigation navigation)) {
            throw new GameTestAssertException("setup: a recruit's navigation can't be recorded: " + recruit.getNavigation());
        }
        Member member = new Member(this.members.size(), recruit);
        try {
            Field budgetField = AsyncPathNavigation.class.getDeclaredField("baseSearchBudget");
            budgetField.setAccessible(true);
            member.budget = budgetField.getInt(navigation);
            Field finder = AsyncPathNavigation.class.getDeclaredField("pathFinder");
            finder.setAccessible(true);
            Recordings.recordInto(recruit, member);
            this.planSteps.watch(recruit);
            TracingDetourEvaluator.fillThePool();
            finder.set(navigation, RecruitsServerConfig.UseAsyncPathfinding.get()
                    ? new TracingPathfinders.Async(member.budget, member, member, this.helper.getLevel())
                    : new TracingPathfinders.Sync(member.budget, member, member));
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("setup: can't attach the group recorder: " + e);
        }
        this.members.add(member);
        return member;
    }

    List<Member> members() {
        return this.members;
    }

    /** Notes every recruit's position and state, deaths, the queue, and the owner's detour plan. Server thread. */
    void tick() {
        if (this.written) return;
        this.ticks++;
        int queued = AsyncPathProcessor.queuedJobs();
        this.maxQueued = Math.max(this.maxQueued, queued);
        if (queued >= Math.max(1, RecruitsServerConfig.AsyncPathfindingThreadsCount.get()) * 8) this.queueFullTicks++;
        for (Member member : this.members) member.tick();
        DetourPlan plan = DetourRoutes.plan(this.owner.getUUID());
        String key = plan == null ? "NONE" : plan.status() + ":" + plan.route().size() + ":" + plan.searches();
        if (!key.equals(this.lastPlan)) {
            this.lastPlan = key;
            StringBuilder entry = new StringBuilder("[").append(this.helper.getTick()).append(",\"")
                    .append(plan == null ? "NONE" : plan.status().name()).append("\",").append(plan == null ? 0 : plan.searches()).append(",[");
            if (plan != null) {
                for (int i = 0; i < plan.route().size(); i++) {
                    if (i > 0) entry.append(',');
                    entry.append(this.rel(plan.route().get(i)));
                }
            }
            this.plans.add(entry.append("]]").toString());
        }
    }

    /** Stops recording without writing anything, for a test that checks the recording in memory. */
    void discard() {
        this.written = true;
        for (Member member : this.members) {
            Recordings.forget(member.recruit);
            PlanSteps.unwatch(member.recruit);
        }
    }

    /** Writes the run once, with how it ended and each recruit's final state as the test judged it. */
    void finish(String outcome, String message) {
        if (this.written) return;
        this.written = true;
        for (Member member : this.members) {
            Recordings.forget(member.recruit);
            PlanSteps.unwatch(member.recruit);
        }
        java.nio.file.Path file = java.nio.file.Path.of("runs", this.name + ".json.gz").toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file));
                 Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                this.write(writer, outcome, message);
            }
            int searches = 0;
            for (Member m : this.members) searches += m.records.size();
            Main.LOGGER.info("[KhaosKorps measure] group run recorded: {} ({}, {} recruits, {} searches, {} ticks, {} bytes)",
                    file, outcome, this.members.size(), searches, this.ticks, Files.size(file));
        } catch (IOException e) {
            Main.LOGGER.warn("[KhaosKorps measure] could not write the group run recording {}", file, e);
        }
    }

    private void write(Writer w, String outcome, String message) throws IOException {
        FollowSettings settings = FollowSettings.current();
        int budget = this.members.isEmpty() ? 0 : this.members.get(0).budget;
        int[] offset = new int[this.members.size()];
        List<SearchTracer.SearchRecord> all = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        for (Member m : this.members) {
            offset[m.index] = all.size();
            synchronized (m) {
                all.addAll(m.records);
                for (int i = 0; i < m.records.size(); i++) owners.add(m.index);
            }
        }
        w.write("{\"test\":" + quote(this.name) + ",\"group\":true,\"seed\":" + this.seed + ",\"fallDistance\":" + settings.maxFallDistance()
                + ",\"async\":" + RecruitsServerConfig.UseAsyncPathfinding.get() + ",\"threads\":" + RecruitsServerConfig.AsyncPathfindingThreadsCount.get()
                + ",\"dirtPath\":" + quote(settings.dirtPathPreference().name()) + ",\"margin\":" + settings.edgeMarginBlocks()
                + ",\"range\":32,\"budget\":" + budget + ",\"outcome\":" + quote(outcome) + ",\"message\":" + quote(message)
                + ",\"endTick\":" + this.helper.getTick() + ",\"tickOffset\":" + (this.helper.getLevel().getGameTime() - this.helper.getTick())
                + ",\"setup\":" + this.setup
                + ",\"queue\":{\"max\":" + this.maxQueued + ",\"fullTicks\":" + this.queueFullTicks + "}"
                + ",\"size\":[" + this.sizeX + "," + this.sizeY + "," + this.sizeZ + "],\"blocks\":\"");
        w.write(Recordings.blocks(this.helper, this.sizeX, this.sizeY, this.sizeZ));
        w.write("\",\"owner\":" + xyz(this.owner.getBlockX() - this.origin.getX(), this.owner.getBlockY() - this.origin.getY(), this.owner.getBlockZ() - this.origin.getZ()));
        w.write(",\"heat\":{\"follow\":" + heat(this.heatFollow) + ",\"detour\":" + heat(this.heatDetour) + "}");
        w.write(",\"plans\":[" + String.join(",", this.plans) + "],\"planSteps\":" + this.planSteps.json());
        // Found routes, each written once: a stuck recruit finds the same route again and again.
        Map<String, Integer> routeIds = new HashMap<>();
        List<String> routes = new ArrayList<>();
        int[] routeOf = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            int[] found = all.get(i).found;
            if (found == null) {
                routeOf[i] = -1;
                continue;
            }
            StringBuilder route = new StringBuilder("[");
            for (int p = 0; p < found.length; p += 3) {
                if (p > 0) route.append(',');
                route.append(xyz(found[p] - this.origin.getX(), found[p + 1] - this.origin.getY(), found[p + 2] - this.origin.getZ()));
            }
            String key = route.append(']').toString();
            routeOf[i] = routeIds.computeIfAbsent(key, k -> {
                routes.add(k);
                return routes.size() - 1;
            });
        }
        w.write(",\"routes\":[" + String.join(",", routes) + "],\"recruits\":[");
        for (Member m : this.members) {
            if (m.index > 0) w.write(',');
            m.write(w, offset[m.index]);
        }
        // The first recruit's ticks at the top level too, so a viewer of single runs still plays something.
        w.write("],\"ticks\":");
        if (this.members.isEmpty()) w.write("[]");
        else this.members.get(0).writeTicks(w, offset[0]);
        w.write(",\"searches\":[");
        Set<Integer> walked = new HashSet<>();
        for (Member m : this.members) for (int id : m.walkedIds) walked.add(offset[m.index] + id);
        Map<Integer, Integer> kept = new HashMap<>();
        for (Member m : this.members) m.keptCount.forEach((local, count) -> kept.put(offset[m.index] + local, count));
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) w.write(',');
            this.writeSearch(w, i, owners.get(i), all.get(i), walked.contains(i), routeOf[i], kept.getOrDefault(i, -1), seen);
        }
        w.write("]}");
    }

    /**
     * One search: its summary, its found route (an index into the routes table), how many of that route's
     * points were kept after edge care if a recruit walked it (else -1), and its points when kept, each
     * [x, y, z, expanded (1 or 0), index of the point it was reached from or -1].
     */
    private void writeSearch(Writer w, int id, int recruit, SearchTracer.SearchRecord r, boolean walked, int route, int keptCount,
                             Map<String, Integer> seen) throws IOException {
        Path path = r.path;
        boolean done = r.finished && (path == null || !(path instanceof AsyncPath async) || async.isProcessed());
        boolean reached = "detour".equals(r.kind) ? r.reachedByPop : done && path != null && path.canReach();
        StringBuilder head = new StringBuilder("{\"id\":").append(id).append(",\"r\":").append(recruit).append(",\"kind\":").append(quote(r.kind))
                .append(",\"tick\":").append(r.tick)
                .append(",\"start\":").append(r.start == null ? "null" : this.rel(r.start))
                .append(",\"target\":").append(r.targets.isEmpty() ? "null" : this.rel(r.targets.get(0)))
                .append(",\"dirtPath\":").append(quote(r.dirtPath)).append(",\"margin\":").append(r.margin).append(",\"maxFall\":").append(r.maxFall)
                .append(",\"done\":").append(done).append(",\"noStart\":").append(r.noStart).append(",\"reached\":").append(reached)
                .append(",\"n\":").append(r.popCount).append(",\"x\":").append(r.expandedCount).append(",\"behind\":").append(r.behindCount)
                .append(",\"server\":").append(r.onServerThread)
                .append(",\"found\":").append(route).append(",\"foundReached\":").append(r.foundReached).append(",\"kept\":").append(keptCount);
        // Whether the path a recruit walked, as edge care left it, is the start of the route the search found.
        String keptIsStart = "null";
        if (walked && done && path != null && r.found != null) {
            boolean start = path.getNodeCount() * 3 <= r.found.length;
            for (int i = 0; start && i < path.getNodeCount(); i++) {
                Node node = path.getNode(i);
                start = node.x == r.found[3 * i] && node.y == r.found[3 * i + 1] && node.z == r.found[3 * i + 2];
            }
            keptIsStart = String.valueOf(start);
        }
        head.append(",\"keptIsStart\":").append(keptIsStart);
        if (!r.keepPoints) {
            w.write(head.append(",\"detail\":false,\"pops\":null}").toString());
            return;
        }
        String signature = r.kind + "|" + head.substring(head.indexOf(",\"start\""), head.indexOf(",\"server\"")) + "|" + route;
        Integer same = seen.get(signature);
        if (same != null) {
            w.write(head.append(",\"same\":").append(same).append('}').toString());
            return;
        }
        seen.put(signature, id);
        w.write(head.append(",\"detail\":").append(r.detail).append(",\"pops\":[").toString());
        Map<Long, Integer> index = new HashMap<>();
        for (int p = 0; p < r.pops.size(); p++) {
            SearchTracer.Pop pop = r.pops.get(p);
            if (p > 0) w.write(',');
            index.put(BlockPos.asLong(pop.x, pop.y, pop.z), p);
            Integer from = pop.from == null ? null : index.get(BlockPos.asLong(pop.from[0], pop.from[1], pop.from[2]));
            int x = pop.x - this.origin.getX(), y = pop.y - this.origin.getY(), z = pop.z - this.origin.getZ();
            if (!r.detail) {
                w.write("[" + x + "," + y + "," + z + "," + (pop.expanded ? 1 : 0) + "," + (from == null ? -1 : from) + "]");
                continue;
            }
            // Full detail: [x, y, z, g, h, f, costMalus, edge distance, edge ring, reached-from index, steps asked, neighbours].
            StringBuilder s = new StringBuilder("[").append(x).append(',').append(y).append(',').append(z).append(',')
                    .append(num(pop.g)).append(',').append(num(pop.h)).append(',').append(num(pop.f)).append(',').append(num(pop.costMalus)).append(',')
                    .append(pop.edgeDistance).append(",\"").append(pop.expanded ? pop.ring : "-").append("\",").append(from == null ? -1 : from).append(",[");
            for (int c = 0; c < pop.candidates.size(); c++) {
                SearchTracer.Candidate cand = pop.candidates.get(c);
                if (c > 0) s.append(',');
                s.append("[\"").append(cand.direction().charAt(0)).append("\",\"").append(cand.result().charAt(0)).append("\",")
                        .append(cand.askX() - this.origin.getX()).append(',').append(cand.askY() - this.origin.getY()).append(',').append(cand.askZ() - this.origin.getZ()).append(',')
                        .append(cand.y() - this.origin.getY()).append(',').append(num(cand.costMalus())).append(',').append(cand.deadEndSteps()).append(']');
            }
            s.append("],[");
            for (int n = 0; n < pop.neighbours.size(); n++) {
                SearchTracer.Neighbour nb = pop.neighbours.get(n);
                if (n > 0) s.append(',');
                s.append('[').append(nb.x - this.origin.getX()).append(',').append(nb.y - this.origin.getY()).append(',').append(nb.z - this.origin.getZ()).append(',')
                        .append(num(nb.costMalus)).append(',').append(num(nb.gBefore)).append(',').append(nb.openBefore ? 1 : 0).append(',')
                        .append(num(nb.gAfter)).append(',').append(num(nb.hAfter)).append(',').append(num(nb.fAfter)).append(',').append(nb.updated ? 1 : 0).append(']');
            }
            w.write(s.append("]]").toString());
        }
        w.write("]}");
    }

    private static String heat(AtomicIntegerArray grid) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < grid.length(); i++) {
            if (i > 0) out.append(',');
            out.append(grid.get(i));
        }
        return out.append(']').toString();
    }

    private String rel(int[] p) {
        return xyz(p[0] - this.origin.getX(), p[1] - this.origin.getY(), p[2] - this.origin.getZ());
    }

    private String rel(BlockPos p) {
        return xyz(p.getX() - this.origin.getX(), p.getY() - this.origin.getY(), p.getZ() - this.origin.getZ());
    }

    /** One recruit's part of the recording; the sink and pairing for its navigation's tracing path finder. */
    final class Member implements SearchTracer.Sink, TracingPathfinders.Pairing {
        final int index;
        final AbstractRecruitEntity recruit;
        int budget;
        final List<SearchTracer.SearchRecord> records = new ArrayList<>();
        private final IdentityHashMap<Path, Integer> pathIds = new IdentityHashMap<>();
        private final Set<Integer> walkedIds = new HashSet<>();
        /** For each walked search, the fewest points its path had while walked: what edge care kept. */
        final Map<Integer, Integer> keptCount = new HashMap<>();
        private SearchTracer.SearchRecord prepared;
        /** The game tick of the last follow search that kept its points; far enough back that the first one does. */
        private long lastKeptFollow = -2L * FOLLOW_SAMPLE_TICKS;
        private final FloatArrayList positions = new FloatArrayList();
        private final IntArrayList states = new IntArrayList();
        int[] spawn;
        long deathTick = -1;
        String deathCause;
        String deathAt;
        long reachedTick = -1;
        double closest = Double.MAX_VALUE;
        String outcome = "unjudged";

        Member(int index, AbstractRecruitEntity recruit) {
            this.index = index;
            this.recruit = recruit;
            BlockPos at = recruit.blockPosition().subtract(GroupRecorder.this.origin);
            this.spawn = new int[]{at.getX(), at.getY(), at.getZ()};
        }

        @Override
        public synchronized void prepared(SearchTracer.SearchRecord record) {
            this.records.add(record);
            if ("follow".equals(record.kind)) this.prepared = record;
        }

        @Override
        public boolean detail(SearchTracer.SearchRecord record) {
            GroupRecorder group = GroupRecorder.this;
            synchronized (group.detailedKeys) {
                if (group.detailed >= group.detailLimit) return false;
                String key = record.kind + ":" + (record.start == null ? "-" : group.rel(record.start)) + ">"
                        + (record.targets.isEmpty() ? "-" : group.rel(record.targets.get(0)));
                if (!group.detailedKeys.add(key)) return false;
                group.detailed++;
                return true;
            }
        }

        @Override
        public synchronized boolean keepPoints(SearchTracer.SearchRecord record) {
            if ("detour".equals(record.kind)) return GroupRecorder.this.detourKept.incrementAndGet() <= DETOUR_POINTS;
            if (record.tick - this.lastKeptFollow < GroupRecorder.this.followSampleTicks) return false;
            this.lastKeptFollow = record.tick;
            return true;
        }

        @Override
        public void point(SearchTracer.SearchRecord record, int x, int y, int z, boolean expanded) {
            int rx = x - GroupRecorder.this.origin.getX(), rz = z - GroupRecorder.this.origin.getZ();
            if (rx < 0 || rz < 0 || rx >= GroupRecorder.this.sizeX || rz >= GroupRecorder.this.sizeZ) return;
            ("detour".equals(record.kind) ? GroupRecorder.this.heatDetour : GroupRecorder.this.heatFollow).incrementAndGet(rz * GroupRecorder.this.sizeX + rx);
        }

        @Override
        public void finished(SearchTracer.SearchRecord record) {
        }

        @Override
        public synchronized SearchTracer.SearchRecord takePrepared() {
            SearchTracer.SearchRecord record = this.prepared;
            this.prepared = null;
            return record;
        }

        @Override
        public synchronized void returned(SearchTracer.SearchRecord record, Path path) {
            if (path == null) {
                record.noStart = true;
                return;
            }
            record.path = path;
            this.pathIds.put(path, this.records.indexOf(record));
        }

        private void tick() {
            if (this.deathTick >= 0) return;
            if (!this.recruit.isAlive()) {
                this.deathTick = GroupRecorder.this.helper.getTick();
                DamageSource source = this.recruit.getLastDamageSource();
                this.deathCause = source == null ? "unknown" : source.getMsgId();
                this.deathAt = String.format(java.util.Locale.ROOT, "[%.1f,%.1f,%.1f]", this.recruit.getX() - GroupRecorder.this.origin.getX(),
                        this.recruit.getY() - GroupRecorder.this.origin.getY(), this.recruit.getZ() - GroupRecorder.this.origin.getZ());
                return;
            }
            double distance = Math.sqrt(this.recruit.distanceToSqr(GroupRecorder.this.owner));
            this.closest = Math.min(this.closest, distance);
            if (this.reachedTick < 0 && distance <= 4.0) this.reachedTick = GroupRecorder.this.helper.getTick();
            Path walking = this.recruit.getNavigation().getPath();
            Integer id;
            synchronized (this) {
                id = walking == null ? null : this.pathIds.get(walking);
            }
            if (id != null) {
                this.walkedIds.add(id);
                if (!(walking instanceof AsyncPath async) || async.isProcessed()) this.keptCount.merge(id, walking.getNodeCount(), Math::min);
            }
            int flags = (this.recruit.isFollowing() ? 1 : 0)
                    | (this.recruit.getNavigation() instanceof AsyncPathNavigation n && n.hasPendingPath() ? 2 : 0)
                    | (walking instanceof AsyncPath async && !async.isProcessed() ? 4 : 0)
                    | (this.recruit.isInWater() ? 8 : 0)
                    | (this.recruit.onGround() ? 16 : 0)
                    | (this.recruit.getTarget() != null ? 32 : 0);
            this.positions.add((float) (this.recruit.getX() - GroupRecorder.this.origin.getX()));
            this.positions.add((float) (this.recruit.getY() - GroupRecorder.this.origin.getY()));
            this.positions.add((float) (this.recruit.getZ() - GroupRecorder.this.origin.getZ()));
            this.states.add(id == null ? -1 : id);
            this.states.add(flags);
        }

        private void writeTicks(Writer w, int offset) throws IOException {
            w.write('[');
            for (int t = 0; t < this.states.size() / 2; t++) {
                if (t > 0) w.write(',');
                int id = this.states.getInt(2 * t);
                w.write("[" + num(this.positions.getFloat(3 * t)) + "," + num(this.positions.getFloat(3 * t + 1)) + "," + num(this.positions.getFloat(3 * t + 2))
                        + "," + (id < 0 ? -1 : offset + id) + "," + this.states.getInt(2 * t + 1) + "]");
            }
            w.write(']');
        }

        private void write(Writer w, int offset) throws IOException {
            w.write("{\"id\":" + this.index + ",\"spawn\":" + xyz(this.spawn[0], this.spawn[1], this.spawn[2])
                    + ",\"outcome\":" + quote(this.outcome) + ",\"reachedTick\":" + this.reachedTick
                    + ",\"closest\":" + num(this.closest == Double.MAX_VALUE ? -1 : this.closest)
                    + ",\"death\":" + (this.deathTick < 0 ? "null" : "{\"tick\":" + this.deathTick + ",\"cause\":" + quote(this.deathCause) + ",\"at\":" + this.deathAt + "}")
                    + ",\"searches\":[" + offset + "," + this.records.size() + "],\"ticks\":");
            this.writeTicks(w, offset);
            w.write('}');
        }
    }
}
