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
 * owner. One per follow goal. Its state outlives the goal's quick stops and starts (a stuck recruit's
 * goal stops each time its cut-back path runs out), but not a longer pause: after
 * {@link #FORGET_AFTER_TICKS} stopped, what it noted is forgotten. Server thread only.
 */
public final class DetourFollow {

    /** How long a recruit may make no progress, with no path to its owner, before a plan is asked for. */
    public static final int STUCK_TICKS = 60;
    /** A follow goal stopped this long starts again with nothing noted. */
    public static final int FORGET_AFTER_TICKS = STUCK_TICKS;
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
    private long stoppedAt = -1;

    /**
     * Records whether a follow path reached its owner, once its search has finished. The follow goal
     * calls this with the newest path before it replaces or clears it.
     */
    public void notePath(@Nullable Path path) {
        if (path == null || (path instanceof AsyncPath async && !async.isProcessed())) return;
        this.lastPathReached = path.canReach();
    }

    /** The follow goal stopped at {@code tick}. */
    public void stopped(long tick) {
        this.stoppedAt = tick;
    }

    /** The follow goal started at {@code tick}: after a long pause, or the first time, nothing noted before counts. */
    public void started(long tick) {
        if (this.stoppedAt >= 0 && tick - this.stoppedAt <= FORGET_AFTER_TICKS) return;
        this.stillAt = null;
        this.lastPathReached = null;
        this.walking = null;
        this.checkingDirect = false;
    }

    /**
     * Where the recruit should walk next instead of straight to its owner, or null to follow the
     * owner as usual. Called each time the follow goal asks for a new path.
     *
     * A found route is taken by a recruit already walking it, or by one whose own last follow path
     * didn't reach the owner; one that can reach the owner, such as one already on the owner's side,
     * follows as usual. A recruit stuck for {@link #STUCK_TICKS} asks for a plan, and replaces a
     * found one that is no use to it: one with no waypoints (its planner found the owner in reach),
     * or one it has walked to the end.
     */
    @Nullable
    public BlockPos waypoint(Mob recruit, LivingEntity owner, FollowSettings settings) {
        if (!settings.detourSearch()) {
            this.walking = null;
            return null;
        }
        this.noteProgress(recruit);
        DetourPlan plan = DetourRoutes.plan(owner.getUUID());
        boolean found = plan != null && plan.status() == DetourPlan.Status.FOUND;
        boolean spent = found && (plan.route().isEmpty() || (plan == this.walking && this.next >= plan.route().size()));
        if (found && !spent && (plan == this.walking || Boolean.FALSE.equals(this.lastPathReached))) {
            return this.alongRoute(recruit, plan);
        }
        long still = recruit.level().getGameTime() - this.stillSince;
        if (still >= STUCK_TICKS && Boolean.FALSE.equals(this.lastPathReached) && owner.onGround()) {
            if (spent) DetourRoutes.replace(owner, recruit, settings);
            else DetourRoutes.request(owner, recruit, settings);
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
