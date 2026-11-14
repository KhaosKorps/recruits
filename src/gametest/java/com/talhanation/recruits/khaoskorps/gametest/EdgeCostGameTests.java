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
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * The edge cost: with EdgeMargin on, a path keeps away from a drop deeper than the fall distance
 * when it can; with it off, it walks the rim as 1.15.2 does.
 *
 * The layout: flat ground at stand height 5, with a five-deep ditch in the lane z = 3 for
 * x = 4..10. The recruit and its owner stand in the middle lane, z = 2, at either end, so the
 * straight path runs along the ditch's rim, and lane z = 1, a block further away, is free.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class EdgeCostGameTests {

    private static final String T = HarnessGameTests.EMPTY_TEMPLATE;
    private static final String EDGE_CARE_OFF = EDGE_CARE_OFF_BATCH;

    @BeforeBatch(batch = EDGE_CARE_OFF)
    public static void edgeCareOff(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.EdgeMargin.set(false);
            KhaosKorpsServerConfig.WaitWhenOwnerUnreachable.set(false);
        });
    }

    @AfterBatch(batch = EDGE_CARE_OFF)
    public static void afterEdgeCareOff(ServerLevel level) {
        resetForkSettings();
    }

    /** EdgeMargin on (the default): the first path leaves the rim for the next lane along the ditch. */
    @GameTest(template = T, timeoutTicks = 100)
    public static void aFollowPathKeepsAwayFromADropWhenItCan(GameTestHelper helper) {
        firstPathAlongTheDitch(helper, (path, onTheRim) -> {
            if (onTheRim > 0) {
                throw new GameTestAssertException("with EdgeMargin on, the first path still has " + onTheRim
                        + " of its points on the ditch's rim (z = 2, x = 5..9): " + pathPoints(helper, path));
            }
        });
    }

    /** EdgeMargin off: the first path runs straight along the rim, as in 1.15.2. */
    @GameTest(template = T, timeoutTicks = 100, batch = EDGE_CARE_OFF)
    public static void withEdgeMarginOffTheFollowPathRunsAlongTheRim(GameTestHelper helper) {
        firstPathAlongTheDitch(helper, (path, onTheRim) -> {
            if (onTheRim != 5) {
                throw new GameTestAssertException("with EdgeMargin off, the first path has " + onTheRim
                        + " of its points on the ditch's rim (z = 2, x = 5..9), not all 5: " + pathPoints(helper, path));
            }
        });
    }

    private interface PathCheck {
        void check(Path path, int pointsOnTheRim);
    }

    private static void firstPathAlongTheDitch(GameTestHelper helper, PathCheck check) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 5);
        for (int x = 4; x <= 10; x++) {
            for (int y = 0; y < 5; y++) helper.setBlock(x, y, CORRIDOR_Z_MAX, Blocks.AIR);
        }
        daytime(helper);
        ServerPlayer owner = owner(helper, 14.5, 5, 2.5);
        pin(helper, owner, 14.5, 5, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 1, 5, 2);

        helper.onEachTick(() -> {
            Path path = finishedPath(recruit);
            if (path == null) return;
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            int onTheRim = 0;
            for (int i = 0; i < path.getNodeCount(); i++) {
                BlockPos p = path.getNode(i).asBlockPos().subtract(origin);
                if (p.getZ() == 2 && p.getX() >= 5 && p.getX() <= 9) onTheRim++;
            }
            check.check(path, onTheRim);
            removeOwner(helper, owner);
            helper.succeed();
        });
    }
}
