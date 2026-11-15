package com.talhanation.recruits.entities.ai;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

public class RecruitMoveToPosGoal extends Goal {
    private final AbstractRecruitEntity recruit;
    private final double speedModifier;
    private int timeToRecalcPath;

    public RecruitMoveToPosGoal(AbstractRecruitEntity recruit, double v) {
        this.recruit = recruit;
        this.speedModifier = v;
    }

    @Override
    public void start() {
        super.start();
        this.timeToRecalcPath = 0;
    }

    public boolean canUse() {
        return recruit.getShouldMovePos() && !recruit.needsToGetFood() && !recruit.getShouldMount();
    }

    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void stop() {
        super.stop();
        com.talhanation.recruits.khaoskorps.OrderEdgeCare.clear(this.recruit); // KhaosKorps fork: EdgeMarginOnOrders
    }

    //maybe?? start(){
    public void tick() {
        BlockPos blockpos = this.recruit.getMovePos();
        if (blockpos != null) {
            if (--this.timeToRecalcPath <= 0) {
                this.timeToRecalcPath = this.recruit.getVehicle() != null ? this.adjustedTickDelay(5) : this.adjustedTickDelay(10);
                double horizontalDistance = recruit.distanceToSqr(blockpos.getX(), recruit.getY(), blockpos.getZ());
                if (horizontalDistance >= 10) {
                    com.talhanation.recruits.khaoskorps.OrderEdgeCare.apply(this.recruit); // KhaosKorps fork: EdgeMarginOnOrders
                    this.recruit.getNavigation().moveTo(blockpos.getX(), blockpos.getY(), blockpos.getZ(), this.speedModifier);
                    if (recruit.horizontalCollision || recruit.minorHorizontalCollision) {
                        this.recruit.getJumpControl().jump();
                    }
                }
                else {
                    recruit.setShouldMovePos(false);
                    recruit.clearMovePos();
                    recruit.reachedMovePos = true;
                }
            }
        }
    }
}

