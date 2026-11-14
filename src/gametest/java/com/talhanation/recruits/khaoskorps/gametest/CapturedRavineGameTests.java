package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Groups on a ravine captured from the user's test world: the box from (-207, -4, -399) to
 * (-112, 72, -352), 96 x 77 x 48, as the template khaoskorps_ravine_1 (leaves made persistent;
 * entities and block entity data left out).
 *
 * In template coordinates: ground at stand height 66 to 67 north and south of a sunken basin; the
 * basin's floor 55 to 65 and lower to the east; in its middle, around x = 31..48, z = 23..32, a pit
 * whose floor stands at 13 to 26, with lava at the bottom of its west end. Below stand height 48 is
 * inside the pit.
 *
 * Optional for now: they measure what a group does there, at fall distance 3 as on the servers.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class CapturedRavineGameTests {

    private static final String RAVINE = "khaoskorps_ravine_1";
    private static final int SIZE_X = 96;
    private static final int SIZE_Z = 48;
    private static final int TOP = 76;
    private static final double IN_THE_PIT_BELOW = 48.0;
    private static final String FALL_THREE = "khaoskorps_captured_ravine_fall_3";
    private static final int MEASURE_AT = 1200;

    @BeforeBatch(batch = FALL_THREE)
    public static void fallThree(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.MaxFallDistance.set(3));
    }

    @AfterBatch(batch = FALL_THREE)
    public static void afterFallThree(ServerLevel level) {
        resetForkSettings();
    }

    /** Barriers on the arena's border columns, from the ground up, so nobody walks off the captured box. */
    private static void fenceIn(GameTestHelper helper) {
        for (int i = 0; i < Math.max(SIZE_X, SIZE_Z); i++) {
            if (i < SIZE_X) {
                wall(helper, i, 0);
                wall(helper, i, SIZE_Z - 1);
            }
            if (i < SIZE_Z) {
                wall(helper, 0, i);
                wall(helper, SIZE_X - 1, i);
            }
        }
        daytime(helper);
    }

    private static void wall(GameTestHelper helper, int x, int z) {
        int y = TOP;
        while (y > 0 && helper.getBlockState(new BlockPos(x, y, z)).isAir()) y--;
        for (int up = y + 1; up <= TOP; up++) helper.setBlock(x, up, z, Blocks.BARRIER);
    }

    /**
     * 24 recruits on the north ground (x = 34..45, z = 9 and 10) follow their owner, who stands on
     * the south side at (40.5, 66, 38.5), across the pit. Measured at tick 1200: how many reached the
     * owner (within 4 blocks), how many are in the pit or dead, and where the rest are.
     */
    @GameTest(template = RAVINE, timeoutTicks = MEASURE_AT + 20, required = false, batch = FALL_THREE)
    public static void aCrowdFollowsAcrossTheCapturedRavineWithoutGoingIntoThePit(GameTestHelper helper) {
        fenceIn(helper);
        ServerPlayer owner = owner(helper, 40.5, 66, 38.5);
        pin(helper, owner, 40.5, 66, 38.5);
        DetourGameTests.standing(helper, owner);
        List<AbstractRecruitEntity> crowd = new ArrayList<>();
        for (int i = 0; i < 24; i++) crowd.add(followingRecruit(helper, owner, 34 + i % 12, 67, 9 + i / 12));
        int[] firstInThePit = {-1};
        helper.runAfterDelay(1, () -> assertFollowPreconditions(crowd.get(0)));
        helper.onEachTick(() -> {
            if (firstInThePit[0] >= 0) return;
            for (AbstractRecruitEntity recruit : crowd) {
                if (recruit.isAlive() && relative(helper, recruit).y < IN_THE_PIT_BELOW) {
                    firstInThePit[0] = (int) helper.getTick();
                    Main.LOGGER.info("[KhaosKorps measure] captured ravine: first recruit in the pit at tick {}, at {}",
                            helper.getTick(), describe(helper, recruit));
                    return;
                }
            }
        });
        helper.runAtTickTime(MEASURE_AT, () -> {
            int reached = 0, inPit = 0, dead = 0;
            List<String> elsewhere = new ArrayList<>();
            for (AbstractRecruitEntity recruit : crowd) {
                if (!recruit.isAlive()) dead++;
                else if (relative(helper, recruit).y < IN_THE_PIT_BELOW) inPit++;
                else if (recruit.distanceToSqr(owner) <= 4.0 * 4.0) reached++;
                else {
                    var at = relative(helper, recruit);
                    elsewhere.add(String.format("(%.0f, %.0f, %.0f)", at.x, at.y, at.z));
                }
            }
            String counts = "reached the owner " + reached + ", in the pit " + inPit + ", dead " + dead
                    + ", elsewhere " + elsewhere.size() + " " + elsewhere;
            Main.LOGGER.info("[KhaosKorps measure] captured ravine, 24 following across the pit: {}", counts);
            removeOwner(helper, owner);
            crowd.forEach(AbstractRecruitEntity::discard);
            if (inPit > 0 || dead > 0) throw new GameTestAssertException("24 recruits following across the captured ravine: " + counts);
            helper.succeed();
        });
    }
}
