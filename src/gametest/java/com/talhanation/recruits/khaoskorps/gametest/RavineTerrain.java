package com.talhanation.recruits.khaoskorps.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import java.util.Random;

/**
 * A seeded ravine for the 64 x 24 x 64 test arena, rough the way real ravines are. The same seed
 * always builds the same terrain, so a failing seed can be rerun and looked at.
 *
 * - Ground north and south of the ravine at different base heights (10 to 16), rolling in both
 *   directions by up to two blocks, never more than a block between neighbouring columns, so it is
 *   all walkable at any fall distance.
 * - The ravine runs west to east from the arena's west wall to {@link #endX}, wandering north and
 *   south, 7 to 15 blocks wide with jagged rims.
 * - Its floor varies in depth along its length, with pools of water two deep in places.
 * - Shelves three blocks below the rim stick out from its walls in places: ledges with no way back
 *   up and no way on.
 * - Its east end is a staircase of three-block drops down to the floor: walkable down at fall
 *   distance 3, never back up.
 * - East of the ravine the ground slopes between the north and south heights, so there is always a
 *   walkable way round.
 *
 * Every column is solid stone up to its stand height (the height a mob's feet are at standing on
 * it); water pools fill the two blocks above a floor.
 */
final class RavineTerrain {

    static final int SIZE = 64;
    static final int TOP = 23;
    /** The spacing of the rolling ground's control points, in blocks. */
    private static final int ROLL = 8;

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

    RavineTerrain(long seed) {
        this.seed = seed;
        Random random = new Random(seed);
        this.northStand = 12 + random.nextInt(3);
        this.southStand = Math.max(10, Math.min(16, this.northStand + random.nextInt(7) - 3));
        this.endX = 40 + random.nextInt(9);

        // The rolling ground: random offsets of -2..2 on a coarse grid, blended smoothly between.
        int cells = SIZE / ROLL + 2;
        double[][] roll = new double[cells][cells];
        for (int i = 0; i < cells; i++) for (int j = 0; j < cells; j++) roll[i][j] = random.nextInt(5) - 2;

        int[] floor = new int[SIZE];
        boolean[] pool = new boolean[SIZE];
        boolean[] shelfNorth = new boolean[SIZE];
        boolean[] shelfSouth = new boolean[SIZE];
        int center = 29 + random.nextInt(5);
        int halfNorth = 3 + random.nextInt(4);
        int halfSouth = 3 + random.nextInt(4);
        int depth = 3 + random.nextInt(4);
        int poolRun = 0, northRun = 0, southRun = 0;
        for (int x = 1; x <= SIZE - 2; x++) {
            center = clamp(center + random.nextInt(3) - 1, 27, 35);
            halfNorth = clamp(halfNorth + random.nextInt(3) - 1, 3, 7);
            halfSouth = clamp(halfSouth + random.nextInt(3) - 1, 3, 7);
            depth = clamp(depth + random.nextInt(3) - 1, 2, Math.min(this.northStand, this.southStand) - 8);
            this.centerZ[x] = center;
            this.northEdge[x] = center - halfNorth;
            this.southEdge[x] = center + halfSouth;
            floor[x] = depth;
            if (poolRun > 0) poolRun--; else if (random.nextInt(9) == 0) poolRun = 3 + random.nextInt(5);
            if (northRun > 0) northRun--; else if (random.nextInt(7) == 0) northRun = 2 + random.nextInt(4);
            if (southRun > 0) southRun--; else if (random.nextInt(7) == 0) southRun = 2 + random.nextInt(4);
            pool[x] = poolRun > 0;
            shelfNorth[x] = northRun > 0;
            shelfSouth[x] = southRun > 0;
        }

        // The ground everywhere outside the ravine.
        for (int x = 1; x <= SIZE - 2; x++) {
            for (int z = 1; z <= SIZE - 2; z++) {
                int offset = (int) Math.round(rolled(roll, x, z));
                int north = this.northStand + offset;
                int south = this.southStand + offset;
                if (x > this.endX) {
                    int from = this.centerZ[x] - 8, to = this.centerZ[x] + 8;
                    double t = z <= from ? 0 : z >= to ? 1 : (z - from) / (double) (to - from);
                    this.stand[x][z] = (int) Math.round(north + t * (south - north));
                } else if (z < this.northEdge[x]) {
                    this.stand[x][z] = north;
                } else if (z > this.southEdge[x]) {
                    this.stand[x][z] = south;
                } else {
                    this.ravine[x][z] = true;
                }
            }
        }
        // No more than a block between neighbouring ground columns: lower any column that stands higher.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int x = 1; x <= SIZE - 2; x++) for (int z = 1; z <= SIZE - 2; z++) {
                if (this.ravine[x][z]) continue;
                int lowest = this.stand[x][z];
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], nz = z + d[1];
                    if (nx < 1 || nz < 1 || nx > SIZE - 2 || nz > SIZE - 2 || this.ravine[nx][nz]) continue;
                    lowest = Math.min(lowest, this.stand[nx][nz] + 1);
                }
                if (lowest < this.stand[x][z]) {
                    this.stand[x][z] = lowest;
                    changed = true;
                }
            }
        }

        // The ravine, measured from the rims beside it.
        for (int x = 1; x <= this.endX; x++) {
            int northRim = this.stand[x][this.northEdge[x] - 1];
            int southRim = this.stand[x][this.southEdge[x] + 1];
            int rim = Math.min(northRim, southRim);
            int fromEnd = this.endX - x;
            for (int z = this.northEdge[x]; z <= this.southEdge[x]; z++) {
                if (fromEnd <= 1) {
                    this.stand[x][z] = rim - 3;
                } else if (fromEnd <= 3) {
                    this.stand[x][z] = rim - 6;
                } else if (shelfNorth[x] && z <= this.northEdge[x] + 1) {
                    this.stand[x][z] = northRim - 3;
                } else if (shelfSouth[x] && z >= this.southEdge[x] - 1) {
                    this.stand[x][z] = southRim - 3;
                } else {
                    this.stand[x][z] = Math.min(floor[x], rim - 5);
                    this.water[x][z] = pool[x];
                }
            }
        }
    }

    /** The rolling-ground offset at (x, z): the coarse grid's values blended with a smooth curve. */
    private static double rolled(double[][] roll, int x, int z) {
        double gx = x / (double) ROLL, gz = z / (double) ROLL;
        int ix = (int) gx, iz = (int) gz;
        double fx = smooth(gx - ix), fz = smooth(gz - iz);
        double top = roll[ix][iz] + (roll[ix + 1][iz] - roll[ix][iz]) * fx;
        double bottom = roll[ix][iz + 1] + (roll[ix + 1][iz + 1] - roll[ix][iz + 1]) * fx;
        return top + (bottom - top) * fz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
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

    /** The stand height of the rim on the side of the ravine column (x, z) is on: below it means in the ravine. */
    int rimAt(int x, int z) {
        x = clamp(x, 1, SIZE - 2);
        if (x > this.endX) return this.stand[x][clamp(z, 1, SIZE - 2)];
        return z < this.centerZ[x] ? this.stand[x][this.northEdge[x] - 1] : this.stand[x][this.southEdge[x] + 1];
    }

    /** A short description for failure messages. */
    String describe() {
        return "ravine seed " + this.seed + " (north ground " + this.northStand + ", south " + this.southStand
                + ", ravine to x " + this.endX + ")";
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
