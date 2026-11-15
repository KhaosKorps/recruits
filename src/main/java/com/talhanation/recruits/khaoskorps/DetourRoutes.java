package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.config.RecruitsServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One detour plan per owner, shared by every recruit following that owner: the first one stuck
 * starts it, and all of them walk the route it finds.
 *
 * Plans advance on the server tick, a few searches at a time: with UseAsyncPathfinding on, at most
 * {@link #MAX_IN_FLIGHT} searches wait on the path worker across all plans, so its queue never
 * overflows onto the server thread; with it off, at most {@link #MAX_SYNC_PER_TICK} run per tick.
 * A plan is dropped when its owner leaves the level, when the owner has moved far from the plan's
 * goal, when its walker is gone, and on server stop. After a failure, a new plan waits until the
 * owner has moved, FailedPlanMemorySeconds have passed, or a player has placed or broken a block near the
 * ground the plan searched; a recruit stuck far from where the failed plan started plans at once. Server
 * thread only.
 */
public final class DetourRoutes {

    /** The step between waypoints, in blocks. */
    public static final int STEP = 16;
    /** The detour's limit, as a multiple of the straight-line distance to the owner. */
    public static final double BUDGET_FACTOR = 4.0;

    static final int MAX_IN_FLIGHT = 2;
    static final int MAX_SYNC_PER_TICK = 4;
    /** After a failure, a new plan may start once the owner has moved this far, in blocks. */
    static final double RETRY_AFTER_OWNER_MOVED = 8.0;
    /** How far beyond the ground between a failed plan's start and goal a player's block change still forgets it, in blocks. */
    static final int CHANGE_MARGIN = STEP;
    /** A route is dropped once the owner is this far from the goal it was planned for. */
    static final double DROP_AFTER_OWNER_MOVED = 16.0;
    /** A found route is kept this long at most. */
    static final long ROUTE_LIFETIME_TICKS = 1200;

    private static final Map<UUID, DetourPlan> BY_OWNER = new LinkedHashMap<>();

    private DetourRoutes() {
    }

    /**
     * Starts a plan for the recruits following {@code owner}, from where {@code walker} stands,
     * unless the owner has one already: planning, found, or failed and still remembered (the tick drops it
     * once that is over). A failed plan from more than three times FailedSearchSameWithin away from where
     * {@code walker} stands is dropped for the new one. Returns whether a new plan started.
     */
    public static boolean request(LivingEntity owner, Mob walker, FollowSettings settings) {
        if (!(owner.level() instanceof ServerLevel level) || walker.level() != level) return false;
        DetourPlan old = BY_OWNER.get(owner.getUUID());
        if (old != null && old.status() == DetourPlan.Status.FAILED && farFromStart(old, walker, settings)) {
            // A recruit stuck somewhere else: the failure from there says nothing about here.
            BY_OWNER.remove(owner.getUUID());
        } else if (old != null) {
            return false;
        }
        int directRange = settings.followMaxPathLength() > 0
                ? settings.followMaxPathLength()
                : (int) walker.getAttributeValue(Attributes.FOLLOW_RANGE);
        DetourPlan plan = new DetourPlan(level, walker, walker.blockPosition(), owner.blockPosition(), STEP, directRange,
                settings.edgeMarginBlocks(), BUDGET_FACTOR);
        BY_OWNER.put(owner.getUUID(), plan);
        return true;
    }

    /**
     * Drops the owner's found plan, which is no use to {@code walker} (it has no waypoints, or the
     * walker has walked it to the end and is stuck again), and starts a new one from where the
     * walker stands; the new plan records the old, so recruits partway along it keep to it while the
     * new one plans. Logged, so a planner that keeps finding the owner in reach while the walker can't
     * reach it shows: DetourFollow allows a replacement only after 60 ticks stuck since the last plan
     * ended, so that case replans about every planning time plus 3 seconds while the walker is stuck.
     */
    public static boolean replace(LivingEntity owner, Mob walker, FollowSettings settings) {
        DetourPlan old = BY_OWNER.get(owner.getUUID());
        if (old == null || old.status() != DetourPlan.Status.FOUND) return request(owner, walker, settings);
        BY_OWNER.remove(owner.getUUID());
        Main.LOGGER.info("[KhaosKorps] Detour for the recruits following {}: a recruit is stuck after a route of {} waypoints; planning again",
                owner.getScoreboardName(), old.route().size());
        boolean started = request(owner, walker, settings);
        DetourPlan replacement = BY_OWNER.get(owner.getUUID());
        if (started && replacement != null) replacement.replacing(old);
        return started;
    }

    /** The plan for the recruits following this owner, if any. */
    @Nullable
    public static DetourPlan plan(UUID owner) {
        return BY_OWNER.get(owner);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || BY_OWNER.isEmpty()) return;
        int inFlight = 0;
        for (DetourPlan plan : BY_OWNER.values()) inFlight += plan.pendingSearches();
        int allowance = RecruitsServerConfig.UseAsyncPathfinding.get() ? MAX_IN_FLIGHT - inFlight : MAX_SYNC_PER_TICK;

        Iterator<Map.Entry<UUID, DetourPlan>> entries = BY_OWNER.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, DetourPlan> entry = entries.next();
            DetourPlan plan = entry.getValue();
            ServerLevel level = (ServerLevel) plan.walker().level();
            Player owner = level.getPlayerByUUID(entry.getKey());
            if (owner == null) {
                entries.remove();
                continue;
            }
            double ownerMoved = horizontalDistance(owner.blockPosition(), plan.goal());
            switch (plan.status()) {
                case PLANNING -> {
                    if (!plan.walker().isAlive() || ownerMoved > DROP_AFTER_OWNER_MOVED) {
                        entries.remove();
                        continue;
                    }
                    if (owner.onGround()) plan.setGoal(owner.blockPosition());
                    allowance -= plan.advance(Math.max(0, allowance));
                    if (plan.status() != DetourPlan.Status.PLANNING) {
                        // The start and the recruit that asked tell a failing plan's place in the world from the log alone.
                        Main.LOGGER.info("[KhaosKorps] Detour for the recruits following {}: {}, {} waypoints, {} searches, {} ticks, from {} (asked by recruit {}) toward {}",
                                owner.getScoreboardName(), plan.status(), plan.route().size(), plan.searches(), plan.ticks(),
                                plan.start().toShortString(), plan.walker().getStringUUID(), plan.goal().toShortString());
                    }
                }
                case FOUND -> {
                    if (ownerMoved > DROP_AFTER_OWNER_MOVED || ticksSinceEnd(plan) > ROUTE_LIFETIME_TICKS) entries.remove();
                }
                case FAILED -> {
                    if (ownerMoved >= RETRY_AFTER_OWNER_MOVED || ticksSinceEnd(plan) > FollowSettings.current().failedPlanMemoryTicks()
                            || FailedSearches.changedSince(plan.start(), plan.goal(), CHANGE_MARGIN, plan.endedTick())) entries.remove();
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BY_OWNER.clear();
    }

    /** Whether {@code walker} stands more than three times FailedSearchSameWithin from where {@code plan} started. */
    private static boolean farFromStart(DetourPlan plan, Mob walker, FollowSettings settings) {
        double far = 3 * settings.failedSearchSameWithin();
        return walker.blockPosition().distSqr(plan.start()) > far * far;
    }

    private static long ticksSinceEnd(DetourPlan plan) {
        return plan.walker().level().getGameTime() - plan.endedTick();
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
