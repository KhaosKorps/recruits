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

    /** G3-1 and G3-3: one row per search the follower starts, each on the thread searches run on. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 320, batch = LOG_ON)
    public static void withPathLogOnEverySearchOfAFollowerHasOneRow(GameTestHelper helper) {
        Scene scene = scene(helper, 1);
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
                if (!row[5].equals("F") || !row[4].equals("R") || row[15].charAt(0) != thread || Integer.parseInt(row[8]) <= 0) {
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
        Scene scene = scene(helper, 3);
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
        Scene scene = scene(helper, 1);
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
        Scene scene = scene(helper, 1);
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

    /** {@code count} followers at the corridor's west end; the owner walks x 6.5 to 13.5 and back every 60 ticks. */
    private static Scene scene(GameTestHelper helper, int count) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 10.5, 3, 2.5);
        List<AbstractRecruitEntity> recruits = new ArrayList<>();
        for (int i = 0; i < count; i++) recruits.add(followingRecruit(helper, owner, 1 + i, 3, 1 + i % 3));
        helper.onEachTick(() -> {
            double phase = (helper.getTick() % 60) / 60.0;
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
