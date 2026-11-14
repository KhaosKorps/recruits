package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.pathfinding.AsyncPathProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * PathLog: the measurement log. While the setting is on, three CSV files are written to
 * logs/recruits-paths/ in the server directory, named by the time (UTC) logging started: one row per
 * path search of every kind (PathLogRecord), one row per following recruit every 100 ticks
 * (PathLogSummary), and one row per settings change (PathLogSettingsRow). PathLogWriter's thread
 * writes them; the server log gets one line when logging starts and one when it stops.
 *
 * The hooks are single calls from the navigation, the path search, the follow and melee goals and
 * the detour search. With the setting off each costs one volatile read. They run on the server
 * thread, except the search hooks (searchStarted, nodes, searchFound, searchFailed, searchEnded),
 * which run wherever the search does.
 */
public final class PathLog {

    public static final int PATHS = 0, RECRUITS = 1, SETTINGS = 2;
    private static final String[] FILES = {"paths", "recruits", "settings"};
    private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private static volatile boolean on;
    private static volatile PathLogWriter writer;
    private static volatile BiConsumer<Integer, String> tap;

    // Server thread only.
    private static boolean failedUntilOff;
    private static long seq;
    private static int settingsVersion;
    private static FollowSettings loggedSettings;
    private static boolean loggedAsync;
    private static int loggedThreads;
    private static long droppedAtLastSummary;
    private static Mob taggedMob;
    private static char taggedPurpose;
    private static final Map<AbstractRecruitEntity, PathLogSummary> FOLLOWERS = new IdentityHashMap<>();

    /** The request handed from the navigation to the search it is about to start, on the server thread. */
    private static final ThreadLocal<PathLogRecord> REQUEST = new ThreadLocal<>();
    /** The node count processPath leaves for the search hooks, on whichever thread runs the search. */
    private static final ThreadLocal<int[]> NODES = ThreadLocal.withInitial(() -> new int[]{-1});

    private PathLog() {
    }

    public static boolean isOn() {
        return on;
    }

    /** For GameTests: every row as it is written, with its file (PATHS, RECRUITS or SETTINGS); null to stop. */
    public static void tap(BiConsumer<Integer, String> listener) {
        tap = listener;
    }

    // ---- lifecycle ----

    /**
     * Opens, closes and summarizes. Logging must never stop the server: anything unexpected here is
     * reported once and turns logging off until the setting is turned off and on again.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try {
            tick(event);
        } catch (RuntimeException e) {
            Main.LOGGER.warn("[KhaosKorps] Path log: stopped after an unexpected error, until PathLog is turned off and on again", e);
            failedUntilOff = true;
            on = false;
            FOLLOWERS.clear();
            PathLogWriter closing = writer;
            writer = null;
            if (closing != null) closing.close(2000);
        }
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        FollowSettings settings = FollowSettings.current();
        long tick = event.getServer().overworld().getGameTime();
        if (!settings.pathLog()) {
            failedUntilOff = false;
            if (on) close(tick, settings);
            return;
        }
        if (!on && !failedUntilOff) open(event.getServer());
        if (!on) return;
        if (writer.hasFailed()) {
            failedUntilOff = true;
            close(tick, settings);
            return;
        }
        logSettingsIfChanged(settings, tick);
        summarize(tick, settings, false);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (on) close(event.getServer().overworld().getGameTime(), FollowSettings.current());
        failedUntilOff = false;
    }

    private static void open(MinecraftServer server) {
        String prefix = ZonedDateTime.now(ZoneOffset.UTC).format(STARTED);
        java.nio.file.Path directory = server.getServerDirectory().toPath().resolve("logs").resolve("recruits-paths");
        try {
            writer = new PathLogWriter(new PathLogWriter.FileSink(directory, prefix, FILES,
                    new String[]{PathLogRecord.HEADER, PathLogSummary.HEADER, PathLogSettingsRow.HEADER}),
                    PathLogWriter.CAPACITY, 1000,
                    e -> Main.LOGGER.warn("[KhaosKorps] Path log: writing failed, so it stops until PathLog is turned off and on again", e));
        } catch (IOException e) {
            Main.LOGGER.warn("[KhaosKorps] Path log: can't open {}, so it stays off until PathLog is turned off and on again", directory, e);
            failedUntilOff = true;
            return;
        }
        seq = 0;
        settingsVersion = 0;
        loggedSettings = null;
        droppedAtLastSummary = 0;
        FOLLOWERS.clear();
        taggedMob = null;
        on = true;
        Main.LOGGER.info("[KhaosKorps] Path log on: {} ({}-paths.csv, -recruits.csv, -settings.csv)", directory, prefix);
    }

    private static void close(long tick, FollowSettings settings) {
        summarize(tick, settings, true);
        on = false;
        FOLLOWERS.clear();
        taggedMob = null;
        PathLogWriter closing = writer;
        writer = null;
        if (closing == null) return;
        closing.close(2000);
        Main.LOGGER.info("[KhaosKorps] Path log closed: {} rows, {} dropped", closing.written(), closing.dropped());
    }

    private static void emit(int file, String row) {
        if (row == null) return;
        PathLogWriter current = writer;
        if (current != null) current.offer(file, row);
        BiConsumer<Integer, String> listener = tap;
        if (listener != null) listener.accept(file, row);
    }

    private static void logSettingsIfChanged(FollowSettings settings, long tick) {
        boolean async = RecruitsServerConfig.UseAsyncPathfinding.get();
        int threads = RecruitsServerConfig.AsyncPathfindingThreadsCount.get();
        if (settings.equals(loggedSettings) && async == loggedAsync && threads == loggedThreads) return;
        settingsVersion++;
        loggedSettings = settings;
        loggedAsync = async;
        loggedThreads = threads;
        emit(SETTINGS, PathLogSettingsRow.row(settingsVersion, tick, settings, async, threads));
    }

    /**
     * One tick for every follower's window, and a row for each full one. With {@code all}, every
     * window is written as it stands instead (logging is closing). A recruit that left the world
     * gets its last row; one that spent a whole window out of Follow Me is dropped without a row,
     * until its follow goal sees it again.
     */
    private static void summarize(long tick, FollowSettings settings, boolean all) {
        PathLogWriter current = writer;
        long dropped = current == null ? 0 : current.dropped() - droppedAtLastSummary;
        boolean wrote = false;
        for (Iterator<Map.Entry<AbstractRecruitEntity, PathLogSummary>> it = FOLLOWERS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<AbstractRecruitEntity, PathLogSummary> entry = it.next();
            AbstractRecruitEntity recruit = entry.getKey();
            PathLogSummary window = entry.getValue();
            boolean gone = recruit.isRemoved();
            if (!gone && !all) {
                LivingEntity owner = recruit.getOwner();
                double distance = owner != null && owner.level() == recruit.level() ? recruit.distanceTo(owner) : -1;
                window.tick(recruit.getFollowState() == 1, distance, distance >= 0 && distance * distance > settings.followStopDistanceSqr());
            }
            if (!(gone || all || window.windowFull()) || window.isEmpty()) continue;
            if (!window.followedThisWindow() && !gone && !all) {
                it.remove();
                continue;
            }
            emit(RECRUITS, window.row(tick, shortId(recruit), speedMilli(recruit), dropped));
            wrote = true;
            if (gone) it.remove();
        }
        if (wrote && current != null) droppedAtLastSummary += dropped;
    }

