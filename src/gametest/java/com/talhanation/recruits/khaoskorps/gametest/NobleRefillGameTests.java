package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.VillagerNobleEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * A noble keeps how far along its offers' refill is when it is saved and loaded again, as it is when its chunk
 * unloads or the server restarts: the long refill (one use back per offer every 12,000 ticks) and the wait
 * before its next refill on sleeping.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class NobleRefillGameTests {

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aNobleKeepsItsRefillTimersWhenSavedAndLoaded(GameTestHelper helper) {
        VillagerNobleEntity noble = helper.spawn(ModEntityTypes.VILLAGER_NOBLE.get(), new BlockPos(4, 2, 4));
        noble.restoreTimeLongTime = 11000;
        setSleepWait(noble, 900);
        CompoundTag saved = new CompoundTag();
        noble.saveWithoutId(saved);
        noble.discard();

        VillagerNobleEntity loaded = ModEntityTypes.VILLAGER_NOBLE.get().create(helper.getLevel());
        if (loaded == null) throw new GameTestAssertException("could not create a noble to load into");
        loaded.load(saved);
        int longTime = loaded.restoreTimeLongTime, sleepWait = sleepWait(loaded);
        Main.LOGGER.info("[KhaosKorps measure] noble refill after save and load: long refill {} (saved 11000), sleep wait {} (saved 900)", longTime, sleepWait);
        if (longTime != 11000 || sleepWait != 900) {
            throw new GameTestAssertException("after save and load: long refill at " + longTime + " (saved 11000), sleep wait " + sleepWait + " (saved 900)");
        }
        helper.succeed();
    }

    /** The noble's wait before its next refill on sleeping, a private field. */
    private static int sleepWait(VillagerNobleEntity noble) {
        try {
            java.lang.reflect.Field field = VillagerNobleEntity.class.getDeclaredField("restoreTimer");
            field.setAccessible(true);
            return field.getInt(noble);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("could not read the noble's restoreTimer: " + e);
        }
    }

    private static void setSleepWait(VillagerNobleEntity noble, int ticks) {
        try {
            java.lang.reflect.Field field = VillagerNobleEntity.class.getDeclaredField("restoreTimer");
            field.setAccessible(true);
            field.setInt(noble, ticks);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("could not set the noble's restoreTimer: " + e);
        }
    }
}
