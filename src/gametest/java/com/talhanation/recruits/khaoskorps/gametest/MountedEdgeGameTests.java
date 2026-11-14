package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.EdgeProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * The edge margin for a mounted recruit. A recruit riding a horse paths with the horse's vanilla
 * navigation, not the recruit navigation, so the margin is added to that search too, while the rider
 * follows. A saddled horse with a recruit riding it, both with their AI off, searches along a ditch
 * five deep: ground at stand height 5, the ditch at z = 3..4 for x = 5..15, start (2, z 6), target
 * (18, z 6). Every path point is checked for a drop deeper than the mounted fall distance within the
 * default margin of 2 blocks, with the same probe the margin uses; the horse is two blocks wide, so
 * its straight way runs two blocks from the drop.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class MountedEdgeGameTests {

    /** While the rider follows, the horse's path keeps off the ditch's rim when it can. */
    @GameTest(template = DetourGameTests.ARENA, timeoutTicks = 40)
    public static void aMountedFollowerKeepsAwayFromADitchWhenItCan(GameTestHelper helper) {
        searchAlongTheDitch(helper, true, onTheRim -> {
            if (!onTheRim.isEmpty()) throw new GameTestAssertException("while following, the horse's path runs along the rim at " + onTheRim);
        });
    }

    /** With the rider not following (a move or hold order), the horse takes the straight way along the rim, as in 1.15.2. */
    @GameTest(template = DetourGameTests.ARENA, timeoutTicks = 40)
    public static void aMountedRecruitNotFollowingTakesTheStraightWayAlongTheRim(GameTestHelper helper) {
        searchAlongTheDitch(helper, false, onTheRim -> {
            if (onTheRim.isEmpty()) throw new GameTestAssertException("setup: without following the path should run along the rim");
        });
    }

    private static void searchAlongTheDitch(GameTestHelper helper, boolean following, java.util.function.Consumer<List<String>> check) {
        for (int x = 1; x <= 20; x++) {
            for (int z = 1; z <= 12; z++) {
                boolean ditch = x >= 5 && x <= 15 && z >= 3 && z <= 4;
                for (int y = 0; y < 5; y++) helper.setBlock(x, y, z, ditch ? Blocks.AIR : Blocks.STONE);
            }
        }
        daytime(helper);
        Horse horse = helper.spawn(EntityType.HORSE, 2, 5, 6);
        horse.setNoAi(true);
        horse.setTamed(true);
        horse.equipSaddle(null);
        horse.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(32.0);
        AbstractRecruitEntity rider = helper.spawn(ModEntityTypes.RECRUIT.get(), 2, 5, 6);
        rider.setNoAi(true);
        rider.setAggroState(3);
        rider.startRiding(horse, true);
        helper.runAfterDelay(1, () -> {
            if (horse.getControllingPassenger() != rider) throw new GameTestAssertException("setup: the recruit doesn't control the horse");
            rider.setIsFollowing(following);
            horse.setOnGround(true);
            Path path = horse.getNavigation().createPath(helper.absolutePos(new BlockPos(18, 5, 6)), 0);
            if (path == null) throw new GameTestAssertException("setup: the horse found no path");
            List<String> onTheRim = new ArrayList<>();
            for (int i = 0; i < path.getNodeCount(); i++) {
                Node node = path.getNode(i);
                BlockPos rel = new BlockPos(node.x, node.y, node.z).subtract(helper.absolutePos(BlockPos.ZERO));
                if (rel.getX() < 6 || rel.getX() > 14) continue;
                int distance = EdgeProbe.distanceToDangerousDrop(helper.getLevel(), node.x, node.y, node.z, horse.getMaxFallDistance(), 2, null);
                if (distance != EdgeProbe.NONE) onTheRim.add(rel.toShortString() + " (" + distance + " from the drop)");
            }
            rider.stopRiding();
            rider.discard();
            horse.discard();
            check.accept(onTheRim);
            helper.succeed();
        });
    }
}
