package com.talhanation.recruits.entities.ai;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.AssassinEntity;
import com.talhanation.recruits.khaoskorps.CheckThrottle;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import com.talhanation.recruits.pathfinding.AsyncPathfinderMob;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

public class FleeFire extends Goal {

    AsyncPathfinderMob entity;
    BlockPos fleePos;
    private final CheckThrottle throttle = new CheckThrottle(); // KhaosKorps fork: FireCheckTicks
    private boolean fireFound; // KhaosKorps fork: fire or lava was found at the last check

    public FleeFire(AsyncPathfinderMob entity) {
    this.entity = entity;
    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        // KhaosKorps fork: FireCheckTicks: checks run at the set interval; once fire is found, every time until none is.
        if (!this.fireFound && !this.throttle.dueEveryTicks(FollowSettings.current().fireCheckTicks(), this.entity.getId())) return;
        this.fireFound = isNearLava(); // KhaosKorps fork
        if (this.fireFound) {
            double fleeDistance = 4D;
            Vec3 vecTarget = new Vec3(fleePos.getX(), fleePos.getY(), fleePos.getZ());
            Vec3 vecRec = new Vec3(entity.getX(), entity.getY(), entity.getZ());
            Vec3 fleeDir = vecRec.subtract(vecTarget);
            fleeDir = fleeDir.normalize();
            Vec3 fleePos1 = new Vec3(vecRec.x + fleeDir.x * fleeDistance, vecRec.y + fleeDir.y * fleeDistance, vecRec.z + fleeDir.z * fleeDistance);
            entity.getNavigation().moveTo(fleePos1.x, fleePos1.y, fleePos1.z, 1.15D);
            if (entity instanceof AbstractRecruitEntity) {
                AbstractRecruitEntity recruit = (AbstractRecruitEntity) entity;
                recruit.setFleeing(true);
            }
            if (entity instanceof AssassinEntity) {
                AssassinEntity recruit = (AssassinEntity) entity;
                recruit.setFleeing(true);
            }
        }
        else
        if (entity instanceof AbstractRecruitEntity) {
            AbstractRecruitEntity recruit = (AbstractRecruitEntity) entity;
            recruit.setFleeing(false);
        }
        if (entity instanceof AssassinEntity) {
            AssassinEntity recruit = (AssassinEntity) entity;
            recruit.setFleeing(false);
        }
    }


public boolean isNearLava() {
        RandomSource random = this.entity.getRandom(); // KhaosKorps fork: was a new Random on every check
        int range = 4;

            for(int i = 0; i < 15; i++){
                BlockPos blockPos = entity.blockPosition(); // KhaosKorps fork: was new BlockPos((int) x, (int) y, (int) z), a block off at negative coordinates
                BlockPos blockpos1 = blockPos.offset(random.nextInt(range) - range/2, 3, random.nextInt(range) - range/2);
                while(this.entity.getCommandSenderWorld().isEmptyBlock(blockpos1) && blockpos1.getY() > this.entity.getCommandSenderWorld().getMinBuildHeight()){ // KhaosKorps fork: was > 1, blind below y 2
                blockpos1 = blockpos1.below();
                }
                if(this.entity.getCommandSenderWorld().getFluidState(blockpos1).is(FluidTags.LAVA) || this.entity.getCommandSenderWorld().getBlockState(blockpos1).isBurning(entity.getCommandSenderWorld(), blockpos1)){
                    this.fleePos = blockpos1;

                    return true;
                }
            }

        return false;
        }
}
