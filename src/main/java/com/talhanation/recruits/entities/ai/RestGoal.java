package com.talhanation.recruits.entities.ai;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.ICompanion;
import com.talhanation.recruits.entities.VillagerNobleEntity;
import com.talhanation.recruits.pathfinding.AsyncPath;
import com.talhanation.recruits.pathfinding.AsyncPathNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

public class RestGoal extends Goal {
    private final AbstractRecruitEntity recruit;
    private Stack<BlockPos> stackOfBeds;
    private BlockPos sleepPos;
    private long lastCanUseCheck;
    /** KhaosKorps fork: how close to a bed's centre a recruit lies down, in blocks: vanilla villagers' rule. */
    private static final double LIE_DOWN_RANGE = 2.0D;
    /** KhaosKorps fork: how many times a walk to a bed may end short of it before the bed is skipped. */
    private static final int MAX_SHORT_WALKS = 3;
    /** KhaosKorps fork: how long a recruit tries one bed before it skips it, in ticks. */
    private static final int MAX_BED_TICKS = 1200;
    /** KhaosKorps fork: how long a skipped bed is left out of the search, in ticks. */
    private static final long SKIPPED_BED_TICKS = 2400L;
    /** KhaosKorps fork: the bed being walked to, the ticks spent on it, and how many walks to it ended short. */
    private BlockPos walkingTo;
    private int bedTicks;
    private int shortWalks;
    /** KhaosKorps fork: whether the searched way to the bed being walked to was found to end beside it. */
    private boolean wayChecked;
    /**
     * KhaosKorps fork: the path asked for toward the bed being walked to. The recruits' navigation doesn't report its
     * target (getTargetPos is always null on it), so the walk to the bed is told from others by this path.
     */
    private Path bedWay;
    /** KhaosKorps fork: beds this recruit couldn't reach, and the game tick each may be tried again. */
    private final Map<BlockPos, Long> skippedBeds = new HashMap<>();
    public RestGoal(AbstractRecruitEntity recruit) {
        this.recruit = recruit;
    }

    private boolean isHealth(){
        return recruit.getHealth() < recruit.getMaxHealth();
    }

    private boolean isMorale(){
        return recruit.getMorale() < 45;
    }
    private boolean canRest(){
        if(recruit instanceof ICompanion companion && companion.isAtMission()){
            return false;
        }
        else if(recruit instanceof VillagerNobleEntity){
            return this.recruit.getCommandSenderWorld().isNight();
        }
        return (recruit.getShouldRest() || this.recruit.getCommandSenderWorld().isNight()) && recruit.getFollowState() == 0 && recruit.getTarget() == null && (isMorale() || isHealth());
    }

    @Override
    public boolean canUse() {
        long i = this.recruit.getCommandSenderWorld().getGameTime();
        if (i - this.lastCanUseCheck >= 20L) {
            this.lastCanUseCheck = i;
            return this.canRest();
        }
        return false;
    }

    public boolean canContinueToUse() {
        return this.canRest();
    }

    @Override
    public void start() {
        super.start();
        this.recruit.setFollowState(0);
        long now = this.recruit.getCommandSenderWorld().getGameTime();
        this.skippedBeds.values().removeIf(until -> until <= now);
        this.sleepPos = null;
        this.walkingTo = null;
        this.stackOfBeds = this.getListOfBeds();
    }

    @Override
    public void stop() {
        super.stop();
        this.stackOfBeds.removeAllElements();
        this.recruit.stopSleeping();
        this.recruit.clearSleepingPos();
        this.recruit.setShouldRest(false);

        if(recruit instanceof VillagerNobleEntity){
            this.recruit.setFollowState(2);
        }
    }

    @Override
    public void tick() {
        if (recruit.isSleeping()) {
            this.recruit.getNavigation().stop();
            this.recruit.heal(0.0025F);
            if(this.recruit.getMorale() < 60) this.recruit.setMoral(this.recruit.getMorale() + 0.0025F);
            return;
        }

        // KhaosKorps fork: the bed taken off the list is walked to on the same tick. Before, the list was checked for
        // emptiness first, so the last bed on it was never walked to. A vanilla bed is listed once per half, which hid
        // this for most beds; a bed listed once, alone in range, was never used.
        while (this.sleepPos == null || this.skippedBeds.containsKey(this.sleepPos) || !this.isFreeBed(this.sleepPos)) {
            if (this.stackOfBeds.isEmpty()) {
                this.sleepPos = null;
                stop();
                return;
            }
            this.sleepPos = this.stackOfBeds.pop();
        }
        this.goToBed(this.sleepPos);
    }

