package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.ai.RecruitFollowOwnerGoal;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * The follow goal's settings: start and stop distance, start-check and repath interval, and speed.
 * Each test sets values 1.15.2 never used, so it fails if the goal still uses its old fixed values,
 * and the repath test changes its value mid-test to show the goal picks up a change while running.
 * Each batch sets its own values over the defaults and resets everything after.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class FollowGoalSettingGameTests {

    private static final String T = HarnessGameTests.EMPTY_TEMPLATE;
    private static final String DISTANCES = "khaoskorps_follow_distances";
    private static final String PACE = "khaoskorps_follow_pace";
    private static final String START_CHECK = "khaoskorps_follow_start_check";

    @BeforeBatch(batch = DISTANCES)
    public static void distances(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.FollowStartDistance.set(5.0);
            KhaosKorpsServerConfig.FollowStopDistance.set(4.0);
        });
    }

    @AfterBatch(batch = DISTANCES)
    public static void afterDistances(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * Start distance 5, stop distance 4: no follow with the owner 4.5 blocks away, which 1.15.2's
     * start distance of 3 would follow; then a stop within 4 blocks, short of 1.15.2's 2.65.
     */
    @GameTest(template = T, timeoutTicks = 240, batch = DISTANCES)
    public static void followStartsAndStopsAtTheSettingsDistances(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        // The recruit stands at x = 2.5. The owner starts 4.5 blocks away, then moves to 6.
        ServerPlayer owner = owner(helper, 7.0, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 3, 2);
        Vec3 near = helper.absoluteVec(new Vec3(7.0, 3, 2.5));
        Vec3 far = helper.absoluteVec(new Vec3(8.5, 3, 2.5));
        boolean[] sawFollow = {false};

        helper.onEachTick(() -> {
            long t = helper.getTick();
            Vec3 at = t <= 40 ? near : far;
            owner.setPos(at.x, at.y, at.z);
            if (t <= 40 && recruit.isFollowing()) {
                throw new GameTestAssertException("the recruit started following with the owner "
                        + blocks(recruit.distanceToSqr(owner)) + " blocks away, inside the setting's start distance of 5");
            }
            if (recruit.isFollowing()) sawFollow[0] = true;
        });
        helper.runAfterDelay(1, () -> assertFollowPreconditions(recruit));
        helper.succeedWhen(() -> {
            if (helper.getTick() <= 40) throw new GameTestAssertException("still in the no-follow phase");
            if (!sawFollow[0]) throw new GameTestAssertException("the recruit never started following with the owner 6 blocks away");
            double d = recruit.distanceToSqr(owner);
            if (recruit.isFollowing()) throw new GameTestAssertException("still following, " + blocks(d) + " blocks from the owner");
            if (d > 16.0 + 1e-6 || d < 7.5) {
                throw new GameTestAssertException("the recruit stopped " + blocks(d)
                        + " blocks from the owner; with stop distance 4 it should stop between 2.74 and 4");
            }
            removeOwner(helper, owner);
        });
    }

    @BeforeBatch(batch = PACE)
    public static void pace(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.FollowSpeed.set(0.1);
            KhaosKorpsServerConfig.FollowRepathTicks.set(40);
        });
    }

    @AfterBatch(batch = PACE)
    public static void afterPace(ServerLevel level) {
        resetForkSettings();
    }

    /** Follow speed 0.1: the recruit walks its follow path at the setting's speed, not 1.15.2's 1.05. */
    @GameTest(template = T, timeoutTicks = 200, batch = PACE)
    public static void aFollowingRecruitWalksAtTheSettingsSpeed(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 13.5, 3, 2.5);
        pin(helper, owner, 13.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 1, 3, 2);

        helper.succeedWhen(() -> {
            double speed = recruit.getMoveControl().getSpeedModifier();
            if (!recruit.isFollowing() || Math.abs(speed - 0.1) > 1e-9) {
                throw new GameTestAssertException("following " + recruit.isFollowing()
                        + ", walking at speed modifier " + speed + ", not the setting's 0.1");
            }
            removeOwner(helper, owner);
        });
    }

    /**
     * Repath every 40 ticks: the goal's countdown peaks at 20, because the goal runs every second
     * tick (1.15.2's 10 ticks peak at 5). Changed to 20 while the recruit follows, it peaks at 10.
     */
    @GameTest(template = T, timeoutTicks = 300, batch = PACE)
    public static void theRepathIntervalComesFromTheSettingAndAChangeAppliesWhileFollowing(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 14.5, 3, 2.5);
        pin(helper, owner, 14.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 1, 3, 2);
        RecruitFollowOwnerGoal goal = followGoal(recruit);
        int[] peak = {0, 0};
        int[] samples = {0, 0};

        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (!recruit.isFollowing()) return;
            // Window 0 runs before the change at tick 120; window 1 starts once the old countdown (at most 40 ticks) has run out.
            int window = t < 120 ? 0 : t >= 170 ? 1 : -1;
            if (window < 0) return;
            peak[window] = Math.max(peak[window], countdown(goal));
            samples[window]++;
        });
        helper.runAtTickTime(120, () -> {
            if (samples[0] == 0) throw new GameTestAssertException("setup: the recruit never followed before tick 120");
            if (peak[0] != 20) {
                throw new GameTestAssertException("with FollowRepathTicks 40 the countdown peaked at " + peak[0] + " goal ticks, not 20");
            }
            KhaosKorpsServerConfig.FollowRepathTicks.set(20);
            KhaosKorpsServerConfig.republish();
        });
        helper.runAtTickTime(280, () -> {
            if (samples[1] == 0) throw new GameTestAssertException("setup: the recruit stopped following before tick 170, at " + describe(helper, recruit));
            if (peak[1] != 10) {
                throw new GameTestAssertException("after the change to 20 the countdown peaked at " + peak[1] + " goal ticks, not 10");
            }
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    @BeforeBatch(batch = START_CHECK)
    public static void slowStartCheck(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.FollowStartCheckTicks.set(100));
    }

    @AfterBatch(batch = START_CHECK)
    public static void afterStartCheck(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * Start checks every 100 ticks: the first check, at spawn, finds the owner close; the owner then
     * walks off at tick 10, and the recruit follows only at the next check, near tick 100.
     * With 1.15.2's 10 it would follow by about tick 20.
     */
    @GameTest(template = T, timeoutTicks = 260, batch = START_CHECK)
    public static void theStartCheckRunsAtTheSettingsInterval(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 4.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 3, 2);
        Vec3 near = helper.absoluteVec(new Vec3(4.5, 3, 2.5));
        Vec3 far = helper.absoluteVec(new Vec3(9.5, 3, 2.5));
        long[] firstFollow = {-1};

        helper.onEachTick(() -> {
            long t = helper.getTick();
            Vec3 at = t < 10 ? near : far;
            owner.setPos(at.x, at.y, at.z);
            if (firstFollow[0] < 0 && recruit.isFollowing()) firstFollow[0] = t;
        });
        helper.runAtTickTime(80, () -> {
            if (firstFollow[0] >= 0) {
                throw new GameTestAssertException("the recruit started following at tick " + firstFollow[0]
                        + "; with a start check every 100 ticks it should wait for the check near tick 100");
            }
        });
        helper.succeedWhen(() -> {
            if (helper.getTick() <= 80 || firstFollow[0] < 0) {
                throw new GameTestAssertException("not following yet at tick " + helper.getTick());
            }
            removeOwner(helper, owner);
        });
    }

    private static final String STAGGER = "khaoskorps_follow_stagger";

    @BeforeBatch(batch = STAGGER)
    public static void stagger(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.StaggerFollowRepaths.set(true));
    }

    @AfterBatch(batch = STAGGER)
    public static void afterStagger(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * StaggerFollowRepaths: five recruits told to follow on the same tick start their re-plan
     * countdowns at different points, by entity id, so a group doesn't search all at once.
     */
    @GameTest(template = T, timeoutTicks = 60, batch = STAGGER)
    public static void withStaggerOnFollowersStartingTogetherReplanAtDifferentTimes(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 14.5, 3, 2.5);
        pin(helper, owner, 14.5, 3, 2.5);
        java.util.List<AbstractRecruitEntity> group = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) group.add(followingRecruit(helper, owner, 1 + i % 3, 3, 1 + i / 3 * 2));
        java.util.Set<Integer> starts = new java.util.HashSet<>();
        boolean[] seen = new boolean[5];
        helper.onEachTick(() -> {
            for (int i = 0; i < group.size(); i++) {
                if (seen[i] || !group.get(i).isFollowing()) continue;
                seen[i] = true;
                starts.add(countdown(followGoal(group.get(i))));
            }
            for (boolean s : seen) if (!s) return;
            removeOwner(helper, owner);
            group.forEach(AbstractRecruitEntity::discard);
            if (starts.size() < 3) {
                throw new GameTestAssertException("five followers that started together have only " + starts.size()
                        + " distinct re-plan countdowns: " + starts);
            }
            helper.succeed();
        });
    }

    private static RecruitFollowOwnerGoal followGoal(AbstractRecruitEntity recruit) {
        for (WrappedGoal wrapped : recruit.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof RecruitFollowOwnerGoal goal) return goal;
        }
        throw new GameTestAssertException("setup: the recruit has no RecruitFollowOwnerGoal");
    }

    /** The goal's private countdown to its next path, read by reflection so upstream's class needs no getter. */
    private static int countdown(RecruitFollowOwnerGoal goal) {
        try {
            Field field = RecruitFollowOwnerGoal.class.getDeclaredField("timeToRecalcPath");
            field.setAccessible(true);
            return field.getInt(goal);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("could not read the follow goal's countdown: " + e);
        }
    }

    private static String blocks(double distanceSqr) {
        return String.format("%.2f", Math.sqrt(distanceSqr));
    }
}
