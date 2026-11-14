package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.DetourPlan;
import com.talhanation.recruits.khaoskorps.DetourRoutes;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import com.talhanation.recruits.pathfinding.AsyncPath;
import com.talhanation.recruits.pathfinding.AsyncPathNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Records one recruit's whole test run for the pathfinding visualiser: its position every tick, the
 * path it is walking, the detour plan for its owner, and every search its navigation makes, with the
 * real values, over the arena's real blocks. The recruit's navigation is given tracing path finders
 * (by reflection, as the field is private), which run the real search code and record as they go.
 *
 * Full detail (every candidate and neighbour) is kept for the first search from each start to each
 * target, up to {@link #DETAIL_LIMIT} searches; the rest keep the points they took off the open set.
 * A search identical to an earlier one is written as a reference to it. The run is written once, to
 * runs/&lt;test name&gt;.json.gz in the test server's folder, when the test passes, fails or times out.
 */
final class RunRecorder implements SearchTracer.Sink, TracingPathfinders.Pairing {

    private static final Map<java.util.UUID, SearchTracer.Sink> BY_RECRUIT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Where a recruit's searches are recorded, so the detour planner's searches for it are recorded too; null when nowhere. */
    static SearchTracer.Sink recorderFor(net.minecraft.world.entity.Mob mob) {
        return BY_RECRUIT.get(mob.getUUID());
    }

    /** Records {@code mob}'s searches into {@code sink} until {@link #forget}; used by the group recorder too. */
    static void recordInto(net.minecraft.world.entity.Mob mob, SearchTracer.Sink sink) {
        BY_RECRUIT.put(mob.getUUID(), sink);
    }

    static void forget(net.minecraft.world.entity.Mob mob) {
        BY_RECRUIT.remove(mob.getUUID());
    }

    static final int DETAIL_LIMIT = 60;

    private final String name;
    private final GameTestHelper helper;
    private final long seed;
    private final AbstractRecruitEntity recruit;
    private final ServerPlayer owner;
    private final BlockPos origin;
    private final List<SearchTracer.SearchRecord> records = new ArrayList<>();
    private final IdentityHashMap<Path, Integer> pathIds = new IdentityHashMap<>();
    private final Set<String> detailedKeys = new HashSet<>();
    private int detailed;
    private SearchTracer.SearchRecord prepared;
    private final StringBuilder ticks = new StringBuilder();
    private int tickCount;
    private final List<String> plans = new ArrayList<>();
    private String lastPlan = "";
    private boolean written;
    private int budget;

    RunRecorder(String name, GameTestHelper helper, long seed, AbstractRecruitEntity recruit, ServerPlayer owner) {
        this.name = name;
        this.helper = helper;
        this.seed = seed;
        this.recruit = recruit;
        this.owner = owner;
        this.origin = helper.absolutePos(BlockPos.ZERO);
    }

    /** Gives the recruit's navigation the tracing path finder for the current UseAsyncPathfinding. */
    void attach() {
        if (!(this.recruit.getNavigation() instanceof AsyncPathNavigation navigation)) {
            throw new GameTestAssertException("setup: the recruit's navigation can't be recorded: " + this.recruit.getNavigation());
        }
        try {
            Field budgetField = AsyncPathNavigation.class.getDeclaredField("baseSearchBudget");
            budgetField.setAccessible(true);
            this.budget = budgetField.getInt(navigation);
            Field finder = AsyncPathNavigation.class.getDeclaredField("pathFinder");
            finder.setAccessible(true);
            recordInto(this.recruit, this);
            TracingDetourEvaluator.fillThePool();
            finder.set(navigation, RecruitsServerConfig.UseAsyncPathfinding.get()
                    ? new TracingPathfinders.Async(this.budget, this, this, this.helper.getLevel())
                    : new TracingPathfinders.Sync(this.budget, this, this));
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("setup: can't attach the recorder: " + e);
        }
    }

    // ---- the sink, called by the tracing evaluators and path finders ----

    @Override
    public synchronized void prepared(SearchTracer.SearchRecord record) {
        this.records.add(record);
        if ("follow".equals(record.kind)) this.prepared = record;
    }

    @Override
    public synchronized boolean detail(SearchTracer.SearchRecord record) {
        String key = record.kind + ":" + (record.start == null ? "-" : rel(record.start)) + ">" + (record.targets.isEmpty() ? "-" : rel(record.targets.get(0)));
        if (this.detailed >= DETAIL_LIMIT || !this.detailedKeys.add(key)) return false;
        this.detailed++;
        return true;
    }

    @Override
    public void finished(SearchTracer.SearchRecord record) {
    }

    @Override
    public SearchTracer.SearchRecord takePrepared() {
        SearchTracer.SearchRecord record = this.prepared;
        this.prepared = null;
        return record;
    }

    @Override
    public void returned(SearchTracer.SearchRecord record, Path path) {
        if (path == null) {
            record.noStart = true;
            return;
        }
        record.path = path;
        this.pathIds.put(path, this.records.indexOf(record));
    }

    // ---- every tick ----

    /** Notes where the recruit is, what it walks, and the owner's detour plan. Server thread. */
    void tick() {
        if (this.written) return;
        Path walking = this.recruit.getNavigation().getPath();
        Integer id = walking == null ? null : this.pathIds.get(walking);
        int flags = (this.recruit.isFollowing() ? 1 : 0)
                | (this.recruit.getNavigation() instanceof AsyncPathNavigation n && n.hasPendingPath() ? 2 : 0)
                | (walking instanceof AsyncPath async && !async.isProcessed() ? 4 : 0)
                | (this.recruit.isInWater() ? 8 : 0)
                | (this.recruit.onGround() ? 16 : 0);
        if (this.tickCount++ > 0) this.ticks.append(',');
        this.ticks.append('[').append(num(this.recruit.getX() - this.origin.getX())).append(',')
                .append(num(this.recruit.getY() - this.origin.getY())).append(',')
                .append(num(this.recruit.getZ() - this.origin.getZ())).append(',')
                .append(id == null ? -1 : id).append(',').append(flags).append(']');
        DetourPlan plan = DetourRoutes.plan(this.owner.getUUID());
        String key = plan == null ? "NONE" : plan.status() + ":" + plan.route().size() + ":" + plan.searches();
        if (!key.equals(this.lastPlan)) {
            this.lastPlan = key;
            StringBuilder entry = new StringBuilder("[").append(this.helper.getTick()).append(",\"")
                    .append(plan == null ? "NONE" : plan.status().name()).append("\",").append(plan == null ? 0 : plan.searches()).append(",[");
            if (plan != null) {
                for (int i = 0; i < plan.route().size(); i++) {
                    if (i > 0) entry.append(',');
                    entry.append(rel(plan.route().get(i)));
                }
            }
            this.plans.add(entry.append("]]").toString());
        }
    }

    /** Writes the run once, with how it ended. */
    void finish(String outcome, String message) {
        if (this.written) return;
        this.written = true;
        forget(this.recruit);
        java.nio.file.Path file = java.nio.file.Path.of("runs", this.name + ".json.gz").toAbsolutePath();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file));
                 Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                this.write(writer, outcome, message);
            }
            Main.LOGGER.info("[KhaosKorps measure] run recorded: {} ({}, {} searches, {} ticks)", file, outcome, this.records.size(), this.tickCount);
        } catch (IOException e) {
            Main.LOGGER.warn("[KhaosKorps measure] could not write the run recording {}", file, e);
        }
    }

    private void write(Writer w, String outcome, String message) throws IOException {
        FollowSettings settings = FollowSettings.current();
        w.write("{\"test\":" + quote(this.name) + ",\"seed\":" + this.seed + ",\"fallDistance\":" + this.recruit.getMaxFallDistance()
                + ",\"async\":" + RecruitsServerConfig.UseAsyncPathfinding.get() + ",\"dirtPath\":" + quote(settings.dirtPathPreference().name())
                + ",\"margin\":" + settings.edgeMarginBlocks() + ",\"range\":32,\"budget\":" + this.budget
                + ",\"outcome\":" + quote(outcome) + ",\"message\":" + quote(message) + ",\"endTick\":" + this.helper.getTick()
                + ",\"size\":[64,24,64],\"blocks\":\"");
        w.write(blocks(this.helper));
        w.write("\",\"owner\":" + xyz(this.owner.getBlockX() - this.origin.getX(), this.owner.getBlockY() - this.origin.getY(), this.owner.getBlockZ() - this.origin.getZ()));
        w.write(",\"ticks\":[" + this.ticks + "],\"plans\":[" + String.join(",", this.plans) + "],\"searches\":[");
        Map<String, Integer> seen = new HashMap<>();
        List<SearchTracer.SearchRecord> all;
        synchronized (this) {
            all = new ArrayList<>(this.records);
        }
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) w.write(',');
            this.writeSearch(w, i, all.get(i), seen);
        }
        w.write("]}");
    }

    private void writeSearch(Writer w, int id, SearchTracer.SearchRecord r, Map<String, Integer> seen) throws IOException {
        Path path = r.path;
        boolean done = r.finished && (path == null || !(path instanceof AsyncPath async) || async.isProcessed());
        StringBuilder pathJson = new StringBuilder("[");
        boolean reached = false;
        if ("detour".equals(r.kind)) reached = r.reachedByPop;
        if (done && path != null) {
            reached = path.canReach();
            for (int i = 0; i < path.getNodeCount(); i++) {
                Node node = path.getNode(i);
                if (i > 0) pathJson.append(',');
                pathJson.append(xyz(node.x - this.origin.getX(), node.y - this.origin.getY(), node.z - this.origin.getZ()));
            }
        }
        pathJson.append(']');
        StringBuilder head = new StringBuilder("{\"id\":").append(id).append(",\"kind\":").append(quote(r.kind)).append(",\"tick\":").append(r.tick)
                .append(",\"start\":").append(r.start == null ? "null" : rel(r.start))
                .append(",\"target\":").append(r.targets.isEmpty() ? "null" : rel(r.targets.get(0)))
                .append(",\"dirtPath\":").append(quote(r.dirtPath)).append(",\"margin\":").append(r.margin).append(",\"maxFall\":").append(r.maxFall)
                .append(",\"done\":").append(done).append(",\"noStart\":").append(r.noStart).append(",\"reached\":").append(reached)
                .append(",\"path\":").append(pathJson).append(",\"n\":").append(r.pops.size());
        String signature = head.substring(head.indexOf(",\"start\"")) + "|" + r.pops.size();
        Integer same = seen.get(signature);
        if (same != null) {
            w.write(head.append(",\"same\":").append(same).append('}').toString());
            return;
        }
        seen.put(signature, id);
        w.write(head.append(",\"detail\":").append(r.detail).append(",\"pops\":[").toString());
        Map<String, Integer> index = new HashMap<>();
        for (int p = 0; p < r.pops.size(); p++) {
            SearchTracer.Pop pop = r.pops.get(p);
            if (p > 0) w.write(',');
            int x = pop.x - this.origin.getX(), y = pop.y - this.origin.getY(), z = pop.z - this.origin.getZ();
            index.put(x + "," + y + "," + z, p);
            if (!r.detail) {
                w.write(pop.expanded ? xyz(x, y, z) : "[" + x + "," + y + "," + z + ",0]");
                continue;
            }
            Integer from = pop.from == null ? null : index.get((pop.from[0] - this.origin.getX()) + "," + (pop.from[1] - this.origin.getY()) + "," + (pop.from[2] - this.origin.getZ()));
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

    // ---- shared helpers ----

    /** Every block of a 64 x 24 x 64 arena, x fastest, then z, then y: '#' solid, 'w' water, 'l' lava, '.' open. */
    static String blocks(GameTestHelper helper) {
        return blocks(helper, 64, 24, 64);
    }

    /** Every block of an arena of the given size, x fastest, then z, then y: '#' solid, 'w' water, 'l' lava, '.' open. */
    static String blocks(GameTestHelper helper, int sizeX, int sizeY, int sizeZ) {
        StringBuilder out = new StringBuilder(sizeX * sizeY * sizeZ);
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
                    BlockState state = helper.getLevel().getBlockState(pos);
                    if (state.getFluidState().is(FluidTags.LAVA)) out.append('l');
                    else if (state.getFluidState().is(FluidTags.WATER)) out.append('w');
                    else if (!state.getCollisionShape(helper.getLevel(), pos).isEmpty()) out.append('#');
                    else out.append('.');
                }
            }
        }
        return out.toString();
    }

    private String rel(int[] p) {
        return xyz(p[0] - this.origin.getX(), p[1] - this.origin.getY(), p[2] - this.origin.getZ());
    }

    private String rel(BlockPos p) {
        return xyz(p.getX() - this.origin.getX(), p.getY() - this.origin.getY(), p.getZ() - this.origin.getZ());
    }

    static String xyz(int x, int y, int z) {
        return "[" + x + "," + y + "," + z + "]";
    }

    static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String quote(String text) {
        if (text == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
