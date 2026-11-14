package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import com.talhanation.recruits.khaoskorps.PathLog;
import com.talhanation.recruits.khaoskorps.PathLogRecord;
import com.talhanation.recruits.khaoskorps.PathLogSettingsRow;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * PathLog (Nena's G3): with the setting on, every search a following recruit starts gets one row,
 * on the thread it ran on; a follower gets a summary row every 100 ticks; logging starts with a
 * settings row. With it off, nothing is written. Rows are read through PathLog's tap, which sees
 * each row as it is written; the files themselves go to the test server's logs/recruits-paths/.
 *
 * In each scene the owner walks back and forth along the corridor, so the follow goal keeps
 * starting new searches.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class PathLogGameTests {

    private static final String LOG_ON = "khaoskorps_path_log_on";
    private static final String LOG_ON_QUEUE_FULL = "khaoskorps_path_log_on_queue_full";

    /** Every row written since the test server started: {file, row}. */
    private static final ConcurrentLinkedQueue<Object[]> ROWS = new ConcurrentLinkedQueue<>();

    static {
        PathLog.tap((file, row) -> ROWS.add(new Object[]{file, row}));
    }

    @BeforeBatch(batch = LOG_ON)
    public static void logOn(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.PathLog.set(true));
    }

    @AfterBatch(batch = LOG_ON)
    public static void afterLogOn(ServerLevel level) {
        resetForkSettings();
    }

    @BeforeBatch(batch = LOG_ON_QUEUE_FULL)
    public static void logOnQueueFull(ServerLevel level) {
        logOn(level);
    }

    @AfterBatch(batch = LOG_ON_QUEUE_FULL)
    public static void afterLogOnQueueFull(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * A search that runs on the server thread because the worker queue is full (thread M) is
     * walked, so its row says so: outcome A, not D. The workers and their queue are filled with
     * latched jobs, so the next search is turned away and runs at once on the server thread.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40, batch = LOG_ON_QUEUE_FULL)
    public static void withPathLogOnASearchRunOnTheServerThreadIsLoggedAsWalked(GameTestHelper helper) {
        if (!RecruitsServerConfig.UseAsyncPathfinding.get()) {
            helper.succeed();
            return;
        }
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity recruit = helper.spawn(com.talhanation.recruits.init.ModEntityTypes.RECRUIT.get(), 2, 3, 2);
        recruit.setNoAi(true);
        Path[] walked = {null};
        helper.runAtTickTime(3, () -> {
            if (!PathLog.isOn()) throw new GameTestAssertException("setup: PathLog isn't on");
            recruit.setOnGround(true);
            com.talhanation.recruits.pathfinding.AsyncPathNavigation navigation =
                    (com.talhanation.recruits.pathfinding.AsyncPathNavigation) recruit.getNavigation();
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            try {
                fillTheWorkersAndTheirQueue(helper, release);
                walked[0] = navigation.createPath(helper.absolutePos(new net.minecraft.core.BlockPos(13, 3, 2)), 1);
                navigation.moveTo(walked[0], 1.0D);
            } finally {
                release.countDown();
            }
        });
        helper.runAtTickTime(8, () -> {
            Path now = recruit.getNavigation().getPath();
            List<String[]> rows = rows(PathLog.PATHS, recruit);
            recruit.discard();
            String[] row = rows.stream().filter(r -> r[15].equals("M")).findFirst().orElseThrow(() ->
                    new GameTestAssertException("setup: no search ran on the server thread: " + rows.size() + " rows"));
            if (now != walked[0]) throw new GameTestAssertException("setup: the navigation isn't walking the search's path");
            if (!row[18].equals("A")) {
                throw new GameTestAssertException("a search run on the server thread and walked is logged with outcome " + row[18] + ": " + String.join(",", row));
            }
            helper.succeed();
        });
    }

    /**
     * A search run on the server thread whose path is never walked (as the melee goal's check that a
     * target is reachable) is logged as discarded: the next path walked replaces it.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40, batch = LOG_ON_QUEUE_FULL)
    public static void withPathLogOnASearchOnTheServerThreadNeverWalkedIsLoggedAsDiscarded(GameTestHelper helper) {
        if (!RecruitsServerConfig.UseAsyncPathfinding.get()) {
            helper.succeed();
            return;
        }
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity recruit = helper.spawn(com.talhanation.recruits.init.ModEntityTypes.RECRUIT.get(), 2, 3, 2);
        recruit.setNoAi(true);
        // Tick 6, after the other test in this batch has filled the queue at tick 3 and let it go.
        helper.runAtTickTime(6, () -> {
            if (!PathLog.isOn()) throw new GameTestAssertException("setup: PathLog isn't on");
            recruit.setOnGround(true);
            com.talhanation.recruits.pathfinding.AsyncPathNavigation navigation =
                    (com.talhanation.recruits.pathfinding.AsyncPathNavigation) recruit.getNavigation();
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            try {
                fillTheWorkersAndTheirQueue(helper, release);
                navigation.createPath(helper.absolutePos(new net.minecraft.core.BlockPos(13, 3, 2)), 1);
            } finally {
                release.countDown();
            }
        });
        helper.runAtTickTime(14, () -> {
            List<String[]> rows = rows(PathLog.PATHS, recruit);
            recruit.discard();
            String[] row = rows.stream().filter(r -> r[15].equals("M")).findFirst().orElseThrow(() ->
                    new GameTestAssertException("setup: no search ran on the server thread: " + rows.size() + " rows"));
            if (!row[18].equals("D")) {
                throw new GameTestAssertException("a search on the server thread that was never walked is logged with outcome " + row[18]
                        + ": " + String.join(",", row));
            }
            helper.succeed();
        });
    }

    /**
     * Holds every path worker on a latched job and fills the queue behind them, so the next search is
     * turned away. A latched job turned away itself runs on the server thread and returns at once.
     */
    private static void fillTheWorkersAndTheirQueue(GameTestHelper helper, java.util.concurrent.CountDownLatch release) {
        int workers = Math.max(1, RecruitsServerConfig.AsyncPathfindingThreadsCount.get());
        java.util.function.Supplier<Path> blocker = () -> {
            if (Thread.currentThread().getName().startsWith("recruits-path-processor-")) {
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        };
        java.util.Set<net.minecraft.core.BlockPos> at = java.util.Set.of(helper.absolutePos(new net.minecraft.core.BlockPos(1, 3, 2)));
        for (int i = 0; i < workers; i++) new com.talhanation.recruits.pathfinding.AsyncPath(new ArrayList<>(), at, helper.getLevel(), blocker);
        long until = System.nanoTime() + 2_000_000_000L;
        while (com.talhanation.recruits.pathfinding.AsyncPathProcessor.queuedJobs() > 0 && System.nanoTime() < until) Thread.onSpinWait();
        // Topped up until full: a worker that was still free takes a job, and one turned away returns at once.
        for (int i = 0; i < workers * 24 && com.talhanation.recruits.pathfinding.AsyncPathProcessor.queuedJobs() < workers * 8; i++) {
            new com.talhanation.recruits.pathfinding.AsyncPath(new ArrayList<>(), at, helper.getLevel(), blocker);
        }
        if (com.talhanation.recruits.pathfinding.AsyncPathProcessor.queuedJobs() < workers * 8) {
            throw new GameTestAssertException("setup: the worker queue holds only " + com.talhanation.recruits.pathfinding.AsyncPathProcessor.queuedJobs());
        }
    }

    /** A follower removed in the tick after its summary row leaves PathLog's list, with no window left to write. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 220, batch = LOG_ON)
    public static void withPathLogOnAFollowerRemovedRightAfterItsSummaryIsForgotten(GameTestHelper helper) {
        Scene scene = scene(helper, 1, Long.MAX_VALUE);
        AbstractRecruitEntity recruit = scene.recruits.get(0);
        long[] removedAt = {-1};
        helper.onEachTick(() -> {
            if (removedAt[0] < 0) {
                if (!rows(PathLog.RECRUITS, recruit).isEmpty()) {
                    recruit.discard();
                    removedAt[0] = helper.getTick();
                }
                return;
            }
            if (helper.getTick() < removedAt[0] + 3) return;
            removeOwner(helper, scene.owner);
            if (followers().containsKey(recruit)) throw new GameTestAssertException("PathLog still holds the removed follower");
            helper.succeed();
        });
    }

    private static java.util.Map<?, ?> followers() {
        try {
            java.lang.reflect.Field field = PathLog.class.getDeclaredField("FOLLOWERS");
            field.setAccessible(true);
            return (java.util.Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("setup: can't read PathLog's followers: " + e);
        }
    }

    /** A detour search's row measures from where the search starts, not from where the recruit stands. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40, batch = LOG_ON)
    public static void withPathLogOnADetourSearchIsMeasuredFromWhereItStarts(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity recruit = helper.spawn(com.talhanation.recruits.init.ModEntityTypes.RECRUIT.get(), 2, 3, 2);
        recruit.setNoAi(true);
        helper.runAtTickTime(3, () -> {
            if (!PathLog.isOn()) throw new GameTestAssertException("setup: PathLog isn't on");
            com.talhanation.recruits.khaoskorps.DetourSearch.find(helper.getLevel(), recruit,
                    helper.absolutePos(new net.minecraft.core.BlockPos(10, 3, 2)), helper.absolutePos(new net.minecraft.core.BlockPos(13, 3, 2)), 8, 1);
        });
        helper.succeedWhen(() -> {
            String[] row = rows(PathLog.PATHS, recruit).stream().filter(r -> r[5].equals("D")).findFirst()
                    .orElseThrow(() -> new GameTestAssertException("no detour row yet"));
            recruit.discard();
            if (Integer.parseInt(row[6]) > 40) {
                throw new GameTestAssertException("the detour row's distance is " + row[6] + " tenths, measured from the recruit; the search started 3 blocks from its target");
            }
        });
    }

    /** G3-1 and G3-3: one row per search the follower starts, each on the thread searches run on. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 320, batch = LOG_ON)
    public static void withPathLogOnEverySearchOfAFollowerHasOneRow(GameTestHelper helper) {
        Scene scene = scene(helper, 1, 220);
        AbstractRecruitEntity recruit = scene.recruits.get(0);
        Set<Path> adopted = Collections.newSetFromMap(new IdentityHashMap<>());
        long[] observingFrom = {-1};
        Path[] before = {null};
        helper.onEachTick(() -> {
            Path path = recruit.getNavigation().getPath();
            if (observingFrom[0] < 0) {
                if (!PathLog.isOn()) return;
                observingFrom[0] = recruit.level().getGameTime();
                before[0] = path;
                return;
            }
            if (path != null && path != before[0]) adopted.add(path);
        });
        helper.runAtTickTime(300, () -> {
            removeOwner(helper, scene.owner);
            recruit.discard();
            if (observingFrom[0] < 0) throw new GameTestAssertException("setup: PathLog never turned on");
            List<String[]> rows = pathRows(recruit, observingFrom[0]);
            long walkedOrNone = rows.stream().filter(r -> r[18].equals("A") || r[18].equals("N")).count();
            char thread = RecruitsServerConfig.UseAsyncPathfinding.get() ? PathLogRecord.WORKER : PathLogRecord.INLINE;
            for (String[] row : rows) {
                boolean nodesKnown = thread != PathLogRecord.WORKER || Integer.parseInt(row[10]) > 0;
                if (!row[5].equals("F") || !row[4].equals("R") || row[15].charAt(0) != thread || Integer.parseInt(row[8]) <= 0 || !nodesKnown) {
                    throw new GameTestAssertException("expected a follow search by the recruit navigation on thread " + thread
                            + " with a budget: " + String.join(",", row));
                }
            }
            if (adopted.isEmpty()) throw new GameTestAssertException("setup: the follower never took a new path");
            if (rows.size() < adopted.size()) {
                throw new GameTestAssertException(adopted.size() + " new paths but only " + rows.size() + " rows");
            }
            if (walkedOrNone > adopted.size()) {
                throw new GameTestAssertException(walkedOrNone + " rows say walked or no path, but only " + adopted.size() + " paths were taken");
            }
            helper.succeed();
        });
    }

    /** G3-3 with three followers: every row's thread is the one searches run on. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 220, batch = LOG_ON)
    public static void withPathLogOnThreeFollowersSearchOnTheExpectedThread(GameTestHelper helper) {
        Scene scene = scene(helper, 3, Long.MAX_VALUE);
        long start = helper.getLevel().getGameTime();
        helper.runAtTickTime(200, () -> {
            removeOwner(helper, scene.owner);
            scene.recruits.forEach(AbstractRecruitEntity::discard);
            String thread = RecruitsServerConfig.UseAsyncPathfinding.get() ? "W" : "I";
            int rows = 0;
            for (AbstractRecruitEntity recruit : scene.recruits) {
                for (String[] row : pathRows(recruit, start)) {
                    rows++;
                    if (!row[15].equals(thread)) {
                        throw new GameTestAssertException("expected thread " + thread + ": " + String.join(",", row));
                    }
                }
            }
            if (rows == 0) throw new GameTestAssertException("no rows for three followers");
            helper.succeed();
        });
    }

    /** A follower gets a summary row every 100 ticks, counting its ticks in Follow Me and its goal's start. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 260, batch = LOG_ON)
    public static void withPathLogOnAFollowerGetsASummaryEvery100Ticks(GameTestHelper helper) {
        Scene scene = scene(helper, 1, Long.MAX_VALUE);
        AbstractRecruitEntity recruit = scene.recruits.get(0);
        helper.runAtTickTime(240, () -> {
            removeOwner(helper, scene.owner);
            recruit.discard();
            List<String[]> rows = rows(PathLog.RECRUITS, recruit);
            if (rows.size() < 2) throw new GameTestAssertException("expected at least 2 summary rows in 240 ticks, got " + rows.size());
            String[] first = rows.get(0);
            boolean started = rows.stream().anyMatch(r -> Integer.parseInt(r[5]) > 0);
            if (!first[2].equals("100") || Integer.parseInt(first[3]) == 0 || !started || Integer.parseInt(first[11]) <= 0) {
                throw new GameTestAssertException("expected a 100-tick window with follow ticks, a goal start and a speed: " + String.join(",", first));
            }
            helper.succeed();
        });
    }

    /** Logging starts with a settings row, with PathLog on in it. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40, batch = LOG_ON)
    public static void withPathLogOnTheSettingsAreLogged(GameTestHelper helper) {
        helper.runAtTickTime(20, () -> {
            String[] header = PathLogSettingsRow.HEADER.split(",");
            int pathLog = java.util.Arrays.asList(header).indexOf("path_log");
            boolean found = false;
            for (Object[] entry : ROWS) {
                if ((int) entry[0] != PathLog.SETTINGS) continue;
                String[] row = ((String) entry[1]).split(",", -1);
                if (row.length != header.length) throw new GameTestAssertException("settings row and header differ in length: " + entry[1]);
                if (row[pathLog].equals("1")) found = true;
            }
            if (!found) throw new GameTestAssertException("no settings row with path_log 1");
            helper.succeed();
        });
    }

    /** G3-2: with the setting off, as by default, nothing is written for a follower. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 220)
    public static void byDefaultNothingIsLogged(GameTestHelper helper) {
        Scene scene = scene(helper, 1, Long.MAX_VALUE);
        AbstractRecruitEntity recruit = scene.recruits.get(0);
        helper.runAtTickTime(200, () -> {
            removeOwner(helper, scene.owner);
            recruit.discard();
            if (PathLog.isOn()) throw new GameTestAssertException("PathLog is on with the default settings");
            int rows = rows(PathLog.PATHS, recruit).size() + rows(PathLog.RECRUITS, recruit).size();
            if (rows > 0) throw new GameTestAssertException(rows + " rows for a follower with PathLog off");
            helper.succeed();
        });
    }

    private record Scene(ServerPlayer owner, List<AbstractRecruitEntity> recruits) {
    }

    /**
     * {@code count} followers at the corridor's west end; the owner walks x 6.5 to 13.5 and back
     * every 60 ticks, and stands still from {@code stopAt} on.
     */
    private static Scene scene(GameTestHelper helper, int count, long stopAt) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 10.5, 3, 2.5);
        List<AbstractRecruitEntity> recruits = new ArrayList<>();
        for (int i = 0; i < count; i++) recruits.add(followingRecruit(helper, owner, 1 + i, 3, 1 + i % 3));
        helper.onEachTick(() -> {
            double phase = (Math.min(helper.getTick(), stopAt) % 60) / 60.0;
            double x = 6.5 + 7.0 * (phase < 0.5 ? phase * 2 : (1 - phase) * 2);
            Vec3 at = helper.absoluteVec(new Vec3(x, 3, 2.5));
            owner.setPos(at.x, at.y, at.z);
        });
        return new Scene(owner, recruits);
    }

    private static String shortId(AbstractRecruitEntity recruit) {
        return recruit.getUUID().toString().substring(0, 8);
    }

    /** The recruit's rows in one file, split into fields. */
    private static List<String[]> rows(int file, AbstractRecruitEntity recruit) {
        String id = shortId(recruit);
        int column = file == PathLog.PATHS ? 3 : 1;
        List<String[]> rows = new ArrayList<>();
        for (Object[] entry : ROWS) {
            if ((int) entry[0] != file) continue;
            String[] row = ((String) entry[1]).split(",", -1);
            if (row.length > column && row[column].equals(id)) rows.add(row);
        }
        return rows;
    }

    /** The recruit's path rows for searches requested after {@code afterTick}. */
    private static List<String[]> pathRows(AbstractRecruitEntity recruit, long afterTick) {
        List<String[]> rows = new ArrayList<>();
        for (String[] row : rows(PathLog.PATHS, recruit)) if (Long.parseLong(row[2]) > afterTick) rows.add(row);
        return rows;
    }
}
