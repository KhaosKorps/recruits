package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * KhaosKorps fork: proves the GameTest harness itself works before any real test relies on it.
 * If these fail, every other GameTest result is meaningless.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class HarnessGameTests {

    public static final String EMPTY_TEMPLATE = "khaoskorps_empty_18x9x9";

    /** The source set is on the mod, the holder is scanned, and the template loads. */
    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void harnessRunsAndLoadsTheEmptyTemplate(GameTestHelper helper) {
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.AIR, 1, 1, 1);
        helper.succeed();
    }

    /** The test owner joins the level, so a recruit's getOwner() can resolve it by UUID. */
    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 40)
    public static void testOwnerJoinsTheLevelAndIsFoundByUuid(GameTestHelper helper) {
        ServerPlayer owner = GameTestSupport.owner(helper, 5.5, 2, 2.5);
        boolean found = helper.getLevel().getPlayerByUUID(owner.getUUID()) != null;
        GameTestSupport.removeOwner(helper, owner);
        if (!found) {
            helper.fail("test owner " + owner.getUUID() + " is not found by Level.getPlayerByUUID");
            return;
        }
        helper.succeed();
    }
}
