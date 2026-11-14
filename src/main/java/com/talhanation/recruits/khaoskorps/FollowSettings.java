package com.talhanation.recruits.khaoskorps;

/**
 * One immutable snapshot of the pathfinding settings.
 *
 * The path worker thread reads settings while it searches, and Forge's config objects aren't safe
 * to read from it. So the config is copied into this record when it loads or is saved, and the
 * record is published through one volatile field: a reader gets a consistent set of values with
 * a single memory read, and no lock.
 *
 * Plain Java with no Minecraft types, so its defaults and rules are covered by JUnit.
 */
public record FollowSettings(
        int maxFallDistance,
        int mountedMaxFallDistance,
        double followStartDistanceSqr,
        double followStopDistanceSqr,
        int followRepathTicks,
        int followRepathTicksRiding,
        int followStartCheckTicks,
        double followSpeed,
        int followMaxPathLength,
        int searchNodesPerBlock,
        DirtPathPreference dirtPathPreference,
        boolean keepWalkingWhileRepathing,
        boolean staggerFollowRepaths,
        boolean edgeMargin,
        int edgeMarginBlocks,
        double edgeCarefulSpeed,
        boolean waitWhenOwnerUnreachable,
        boolean detourSearch,
        double movementSpeedMultiplier,
        double swimSpeedMultiplier,
        boolean creepersTargetRecruits,
        boolean pathLog) {

    /** How strongly paths prefer dirt paths (RecruitsPathNodeEvaluator.getNode). */
    public enum DirtPathPreference {
        /** The 1.15.2 release: +2 per side without a dirt path, added again on every look at a node. */
        ORIGINAL,
        /** Upstream's fix (453794ce): the same cost, added once per node. */
        ONCE,
        /** No preference: a step costs what vanilla charges, so the search heads straight for the target. */
        OFF
    }

    /** The 1.15.2 release's values, except two safety behaviours that are on: EdgeMargin and WaitWhenOwnerUnreachable. */
    public static final FollowSettings DEFAULTS = new FollowSettings(
            1, 1,
            9.0, 7.0,
            10, 5, 10,
            1.05,
            0, 16,
            DirtPathPreference.ORIGINAL,
            false, false,
            true, 2, 0.5,
            true,
            true,
            1.0, 1.0,
            false,
            false);

    private static volatile FollowSettings current = DEFAULTS;

    /** The settings in force: one volatile read, safe from any thread. */
    public static FollowSettings current() {
        return current;
    }

    /**
     * The squared stop distance, held to the squared start distance. A stop distance beyond the
     * start distance would start the follow goal and stop it on the same check, every check, and the
     * recruit would never move.
     */
    public static double stopDistanceSqrWithinStart(double startDistanceSqr, double stopDistanceSqr) {
        return Math.min(stopDistanceSqr, startDistanceSqr);
    }

    /**
     * The multiplier a navigation passes to its path finder for one search, so that search may
     * examine {@code nodesPerBlock} path points per block of {@code range}. {@code baseBudget} is
     * the budget the path finder was built with, 16 per block of the follow range, so at the
     * defaults, where the range is the follow range, this is exactly 1.
     */
    public static float searchBudgetMultiplier(int nodesPerBlock, float range, int baseBudget) {
        if (baseBudget <= 0) return 1.0F;
        return nodesPerBlock * range / baseBudget;
    }

    /** Publishes a new snapshot. Called on config load and save. */
    static void publish(FollowSettings settings) {
        current = settings;
    }
}
