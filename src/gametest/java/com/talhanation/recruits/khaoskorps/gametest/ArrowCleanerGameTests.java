package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * AllowArrowCleaning (on by default): an arrow nobody may pick up, such as one a mob or a recruit
 * shot, is removed once it has been stuck in the ground for over 300 ticks; the cleaner passes
 * every 100 ticks. Each test drops an arrow onto the floor, and once it has stuck, sets how long it
 * has been there, so the test needn't wait 300 ticks. Vanilla removes arrows only after 1200 ticks
 * in the ground (its own counter, not this one), so nothing else removes them within these tests.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class ArrowCleanerGameTests {

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 240)
    public static void anArrowNobodyMayPickUpIsRemovedAfter300TicksInTheGround(GameTestHelper helper) {
        Arrow arrow = droppedArrow(helper, 3, AbstractArrow.Pickup.DISALLOWED, 400);
        helper.succeedWhen(() -> {
            if (!arrow.isRemoved()) {
                throw new GameTestAssertException("the arrow is still there, " + arrow.inGroundTime + " ticks in the ground"
                        + (arrow.inGround ? "" : " (it hasn't stuck)"));
            }
        });
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 240)
    public static void anArrowAPlayerMayPickUpIsKept(GameTestHelper helper) {
        Arrow arrow = droppedArrow(helper, 6, AbstractArrow.Pickup.ALLOWED, 400);
        stillThereAt(helper, arrow, 220);
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 240)
    public static void anArrowNotYet300TicksInTheGroundIsKept(GameTestHelper helper) {
        Arrow arrow = droppedArrow(helper, 9, AbstractArrow.Pickup.DISALLOWED, 0);
        stillThereAt(helper, arrow, 220);
    }

    /**
     * An arrow dropped onto the corridor floor at x; once it has stuck, its time in the ground is
     * set to {@code inGroundTicks} (0 leaves it counting from when it stuck).
     */
    private static Arrow droppedArrow(GameTestHelper helper, int x, AbstractArrow.Pickup pickup, int inGroundTicks) {
        ground(helper, x, x, 3);
        Arrow arrow = helper.spawn(EntityType.ARROW, new Vec3(x + 0.5, 5.0, 2.5));
        arrow.pickup = pickup;
        arrow.setDeltaMovement(0.0, -1.0, 0.0);
        boolean[] set = {inGroundTicks == 0};
        helper.onEachTick(() -> {
            if (!set[0] && arrow.inGround) {
                arrow.inGroundTime = inGroundTicks;
                set[0] = true;
            }
        });
        return arrow;
    }

    private static void stillThereAt(GameTestHelper helper, Arrow arrow, int tick) {
        helper.runAtTickTime(tick, () -> {
            boolean removed = arrow.isRemoved();
            boolean stuck = arrow.inGround;
            arrow.discard();
            if (removed) throw new GameTestAssertException("the arrow was removed");
            if (!stuck) throw new GameTestAssertException("setup: the arrow never stuck in the ground");
            helper.succeed();
        });
    }
}
