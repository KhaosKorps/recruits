package com.talhanation.recruits.khaoskorps;

/**
 * The extra path cost of standing near a dangerous drop, so a search prefers a route further from
 * it when one exists. Full cost beside the drop, falling off evenly to the edge of the margin.
 */
public final class EdgeCost {

    /** The cost beside a dangerous drop: about eight steps of flat walking. */
    public static final float BESIDE_A_DROP = 8.0F;

    private EdgeCost() {
    }

    /**
     * The cost of a path point {@code distance} blocks from the nearest dangerous drop, with the
     * margin reaching {@code radius} blocks. {@link EdgeProbe#NONE} and anything past the radius cost nothing.
     */
    public static float malus(int distance, int radius) {
        if (distance == EdgeProbe.NONE || radius <= 0 || distance > radius) return 0.0F;
        return BESIDE_A_DROP * (radius - distance + 1) / radius;
    }
}
