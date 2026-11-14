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

import javax.annotation.Nullable;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * PathLog: the measurement log. While the setting is on, three CSV files are written to
 * logs/recruits-paths/ in the server directory, named by the time (UTC, to the millisecond) logging
 * started: one row per path search of every kind (PathLogRecord), one row per following recruit
 * every 100 ticks (PathLogSummary), and one row per settings change (PathLogSettingsRow).
 * PathLogWriter's thread writes them; the server log gets one line when logging starts and one when
 * the files are closed.
 *
 * The hooks are single calls from the navigation, the path search, the follow and melee goals and
 * the detour search. With the setting off each costs one volatile read. They run on the server
 * thread, except the search hooks (searchStarted, nodes, searchFound, searchFailed, searchEnded),
 * which run wherever the search does and never throw: a fault there turns logging off.
 *
 * With UseAsyncPathfinding off, searches run in vanilla's PathFinder, which doesn't report how many
 * nodes it took, so those rows have nodes -1.
 */
public final class PathLog {

    public static final int PATHS = 0, RECRUITS = 1, SETTINGS = 2;
    private static final String[] FILES = {"paths", "recruits", "settings"};
    private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'");

    private static volatile boolean on;
    private static volatile PathLogWriter writer;
    private static volatile BiConsumer<Integer, String> tap;
    /** Set by a search hook that failed, on whatever thread; the server tick then turns logging off. */
    private static volatile boolean faulted;
    private static final AtomicBoolean FAULT_REPORTED = new AtomicBoolean();

    // Server thread only.
    private static boolean failedUntilOff;
    private static long seq;
    private static int settingsVersion;
    private static FollowSettings loggedSettings;
    private static boolean loggedAsync;
    private static int loggedThreads;
    private static long droppedAtLastSummary;
    @Nullable private static PathNavigation taggedNavigation;
    private static char taggedPurpose;
    private static final Map<AbstractRecruitEntity, PathLogSummary> FOLLOWERS = new IdentityHashMap<>();

