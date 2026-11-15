package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.RecruitSpeedSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How fast recruits swim against a current, by swim speed multiplier. Each lane is a one-block-deep
 * channel of flowing water on stone, fed by a source at its east end, so the water runs west. One
 * recruit drifts with no orders, to show the current alone; the others are steered east, upstream,
 * every tick, each with its own swim speed multiplier and no other goals.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class SwimCurrentGameTests {

    private static final String WIDE = "khaoskorps_empty_48x12x48";
    /** The source's column; the water runs west from it for 7 blocks. */
    private static final int SOURCE_X = 12;
    /** Where each recruit starts, in the flowing water, 3 blocks downstream of the source and 4 from its end. */
    private static final double START_X = 9.5;
    private static final int RUN_TICKS = 100;

    /**
     * Logs how many blocks each recruit moved east (upstream) in 100 ticks: the drifting one, then
     * swim speed multipliers 3, 5, 8, 10 and 12 on a recruit's own swim speed of 0.3. Optional: it
     * measures, the choice of multiplier rests on it.
     */
    @GameTest(template = WIDE, timeoutTicks = RUN_TICKS + 40, required = false)
    public static void howFarRecruitsSwimAgainstACurrent(GameTestHelper helper) {
        double[] multipliers = {0, 3.0, 5.0, 8.0, 10.0, 12.0};
        List<AbstractRecruitEntity> recruits = new ArrayList<>();
        for (int lane = 0; lane < multipliers.length; lane++) {
            int z = 2 + 2 * lane;
            channel(helper, z);
        }
        helper.runAfterDelay(20, () -> {
            for (int lane = 0; lane < multipliers.length; lane++) {
                int z = 2 + 2 * lane;
                AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), new BlockPos((int) START_X, 2, z));
                recruit.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(START_X, 2, z + 0.5)));
                recruit.goalSelector.removeAllGoals(goal -> true);
                recruit.targetSelector.removeAllGoals(goal -> true);
                swimSpeed(recruit, multipliers[lane] == 0 ? 1.0 : multipliers[lane]);
                recruits.add(recruit);
            }
        });
        helper.onEachTick(() -> {
            if (recruits.isEmpty()) return;
            for (int lane = 1; lane < multipliers.length; lane++) {
                int z = 2 + 2 * lane;
                net.minecraft.world.phys.Vec3 goal = helper.absoluteVec(new net.minecraft.world.phys.Vec3(SOURCE_X + 0.5, 2, z + 0.5));
                recruits.get(lane).getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
            }
        });
        for (int at : new int[]{20 + 10, 20 + 50}) {
            helper.runAfterDelay(at, () -> {
                StringBuilder state = new StringBuilder();
                for (int lane = 0; lane < multipliers.length; lane++) {
                    AbstractRecruitEntity r = recruits.get(lane);
                    state.append(String.format(Locale.ROOT, "lane %d x %.2f vx %.3f zza %.3f speed %.3f swim %.2f ground %s water %s; ",
                            lane, r.getX() - helper.absoluteVec(new net.minecraft.world.phys.Vec3(0, 0, 0)).x, r.getDeltaMovement().x, r.zza,
                            r.getSpeed(), r.getAttributeValue(ForgeMod.SWIM_SPEED.get()), r.onGround(), r.isInWater()));
                }
                Main.LOGGER.info("[KhaosKorps measure] swim state at tick {}: {}", helper.getTick(), state);
            });
        }
        helper.runAfterDelay(20 + RUN_TICKS, () -> {
            StringBuilder out = new StringBuilder();
            for (int lane = 0; lane < multipliers.length; lane++) {
                AbstractRecruitEntity recruit = recruits.get(lane);
                double moved = recruit.getX() - helper.absoluteVec(new net.minecraft.world.phys.Vec3(START_X, 2, 0)).x;
                out.append(lane == 0 ? "drifting" : String.format(Locale.ROOT, "swim x%.1f", multipliers[lane]))
                        .append(String.format(Locale.ROOT, ": %+.2f blocks (in water %s); ", moved, recruit.isInWater()));
                recruit.discard();
            }
            Main.LOGGER.info("[KhaosKorps measure] upstream in {} ticks against a one-deep current: {}", RUN_TICKS, out);
            helper.succeed();
        });
    }

    /**
     * With the swim speed multiplier at 10, a recruit steered upstream gains ground against a full
     * current: more than a block in 100 ticks.
     */
    @GameTest(template = WIDE, timeoutTicks = RUN_TICKS + 40)
    public static void atSwimSpeedTenARecruitOutSwimsACurrent(GameTestHelper helper) {
        channel(helper, 2);
        AbstractRecruitEntity[] recruit = new AbstractRecruitEntity[1];
        helper.runAfterDelay(20, () -> {
            recruit[0] = helper.spawn(ModEntityTypes.RECRUIT.get(), new BlockPos((int) START_X, 2, 2));
            recruit[0].moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(START_X, 2, 2.5)));
            recruit[0].goalSelector.removeAllGoals(goal -> true);
            recruit[0].targetSelector.removeAllGoals(goal -> true);
            swimSpeed(recruit[0], 10.0);
        });
        helper.onEachTick(() -> {
            if (recruit[0] == null) return;
            net.minecraft.world.phys.Vec3 goal = helper.absoluteVec(new net.minecraft.world.phys.Vec3(SOURCE_X + 0.5, 2, 2.5));
            recruit[0].getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
        });
        helper.runAfterDelay(20 + RUN_TICKS, () -> {
            double moved = recruit[0].getX() - helper.absoluteVec(new net.minecraft.world.phys.Vec3(START_X, 2, 0)).x;
            recruit[0].discard();
            if (moved < 1.0) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(String.format(Locale.ROOT,
                        "at swim speed x10 the recruit moved %+.2f blocks upstream in %d ticks; expected more than +1", moved, RUN_TICKS));
            }
            helper.succeed();
        });
    }

    /** A one-block-deep channel along x at {@code z}: stone floor, glass sides and ends, a source at its east end. */
    private static void channel(GameTestHelper helper, int z) {
        for (int x = 0; x <= SOURCE_X + 1; x++) {
            helper.setBlock(x, 1, z, Blocks.STONE);
            for (int y = 2; y <= 3; y++) {
                helper.setBlock(x, y, z - 1, Blocks.GLASS);
                helper.setBlock(x, y, z + 1, Blocks.GLASS);
            }
        }
        for (int y = 2; y <= 3; y++) {
            helper.setBlock(0, y, z, Blocks.GLASS);
            helper.setBlock(SOURCE_X + 1, y, z, Blocks.GLASS);
        }
        helper.setBlock(SOURCE_X, 2, z, Blocks.WATER);
    }

    /** Sets a recruit's swim speed multiplier, the way the SwimSpeedMultiplier setting does. */
    private static void swimSpeed(AbstractRecruitEntity recruit, double multiplier) {
        AttributeInstance swim = recruit.getAttribute(ForgeMod.SWIM_SPEED.get());
        if (swim == null) throw new net.minecraft.gametest.framework.GameTestAssertException("setup: a recruit has no swim speed attribute");
        swim.removeModifier(RecruitSpeedSettings.SWIM);
        if (multiplier != 1.0) {
            swim.addTransientModifier(new AttributeModifier(RecruitSpeedSettings.SWIM, "swim current test", multiplier - 1.0, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }
}