    // ---- what a search is for, from its call site ----

    /** The next search this mob's navigation starts is for {@code purpose}, a PathLogRecord code. */
    public static void purpose(Mob mob, char purpose) {
        if (!on) return;
        taggedMob = mob;
        taggedPurpose = purpose;
    }

    /** At the start of a search request: the purpose set for this mob, or OTHER; it is used up either way. */
    public static char takePurpose(Mob mob) {
        if (!on) return PathLogRecord.OTHER;
        char purpose = taggedMob == mob ? taggedPurpose : PathLogRecord.OTHER;
        taggedMob = null;
        return purpose;
    }

    // ---- a search: request, run, result ----

    /**
     * Right before a search starts, on the server thread: its record, also handed to the search
     * through the request slot until {@link #requestDone} (call it in a finally). Null when off.
     */
    public static PathLogRecord request(Mob mob, char purpose, Set<BlockPos> targets, int budget) {
        if (!on || targets.isEmpty()) return null;
        BlockPos target = targets.iterator().next();
        PathLogRecord record = new PathLogRecord(++seq, settingsVersion, mob.level().getGameTime(), shortId(mob),
                PathLogRecord.NAV_RECRUIT, purpose, Math.sqrt(mob.distanceToSqr(Vec3.atBottomCenterOf(target))),
                target.getY() - mob.getBlockY(), budget, AsyncPathProcessor.queuedJobs(), System.nanoTime());
        REQUEST.set(record);
        return record;
    }

    public static void requestDone(PathLogRecord record) {
        if (record != null) REQUEST.remove();
    }

    /** In the search, on the server thread as it is set up: the record of the request it serves, or null. */
    public static PathLogRecord requested() {
        return on ? REQUEST.get() : null;
    }

    /** As the search starts running, wherever it runs: the start time for the hooks below. */
    public static long searchStarted(PathLogRecord record) {
        if (record == null) return 0L;
        NODES.get()[0] = -1;
        return System.nanoTime();
    }

    /** processPath, as it returns: how many nodes it took off the open set. */
    public static void nodes(int count) {
        if (on) NODES.get()[0] = count;
    }

    public static void searchFound(PathLogRecord record, Path path, long started) {
        if (record == null) return;
        record.searched(threadCode(), NODES.get()[0], micros(started));
        result(record, path);
    }

    public static void searchFailed(PathLogRecord record, long started) {
        if (record == null) return;
        record.searched(threadCode(), NODES.get()[0], micros(started));
        record.failed();
    }

