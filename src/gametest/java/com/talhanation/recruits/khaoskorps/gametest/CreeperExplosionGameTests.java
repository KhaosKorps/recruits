package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * KhaosKorps fork: the user's creeper settings
 * (DRFT-261113-Recruits_Follow_Pathfinding_Settings_And_Measurement, section 1):
 * "stops block damage on by default. stops damage also as an option off by default."
 *
 * A creeper is lit next to a stone pillar and a pig. With the defaults the pillar stands and the
 * pig is hurt; with PreventCreeperDamage on, the pig is unhurt too. The second case changes a
 * server-wide setting, so it runs in its own batch, set before and reset after.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class CreeperExplosionGameTests {

    private static final String NO_DAMAGE_BATCH = "khaoskorps_creeper_no_damage";

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void byDefaultACreeperExplosionBreaksNoBlocksButStillHurts(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        helper.runAtTickTime(70, () -> {
            scene.assertBlocksStand();
            if (scene.pig.isAlive() && scene.pig.getHealth() >= scene.pig.getMaxHealth()) {
                throw new GameTestAssertException("the creeper exploded but the pig was not hurt; by default creepers still do damage");
            }
            scene.cleanUp();
            helper.succeed();
        });
    }

    @BeforeBatch(batch = NO_DAMAGE_BATCH)
    public static void preventCreeperDamage(ServerLevel level) {
        KhaosKorpsServerConfig.PreventCreeperDamage.set(true);
    }

    @AfterBatch(batch = NO_DAMAGE_BATCH)
    public static void restoreCreeperDamage(ServerLevel level) {
        KhaosKorpsServerConfig.PreventCreeperDamage.set(false);
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 100, batch = NO_DAMAGE_BATCH)
    public static void withPreventCreeperDamageACreeperExplosionHurtsNothing(GameTestHelper helper) {
        if (!KhaosKorpsServerConfig.PreventCreeperDamage.get()) {
            throw new GameTestAssertException("precondition failed: PreventCreeperDamage is true in this batch");
        }
        Scene scene = new Scene(helper);
        helper.runAtTickTime(70, () -> {
            scene.assertBlocksStand();
            if (!scene.pig.isAlive() || scene.pig.getHealth() < scene.pig.getMaxHealth()) {
                throw new GameTestAssertException("with PreventCreeperDamage on, the pig was hurt: health "
                        + scene.pig.getHealth() + " of " + scene.pig.getMaxHealth() + ", alive " + scene.pig.isAlive());
            }
            scene.cleanUp();
            helper.succeed();
        });
    }

    /** A stone floor, a lit creeper in the middle, a stone pillar two blocks east, a pig two blocks west. */
    private static final class Scene {
        final GameTestHelper helper;
        final Creeper creeper;
        final Pig pig;

        Scene(GameTestHelper helper) {
            this.helper = helper;
            for (int x = 2; x <= 14; x++) for (int z = 1; z <= 7; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            helper.setBlock(10, 2, 4, Blocks.STONE);
            helper.setBlock(10, 3, 4, Blocks.STONE);
            pig = helper.spawn(EntityType.PIG, 6, 2, 4);
            pig.setNoAi(true); // stays in range
            creeper = helper.spawn(EntityType.CREEPER, 8, 2, 4);
            creeper.ignite(); // explodes after its 30-tick fuse
        }

        void assertBlocksStand() {
            if (creeper.isAlive()) throw new GameTestAssertException("the creeper never exploded");
            helper.assertBlockPresent(Blocks.STONE, 8, 1, 4);   // under the creeper
            helper.assertBlockPresent(Blocks.STONE, 10, 2, 4);  // the pillar
            helper.assertBlockPresent(Blocks.STONE, 10, 3, 4);
        }

        void cleanUp() {
            if (pig.isAlive()) pig.discard();
        }
    }
}
