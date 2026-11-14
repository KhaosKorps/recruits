package com.talhanation.recruits.entities.ai;

import com.talhanation.recruits.entities.AbstractRecruitEntity;

import com.talhanation.recruits.entities.CaptainEntity;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.vehicle.Boat;


/**
 * KhaosKorps fork: the start and stop distances, the start-check and repath intervals and the speed
 * come from {@link FollowSettings} each time they are used, so a saved change reaches recruits already
 * in the world. The constructor's values are no longer read; the settings' defaults are the values
 * 1.15.2 used.
 */
public class RecruitFollowOwnerGoal extends Goal {
    private final AbstractRecruitEntity recruit;
    private LivingEntity owner;
    private int timeToRecalcPath;
    private long lastCanUseCheck;

    public RecruitFollowOwnerGoal(AbstractRecruitEntity recruit, double speedModifier, float startDistance, float stopDistance) {
        this.recruit = recruit;
    }

    public boolean canUse() {
        FollowSettings settings = FollowSettings.current();
        long i = this.recruit.getCommandSenderWorld().getGameTime();
        if (i - this.lastCanUseCheck >= settings.followStartCheckTicks()) {
            this.lastCanUseCheck = i;
            LivingEntity livingentity = this.recruit.getOwner();
            LivingEntity target = this.recruit.getTarget();
            // As in 1.15.2: with a target, 50 is added to the squared start distance.
            double start = target == null ? settings.followStartDistanceSqr() : settings.followStartDistanceSqr() + 50;
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
        return !(this.recruit.distanceToSqr(this.owner) <= FollowSettings.current().followStopDistanceSqr()) && this.recruit.getShouldFollow() && !recruit.getFleeing() && recruit.getFollowState() == 1 && !recruit.needsToGetFood() && !recruit.getShouldMount() && !recruit.getShouldMovePos();
    }

    public void start() {
        this.timeToRecalcPath = 0;
        this.recruit.setIsFollowing(true);
    }

    public void stop() {
        super.stop();
        this.owner = null;
        this.recruit.setIsFollowing(false);
        this.recruit.getNavigation().stop();
    }

    public void tick() {
        if (--this.timeToRecalcPath <= 0) {
            FollowSettings settings = FollowSettings.current();
            // In game ticks: the goal runs every second tick, and adjustedTickDelay converts, as in 1.15.2.
            this.timeToRecalcPath = this.recruit.getVehicle() != null
                    ? this.adjustedTickDelay(settings.followRepathTicksRiding())
                    : this.adjustedTickDelay(settings.followRepathTicks());

            this.recruit.getLookControl().setLookAt(this.owner, 10.0F, (float)this.recruit.getMaxHeadXRot());
            this.recruit.getNavigation().moveTo(this.owner, settings.followSpeed());

            if(this.recruit instanceof CaptainEntity captain && captain.getVehicle() instanceof Boat){
                captain.setSailPos(this.owner.getOnPos());
            }
        }
    }
}
