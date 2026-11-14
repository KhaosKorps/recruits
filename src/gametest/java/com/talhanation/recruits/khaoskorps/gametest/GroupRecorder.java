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

import static com.talhanation.recruits.khaoskorps.gametest.RunRecorder.num;
import static com.talhanation.recruits.khaoskorps.gametest.RunRecorder.quote;
import static com.talhanation.recruits.khaoskorps.gametest.RunRecorder.xyz;

/**
 * Records a whole group's test run for the pathfinding visualiser: every recruit's position each
 * tick, a summary of every search each recruit's navigation and the detour planner make for it, the
 * points of a sample of those searches, the owner's detour plan, each death and its cause, how full
 * the path worker's queue got, and a heat map of every point any search took off its open set.
 *
 * One per test. Points are kept for every detour search up to {@link #DETOUR_POINTS} and for one
 * follow search per recruit every {@link #FOLLOW_SAMPLE_TICKS}; the other searches keep their counts
 * only, which is what makes a long run of a large group fit in memory. The run is written once, to
 * runs/&lt;test name&gt;.json.gz in the test server's folder, in the single-run format with a
 * recruits list, a heat map and a summary added.
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
    private final List<Member> members = new ArrayList<>();
    private final AtomicIntegerArray heatFollow;
    private final AtomicIntegerArray heatDetour;
    private final AtomicInteger detourKept = new AtomicInteger();
    private final List<String> plans = new ArrayList<>();
    private String lastPlan = "";
    private int maxQueued;
    private int queueFullTicks;
    private int ticks;
    private boolean written;

    /**
     * @param setup a JSON object describing the test's setup (direction, group size, spawn points),
     *              written into the run as it is
     */
    GroupRecorder(String name, GameTestHelper helper, ServerPlayer owner, int sizeX, int sizeY, int sizeZ, String setup) {
        this.name = name;
        this.helper = helper;
        this.owner = owner;
        this.origin = helper.absolutePos(BlockPos.ZERO);
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.setup = setup;
        this.heatFollow = new AtomicIntegerArray(sizeX * sizeZ);
        this.heatDetour = new AtomicIntegerArray(sizeX * sizeZ);
    }

    /** Starts recording {@code recruit}: its navigation gets a tracing path finder, as RunRecorder does for one. */
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
            RunRecorder.recordInto(recruit, member);
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

    /** Writes the run once, with how it ended and each recruit's final state as the test judged it. */
    void finish(String outcome, String message) {
        if (this.written) return;
        this.written = true;
        for (Member member : this.members) RunRecorder.forget(member.recruit);
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
        w.write("{\"test\":" + quote(this.name) + ",\"group\":true,\"seed\":0,\"fallDistance\":" + settings.maxFallDistance()
                + ",\"async\":" + RecruitsServerConfig.UseAsyncPathfinding.get() + ",\"threads\":" + RecruitsServerConfig.AsyncPathfindingThreadsCount.get()
                + ",\"dirtPath\":" + quote(settings.dirtPathPreference().name()) + ",\"margin\":" + settings.edgeMarginBlocks()
                + ",\"range\":32,\"budget\":" + budget + ",\"outcome\":" + quote(outcome) + ",\"message\":" + quote(message)
                + ",\"endTick\":" + this.helper.getTick() + ",\"setup\":" + this.setup
                + ",\"queue\":{\"max\":" + this.maxQueued + ",\"fullTicks\":" + this.queueFullTicks + "}"
                + ",\"size\":[" + this.sizeX + "," + this.sizeY + "," + this.sizeZ + "],\"blocks\":\"");
        w.write(RunRecorder.blocks(this.helper, this.sizeX, this.sizeY, this.sizeZ));
        w.write("\",\"owner\":" + xyz(this.owner.getBlockX() - this.origin.getX(), this.owner.getBlockY() - this.origin.getY(), this.owner.getBlockZ() - this.origin.getZ()));
        w.write(",\"heat\":{\"follow\":" + heat(this.heatFollow) + ",\"detour\":" + heat(this.heatDetour) + "}");
        w.write(",\"plans\":[" + String.join(",", this.plans) + "],\"recruits\":[");
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
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) w.write(',');
            this.writeSearch(w, i, owners.get(i), all.get(i), walked.contains(i), seen);
        }
        w.write("]}");
    }

    private void writeSearch(Writer w, int id, int recruit, SearchTracer.SearchRecord r, boolean walked, Map<String, Integer> seen) throws IOException {
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
                .append(",\"server\":").append(r.onServerThread);
        if ((walked || r.keepPoints) && done && path != null) {
            head.append(",\"path\":[");
            for (int i = 0; i < path.getNodeCount(); i++) {
                Node node = path.getNode(i);
                if (i > 0) head.append(',');
                head.append(xyz(node.x - this.origin.getX(), node.y - this.origin.getY(), node.z - this.origin.getZ()));
            }
            head.append(']');
        } else {
            head.append(",\"path\":[]");
        }
        if (!r.keepPoints) {
            w.write(head.append(",\"detail\":false,\"pops\":null}").toString());
            return;
        }
        String signature = r.kind + "|" + head.substring(head.indexOf(",\"start\""), head.indexOf(",\"server\""));
        Integer same = seen.get(signature);
        if (same != null) {
            w.write(head.append(",\"same\":").append(same).append('}').toString());
            return;
        }
        seen.put(signature, id);
        w.write(head.append(",\"detail\":false,\"pops\":[").toString());
        for (int p = 0; p < r.pops.size(); p++) {
            SearchTracer.Pop pop = r.pops.get(p);
            if (p > 0) w.write(',');
            int x = pop.x - this.origin.getX(), y = pop.y - this.origin.getY(), z = pop.z - this.origin.getZ();
            w.write(pop.expanded ? xyz(x, y, z) : "[" + x + "," + y + "," + z + ",0]");
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
        private SearchTracer.SearchRecord prepared;
        private long lastKeptFollow = Long.MIN_VALUE;
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
            return false;
        }

        @Override
        public synchronized boolean keepPoints(SearchTracer.SearchRecord record) {
            if ("detour".equals(record.kind)) return GroupRecorder.this.detourKept.incrementAndGet() <= DETOUR_POINTS;
            if (record.tick - this.lastKeptFollow < FOLLOW_SAMPLE_TICKS) return false;
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
            if (id != null) this.walkedIds.add(id);
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
