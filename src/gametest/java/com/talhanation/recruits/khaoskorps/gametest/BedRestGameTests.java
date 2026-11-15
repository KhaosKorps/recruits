package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/**
 * A recruit resting at night walks to a free bed and lies down beside it: the nearest bed it can reach,
 * never one behind a wall, and a lone bed too.
 *
 * Each scene is a stone floor (recruits stand at y 2) inside a barrier ring at the template's edge. A
 * recruit rests at night when it wanders (follow state 0), has no target and is hurt, so each one starts
 * at half health. Each test runs in its own batch, set to night before it and back to day after it:
 * level time is shared, and a bed in a neighbouring test's scene is within a recruit's bed search.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class BedRestGameTests {

    private static final String EMPTY = HarnessGameTests.EMPTY_TEMPLATE;
    private static final String NIGHT_WALLED = "khaoskorps_night_walled_bed";
    private static final String NIGHT_NEAREST = "khaoskorps_night_nearest_bed";
    private static final String NIGHT_LONE = "khaoskorps_night_lone_bed";
    private static final String NIGHT_FLOOR = "khaoskorps_night_upper_floor_bed";
    private static final String NIGHT_RESPAWN = "khaoskorps_night_respawn_bed";
    private static final long NIGHT = 18000L, DAY = 1000L;
    /** How far from a bed's centre a recruit may last stand awake and still have walked to it, in blocks. */
    private static final double WALKED_TO = 2.5;

    @BeforeBatch(batch = NIGHT_WALLED)
    public static void nightWalled(ServerLevel level) {
        level.setDayTime(NIGHT);
    }

    @AfterBatch(batch = NIGHT_WALLED)
    public static void dayAfterWalled(ServerLevel level) {
        level.setDayTime(DAY);
    }

    @BeforeBatch(batch = NIGHT_NEAREST)
    public static void nightNearest(ServerLevel level) {
        level.setDayTime(NIGHT);
    }

    @AfterBatch(batch = NIGHT_NEAREST)
    public static void dayAfterNearest(ServerLevel level) {
        level.setDayTime(DAY);
    }

    @BeforeBatch(batch = NIGHT_LONE)
    public static void nightLone(ServerLevel level) {
        level.setDayTime(NIGHT);
    }

    @AfterBatch(batch = NIGHT_LONE)
    public static void dayAfterLone(ServerLevel level) {
        level.setDayTime(DAY);
    }

    @BeforeBatch(batch = NIGHT_FLOOR)
    public static void nightFloor(ServerLevel level) {
        level.setDayTime(NIGHT);
    }

    @AfterBatch(batch = NIGHT_FLOOR)
    public static void dayAfterFloor(ServerLevel level) {
        level.setDayTime(DAY);
    }

    @BeforeBatch(batch = NIGHT_RESPAWN)
    public static void nightRespawn(ServerLevel level) {
        level.setDayTime(NIGHT);
    }

    @AfterBatch(batch = NIGHT_RESPAWN)
    public static void dayAfterRespawn(ServerLevel level) {
        level.setDayTime(DAY);
    }

    /**
     * A bed in a sealed room 4 blocks away and a bed in the open 5 blocks away: the recruit sleeps in the
     * open one, having walked to it, never in the sealed one, and never sets off toward the sealed one (the
     * room is west of it, the open bed east).
     */
    @GameTest(template = EMPTY, timeoutTicks = 1300, batch = NIGHT_WALLED)
    public static void aRecruitSleepsInABedItCanReachNotOneBehindAWall(GameTestHelper helper) {
        floor(helper);
        for (int x = 3; x <= 7; x++) for (int z = 2; z <= 6; z++) {
            helper.setBlock(x, 5, z, Blocks.STONE_BRICKS);
            for (int y = 2; y <= 4; y++) if (x == 3 || x == 7 || z == 2 || z == 6) helper.setBlock(x, y, z, Blocks.STONE_BRICKS);
        }
        BlockPos sealed = bed(helper, 5, 4, 2);
        BlockPos open = bed(helper, 14, 4, 2);
        // It starts at x 9.5; standing west of 9.2 means it set off toward the sealed room.
        watch(helper, recruit(helper, 9, 4), open, sealed, 1200, 9.2);
    }

    /**
     * A bed in a sealed room on an upper floor, its floor 3 blocks above the ground the recruit walks on, and a
     * bed in the open farther away: the recruit sleeps in the open one, never in the upper one, and never walks
     * in under the room (west of it) toward the upper bed.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1300, batch = NIGHT_FLOOR)
    public static void aRecruitDoesNotSleepInABedOnAFloorItCannotReach(GameTestHelper helper) {
        floor(helper);
        // The room: x 4..8, z 2..6, its floor at y 4 on four corner posts, walls y 5..7, roof y 8.
        for (int x = 4; x <= 8; x++) for (int z = 2; z <= 6; z++) {
            helper.setBlock(x, 4, z, Blocks.STONE_BRICKS);
            helper.setBlock(x, 8, z, Blocks.STONE_BRICKS);
            for (int y = 5; y <= 7; y++) if (x == 4 || x == 8 || z == 2 || z == 6) helper.setBlock(x, y, z, Blocks.STONE_BRICKS);
        }
        for (int y = 2; y <= 3; y++) for (int[] post : new int[][]{{4, 2}, {8, 2}, {4, 6}, {8, 6}}) helper.setBlock(post[0], y, post[1], Blocks.STONE_BRICKS);
        BlockPos upper = bed(helper, 6, 4, 5);
        BlockPos open = bed(helper, 16, 4, 2);
        // It starts at x 10.5; standing west of 10.2 means it set off toward the room.
        watch(helper, recruit(helper, 10, 4), open, upper, 1200, 10.2);
    }

    /**
     * A free bed 3 blocks away that is an online player's respawn point, and another 7 blocks away: the recruit
     * sleeps in the other one.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = NIGHT_RESPAWN)
    public static void aRecruitLeavesAPlayersRespawnBedAlone(GameTestHelper helper) {
        floor(helper);
        BlockPos theirs = bed(helper, 7, 4, 2);
        BlockPos other = bed(helper, 11, 4, 2);
        // The player stands out of the way; a player respawns at a bed's head, where sleeping in it sets it.
        net.minecraft.server.level.ServerPlayer player = GameTestSupport.owner(helper, 2.5, 2, 7.5);
        player.setRespawnPosition(helper.getLevel().dimension(), theirs.south(), 0.0F, true, false);
        // An online player is one on the server's player list; the test player is only added to the level, and
        // the list Forge hands out can't be changed, so it goes on the list underneath.
        java.util.List<net.minecraft.server.level.ServerPlayer> online = onlinePlayers(helper);
        online.add(player);
        Runnable leave = () -> {
            online.remove(player);
            if (!player.isRemoved()) GameTestSupport.removeOwner(helper, player);
        };
        helper.onEachTick(() -> {
            if (helper.getTick() >= 850) leave.run();
        });
        watch(helper, recruit(helper, 4, 4), other, theirs, 800, Double.NaN, leave);
    }

    /** Free beds 3 and 10 blocks away: the recruit sleeps in the nearer one. */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = NIGHT_NEAREST)
    public static void aRecruitSleepsInTheNearestFreeBed(GameTestHelper helper) {
        floor(helper);
        BlockPos near = bed(helper, 7, 4, 2);
        BlockPos far = bed(helper, 14, 4, 2);
        watch(helper, recruit(helper, 4, 4), near, far, 800, Double.NaN);
    }

    /** The only free bed in range, 10 blocks away: the recruit walks to it and sleeps. */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = NIGHT_LONE)
    public static void aRecruitSleepsInTheOnlyFreeBedInRange(GameTestHelper helper) {
        floor(helper);
        BlockPos lone = bed(helper, 13, 4, 2);
        watch(helper, recruit(helper, 3, 4), lone, null, 800, Double.NaN);
    }

    /** The server's own list of online players, which PlayerList.getPlayers only shows. */
    @SuppressWarnings("unchecked")
    private static java.util.List<net.minecraft.server.level.ServerPlayer> onlinePlayers(GameTestHelper helper) {
        try {
            java.lang.reflect.Field field = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
            field.setAccessible(true);
            return (java.util.List<net.minecraft.server.level.ServerPlayer>) field.get(helper.getLevel().getServer().getPlayerList());
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("could not reach the server's player list: " + e);
        }
    }

    /** Stone under y 2 across the template, and barriers on its edge up to y 6. */
    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 18; x++) for (int z = 0; z < 9; z++) {
            helper.setBlock(x, 0, z, Blocks.STONE);
            helper.setBlock(x, 1, z, Blocks.STONE);
            if (x == 0 || x == 17 || z == 0 || z == 8) for (int y = 2; y <= 6; y++) helper.setBlock(x, y, z, Blocks.BARRIER);
        }
    }

    /** A red bed with its foot at (x, y, z) and its head one block south; returns the foot's absolute position. */
    private static BlockPos bed(GameTestHelper helper, int x, int z, int y) {
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        helper.setBlock(x, y, z, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(x, y, z + 1, bed.setValue(BedBlock.PART, BedPart.HEAD));
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** A wandering recruit at half health standing at (x, 2, z). */
    private static AbstractRecruitEntity recruit(GameTestHelper helper, int x, int z) {
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), x, 2, z);
        recruit.setFollowState(0);
        recruit.setHealth(recruit.getMaxHealth() * 0.5F);
        return recruit;
    }

    /**
     * Follows the recruit each tick until it lies down or {@code ticks} pass, then checks that it lies in
     * {@code expected}, last stood awake within {@link #WALKED_TO} blocks of it, never lay in {@code never}, and,
     * unless {@code westLimit} is NaN, never stood west of relative x {@code westLimit} while awake.
     * A bed is given by its foot; a sleeper may lie at either half (vanilla's own sleeping spot is the head).
     */
    private static void watch(GameTestHelper helper, AbstractRecruitEntity recruit, BlockPos expected, BlockPos never, int ticks, double westLimit) {
        watch(helper, recruit, expected, never, ticks, westLimit, () -> { });
    }

    /** {@link #watch(GameTestHelper, AbstractRecruitEntity, BlockPos, BlockPos, int, double)}, then {@code cleanUp} once judged. */
    private static void watch(GameTestHelper helper, AbstractRecruitEntity recruit, BlockPos expected, BlockPos never, int ticks, double westLimit,
                              Runnable cleanUp) {
        Vec3[] lastAwake = {recruit.position()};
        double[] westmost = {Double.MAX_VALUE};
        boolean[] judged = {false};
        boolean night = helper.getLevel().isNight();
        helper.onEachTick(() -> {
            if (judged[0]) return;
            long tick = helper.getTick();
            if (!recruit.isSleeping()) {
                lastAwake[0] = recruit.position();
                westmost[0] = Math.min(westmost[0], recruit.getX() - helper.absolutePos(BlockPos.ZERO).getX());
                if (tick < ticks) return;
            }
            judged[0] = true;
            BlockPos in = recruit.getSleepingPos().orElse(null);
            double jump = in == null ? -1 : Math.sqrt(in.distToCenterSqr(lastAwake[0]));
            // Relative to the template's corner; GameTestHelper.relativePos turns positions half round.
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            Vec3 at = lastAwake[0].subtract(origin.getX(), origin.getY(), origin.getZ());
            String report = String.format(Locale.ROOT, "night %s; by tick %d asleep in %s (expected %s); last stood awake at %.1f %.1f %.1f, %.1f blocks from that bed's centre; westmost x awake %.2f",
                    night, tick, in == null ? "no bed" : in.subtract(origin).toShortString(), expected.subtract(origin).toShortString(),
                    at.x, at.y, at.z, jump, westmost[0]);
            Main.LOGGER.info("[KhaosKorps measure] bed rest: {}", report);
            recruit.discard();
            cleanUp.run();
            if (!Double.isNaN(westLimit) && westmost[0] < westLimit) throw new GameTestAssertException("set off toward the bed it can't reach: " + report);
            if (in != null && never != null && (in.equals(never) || in.equals(never.south()))) throw new GameTestAssertException("asleep in the bed it must not use: " + report);
            if (!expected.equals(in) && !expected.south().equals(in)) throw new GameTestAssertException("not asleep in the expected bed: " + report);
            if (jump > WALKED_TO) throw new GameTestAssertException("lay down from too far away: " + report);
            helper.succeed();
        });
    }
}
