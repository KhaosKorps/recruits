package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.pathfinding.AsyncPath;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A following recruit's part in a detour: noticing it is stuck, asking {@link DetourRoutes} for a
 * plan, and walking the plan's waypoints, re-checking at each waypoint for a direct path to its
 * owner. One per follow goal; its state outlives the goal's stops and starts. Server thread only.
 */
public final class DetourFollow {

    /** How long a recruit may make no progress, with no path to its owner, before a plan is asked for. */
    public static final int STUCK_TICKS = 60;
    /** Moving less than this, in blocks, isn't progress. */
    private static final double PROGRESS = 1.5;
    /** Within this many blocks of a waypoint, horizontally, the recruit has reached it. */
    private static final double ARRIVED = 2.5;

    @Nullable private Vec3 stillAt;
    private long stillSince;
    /** Whether the recruit's last finished follow path reached its owner; null until one has finished. */
    @Nullable private Boolean lastPathReached;
    @Nullable private DetourPlan walking;
    private int next;
    private boolean checkingDirect;

    /**
     * Records whether a follow path reached its owner, once its search has finished. The follow goal
     * calls this before it replaces or clears the path.
     */
    public void notePath(@Nullable Path path) {
        if (path == null || (path instanceof AsyncPath async && !async.isProcessed())) return;
        this.lastPathReached = path.canReach();
    }

    /**
     * Where the recruit should walk next instead of straight to its owner, or null to follow the
     * owner as usual. Called each time the follow goal asks for a new path.
     */
    @Nullable
    public BlockPos waypoint(Mob recruit, LivingEntity owner, FollowSettings settings) {
        if (!settings.detourSearch()) {
            this.walking = null;
            return null;
        }
        DetourPlan plan = DetourRoutes.plan(owner.getUUID());
        if (plan != null && plan.status() == DetourPlan.Status.FOUND && !plan.route().isEmpty()) {
            return this.alongRoute(recruit, plan);
        }
        this.walking = null;
        this.noteProgress(recruit);
        long still = recruit.level().getGameTime() - this.stillSince;
        if (still >= STUCK_TICKS && Boolean.FALSE.equals(this.lastPathReached) && owner.onGround()) {
            DetourRoutes.request(owner, recruit, settings);
        }
        return null;
    }

    @Nullable
    private BlockPos alongRoute(Mob recruit, DetourPlan plan) {
        List<BlockPos> route = plan.route();
        if (plan != this.walking) {
            this.walking = plan;
            this.next = 0;
            this.checkingDirect = false;
        }
        if (this.checkingDirect) {
            if (this.lastPathReached == null) return null;
            this.checkingDirect = false;
            if (this.lastPathReached) this.next = route.size();
        }
        boolean arrived = false;
        while (this.next < route.size() && horizontalDistanceSqr(recruit.position(), route.get(this.next)) <= ARRIVED * ARRIVED) {
            this.next++;
            arrived = true;
        }
        if (this.next >= route.size()) return null;
        if (arrived) {
            // At a waypoint: try the owner directly first; the answer comes with the next path.
            this.checkingDirect = true;
            this.lastPathReached = null;
            return null;
        }
        return route.get(this.next);
    }

    private void noteProgress(Mob recruit) {
        Vec3 at = recruit.position();
        if (this.stillAt == null || at.distanceToSqr(this.stillAt) > PROGRESS * PROGRESS) {
            this.stillAt = at;
            this.stillSince = recruit.level().getGameTime();
        }
    }

    private static double horizontalDistanceSqr(Vec3 at, BlockPos pos) {
        double dx = at.x - (pos.getX() + 0.5);
        double dz = at.z - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }
}
