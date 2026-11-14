package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * The search settings: FollowMaxPathLength, which caps how far a follow search reaches and how long
 * its path may be (1.15.2 always used the recruit's follow range), and SearchNodesPerBlock, how
 * many path points a search may examine per block of that range (1.15.2 fixed 16 when the
 * recruit's navigation was built).
 *
 * Both read the first finished path, not where the recruit ends up: a short path is walked, the goal
 * restarts, and the recruit reaches the owner anyway, one short path at a time.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class SearchSettingGameTests {

    private static final String T = HarnessGameTests.EMPTY_TEMPLATE;
    private static final String PATH_LENGTH = "khaoskorps_follow_path_length";
    private static final String BUDGET = "khaoskorps_search_budget";

    @BeforeBatch(batch = PATH_LENGTH)
    public static void shortFollowPaths(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.FollowMaxPathLength.set(6));
    }

    @AfterBatch(batch = PATH_LENGTH)
    public static void afterShortFollowPaths(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * Follow path length 6: the first path toward an owner 13 blocks down the corridor stops within
     * 6 blocks of walking, where 1.15.2's follow range of 32 reaches the owner. Set back to 0 (each
     * recruit's follow range) while the recruit follows, the next path reaches the owner.
     */
    @GameTest(template = T, timeoutTicks = 400, batch = PATH_LENGTH)
    public static void theFollowPathLengthCapsTheSearchAndAChangeAppliesToTheNextPath(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 14.5, 3, 2.5);
        pin(helper, owner, 14.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 1, 3, 2);
        Path[] first = {null};

        helper.onEachTick(() -> {
            Path path = finishedPath(recruit);
            if (path == null) return;
            if (first[0] == null) {
                first[0] = path;
                int endX = path.getEndNode().x - helper.absolutePos(BlockPos.ZERO).getX();
                if (path.canReach() || endX > 1 + 6 + 1) {
                    throw new GameTestAssertException("with FollowMaxPathLength 6 the first path "
                            + (path.canReach() ? "reaches the owner" : "stops short") + " and ends at x = " + endX
                            + "; it should stop by x = 8");
                }
                KhaosKorpsServerConfig.FollowMaxPathLength.set(0);
                KhaosKorpsServerConfig.republish();
            } else if (path != first[0]) {
                if (!path.canReach()) {
                    throw new GameTestAssertException("after FollowMaxPathLength went back to 0 the next path still stops short, at "
                            + endOf(helper, path));
                }
                removeOwner(helper, owner);
                helper.succeed();
            }
        });
    }

    /**
     * The budget test relies on the search spreading over the whole corridor, which the original
     * dirt-path preference causes, so the batch sets that preference itself rather than trusting
     * whatever the default becomes.
     */
    @BeforeBatch(batch = BUDGET)
    public static void defaultBudget(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.DirtPathPreference.set(FollowSettings.DirtPathPreference.ORIGINAL));
    }

    @AfterBatch(batch = BUDGET)
    public static void afterBudget(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * Search budget of 1 node per block: 32 nodes for a recruit's follow range of 32. With 1.15.2's
     * dirt-path preference the search spreads over the whole corridor, so 32 nodes run out before
     * an owner 13 blocks away, where 16 per block (512 nodes) reach it. The recruit exists before the
     * setting changes, so this also shows the budget is read per search, not when the navigation is built.
     */
    @GameTest(template = T, timeoutTicks = 200, batch = BUDGET)
    public static void theSearchBudgetIsReadForEachSearch(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        // The owner starts a block from the recruit, inside the start distance, and walks off at tick 10.
        ServerPlayer owner = owner(helper, 2.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 1, 3, 2);
        Vec3 near = helper.absoluteVec(new Vec3(2.5, 3, 2.5));
        Vec3 far = helper.absoluteVec(new Vec3(14.5, 3, 2.5));

        helper.runAtTickTime(5, () -> {
            KhaosKorpsServerConfig.SearchNodesPerBlock.set(1);
            KhaosKorpsServerConfig.republish();
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Vec3 at = t < 10 ? near : far;
            owner.setPos(at.x, at.y, at.z);
            if (t < 10) return;
            Path path = finishedPath(recruit);
            if (path == null) return;
            if (path.canReach()) {
                throw new GameTestAssertException("with SearchNodesPerBlock 1 (32 nodes) the first path reached the owner 13 blocks away");
            }
            removeOwner(helper, owner);
            helper.succeed();
        });
    }

    private static String endOf(GameTestHelper helper, Path path) {
        Node end = path.getEndNode();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return "(" + (end.x - origin.getX()) + ", " + (end.y - origin.getY()) + ", " + (end.z - origin.getZ()) + ")";
    }
}
