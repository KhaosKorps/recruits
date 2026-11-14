package com.talhanation.recruits.khaoskorps.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import java.util.Random;

/**
 * A seeded ravine for the 64 x 24 x 64 test arena, rough the way real ravines are. The same seed
 * always builds the same terrain, so a failing seed can be rerun and looked at.
 *
 * - Ground north and south of the ravine at different heights (stand heights 10 to 16), each
 *   rising and falling a block at a time along the ravine.
 * - The ravine runs west to east from the arena's west wall to {@link #endX}, wandering north and
 *   south, 7 to 15 blocks wide with jagged rims.
 * - Its floor varies in depth along its length, with pools of water two deep in places.
 * - Shelves three blocks below the rim stick out from its walls in places: ledges with no way back
 *   up and no way on.
 * - Its east end is a staircase of three-block drops down to the floor: walkable down at fall
 *   distance 3, never back up.
 * - East of the ravine the ground slopes a block at a time between the north and south heights, so
 *   there is always a walkable way round.
 *
 * Every column is solid stone up to its stand height (the height a mob's feet are at standing on
 * it); water pools fill the two blocks above a floor.
 */
final class RavineTerrain {

    static final int SIZE = 64;
    static final int TOP = 23;

    final long seed;
    final int northStand;
    final int southStand;
    /** The ravine's last column: from x = 1 to here; east of it is the way round. */
    final int endX;
    /** Per column x: the ravine's first and last cut z (inclusive); unused east of {@link #endX}. */
    final int[] northEdge = new int[SIZE];
    final int[] southEdge = new int[SIZE];
    final int[] centerZ = new int[SIZE];
    /** Per column: the stand height (top solid block + 1). */
    final int[][] stand = new int[SIZE][SIZE];
    /** Per column: whether it lies inside the ravine (cut below the rims, shelves and stairs included). */
    final boolean[][] ravine = new boolean[SIZE][SIZE];
    /** Per column: whether two blocks of water sit on it. */
    final boolean[][] water = new boolean[SIZE][SIZE];
    final int[] northGround = new int[SIZE];
    final int[] southGround = new int[SIZE];

    RavineTerrain(long seed) {
        this.seed = seed;
        Random random = new Random(seed);
        this.northStand = 12 + random.nextInt(3);
        this.southStand = Math.max(10, Math.min(16, this.northStand + random.nextInt(7) - 3));
        this.endX = 40 + random.nextInt(9);
        int center = 29 + random.nextInt(5);
        int halfNorth = 3 + random.nextInt(4);
        int halfSouth = 3 + random.nextInt(4);
        int lowestRim = Math.min(this.northStand, this.southStand) - 1;
        int floor = 3 + random.nextInt(4);
        int bumpNorth = 0;
        int bumpSouth = 0;
        int pool = 0;
        int shelfNorth = 0;
        int shelfSouth = 0;
        for (int x = 1; x <= SIZE - 2; x++) {
            center = clamp(center + random.nextInt(3) - 1, 27, 35);
            halfNorth = clamp(halfNorth + random.nextInt(3) - 1, 3, 7);
            halfSouth = clamp(halfSouth + random.nextInt(3) - 1, 3, 7);
            floor = clamp(floor + random.nextInt(3) - 1, 2, lowestRim - 5);
            boolean nearEnd = x >= this.endX - 6;
            bumpNorth = nearEnd ? stepToward(bumpNorth, 0) : clamp(bumpNorth + random.nextInt(3) - 1, -1, 1);
            bumpSouth = nearEnd ? stepToward(bumpSouth, 0) : clamp(bumpSouth + random.nextInt(3) - 1, -1, 1);
            this.centerZ[x] = center;
            this.northEdge[x] = center - halfNorth;
            this.southEdge[x] = center + halfSouth;
            this.northGround[x] = this.northStand + bumpNorth;
            this.southGround[x] = this.southStand + bumpSouth;
            if (pool > 0) pool--;
            else if (random.nextInt(9) == 0) pool = 3 + random.nextInt(5);
            if (shelfNorth > 0) shelfNorth--;
            else if (random.nextInt(7) == 0) shelfNorth = 2 + random.nextInt(4);
            if (shelfSouth > 0) shelfSouth--;
            else if (random.nextInt(7) == 0) shelfSouth = 2 + random.nextInt(4);

            for (int z = 1; z <= SIZE - 2; z++) {
                if (x > this.endX) {
                    // The way round: from the north ground to the south ground, a block at a time.
                    int from = center - 8;
                    int to = center + 8;
                    double t = z <= from ? 0 : z >= to ? 1 : (z - from) / (double) (to - from);
                    this.stand[x][z] = (int) Math.round(this.northStand + t * (this.southStand - this.northStand));
                    continue;
                }
                if (z < this.northEdge[x]) {
                    this.stand[x][z] = this.northGround[x];
                    continue;
                }
                if (z > this.southEdge[x]) {
                    this.stand[x][z] = this.southGround[x];
                    continue;
                }
                this.ravine[x][z] = true;
                int fromEnd = this.endX - x;
                int rim = Math.min(this.northGround[x], this.southGround[x]);
                if (fromEnd <= 1) {
                    this.stand[x][z] = rim - 3;
                } else if (fromEnd <= 3) {
                    this.stand[x][z] = rim - 6;
                } else if (shelfNorth > 0 && z <= this.northEdge[x] + 1) {
                    this.stand[x][z] = this.northGround[x] - 3;
                } else if (shelfSouth > 0 && z >= this.southEdge[x] - 1) {
                    this.stand[x][z] = this.southGround[x] - 3;
                } else {
                    this.stand[x][z] = floor;
                    this.water[x][z] = pool > 0;
                }
            }
        }
    }

    /** Builds the terrain in the test's arena: barrier walls, stone, water. */
    void build(GameTestHelper helper) {
        for (int y = 0; y <= TOP; y++) {
            for (int i = 0; i < SIZE; i++) {
                helper.setBlock(i, y, 0, Blocks.BARRIER);
                helper.setBlock(i, y, SIZE - 1, Blocks.BARRIER);
                helper.setBlock(0, y, i, Blocks.BARRIER);
                helper.setBlock(SIZE - 1, y, i, Blocks.BARRIER);
            }
        }
        for (int x = 1; x <= SIZE - 2; x++) {
            for (int z = 1; z <= SIZE - 2; z++) {
                int top = this.stand[x][z];
                for (int y = 0; y < top; y++) helper.setBlock(x, y, z, Blocks.STONE);
                if (this.water[x][z]) {
                    helper.setBlock(x, top, z, Blocks.WATER);
                    helper.setBlock(x, top + 1, z, Blocks.WATER);
                }
            }
        }
    }

    /** The north rim's stand height above column x, for placing a recruit or checking it hasn't gone down. */
    int rimAt(int x, int z) {
        return z < this.centerZ[clamp(x, 1, SIZE - 2)] ? this.northGround[clamp(x, 1, SIZE - 2)] : this.southGround[clamp(x, 1, SIZE - 2)];
    }

    /** A short description for failure messages. */
    String describe() {
        return "ravine seed " + this.seed + " (north ground " + this.northStand + ", south " + this.southStand
                + ", ravine to x " + this.endX + ")";
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static int stepToward(int value, int target) {
        return value < target ? value + 1 : value > target ? value - 1 : value;
    }
}