    /**
     * In the search's finally: a search that ended without being found or failed threw an Error.
     * Its path stays unfinished and no result will arrive, so its row is written now.
     */
    public static void searchEnded(PathLogRecord record, long started) {
        if (record == null || record.ran()) return;
        record.searched(threadCode(), NODES.get()[0], micros(started));
        record.failed();
        emit(PATHS, record.finish(-1, PathLogRecord.ERROR));
    }

    /** A search that ran inline on the server thread, with vanilla's PathFinder: its row now. */
    public static void ranInline(PathLogRecord record, Path path) {
        if (record == null) return;
        record.searched(PathLogRecord.INLINE, -1, micros(record.requestNanos()));
        if (path != null) result(record, path);
        emit(PATHS, record.finish(0, path == null ? PathLogRecord.NO_PATH : PathLogRecord.APPLIED));
    }

    /** A search's result back on the server thread at {@code tick}: walked, or discarded for a newer path. */
    public static void arrived(PathLogRecord record, Path path, boolean discarded, long tick) {
        if (record == null) return;
        char outcome = discarded ? PathLogRecord.DISCARDED
                : path == null || path.getNodeCount() == 0 ? PathLogRecord.NO_PATH : PathLogRecord.APPLIED;
        emit(PATHS, record.finish(tick - record.requestTick(), outcome));
    }

    // ---- a mounted follower, on its horse's vanilla navigation ----

    /** The path a mounted follower's navigation held before a move, to tell a new search from a reused path. */
    public record VanillaMove(Path before, long started) {
    }

    public static VanillaMove vanillaMove(PathNavigation navigation) {
        return on ? new VanillaMove(navigation.getPath(), System.nanoTime()) : null;
    }

    /** After the move: a row if the navigation searched (it holds a new path), else a reuse. */
    public static void vanillaMoved(VanillaMove move, AbstractRecruitEntity recruit, PathNavigation navigation, Entity target) {
        if (move == null || !on) return;
        Path after = navigation.getPath();
        if (after == null) return;
        if (after == move.before()) {
            PathLogSummary window = FOLLOWERS.get(recruit);
            if (window != null) window.reused();
            return;
        }
        BlockPos at = target.blockPosition();
        PathLogRecord record = new PathLogRecord(++seq, settingsVersion, recruit.level().getGameTime(), shortId(recruit),
                PathLogRecord.NAV_VANILLA, PathLogRecord.FOLLOW, recruit.distanceTo(target), at.getY() - recruit.getBlockY(),
                -1, -1, move.started());
        record.searched(PathLogRecord.INLINE, -1, micros(move.started()));
        result(record, after);
        emit(PATHS, record.finish(0, PathLogRecord.APPLIED));
    }

    // ---- per-follower counts ----

    /** The follow goal sees this recruit in Follow Me; it gets a window from now on. */
    public static void follower(AbstractRecruitEntity recruit) {
        if (on) FOLLOWERS.computeIfAbsent(recruit, r -> new PathLogSummary());
    }

    public static void followGoalStarted(AbstractRecruitEntity recruit) {
        if (on) FOLLOWERS.computeIfAbsent(recruit, r -> new PathLogSummary()).goalStarted();
    }

    public static void followGoalStopped(AbstractRecruitEntity recruit) {
        if (!on) return;
        PathLogSummary window = FOLLOWERS.get(recruit);
        if (window != null) window.goalStopped();
    }

    public static void stalled(Mob mob) {
        if (!on) return;
        PathLogSummary window = FOLLOWERS.get(mob);
        if (window != null) window.stalled();
    }

    public static void gaveUp(Mob mob) {
        if (!on) return;
        if (taggedMob == mob) taggedMob = null;
        PathLogSummary window = FOLLOWERS.get(mob);
        if (window != null) window.gaveUp();
    }

    public static void reused(Mob mob) {
        if (!on) return;
        PathLogSummary window = FOLLOWERS.get(mob);
        if (window != null) window.reused();
    }

    // ---- helpers ----

    private static void result(PathLogRecord record, Path path) {
        if (path == null) return;
        Node end = path.getEndNode();
        BlockPos target = path.getTarget();
        record.result(path.canReach(), path.getNodeCount(), path.getDistToTarget(), end == null || target == null ? 0 : end.y - target.getY());
    }

    private static char threadCode() {
        if (Thread.currentThread().getName().startsWith("recruits-path-processor-")) return PathLogRecord.WORKER;
        return AsyncPathProcessor.workersRunning() ? PathLogRecord.QUEUE_FULL : PathLogRecord.WORKERS_OFF;
    }

    private static long micros(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1000L;
    }

    private static String shortId(Entity entity) {
        return entity.getUUID().toString().substring(0, 8);
    }

    private static int speedMilli(AbstractRecruitEntity recruit) {
        return (int) Math.round(recruit.getAttributeValue(Attributes.MOVEMENT_SPEED) * 1000.0);
    }
}
