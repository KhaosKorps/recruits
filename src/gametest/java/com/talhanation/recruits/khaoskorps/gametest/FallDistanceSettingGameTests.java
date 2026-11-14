package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * The fall-distance settings: MaxFallDistance wins over each recruit's own saved value, so it
 * reaches recruits already in the world and survives a move order, while the save still writes the
 * recruit's own value, so an older jar reads back what it wrote. A horse ridden by a recruit uses
 * MountedMaxFallDistance. These change server-wide settings, so they run in their own batch.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class FallDistanceSettingGameTests {

    private static final String BATCH = "khaoskorps_fall_distance_3";

    @BeforeBatch(batch = BATCH)
    public static void raiseFallDistances(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.MaxFallDistance.set(3);
            KhaosKorpsServerConfig.MountedMaxFallDistance.set(4);
        });
    }

    @AfterBatch(batch = BATCH)
    public static void restoreFallDistances(ServerLevel level) {
        resetForkSettings();
    }

    /** With the setting at 3, a recruit follows its owner down a two-block drop it refuses at 1. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 300, batch = BATCH)
    public static void fallDistanceSettingLetsARecruitFollowDownATwoBlockDrop(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, 6, 5);
        ground(helper, 7, CORRIDOR_X_MAX, 3);
        daytime(helper);
        ServerPlayer owner = owner(helper, 12.5, 3, 2.5);
        pin(helper, owner, 12.5, 3, 2.5);
        AbstractRecruitEntity recruit = followingRecruit(helper, owner, 2, 5, 2);
        helper.succeedWhen(() -> {
            double d = recruit.distanceToSqr(owner);
            if (d > 9.0) throw new GameTestAssertException("at fall distance setting 3 the recruit is still "
                    + String.format("%.2f", Math.sqrt(d)) + " blocks away, at " + describe(helper, recruit));
            removeOwner(helper, owner);
        });
    }

    /** The setting wins over a recruit's own value, but the save still holds the recruit's own value. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void theSettingWinsButTheSaveKeepsTheRecruitsOwnFallDistance(GameTestHelper helper) {
        AbstractRecruitEntity recruit = helper.spawn(com.talhanation.recruits.init.ModEntityTypes.RECRUIT.get(), 5, 2, 2);
        recruit.setMaxFallDistance(1);
        CompoundTag saved = new CompoundTag();
        recruit.addAdditionalSaveData(saved);
        int used = recruit.getMaxFallDistance();
        recruit.discard();
        if (used != 3) throw new GameTestAssertException("the recruit uses fall distance " + used + ", not the setting's 3");
        if (saved.getInt("MaxFallDistance") != 1) {
            throw new GameTestAssertException("the save holds " + saved.getInt("MaxFallDistance")
                    + "; it must keep the recruit's own 1, so an older jar reads back what it wrote");
        }
        helper.succeed();
    }

    /** A positional move order resets the recruit's own value to 1; the setting still wins after it. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void aMoveOrderDoesNotResetTheFallDistanceSetting(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 2);
        AbstractRecruitEntity recruit = helper.spawn(com.talhanation.recruits.init.ModEntityTypes.RECRUIT.get(), 3, 2, 2);
        var to = helper.absoluteVec(new net.minecraft.world.phys.Vec3(10.5, 2, 2.5));
        recruit.getNavigation().moveTo(to.x, to.y, to.z, 1.0D); // RecruitPathNavigation.moveTo sets the recruit's own value to 1
        int used = recruit.getMaxFallDistance();
        recruit.discard();
        if (used != 3) throw new GameTestAssertException("after a move order the recruit uses fall distance " + used + ", not 3");
        helper.succeed();
    }

    /** A horse ridden by a recruit uses MountedMaxFallDistance, through MobMixin. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void aHorseRiddenByARecruitUsesTheMountedFallDistance(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 2);
        Horse horse = helper.spawn(EntityType.HORSE, 5, 2, 2);
        AbstractRecruitEntity rider = helper.spawn(com.talhanation.recruits.init.ModEntityTypes.RECRUIT.get(), 5, 2, 2);
        rider.startRiding(horse, true);
        int used = horse.getMaxFallDistance();
        rider.discard();
        horse.discard();
        if (used != 4) throw new GameTestAssertException("the ridden horse uses fall distance " + used + ", not the mounted setting's 4");
        helper.succeed();
    }
}