    /** The request handed from the navigation to the search it is about to set up, on the server thread. */
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
     * Opens, closes and summarizes. Logging must never stop the server: anything unexpected here,
     * or in a search hook, is reported once and turns logging off until the setting is turned off
     * and on again.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try {
            tick(event);
        } catch (RuntimeException e) {
            Main.LOGGER.warn("[KhaosKorps] Path log: stopped after an unexpected error, until PathLog is turned off and on again", e);
            stopAfterFault();
        }
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        FollowSettings settings = FollowSettings.current();
        long tick = event.getServer().overworld().getGameTime();
        if (!settings.pathLog()) {
            failedUntilOff = false;
            if (on) close(tick, settings, 0);
            return;
        }
        if (!on && !failedUntilOff) open(event.getServer());
        if (!on) return;
        if (faulted) {
            stopAfterFault();
            return;
        }
        if (writer.hasFailed()) {
            failedUntilOff = true;
            close(tick, settings, 0);
            return;
        }
        logSettingsIfChanged(settings, tick);
        summarize(tick, settings, false);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        try {
            if (on) close(event.getServer().overworld().getGameTime(), FollowSettings.current(), 2000);
        } catch (RuntimeException e) {
            Main.LOGGER.warn("[KhaosKorps] Path log: couldn't close cleanly", e);
        }
        failedUntilOff = false;
    }

    private static void open(MinecraftServer server) {
        String prefix = ZonedDateTime.now(ZoneOffset.UTC).format(STARTED);
        java.nio.file.Path directory = server.getServerDirectory().toPath().resolve("logs").resolve("recruits-paths");
        PathLogWriter opened;
        try {
            PathLogWriter[] self = {null};
            opened = new PathLogWriter(new PathLogWriter.FileSink(directory, prefix, FILES,
                    new String[]{PathLogRecord.HEADER, PathLogSummary.HEADER, PathLogSettingsRow.HEADER}),
                    PathLogWriter.CAPACITY, 1000,
                    e -> Main.LOGGER.warn("[KhaosKorps] Path log: writing failed, so it stops until PathLog is turned off and on again", e),
                    () -> {
                        PathLogWriter closed = self[0];
                        if (closed != null) {
                            Main.LOGGER.info("[KhaosKorps] Path log closed: {} rows, {} dropped", closed.written(), closed.dropped());
                        }
                    });
            self[0] = opened;
        } catch (IOException e) {
            Main.LOGGER.warn("[KhaosKorps] Path log: can't open {}, so it stays off until PathLog is turned off and on again", directory, e);
            failedUntilOff = true;
            return;
        }
        writer = opened;
        seq = 0;
        settingsVersion = 0;
        loggedSettings = null;
        droppedAtLastSummary = 0;
        FOLLOWERS.clear();
        taggedNavigation = null;
        faulted = false;
        FAULT_REPORTED.set(false);
        on = true;
        Main.LOGGER.info("[KhaosKorps] Path log on: {} ({}-paths.csv, -recruits.csv, -settings.csv)", directory, prefix);
    }

    /** Writes the windows as they stand and closes; {@code waitMillis} 0 doesn't wait for the files. */
    private static void close(long tick, FollowSettings settings, long waitMillis) {
        summarize(tick, settings, true);
        shutDown(waitMillis);
    }

    private static void stopAfterFault() {
        failedUntilOff = true;
        shutDown(0);
    }

    private static void shutDown(long waitMillis) {
        on = false;
        FOLLOWERS.clear();
        taggedNavigation = null;
        PathLogWriter closing = writer;
        writer = null;
        if (closing != null) closing.close(waitMillis);
    }

    /** Whether the writer took the row. */
    private static boolean emit(int file, String row) {
        if (row == null) return false;
        BiConsumer<Integer, String> listener = tap;
        if (listener != null) listener.accept(file, row);
        PathLogWriter current = writer;
        return current != null && current.offer(file, row);
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
     * window is written as it stands instead (logging is closing). A recruit that left the world gets
     * its last row if its window has any ticks, and is dropped either way; one that spent a whole
     * window out of Follow Me is dropped without a row, until its follow goal sees it again. The
     * rows dropped since the last summary rows go into this pass's rows, and count as reported only
     * once one of them reaches the writer.
     */
    private static void summarize(long tick, FollowSettings settings, boolean all) {
        PathLogWriter current = writer;
        long dropped = current == null ? 0 : current.dropped() - droppedAtLastSummary;
        boolean reported = false;
        for (Iterator<Map.Entry<AbstractRecruitEntity, PathLogSummary>> it = FOLLOWERS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<AbstractRecruitEntity, PathLogSummary> entry = it.next();
            AbstractRecruitEntity recruit = entry.getKey();
            PathLogSummary window = entry.getValue();
            if (recruit.isRemoved()) {
                if (!window.isEmpty()) reported |= emit(RECRUITS, window.row(tick, shortId(recruit), speedMilli(recruit), dropped));
                it.remove();
                continue;
            }
            if (!all) {
                LivingEntity owner = recruit.getOwner();
                double distance = owner != null && owner.level() == recruit.level() ? recruit.distanceTo(owner) : -1;
                window.tick(recruit.getFollowState() == 1, distance, distance >= 0 && distance * distance > settings.followStopDistanceSqr());
                if (!window.windowFull()) continue;
                if (!window.followedThisWindow()) {
                    it.remove();
                    continue;
                }
            } else if (window.isEmpty()) {
                continue;
            }
            reported |= emit(RECRUITS, window.row(tick, shortId(recruit), speedMilli(recruit), dropped));
        }
        if (reported) droppedAtLastSummary += dropped;
    }

    // ---- what a search is for, from its call site ----

    /** The next search {@code mob}'s navigation starts is for {@code purpose}, a PathLogRecord code. */
    public static void purpose(Mob mob, char purpose) {
        if (!on) return;
        taggedNavigation = mob.getNavigation();
        taggedPurpose = purpose;
    }

    /** At the start of a search request on {@code navigation}: the purpose set for it, or OTHER; any tag is used up either way. */
    public static char takePurpose(PathNavigation navigation) {
        if (!on) return PathLogRecord.OTHER;
        char purpose = taggedNavigation == navigation ? taggedPurpose : PathLogRecord.OTHER;
        taggedNavigation = null;
        return purpose;
    }

    // ---- a search: request, run, result ----

    /**
     * Right before a search is set up, on the server thread: its record, also handed to the search
     * through the request slot until {@link #requestDone}. Null when off. Distance and height are
     * measured from the mob.
     */
    public static PathLogRecord request(Mob mob, char purpose, Set<BlockPos> targets, int budget) {
        return request(mob, purpose, targets, budget, null);
    }

    /** As above, measured from {@code from} when the search starts somewhere other than where the mob stands. */
    public static PathLogRecord request(Mob mob, char purpose, Set<BlockPos> targets, int budget, @Nullable BlockPos from) {
        if (!on || targets.isEmpty()) return null;
        BlockPos target = targets.iterator().next();
        Vec3 start = from == null ? mob.position() : Vec3.atBottomCenterOf(from);
        int startY = from == null ? mob.getBlockY() : from.getY();
        PathLogRecord record = new PathLogRecord(++seq, settingsVersion, mob.level().getGameTime(), shortId(mob),
                PathLogRecord.NAV_RECRUIT, purpose, Math.sqrt(start.distanceToSqr(Vec3.atBottomCenterOf(target))),
                target.getY() - startY, budget, AsyncPathProcessor.queuedJobs(), System.nanoTime());
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
        try {
            NODES.get()[0] = -1;
        } catch (RuntimeException e) {
            hookFailed(e);
        }
        return System.nanoTime();
    }

    /** processPath, as it returns: how many nodes it took off the open set. */
    public static void nodes(int count) {
        if (!on) return;
        try {
            NODES.get()[0] = count;
        } catch (RuntimeException e) {
            hookFailed(e);
        }
    }

    public static void searchFound(PathLogRecord record, Path path, long started) {
        if (record == null) return;
        try {
            record.searched(threadCode(), NODES.get()[0], micros(started));
            result(record, path);
        } catch (RuntimeException e) {
            hookFailed(e);
        }
    }

    public static void searchFailed(PathLogRecord record, long started) {
        if (record == null) return;
        try {
            record.searched(threadCode(), NODES.get()[0], micros(started));
            record.failed();
        } catch (RuntimeException e) {
            hookFailed(e);
        }
    }

    /**
     * In the search's finally: a search that ended without being found or failed threw an Error.
     * Its path stays unfinished and no result will arrive, so its row is written now.
     */
    public static void searchEnded(PathLogRecord record, long started) {
        if (record == null || record.ran()) return;
        try {
            record.searched(threadCode(), NODES.get()[0], micros(started));
            record.failed();
            emit(PATHS, record.finish(-1, PathLogRecord.ERROR));
        } catch (RuntimeException e) {
            hookFailed(e);
        }
    }

    /**
     * A search that ran inline on the server thread with vanilla's PathFinder: its row now. No path
     * means no search ran: the mob had no start node.
     */
    public static void ranInline(PathLogRecord record, Path path) {
        if (record == null) return;
        if (path != null) {
            record.searched(PathLogRecord.INLINE, -1, micros(record.requestNanos()));
            result(record, path);
        }
        emit(PATHS, record.finish(0, path == null ? PathLogRecord.NO_PATH : PathLogRecord.APPLIED));
    }

    /** The search's path has been handed back to its caller; a result arriving before this is the caller's to walk. */
    public static void handedBack(PathLogRecord record) {
        if (record != null) record.handedBack();
    }

    /**
     * A search's result back on the server thread at {@code tick}: walked, or discarded because a
     * newer path replaced it. Discarded only applies once the path was handed back: a search that
     * finished at once on the server thread (queue full, or workers off) arrives before its caller
     * has even taken the path.
     */
    public static void arrived(PathLogRecord record, Path path, boolean replaced, long tick) {
        if (record == null) return;
        char outcome = replaced && record.wasHandedBack() ? PathLogRecord.DISCARDED
                : path == null || path.getNodeCount() == 0 ? PathLogRecord.NO_PATH : PathLogRecord.APPLIED;
        emit(PATHS, record.finish(tick - record.requestTick(), outcome));
    }

    // ---- a mounted follower, on its horse's vanilla navigation ----

    /** The path a mounted follower's navigation held before a move, to tell a new search from none. */
    public record VanillaMove(Path before, long started) {
    }

    public static VanillaMove vanillaMove(PathNavigation navigation) {
        return on ? new VanillaMove(navigation.getPath(), System.nanoTime()) : null;
    }

    /**
     * After the move: a row if the navigation holds a new path. Vanilla's navigation gives no sign
     * of a search that found nothing, or of a new path equal to the old one, so those leave no row.
     */
    public static void vanillaMoved(VanillaMove move, AbstractRecruitEntity recruit, PathNavigation navigation, Entity target) {
        if (move == null || !on) return;
        Path after = navigation.getPath();
        if (after == null || after == move.before()) return;
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

    /** {@code mob}'s {@code navigation} refused a move because it had given up; a purpose set for it is used up. */
    public static void gaveUp(Mob mob, PathNavigation navigation) {
        if (!on) return;
        if (taggedNavigation == navigation) taggedNavigation = null;
        PathLogSummary window = FOLLOWERS.get(mob);
        if (window != null) window.gaveUp();
    }

    public static void reused(Mob mob) {
        if (!on) return;
        PathLogSummary window = FOLLOWERS.get(mob);
        if (window != null) window.reused();
    }

    // ---- helpers ----

    private static void hookFailed(RuntimeException e) {
        faulted = true;
        if (FAULT_REPORTED.compareAndSet(false, true)) {
            Main.LOGGER.warn("[KhaosKorps] Path log: a search hook failed, so logging stops until PathLog is turned off and on again", e);
        }
    }

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
