package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/** A recruit's saved values come back after a save and a load. */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class SaveRoundTripGameTests {

    /** The upkeep timer and the ranged-fire order survive a save and a load. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void theUpkeepTimerAndTheRangedOrderSurviveASaveAndALoad(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity saved = helper.spawn(ModEntityTypes.RECRUIT.get(), 3, 3, 2);
        saved.setUpkeepTimer(123);
        saved.setShouldRanged(false);
        CompoundTag tag = new CompoundTag();
        saved.addAdditionalSaveData(tag);
        AbstractRecruitEntity loaded = helper.spawn(ModEntityTypes.RECRUIT.get(), 6, 3, 2);
        loaded.readAdditionalSaveData(tag);
        int timer = loaded.getUpkeepTimer();
        boolean ranged = loaded.getShouldRanged();
        saved.discard();
        loaded.discard();
        if (timer != 123) throw new GameTestAssertException("the upkeep timer came back as " + timer + ", not the 123 saved");
        if (ranged) throw new GameTestAssertException("the ranged-fire order came back on; it was saved off");
        helper.succeed();
    }

    /** A save written with the capitalised key some older data may hold still loads its upkeep timer. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void anUpkeepTimerSavedUnderTheCapitalisedKeyStillLoads(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity saved = helper.spawn(ModEntityTypes.RECRUIT.get(), 3, 3, 2);
        CompoundTag tag = new CompoundTag();
        saved.addAdditionalSaveData(tag);
        tag.remove("upkeepTimer");
        tag.putInt("UpkeepTimer", 77);
        AbstractRecruitEntity loaded = helper.spawn(ModEntityTypes.RECRUIT.get(), 6, 3, 2);
        loaded.readAdditionalSaveData(tag);
        int timer = loaded.getUpkeepTimer();
        saved.discard();
        loaded.discard();
        if (timer != 77) throw new GameTestAssertException("an upkeep timer saved as UpkeepTimer came back as " + timer + ", not 77");
        helper.succeed();
    }
}