    /**
     * KhaosKorps fork: leaves the bed at {@code pos} out of the search for {@link #SKIPPED_BED_TICKS}, with its
     * other half, which the search lists too.
     *
     * @param pos one half of the bed
     */
    private void skipBed(BlockPos pos) {
        long until = recruit.getCommandSenderWorld().getGameTime() + SKIPPED_BED_TICKS;
        this.skippedBeds.put(pos, until);
        BlockState state = recruit.getCommandSenderWorld().getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            this.skippedBeds.put(pos.relative(net.minecraft.world.level.block.BedBlock.getConnectedDirection(state)), until);
        }
    }

    /**
     * KhaosKorps fork: the navigation's newest path: one still being searched while an older one is walked, else the
     * one walked.
     */
    private static Path latestPath(PathNavigation pathFinder) {
        return pathFinder instanceof AsyncPathNavigation async ? async.latestPath() : pathFinder.getPath();
    }

    /** KhaosKorps fork: whether {@code pos} still holds a bed no one lies in. */
    private boolean isFreeBed(BlockPos pos) {
        BlockState state = recruit.getCommandSenderWorld().getBlockState(pos);
        return state.isBed(recruit.getCommandSenderWorld(), pos, recruit) && !state.getValue(BlockStateProperties.OCCUPIED);
    }


    private void goToBed(BlockPos bedPos) {
        if (bedPos == null) {
            return;
        }
        PathNavigation pathFinder = this.recruit.getNavigation();
        // KhaosKorps fork: lie down only beside the bed, as vanilla villagers do. Before, a recruit lay down from up to
        // 5.9 blocks away, and lying down moves a sleeper onto its bed, so it went through walls and floors into it.
        if (bedPos.closerToCenterThan(recruit.position(), LIE_DOWN_RANGE)) {
            this.recruit.startSleeping(bedPos);
            this.recruit.setSleepingPos(bedPos);
            pathFinder.stop();
            return;
        }
        if (!bedPos.equals(this.walkingTo)) {
            this.walkingTo = bedPos;
            this.bedTicks = 0;
            this.shortWalks = 0;
            this.wayChecked = false;
            this.bedWay = null;
        }
        // KhaosKorps fork: the way to a bed is searched before the recruit sets off, and a bed the way doesn't bring
        // it beside (behind a wall, on a floor it can't climb to) is skipped without a step toward it. Before, the
        // recruit walked as near as it could get, the far side of a wall, and gave up only after several tries.
        Path way = latestPath(pathFinder);
        boolean towardBed = way != null && way == this.bedWay;
        if (!this.wayChecked && towardBed) {
            boolean searched = !(way instanceof AsyncPath searching && !searching.isProcessed());
            if (searched) {
                this.wayChecked = true;
                Node end = way.getEndNode();
                if (end == null || !bedPos.closerToCenterThan(net.minecraft.world.phys.Vec3.atBottomCenterOf(end.asBlockPos()), LIE_DOWN_RANGE)) {
                    this.skipBed(bedPos);
                    this.sleepPos = null;
                    this.walkingTo = null;
                    pathFinder.stop();
                    return;
                }
            }
        }
        // KhaosKorps fork: a bed the recruit can't get to is skipped, so it tries the next one instead of
        // searching for a way to this one all night.
        boolean headingHere = towardBed && !pathFinder.isDone();
        if (pathFinder.isDone() && this.bedTicks > 0 && this.bedTicks % 20 == 0) this.shortWalks++;
        if (++this.bedTicks > MAX_BED_TICKS || this.shortWalks > MAX_SHORT_WALKS) {
            this.skipBed(bedPos);
            this.sleepPos = null;
            this.walkingTo = null;
            pathFinder.stop();
            return;
        }
        // Move to the bed and stay there. A walk already headed here is left alone; otherwise (none yet, it
        // ended short, or the wander goal sent the recruit elsewhere) the walk is asked for again, once a second
        // while it keeps ending short.
        if (!headingHere && (!pathFinder.isDone() || this.bedTicks % 20 == 1)) {
            pathFinder.moveTo(bedPos.getX(), bedPos.getY(), bedPos.getZ(), 1D);
            this.bedWay = latestPath(pathFinder);
        }
        this.recruit.getLookControl().setLookAt(
                bedPos.getX(),
                bedPos.getY() + 1,
                bedPos.getZ(),
                10.0F,
                (float) this.recruit.getMaxHeadXRot()
        );
    }

    /**
     * KhaosKorps fork: the beds where an online player respawns in this recruit's level, both halves of each, so
     * no recruit takes a player's own bed.
     *
     * @return the bed halves to leave alone
     */
    private Set<BlockPos> playersRespawnBeds() {
        Set<BlockPos> beds = new HashSet<>();
        if (!(recruit.getCommandSenderWorld() instanceof ServerLevel level)) return beds;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            BlockPos pos = player.getRespawnPosition();
            if (pos == null || !level.dimension().equals(player.getRespawnDimension())) continue;
            beds.add(pos);
            BlockState state = level.getBlockState(pos);
            if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                beds.add(pos.relative(net.minecraft.world.level.block.BedBlock.getConnectedDirection(state)));
            }
        }
        return beds;
    }

    public static final IntegerProperty MULTI_BLOCK_INDEX = IntegerProperty.create("multi_block_index", 0, 2); // Fantasy Furniture beds
    private Stack<BlockPos> getListOfBeds() {
        Stack<BlockPos> stack = new Stack<>();
        int range = 25;
        Set<BlockPos> playersBeds = this.playersRespawnBeds();

        for (int x = -range; x < range; x++) {
            for (int y = -3; y < 10; y++) {
                for (int z = -range; z < range; z++) {
                    BlockPos pos = recruit.getOnPos().offset(x, y, z);
                    BlockState state = recruit.getCommandSenderWorld().getBlockState(pos);

                    if (this.skippedBeds.containsKey(pos)) continue; // KhaosKorps fork: couldn't reach it a moment ago
                    if (playersBeds.contains(pos)) continue; // KhaosKorps fork: a player respawns there
                    if (state.isBed(recruit.getCommandSenderWorld(), pos, this.recruit) && !state.getValue(BlockStateProperties.OCCUPIED)){
                        try{
                            if (state.getValue(MULTI_BLOCK_INDEX) == 1){
                                stack.push(pos);
                            }
                            else if(state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT){
                                stack.push(pos);
                            }

                        } catch (IllegalArgumentException e){
                            stack.push(pos);
                        }
                    }
                }
            }
        }
        // KhaosKorps fork: farthest first, so pop() takes the nearest. Before, the nearest came first and pop(),
        // which takes from the end, took the farthest bed in range.
        stack.sort(Comparator.comparingDouble((BlockPos pos) -> recruit.distanceToSqr(pos.getCenter())).reversed());

        return stack;
    }


}