package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/**
 * MovementSpeedMultiplier and SwimSpeedMultiplier: a modifier on every recruit's movement and
 * swim speed, multiplying the total. The modifiers are transient, so they are never saved and a
 * recruit's own speed (its base and its levelling bonuses) stays as it was. They are put on each
 * recruit as it joins a level, and on every loaded recruit at the first server tick after the
 * setting changes. Server thread only.
 */
public final class RecruitSpeedSettings {

    public static final UUID MOVEMENT = UUID.fromString("6b0f5f3e-2a8e-4b58-9c1e-4b6f0a7d1c01");
    public static final UUID SWIM = UUID.fromString("6b0f5f3e-2a8e-4b58-9c1e-4b6f0a7d1c02");

    private static double appliedMovement = 1.0;
    private static double appliedSwim = 1.0;
    private static boolean warnedNoSwimSpeed;

    private RecruitSpeedSettings() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractRecruitEntity recruit)) return;
        FollowSettings settings = FollowSettings.current();
        apply(recruit, settings.movementSpeedMultiplier(), settings.swimSpeedMultiplier());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        FollowSettings settings = FollowSettings.current();
        double movement = settings.movementSpeedMultiplier();
        double swim = settings.swimSpeedMultiplier();
        if (movement == appliedMovement && swim == appliedSwim) return;
        MinecraftServer server = event.getServer();
        int recruits = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof AbstractRecruitEntity recruit) {
                    apply(recruit, movement, swim);
                    recruits++;
                }
            }
        }
        appliedMovement = movement;
        appliedSwim = swim;
        Main.LOGGER.info("[KhaosKorps] Recruit speed multipliers now movement x{}, swim x{}, on {} loaded recruits", movement, swim, recruits);
    }

    static void apply(LivingEntity recruit, double movement, double swim) {
        set(recruit, Attributes.MOVEMENT_SPEED, MOVEMENT, "KhaosKorps movement speed setting", movement);
        set(recruit, ForgeMod.SWIM_SPEED.get(), SWIM, "KhaosKorps swim speed setting", swim);
    }

    private static void set(LivingEntity recruit, Attribute attribute, UUID id, String name, double multiplier) {
        AttributeInstance instance = recruit.getAttribute(attribute);
        if (instance == null) {
            if (!warnedNoSwimSpeed) {
                warnedNoSwimSpeed = true;
                Main.LOGGER.warn("[KhaosKorps] {} has no {} attribute, so its speed setting can't apply to it",
                        recruit.getType().getDescriptionId(), attribute.getDescriptionId());
            }
            return;
        }
        instance.removeModifier(id);
        if (multiplier != 1.0) {
            instance.addTransientModifier(new AttributeModifier(id, name, multiplier - 1.0, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }
}
