package com.talhanation.recruits.mixin;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.khaoskorps.EdgeCost;
import com.talhanation.recruits.khaoskorps.EdgeProbe;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin extends NodeEvaluator {

    // KhaosKorps fork: the edge margin for a recruit-ridden horse's search while its rider follows; 0 is off. Set in prepare.
    @Unique private int khaoskorps$edgeMargin;
    @Unique private int khaoskorps$edgeMaxFall;
    @Unique private Long2ByteOpenHashMap khaoskorps$dropColumns;
    @Unique private LongOpenHashSet khaoskorps$costedNodes;

    @Inject(method = "prepare", at = @At(value = "TAIL", target = "Lnet/minecraft/world/level/pathfinder/NodeEvaluator;prepare(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;)V"), cancellable = true)
    public void horseGetStartNodeWhenRecruitsRides(PathNavigationRegion region, Mob mob, CallbackInfo info){
        this.khaoskorps$edgeMargin = 0; // KhaosKorps fork
        if(mob instanceof AbstractHorse horse && horse.isVehicle() && horse.getControllingPassenger() instanceof AbstractRecruitEntity recruit){
            this.entityHeight = Mth.floor(horse.getBbHeight() + 2.0F);

            horse.setPathfindingMalus(BlockPathTypes.WATER, 32.0F);
            horse.setPathfindingMalus(BlockPathTypes.TRAPDOOR, -1.0F);
            horse.setPathfindingMalus(BlockPathTypes.DAMAGE_FIRE, 32.0F);
            horse.setPathfindingMalus(BlockPathTypes.DAMAGE_CAUTIOUS, 32.0F);
            horse.setPathfindingMalus(BlockPathTypes.DANGER_POWDER_SNOW, -1.0F);
            horse.setPathfindingMalus(BlockPathTypes.DOOR_WOOD_CLOSED, 0.0F);
            horse.setPathfindingMalus(BlockPathTypes.FENCE, -1.0F);
            horse.setPathfindingMalus(BlockPathTypes.LAVA, -1.0F);
            horse.setPathfindingMalus(BlockPathTypes.LEAVES, -1.0F);

            // KhaosKorps fork: the edge margin, while the rider follows its owner (hold and move orders stay free, as on foot).
            FollowSettings settings = FollowSettings.current();
            if (settings.edgeMargin() && recruit.isFollowing()) {
                this.khaoskorps$edgeMargin = settings.edgeMarginBlocks();
                this.khaoskorps$edgeMaxFall = horse.getMaxFallDistance();
            }
        }
    }

    /**
     * KhaosKorps fork: each node the horse's search accepts costs more the nearer it is to a drop
     * deeper than the mounted fall distance, as in the recruit navigation; counted once per node.
     */
    @Inject(method = "findAcceptedNode", at = @At("RETURN"))
    private void khaoskorps$edgeCost(int x, int y, int z, int verticalDeltaLimit, double nodeFloorLevel, Direction direction,
                                     BlockPathTypes pathType, CallbackInfoReturnable<Node> result) {
        if (this.khaoskorps$edgeMargin <= 0) return;
        Node node = result.getReturnValue();
        if (node == null || node.costMalus < 0.0F) return;
        if (this.khaoskorps$costedNodes == null) {
            this.khaoskorps$costedNodes = new LongOpenHashSet();
            this.khaoskorps$dropColumns = new Long2ByteOpenHashMap();
            this.khaoskorps$dropColumns.defaultReturnValue((byte) -1);
        }
        if (!this.khaoskorps$costedNodes.add(BlockPos.asLong(node.x, node.y, node.z))) return;
        int distance = EdgeProbe.distanceToDangerousDrop(this.level, node.x, node.y, node.z, this.khaoskorps$edgeMaxFall,
                this.khaoskorps$edgeMargin, this.khaoskorps$dropColumns);
        node.costMalus += EdgeCost.malus(distance, this.khaoskorps$edgeMargin);
    }

    // KhaosKorps fork: nothing from one search carries into the next.
    @Inject(method = "done", at = @At("HEAD"))
    private void khaoskorps$clearEdgeCare(CallbackInfo info) {
        this.khaoskorps$edgeMargin = 0;
        if (this.khaoskorps$costedNodes != null) {
            this.khaoskorps$costedNodes.clear();
            this.khaoskorps$dropColumns.clear();
        }
    }
}
