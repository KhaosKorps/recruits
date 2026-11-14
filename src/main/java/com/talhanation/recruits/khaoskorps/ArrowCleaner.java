package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.config.RecruitsServerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AllowArrowCleaning: removes arrows nobody may pick up (those mobs and recruits shoot) once they
 * have been stuck in the ground for over 300 ticks. Each level keeps the arrows in it, recorded as
 * they join it (shot, or loaded with their chunk) and dropped as they leave it, so a pass, every 100
 * ticks per level, looks only at those arrows, never at the world. Server thread only.
 */
public final class ArrowCleaner {

    static final int PASS_TICKS = 100;
    static final int IN_GROUND_TICKS = 300;

    private static final Map<ServerLevel, Arrows> LEVELS = new HashMap<>();

    private static final class Arrows {
        final Set<AbstractArrow> inLevel = new HashSet<>();
        int ticksSincePass;
    }

    private ArrowCleaner() {
    }

    /** Lowest priority, and not for cancelled joins, so only arrows that really enter the level are kept. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof AbstractArrow arrow && event.getLevel() instanceof ServerLevel level) {
            LEVELS.computeIfAbsent(level, l -> new Arrows()).inLevel.add(arrow);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractArrow arrow && event.getLevel() instanceof ServerLevel level) {
            Arrows arrows = LEVELS.get(level);
            if (arrows != null) arrows.inLevel.remove(arrow);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        Arrows arrows = LEVELS.get(level);
        if (arrows == null || ++arrows.ticksSincePass < PASS_TICKS) return;
        arrows.ticksSincePass = 0;
        if (!RecruitsServerConfig.AllowArrowCleaning.get()) return;
        // Discarding an arrow fires its leave event, which edits the set, so they're discarded after the walk.
        List<AbstractArrow> expired = new ArrayList<>();
        for (Iterator<AbstractArrow> it = arrows.inLevel.iterator(); it.hasNext(); ) {
            AbstractArrow arrow = it.next();
            if (arrow.isRemoved()) {
                it.remove();
            } else if (arrow.pickup == AbstractArrow.Pickup.DISALLOWED && arrow.inGroundTime > IN_GROUND_TICKS) {
                expired.add(arrow);
            }
        }
        expired.forEach(AbstractArrow::discard);
    }

    /** The sets hold arrows, which hold their level, so nothing is kept past the server. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }
}
