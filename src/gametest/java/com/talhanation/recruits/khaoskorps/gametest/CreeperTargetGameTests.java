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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * CreepersTargetRecruits: off by default, as in 1.15.2, creepers leave recruits alone; on, a creeper
 * that appears hunts them as other monsters do. A creeper four blocks from a passive recruit.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class CreeperTargetGameTests {

    private static final String HUNT = "khaoskorps_creepers_hunt_recruits";

    @BeforeBatch(batch = HUNT)
    public static void creepersHunt(ServerLevel level) {
        forkSettings(() -> KhaosKorpsServerConfig.CreepersTargetRecruits.set(true));
    }

    @AfterBatch(batch = HUNT)
    public static void afterCreepersHunt(ServerLevel level) {
        resetForkSettings();
    }

    /** With the setting on, the creeper targets the recruit within three seconds. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 60, batch = HUNT)
    public static void withTheSettingOnACreeperTargetsARecruit(GameTestHelper helper) {
        Scene scene = scene(helper);
        helper.succeedWhen(() -> {
            if (scene.creeper.getTarget() != scene.recruit) {
                throw new GameTestAssertException("the creeper hasn't targeted the recruit four blocks away; its target is " + scene.creeper.getTarget());
            }
            scene.cleanUp();
        });
    }

    /** By default, the creeper never targets the recruit. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 80)
    public static void byDefaultACreeperLeavesRecruitsAlone(GameTestHelper helper) {
        Scene scene = scene(helper);
        helper.onEachTick(() -> {
            if (scene.creeper.getTarget() == scene.recruit) {
                scene.cleanUp();
                throw new GameTestAssertException("by default the creeper targeted the recruit");
            }
        });
        helper.runAtTickTime(60, () -> {
            scene.cleanUp();
            helper.succeed();
        });
    }

    private record Scene(Creeper creeper, AbstractRecruitEntity recruit) {
        void cleanUp() {
            this.creeper.discard();
            this.recruit.discard();
        }
    }

    private static Scene scene(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        daytime(helper);
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), 3, 3, 2);
        recruit.setAggroState(3);
        Creeper creeper = helper.spawn(EntityType.CREEPER, 7, 3, 2);
        return new Scene(creeper, recruit);
    }
}
