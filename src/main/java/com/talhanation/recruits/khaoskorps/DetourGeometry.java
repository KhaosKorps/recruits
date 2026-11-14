package com.talhanation.recruits.khaoskorps;

import java.util.ArrayList;
import java.util.List;

/**
 * The geometry of a detour search, in blocks on the ground plane (x east, z south): which spots to
 * try next from a waypoint, and when a detour has gone too far. Plain Java, covered by JUnit.
 */
public final class DetourGeometry {

    /** One side of an obstacle: candidates are turned one way or the other from the bearing to the goal. */
    public static final int LEFT = 1;
    public static final int RIGHT = -1;

    /** Turns from the bearing to the goal, in the order tried: toward it at an angle, across, slightly back. */
    private static final int[] TURNS = {45, 90, 135};

    /** An offset from a waypoint to a candidate spot. */
    public record Offset(int dx, int dz) {
    }

    private DetourGeometry() {
    }

    /**
     * The spots to try from a waypoint, for one side, in order: for each turn from the bearing to
     * the goal, a full step and then half a step, so a direction is given up only when neither
     * works. Empty when the waypoint is at the goal.
     */
    public static List<Offset> candidates(double toGoalX, double toGoalZ, int side, int step) {
        double length = Math.hypot(toGoalX, toGoalZ);
        List<Offset> candidates = new ArrayList<>();
        if (length < 1e-6) return candidates;
        double ux = toGoalX / length;
        double uz = toGoalZ / length;
        for (int turn : TURNS) {
            double radians = Math.toRadians(turn * side);
            double rx = ux * Math.cos(radians) - uz * Math.sin(radians);
            double rz = ux * Math.sin(radians) + uz * Math.cos(radians);
            for (int distance : new int[]{step, step / 2}) {
                candidates.add(new Offset((int) Math.round(rx * distance), (int) Math.round(rz * distance)));
            }
        }
        return candidates;
    }

    /** Whether (x, z) lies within {@code radius} blocks of any of the {@code visited} spots, given as x, z pairs. */
    public static boolean nearAny(int x, int z, List<int[]> visited, int radius) {
        long radiusSqr = (long) radius * radius;
        for (int[] spot : visited) {
            long dx = x - spot[0];
            long dz = z - spot[1];
            if (dx * dx + dz * dz <= radiusSqr) return true;
        }
        return false;
    }

    /** The most walking a detour may take: {@code factor} times the straight-line distance to the goal. */
    public static double budget(double straightDistance, double factor) {
        return straightDistance * factor;
    }
}
