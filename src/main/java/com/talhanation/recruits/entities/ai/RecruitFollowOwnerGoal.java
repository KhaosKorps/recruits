package com.talhanation.recruits.entities.ai;

import com.talhanation.recruits.entities.AbstractRecruitEntity;

import com.talhanation.recruits.entities.CaptainEntity;
import com.talhanation.recruits.khaoskorps.DetourFollow;
import com.talhanation.recruits.khaoskorps.EdgeCare;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import com.talhanation.recruits.pathfinding.AsyncPathNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.vehicle.Boat;

import javax.annotation.Nullable;


/**
 * KhaosKorps fork: the start and stop distances, the start-check and repath intervals, the speed,
 * the follow path length and the edge care come from {@link FollowSettings} each time they are used, so a saved change reaches recruits already
 * in the world. The constructor's values are no longer read; the settings' defaults are the values
 * 1.15.2 used. A recruit stuck with no path to its owner walks a planned way round ({@link DetourFollow}).
 */
public class RecruitFollowOwnerGoal extends Goal {
    private final AbstractRecruitEntity recruit;
    private LivingEntity owner;
    private int timeToRecalcPath;
    private long lastCanUseCheck;
    private final DetourFollow detour = new DetourFollow(); // KhaosKorps fork

    public RecruitFollowOwnerGoal(AbstractRecruitEntity recruit, double speedModifier, float startDistance, float stopDistance) {
        this.recruit = recruit;
    }

    public boolean canUse() {
        FollowSettings settings = FollowSettings.current(); // KhaosKorps fork
        long i = this.recruit.getCommandSenderWorld().getGameTime();
        if (i - this.lastCanUseCheck >= settings.followStartCheckTicks()) { // KhaosKorps fork: was 10L
            this.lastCanUseCheck = i;
            LivingEntity livingentity = this.recruit.getOwner();
            LivingEntity target = this.recruit.getTarget();
            // As in 1.15.2: with a target, 50 is added to the squared start distance.
            double start = target == null ? settings.followStartDistanceSqr() : settings.followStartDistanceSqr() + 50; // KhaosKorps fork
            if (livingentity == null) {
                return false;
            }
            else if (livingentity.isSpectator()) {
                return false;
            }
            else if (this.recruit.distanceToSqr(livingentity) < start) {
                return false;
            }
            else {
                this.owner = livingentity;
                return this.recruit.getShouldFollow() && !recruit.getFleeing() && recruit.getFollowState() == 1 && !recruit.needsToGetFood() && !recruit.getShouldMount() && !recruit.getShouldMovePos();
            }
        }
        return false;

    }

    public boolean canContinueToUse() {
        if (this.recruit.getNavigation().isDone()) {
            return false;
        }
        return !(this.recruit.distanceToSqr(this.owner) <= FollowSettings.current().followStopDistanceSqr()) && this.recruit.getShouldFollow() && !recruit.getFleeing() && recruit.getFollowState() == 1 && !recruit.needsToGetFood() && !recruit.getShouldMount() && !recruit.getShouldMovePos(); // KhaosKorps fork: stop distance
    }

    public void start() {
        this.timeToRecalcPath = 0;
        this.recruit.setIsFollowing(true);
    }

    public void stop() {
        super.stop();
        this.owner = null;
        this.recruit.setIsFollowing(false);
        this.clearEdgeCare(); // KhaosKorps fork
        this.detour.notePath(this.recruit.getNavigation().getPath()); // KhaosKorps fork: before the path is cleared
        this.recruit.getNavigation().stop();
    }

    /** KhaosKorps fork: the navigation this goal last gave edge care to. */
    @Nullable
    private AsyncPathNavigation caredNavigation;

    private void clearEdgeCare() {
        if (this.caredNavigation != null) this.caredNavigation.setEdgeCare(null);
        this.caredNavigation = null;
    }

    public void tick() {
        if (--this.timeToRecalcPath <= 0) {
            FollowSettings settings = FollowSettings.current(); // KhaosKorps fork
            // In game ticks: the goal runs every second tick, and adjustedTickDelay converts, as in 1.15.2.
            this.timeToRecalcPath = this.recruit.getVehicle() != null
                    ? this.adjustedTickDelay(settings.followRepathTicksRiding()) // KhaosKorps fork: was 5
                    : this.adjustedTickDelay(settings.followRepathTicks()); // KhaosKorps fork: was 10

            this.recruit.getLookControl().setLookAt(this.owner, 10.0F, (float)this.recruit.getMaxHeadXRot());
            PathNavigation navigation = this.recruit.getNavigation();
            if (navigation instanceof AsyncPathNavigation asyncNavigation) { // KhaosKorps fork: edge care, path length, detour
                if (this.caredNavigation != asyncNavigation) this.clearEdgeCare();
                asyncNavigation.setEdgeCare(EdgeCare.forFollowing(settings));
                this.caredNavigation = asyncNavigation;
                this.detour.notePath(asyncNavigation.getPath());
                BlockPos waypoint = this.detour.waypoint(this.recruit, this.owner, settings);
                if (waypoint != null) {
                    asyncNavigation.moveTo(asyncNavigation.createPath(waypoint, 1), settings.followSpeed());
                } else {
                    asyncNavigation.moveTo(this.owner, settings.followSpeed(), settings.followMaxPathLength());
                }
            } else {
                navigation.moveTo(this.owner, settings.followSpeed()); // KhaosKorps fork: speed from the settings
            }

            if(this.recruit instanceof CaptainEntity captain && captain.getVehicle() instanceof Boat){
                captain.setSailPos(this.owner.getOnPos());
            }
        }
    }
}
