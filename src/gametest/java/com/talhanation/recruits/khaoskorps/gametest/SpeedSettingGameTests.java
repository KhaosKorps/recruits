package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.khaoskorps.KhaosKorpsServerConfig;
import com.talhanation.recruits.khaoskorps.RecruitSpeedSettings;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * MovementSpeedMultiplier and SwimSpeedMultiplier: a modifier on every recruit, never saved, so
 * a recruit's own speed stays as it was, and a change reaches recruits already in the world.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class SpeedSettingGameTests {

    private static final String FASTER = "khaoskorps_faster_recruits";

    @BeforeBatch(batch = FASTER)
    public static void faster(ServerLevel level) {
        forkSettings(() -> {
            KhaosKorpsServerConfig.MovementSpeedMultiplier.set(2.0);
            KhaosKorpsServerConfig.SwimSpeedMultiplier.set(1.5);
        });
    }

    @AfterBatch(batch = FASTER)
    public static void afterFaster(ServerLevel level) {
        resetForkSettings();
    }

    /**
     * Movement x2 and swim x1.5: a new recruit carries both modifiers, its save holds neither, and
     * setting both back to 1 removes them from it.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 40, batch = FASTER)
    public static void theSpeedMultipliersReachRecruitsAndAreNeverSaved(GameTestHelper helper) {
        walls(helper);
        ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 3);
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), 5, 3, 2);
        recruit.setAggroState(3);

        helper.runAtTickTime(2, () -> {
            expectModifier(recruit, Attributes.MOVEMENT_SPEED, RecruitSpeedSettings.MOVEMENT, 1.0, "movement");
            expectModifier(recruit, ForgeMod.SWIM_SPEED.get(), RecruitSpeedSettings.SWIM, 0.5, "swim");
            CompoundTag saved = new CompoundTag();
            recruit.addAdditionalSaveData(saved);
            // Each saved attribute lists its saved modifiers, each with its id as a UUID.
            ListTag attributes = saved.getList("Attributes", Tag.TAG_COMPOUND);
            if (attributes.isEmpty()) throw new GameTestAssertException("setup: the recruit's save has no attributes");
            List<UUID> savedModifiers = new ArrayList<>();
            for (int i = 0; i < attributes.size(); i++) {
                ListTag modifiers = attributes.getCompound(i).getList("Modifiers", Tag.TAG_COMPOUND);
                for (int j = 0; j < modifiers.size(); j++) {
                    if (modifiers.getCompound(j).hasUUID("UUID")) savedModifiers.add(modifiers.getCompound(j).getUUID("UUID"));
                }
            }
            for (UUID id : new UUID[]{RecruitSpeedSettings.MOVEMENT, RecruitSpeedSettings.SWIM}) {
                if (savedModifiers.contains(id)) {
                    throw new GameTestAssertException("the recruit's save holds the speed setting's modifier " + id);
                }
            }
            KhaosKorpsServerConfig.MovementSpeedMultiplier.set(1.0);
            KhaosKorpsServerConfig.SwimSpeedMultiplier.set(1.0);
            KhaosKorpsServerConfig.republish();
        });
        helper.runAtTickTime(6, () -> {
            for (Attribute attribute : new Attribute[]{Attributes.MOVEMENT_SPEED, ForgeMod.SWIM_SPEED.get()}) {
                AttributeInstance instance = recruit.getAttribute(attribute);
                if (instance != null && (instance.getModifier(RecruitSpeedSettings.MOVEMENT) != null
                        || instance.getModifier(RecruitSpeedSettings.SWIM) != null)) {
                    throw new GameTestAssertException("after setting the multipliers back to 1 the recruit still has a speed setting's modifier");
                }
            }
            recruit.discard();
            helper.succeed();
        });
    }

    private static void expectModifier(AbstractRecruitEntity recruit, Attribute attribute, UUID id, double amount, String what) {
        AttributeInstance instance = recruit.getAttribute(attribute);
        if (instance == null) throw new GameTestAssertException("the recruit has no " + what + " speed attribute");
        AttributeModifier modifier = instance.getModifier(id);
        if (modifier == null || modifier.getAmount() != amount || modifier.getOperation() != AttributeModifier.Operation.MULTIPLY_TOTAL) {
            throw new GameTestAssertException("the recruit's " + what + " speed modifier is " + modifier + "; expected +" + amount + " of the total");
        }
    }
}
