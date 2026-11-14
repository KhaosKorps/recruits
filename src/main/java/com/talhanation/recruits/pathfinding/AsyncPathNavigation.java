package com.talhanation.recruits.pathfinding;

import com.talhanation.recruits.khaoskorps.PathLog;
import com.talhanation.recruits.khaoskorps.PathLogRecord;
import com.google.common.collect.ImmutableSet;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.khaoskorps.EdgeCare;
import com.talhanation.recruits.khaoskorps.EdgeProbe;
import com.talhanation.recruits.khaoskorps.FollowSettings;
import com.talhanation.recruits.khaoskorps.PathEdgeTrim;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.*;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AsyncPathNavigation extends PathNavigation {
    @Nullable
    private BlockPos targetPos;
    private int reachRange;
    private final PathFinder pathFinder;
    private boolean isStuck;
    // KhaosKorps fork: the budget the path finder was built with, so each search can scale it (SearchNodesPerBlock).
    private final int baseSearchBudget;
    // KhaosKorps fork: the search range for the moveTo call in progress, set only by moveTo(Entity, double, float); 0 = the follow range.
    private float searchRange;
    // KhaosKorps fork: the edge care every path gets while it is set, and the path it was last applied to.
    @Nullable private EdgeCare edgeCare;
    @Nullable private Path edgeCareAppliedTo;
    // KhaosKorps fork: KeepWalkingWhileRepathing: a new path still being searched, waiting beside the one walked.
    @Nullable private Path pendingPath;
    // KhaosKorps fork: whether the next path point is beside a dangerous drop, worked out once per point.
    @Nullable private Path carefulPath;
    private int carefulNodeIndex = -1;
    private boolean carefulNode;

    public AsyncPathNavigation(PathfinderMob p_26515_, Level p_26516_) {
        super(p_26515_, p_26516_);
        int i = Mth.floor(p_26515_.getAttributeValue(Attributes.FOLLOW_RANGE) * 16.0D);
        this.baseSearchBudget = i;
        this.pathFinder = this.createPathFinder(i);
    }

    protected @NotNull PathFinder createPathFinder(int p_26531_) {
        if (RecruitsServerConfig.UseAsyncPathfinding.get()) {
            return new AsyncPathfinder(this.nodeEvaluator, p_26531_, this.level);
        }
        return new PathFinder(this.nodeEvaluator, p_26531_);
    }

    @Nullable
    public final Path createPathAsync(double p_26525_, double p_26526_, double p_26527_, int p_26528_) {
        return this.createPath(new BlockPos((int) p_26525_, (int) p_26526_, (int) p_26527_), p_26528_);
    }

    @Nullable
    public Path createPath(Stream<BlockPos> p_26557_, int p_26558_) {
        return this.createPath(p_26557_.collect(Collectors.toSet()), 8, false, p_26558_);
    }

    
    @Nullable
    public Path createPath(Set<BlockPos> p_26549_, int p_26550_) {
        return this.createPath(p_26549_, 8, false, p_26550_);
    }

    
    @Nullable
    public Path createPath(BlockPos p_26546_, int p_26547_) {
        return this.createPath(ImmutableSet.of(p_26546_), 8, false, p_26547_);
    }

    
    @Nullable
    public Path createPath(BlockPos p_148219_, int p_148220_, int p_148221_) {
        return this.createPath(ImmutableSet.of(p_148219_), 8, false, p_148220_, (float) p_148221_);
    }

    
    @Nullable
    public Path createPath(Entity p_26534_, int p_26535_) {
        return this.createPath(ImmutableSet.of(p_26534_.blockPosition()), 16, true, p_26535_);
    }


    @Nullable
    protected Path createPath(Set<BlockPos> p_26552_, int p_26553_, boolean p_26554_, int p_26555_) {
        float range = this.searchRange > 0 ? this.searchRange : (float) this.mob.getAttributeValue(Attributes.FOLLOW_RANGE); // KhaosKorps fork: was always the follow range
        return this.createPath(p_26552_, p_26553_, p_26554_, p_26555_, range);
    }


    @Nullable
    protected Path createPath(Set<BlockPos> p_148223_, int p_148224_, boolean p_148225_, int p_148226_, float p_148227_) {
        if(Thread.currentThread().getThreadGroup() != SidedThreadGroups.SERVER) return null;
        this.takePendingIfFinished(); // KhaosKorps fork: so the checks below see the newest path
        char purpose = PathLog.takePurpose(this); // KhaosKorps fork: PathLog, what the caller asked this search for
        if (p_148223_.isEmpty()) {
            return null;
        } else if (this.mob.getY() < (double) this.level.getMinBuildHeight()) {
            return null;
        } else if (!this.canUpdatePath()) {
            return null;
        } else if (this.pendingPath instanceof AsyncPath pending && !pending.isProcessed() && pending.hasSameProcessingPositions(p_148223_)) { // KhaosKorps fork: the waiting path is already searching these positions
            PathLog.reused(this.mob); // KhaosKorps fork
            return this.pendingPath;
        } else if (this.path != null && (path instanceof AsyncPath asyncPath && !asyncPath.isProcessed() && asyncPath.hasSameProcessingPositions(p_148223_))) { // petal start - catch early if it's still processing these positions let it keep processing
            PathLog.reused(this.mob); // KhaosKorps fork
            return this.path;
        } else if (this.path != null && !this.path.isDone() && p_148223_.contains(this.targetPos)) {
            return this.path;
        } else {
            BlockPos blockpos = p_148225_ ? this.mob.blockPosition().above() : this.mob.blockPosition();
            int i = (int)(p_148227_ + (float)p_148224_);
            PathNavigationRegion pathnavigationregion = new PathNavigationRegion(this.level, blockpos.offset(-i, -i, -i), blockpos.offset(i, i, i));
            float maxVisitedNodesMultiplier = FollowSettings.searchBudgetMultiplier( // KhaosKorps fork: was 1.0F
                    FollowSettings.current().searchNodesPerBlock(), p_148227_, this.baseSearchBudget);
            PathLogRecord logged = PathLog.request(this.mob, purpose, p_148223_, (int) (this.baseSearchBudget * maxVisitedNodesMultiplier)); // KhaosKorps fork
            Path path = this.pathFinder.findPath(pathnavigationregion, this.mob, p_148223_, p_148227_, p_148226_, maxVisitedNodesMultiplier);
            PathLog.requestDone(logged); // KhaosKorps fork
            if (!(path instanceof AsyncPath)) PathLog.ranInline(logged, path); // KhaosKorps fork: searched here, or no start found

            if (!p_148223_.isEmpty())
                this.targetPos = p_148223_.iterator().next(); // petal - assign early a target position. most calls will only have 1 position

            // petal start - async
            if(RecruitsServerConfig.UseAsyncPathfinding.get()) {
                AsyncPathProcessor.awaitProcessing(path, this.level.getServer(), processedPath -> {
                    PathLog.arrived(logged, processedPath, processedPath != this.path && processedPath != this.pendingPath, this.level.getGameTime()); // KhaosKorps fork
                    if (processedPath != this.path && processedPath != this.pendingPath){ // KhaosKorps fork: or the path waiting to take over
                        return; // petal - check that processing didn't take so long that we calculated a new path
                    }

                    if (processedPath != null) {
                        this.targetPos = processedPath.getTarget();
                        this.reachRange = p_148226_;
                        this.resetStuckTimeout();
                    }
                });
            } else if (logged != null && path instanceof AsyncPath) { // KhaosKorps fork: PathLog, a navigation built for async searches after the setting was turned off
                AsyncPathProcessor.awaitProcessing(path, this.level.getServer(), processedPath -> PathLog.arrived(logged, processedPath, false, this.level.getGameTime()));
            }
            PathLog.handedBack(logged); // KhaosKorps fork
            return path;
        }
    }

    @Override
    public boolean moveTo(double p_26520_, double p_26521_, double p_26522_, double p_26523_) {
        Path path = this.createPath(new BlockPos((int) p_26520_, (int) p_26521_, (int) p_26522_), 1);
        return this.moveTo(path, p_26523_);
    }

    /**
     * KhaosKorps fork: {@link #moveTo(Entity, double)} with its own search range in blocks, which caps
     * how far the search reaches and how long the path may be. 0 uses the mob's follow range, as
     * 1.15.2 always did. The follow goal passes FollowMaxPathLength here. Server thread only, as moveTo is.
     */
    public boolean moveTo(@NotNull Entity target, double speed, float range) {
        this.searchRange = range;
        try {
            return this.moveTo(target, speed);
        } finally {
            this.searchRange = 0;
        }
    }

    /**
     * KhaosKorps fork: KeepWalkingWhileRepathing: a waiting path takes over once its search has
     * finished, trimmed as moveTo trims; or, still unfinished, once the walked path has run out, which
     * leaves the navigation as it would be without the setting, holding a search not yet done. Called
     * from tick (before and after walking), createPath and moveTo. Returns whether it took over.
     */
    private boolean takePendingIfFinished() {
        if (this.pendingPath == null) return false;
        boolean finished = this.pendingPath instanceof AsyncPath pending && pending.isProcessed();
        if (!finished && this.path != null && !this.path.isDone()) return false;
        this.path = this.pendingPath;
        this.pendingPath = null;
        if (finished) this.trimPath();
        return true;
    }

    /** KhaosKorps fork: the newest path asked for: the one still being searched while the old one is walked, else the walked one. */
    @Nullable
    public Path latestPath() {
        return this.pendingPath != null ? this.pendingPath : this.path;
    }

    /** KhaosKorps fork: whether a new path is being searched while the old one is walked. */
    public boolean hasPendingPath() {
        return this.pendingPath != null;
    }

    /** KhaosKorps fork: whether a finished path with points left is being walked. */
    private boolean isWalkingAFinishedPath() {
        return this.path != null && !(this.path instanceof AsyncPath walked && !walked.isProcessed()) && !this.path.isDone();
    }

    /** KhaosKorps fork: stopping also drops a path waiting to take over. */
    @Override
    public void stop() {
        super.stop();
        this.pendingPath = null;
    }

    /**
     * KhaosKorps fork: the edge care applied to every path this navigation walks, until it is set to
     * null. The follow goal sets it while it runs, so a path rebuilt by the navigation itself
     * (recomputePath, after a block changes near the path) gets it too.
     */
    public void setEdgeCare(@Nullable EdgeCare care) {
        this.edgeCare = care;
    }

    /** KhaosKorps fork: whether a follow goal has put edge care on this navigation. Server thread only. */
    public boolean hasEdgeCare() {
        return this.edgeCare != null;
    }

    /**
     * KhaosKorps fork: applies the edge care the first time a path is seen finished, which covers
     * sync paths and async ones alike (PathEdgeTrim). A path that can't reach its target past a
     * dangerous drop is cut back, so the mob waits short of the rim; and a path's end near its target
     * is kept back from a drop, so the mob doesn't stop at the rim its owner stands at.
     */
    private void applyEdgeCare() {
        if (this.path == null || this.path == this.edgeCareAppliedTo || this.edgeCare == null) return;
        this.edgeCareAppliedTo = this.path;
        int maxFall = this.mob.getMaxFallDistance();
        int keep = this.path.getNodeCount();
        if (this.edgeCare.trimUnreachable()) {
            keep = PathEdgeTrim.pointsToKeep(this.level, this.path, maxFall, this.edgeCare.keepBackBlocks());
            keep = Math.min(keep, PathEdgeTrim.pointsToKeepForDepth(this.path, this.edgeCare.depthWeight()));
        }
        if (keep == this.path.getNodeCount() && this.edgeCare.nearOwnerBlocks() > 0) {
            keep = PathEdgeTrim.pointsToKeepNearTarget(this.level, this.path, maxFall,
                    this.edgeCare.keepBackBlocks(), this.edgeCare.nearOwnerBlocks());
        }
        if (keep < this.path.getNodeCount()) this.path.truncateNodes(keep);
    }

    // Paper start - optimise pathfinding
    private long pathfindFailures = 0;
    private long lastFailure = 0;
    // Paper end

    @Override
    public boolean moveTo(@NotNull Entity p_26532_, double p_26533_) {
        if(Thread.currentThread().getThreadGroup() != SidedThreadGroups.SERVER) return false;
        long currentTick = this.level.getGameTime();
        // Paper start - Pathfinding optimizations
        if (this.pathfindFailures > 10 && this.path == null && currentTick < this.lastFailure + 40) {
            PathLog.gaveUp(this.mob, this); // KhaosKorps fork
            return false;
        }
        // Paper end
        Path path = this.createPath(p_26532_, 1);
        // Paper start - Pathfinding optimizations
        if (path != null && this.moveTo(path, p_26533_)) {
            this.lastFailure = 0;
            this.pathfindFailures = 0;
            return true;
        } else {
            this.pathfindFailures++;
            this.lastFailure = currentTick;
            return false;
        }
        // Paper end
    }

    @Override
    public boolean moveTo(@Nullable Path p_26537_, double p_26538_) {
        if(Thread.currentThread().getThreadGroup() != SidedThreadGroups.SERVER) return false;
        if (p_26537_ == null) {
            this.path = null;
            this.pendingPath = null; // KhaosKorps fork
            return false;
        }
        Path walkedBefore = this.path; // KhaosKorps fork: the walked path handed back doesn't undo a search that has just finished
        if (this.takePendingIfFinished() && p_26537_ == walkedBefore) p_26537_ = this.path; // KhaosKorps fork
        // KhaosKorps fork: KeepWalkingWhileRepathing: while the new path is still searched, keep walking the old one.
        if (p_26537_ instanceof AsyncPath incoming && !incoming.isProcessed() && p_26537_ != this.path
                && FollowSettings.current().keepWalkingWhileRepathing() && this.isWalkingAFinishedPath()) {
            this.pendingPath = p_26537_;
            this.speedModifier = p_26538_;
            return true;
        }
        if (p_26537_ != this.path) this.pendingPath = null; // KhaosKorps fork: a different path drops a waiting one; the walked one handed back keeps it
        if (!p_26537_.sameAs(this.path)) {
            this.path = p_26537_;
        }

        if (this.isDone()) {
            return false;
        }

        boolean isProcessed = (this.path instanceof AsyncPath asyncPath && asyncPath.isProcessed()) || (!(this.path instanceof AsyncPath asyncPath));

        if (isProcessed) {
            this.trimPath();
            if(this.path.getNodeCount() <= 0) return false;
        }

        this.speedModifier = p_26538_;
        Vec3 vec3 = this.getTempMobPos();
        this.lastStuckCheck = this.tick;
        this.lastStuckCheckPos = vec3;

        return true;
    }


    protected void trimPath() {
        if (this.path == null) return;

        for (int i = 0; i < this.path.getNodeCount(); ++i) {
            Node node = this.path.getNode(i);
            Node node1 = i + 1 < this.path.getNodeCount() ? this.path.getNode(i + 1) : null;
            BlockState blockstate = this.level.getBlockState(new BlockPos(node.x, node.y, node.z));
            if (blockstate.is(BlockTags.CAULDRONS)) {
                this.path.replaceNode(i, node.cloneAndMove(node.x, node.y + 1, node.z));
                if (node1 != null && node.y >= node1.y) {
                    this.path.replaceNode(i + 1, node.cloneAndMove(node1.x, node.y + 1, node1.z));
                }
            }
        }
    }

    @Override
    public void tick() {
        ++this.tick;
        this.takePendingIfFinished(); // KhaosKorps fork: KeepWalkingWhileRepathing
        if (this.hasDelayedRecomputation) {
            this.recomputePath();
        }

        if (this.path instanceof AsyncPath asyncPath && !asyncPath.isProcessed()) {
            PathLog.stalled(this.mob); // KhaosKorps fork: no walk input this tick, waiting for the search
            return;
        }
        this.applyEdgeCare(); // KhaosKorps fork

        if (!this.isDone()) {
            if (this.canUpdatePath()) {
                this.followThePath();
            } else if (this.path != null && !this.path.isDone()) {
                Vec3 vec3 = this.getTempMobPos();
                Vec3 vec31 = this.path.getNextEntityPos(this.mob);
                if (vec3.y > vec31.y && !this.mob.onGround() && Mth.floor(vec3.x) == Mth.floor(vec31.x) && Mth.floor(vec3.z) == Mth.floor(vec31.z)) {
                    this.path.advance();
                }
            }

            DebugPackets.sendPathFindingPacket(this.level, this.mob, this.path, this.maxDistanceToWaypoint);
            if (!this.isDone()) {
                Vec3 vec32 = this.path.getNextEntityPos(this.mob);
                this.mob.getMoveControl().setWantedPosition(vec32.x, this.getGroundY(vec32), vec32.z, this.speedModifier * this.edgeSpeedFactor()); // KhaosKorps fork: careful steps
            }
        }
        this.takePendingIfFinished(); // KhaosKorps fork: if the walked path ran out this tick, a waiting search takes its place now
    }

    /**
     * KhaosKorps fork: careful steps. The fraction of its speed the mob keeps while walking toward its
     * next path point: the edge care's careful speed when that point is right beside a drop deeper than
     * the fall distance, so momentum can't carry the mob over; otherwise 1. Each point is checked once.
     */
    private double edgeSpeedFactor() {
        if (this.edgeCare == null || this.edgeCare.carefulSpeed() >= 1.0 || this.path == null) return 1.0;
        int index = this.path.getNextNodeIndex();
        if (this.path != this.carefulPath || index != this.carefulNodeIndex) {
            Node next = this.path.getNode(index);
            this.carefulPath = this.path;
            this.carefulNodeIndex = index;
            this.carefulNode = EdgeProbe.distanceToDangerousDrop(this.level, next.x, next.y, next.z,
                    this.mob.getMaxFallDistance(), 1, null) == 1;
        }
        return this.carefulNode ? this.edgeCare.carefulSpeed() : 1.0;
    }

    protected void followThePath() {
        if ((this.path instanceof AsyncPath asyncPath && !asyncPath.isProcessed())) return;
        Vec3 vec3 = this.getTempMobPos();
        this.maxDistanceToWaypoint = this.mob.getBbWidth() > 0.75F ? this.mob.getBbWidth() / 2.0F : 0.75F - this.mob.getBbWidth() / 2.0F;
        Vec3i vec3i = this.path.getNextNodePos();
        double d0 = Math.abs(this.mob.getX() - ((double)vec3i.getX() + (this.mob.getBbWidth() + 1) / 2D)); //Forge: Fix MC-94054
        double d1 = Math.abs(this.mob.getY() - (double)vec3i.getY());
        double d2 = Math.abs(this.mob.getZ() - ((double)vec3i.getZ() + (this.mob.getBbWidth() + 1) / 2D)); //Forge: Fix MC-94054
        boolean flag = d0 <= (double)this.maxDistanceToWaypoint && d2 <= (double)this.maxDistanceToWaypoint && d1 < 1.0D; //Forge: Fix MC-94054
        if (flag || this.canCutCorner(this.path.getNextNode().type) && this.shouldTargetNextNodeInDirection(vec3)) {
            this.path.advance();
        }

        this.doStuckDetection(vec3);
    }

    private boolean shouldTargetNextNodeInDirection(Vec3 p_26560_) {
        if (this.path.getNextNodeIndex() + 1 >= this.path.getNodeCount()) {
            return false;
        } else {
            Vec3 vec3 = Vec3.atBottomCenterOf(this.path.getNextNodePos());
            if (!p_26560_.closerThan(vec3, 2.0D)) {
                return false;
            } else if (this.canMoveDirectly(p_26560_, this.path.getNextEntityPos(this.mob))) {
                return true;
            } else {
                Vec3 vec31 = Vec3.atBottomCenterOf(this.path.getNodePos(this.path.getNextNodeIndex() + 1));
                Vec3 vec32 = vec31.subtract(vec3);
                Vec3 vec33 = p_26560_.subtract(vec3);
                return vec32.dot(vec33) > 0.0D;
            }
        }
    }

    public void recomputePath() {
        if (this.level.getGameTime() - this.timeLastRecompute > 20L) {
            if (this.targetPos != null) {
                this.path = null;
                this.path = this.createPath(this.targetPos, this.reachRange);
                this.timeLastRecompute = this.level.getGameTime();
                this.hasDelayedRecomputation = false;
            }
        } else {
            this.hasDelayedRecomputation = true;
        }
    }

    private void resetStuckTimeout() {
        this.timeoutCachedNode = Vec3i.ZERO;
        this.timeoutTimer = 0L;
        this.timeoutLimit = 0.0D;
        this.isStuck = false;
    }

    @Override
    public boolean isStuck() {
        return this.isStuck;
    }
}
