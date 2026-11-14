package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * FireCheckTicks, TntCheckTicks and VillageDefenceCheckTicks: how often each recruit runs its
 * always-on checks, staggered by recruit id. Each check gets a scene that sets it off at once:
 *
 * - TNT: lit TNT three blocks away, with a fuse long enough not to go off; the check sends the
 *   recruit away on a path.
 * - Fire: fire overhead on both sides of the recruit; the check samples columns at random, so it
 *   may take more than one check to find it, and it too sends the recruit away on a path.
 * - Village defence: a villager on the recruit's team that hates a player nearby; the check makes
 *   that player the recruit's target.
 *
 * By default each reacts within six ticks, as in 1.15.2. With all three set to every 100 ticks, a
 * recruit whose stagger puts its first check 20 to 50 ticks after it starts doesn't react before
 * tick 15, and reacts by tick 160, which leaves the fire check a second try.
 *
 * Each recruit is in Follow Me with no owner, so neither following nor wandering gives it a path.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class CheckThrottleGameTests {

    private static final String SLOW = "khaoskorps_checks_slow";

    @BeforeBatch(batch = SLOW)
    public static void slowChecks(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.FireCheckTicks.set(100);
            KhaosKorpsServerConfig.TntCheckTicks.set(100);
            KhaosKorpsServerConfig.VillageDefenceCheckTicks.set(100);
        });
    }

    @AfterBatch(batch = SLOW)
    public static void afterSlowChecks(ServerLevel level) {
        resetForkSettings();
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void byDefaultARecruitFleesLitTntAtOnce(GameTestHelper helper) {
        reactsAtOnce(helper, tnt(helper, false), "lit TNT three blocks away");
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 180, batch = SLOW)
    public static void withTntChecksEvery100TicksARecruitReactsAtItsOwnCheck(GameTestHelper helper) {
        reactsAtItsOwnCheck(helper, tnt(helper, true), "TntCheckTicks");
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void byDefaultARecruitFleesFireAtOnce(GameTestHelper helper) {
        reactsAtOnce(helper, fire(helper, false), "fire overhead on both sides");
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 180, batch = SLOW)
    public static void withFireChecksEvery100TicksARecruitReactsAtItsOwnCheck(GameTestHelper helper) {
        reactsAtItsOwnCheck(helper, fire(helper, true), "FireCheckTicks");
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void byDefaultARecruitDefendsItsVillageAtOnce(GameTestHelper helper) {
        reactsAtOnce(helper, village(helper, false), "a player its team's villager hates");
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 180, batch = SLOW)
    public static void withVillageChecksEvery100TicksARecruitReactsAtItsOwnCheck(GameTestHelper helper) {
        reactsAtItsOwnCheck(helper, village(helper, true), "VillageDefenceCheckTicks");
    }

    /** A scene: the recruit, whether it has reacted yet, and how to remove what the test made. */
    private record Scene(AbstractRecruitEntity recruit, BooleanSupplier reacted, Runnable cleanUp) {
    }

    private static void reactsAtOnce(GameTestHelper helper, Scene scene, String what) {
        long[] reactedAt = watch(helper, scene);
        helper.runAtTickTime(7, () -> {
            scene.cleanUp().run();
            if (reactedAt[0] < 0) throw new GameTestAssertException("by default the recruit hadn't reacted to " + what + " after six ticks");
            helper.succeed();
        });
    }

    private static void reactsAtItsOwnCheck(GameTestHelper helper, Scene scene, String setting) {
        long[] reactedAt = watch(helper, scene);
        helper.runAtTickTime(15, () -> {
            if (reactedAt[0] >= 0) {
                scene.cleanUp().run();
                throw new GameTestAssertException("with " + setting + " 100 the recruit (id " + scene.recruit().getId() + ") reacted at tick "
                        + reactedAt[0] + ", before its first check");
            }
        });
        helper.runAtTickTime(160, () -> {
            scene.cleanUp().run();
            if (reactedAt[0] < 0) throw new GameTestAssertException("with " + setting + " 100 the recruit (id " + scene.recruit().getId() + ") never reacted");
            helper.succeed();
        });
    }

    /** The first tick the recruit reacted on, or -1. */
    private static long[] watch(GameTestHelper helper, Scene scene) {
        long[] reactedAt = {-1};
        helper.onEachTick(() -> {
            if (reactedAt[0] < 0 && scene.reacted().getAsBoolean()) reactedAt[0] = helper.getTick();
        });
        return reactedAt;
    }

    /**
     * A recruit in the corridor, in Follow Me with no owner. With {@code staggered}, recruits are
     * spawned until one's id puts its first check at a 100-tick interval (every 50 goal runs) 10 to
     * 25 goal runs in, that is 20 to 50 ticks.
     */
    private static AbstractRecruitEntity recruit(GameTestHelper helper, int x, boolean staggered) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), x, 3, 2);
        while (staggered && (recruit.getId() % 50 < 10 || recruit.getId() % 50 > 25)) {
            recruit.discard();
            recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), x, 3, 2);
        }
        recruit.setFollowState(1);
        return recruit;
    }

    /** Whether something has given the recruit a path. */
    private static boolean hasAPath(AbstractRecruitEntity recruit) {
        return recruit.getNavigation().getPath() != null;
    }

    private static Scene tnt(GameTestHelper helper, boolean staggered) {
        AbstractRecruitEntity recruit = recruit(helper, 4, staggered);
        recruit.setAggroState(3);
        PrimedTnt tnt = helper.spawn(EntityType.TNT, 7, 3, 2);
        tnt.setFuse(400);
        return new Scene(recruit, () -> hasAPath(recruit), () -> {
            recruit.discard();
            tnt.discard();
        });
    }

    private static final int[] FIRE_COLUMNS = {3, 4, 6, 7};

    /**
     * The recruit stands at x 5; fire burns on netherrack at x 3, 4, 6 and 7, across the corridor,
     * three blocks above its feet, with two blocks of headroom under the netherrack. Each sample
     * starts three blocks above the recruit and walks down only while above y 1; the test world is
     * below that, so the fire sits exactly where the samples start.
     */
    private static Scene fire(GameTestHelper helper, boolean staggered) {
        AbstractRecruitEntity recruit = recruit(helper, 5, staggered);
        recruit.setAggroState(3);
        for (int x : FIRE_COLUMNS) {
            for (int z = CORRIDOR_Z_MIN; z <= CORRIDOR_Z_MAX; z++) {
                helper.setBlock(x, 5, z, Blocks.NETHERRACK);
                helper.setBlock(x, 6, z, Blocks.FIRE);
            }
        }
        return new Scene(recruit, () -> hasAPath(recruit), () -> {
            recruit.discard();
            for (int x : FIRE_COLUMNS) {
                for (int z = CORRIDOR_Z_MIN; z <= CORRIDOR_Z_MAX; z++) {
                    helper.setBlock(x, 6, z, Blocks.AIR);
                    helper.setBlock(x, 5, z, Blocks.AIR);
                }
            }
        });
    }

    /**
     * A villager whose gossip about the player is below -100, on a team with the recruit and with
     * friendly fire off: the village check only defends villagers the recruit itself mustn't attack.
     */
    private static Scene village(GameTestHelper helper, boolean staggered) {
        AbstractRecruitEntity recruit = recruit(helper, 3, staggered);
        ServerPlayer player = owner(helper, 12.5, 3, 2.5);
        Villager villager = helper.spawn(EntityType.VILLAGER, 8, 3, 2);
        villager.setNoAi(true);
        villager.getGossips().add(player.getUUID(), GossipType.MAJOR_NEGATIVE, 25);
        Scoreboard scoreboard = helper.getLevel().getScoreboard();
        PlayerTeam team = scoreboard.addPlayerTeam("kkt" + UUID.randomUUID().toString().substring(0, 8));
        team.setAllowFriendlyFire(false);
        scoreboard.addPlayerToTeam(recruit.getStringUUID(), team);
        scoreboard.addPlayerToTeam(villager.getStringUUID(), team);
        if (villager.getPlayerReputation(player) > -100) {
            throw new GameTestAssertException("setup: the villager's reputation of the player is " + villager.getPlayerReputation(player));
        }
        return new Scene(recruit, () -> recruit.getTarget() == player, () -> {
            recruit.discard();
            villager.discard();
            removeOwner(helper, player);
            scoreboard.removePlayerTeam(team);
        });
    }
}
