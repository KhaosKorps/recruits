package com.talhanation.recruits.khaoskorps;

/**
 * The edge care a navigation applies to the paths it walks, taken from the settings each time the
 * follow goal asks for a path.
 *
 * @param keepBackBlocks  how far from a dangerous drop a cut-back path ends
 * @param trimUnreachable whether a path that can't reach the owner past a drop is cut back (WaitWhenOwnerUnreachable)
 * @param nearOwnerBlocks how close to the owner a path's end is kept back from a drop even when it can
 *                        reach the owner; 0 is off
 * @param carefulSpeed    the fraction of its speed a mob keeps while walking toward a path point right
 *                        beside a dangerous drop; 1.0 is no slowdown
 * @param depthWeight     for a path that can't reach the owner, how many blocks of distance each block
 *                        below the mob's start counts as, in choosing where the path ends; 0 is off
 * @param crossOneAtATime whether the group crosses risky narrow spots one at a time (CrossingLocks)
 */
public record EdgeCare(int keepBackBlocks, boolean trimUnreachable, int nearOwnerBlocks, double carefulSpeed, double depthWeight,
                       boolean crossOneAtATime) {

    /**
     * The care a following recruit's path gets under {@code settings}. Keeping back near the owner
     * and careful steps belong to EdgeMargin. Near the owner means within the margin plus the stop
     * distance, so a recruit stops back from an edge its owner stands at, not along the whole way there.
     */
    public static EdgeCare forFollowing(FollowSettings settings) {
        int keepBack = settings.edgeMarginBlocks();
        int nearOwner = settings.edgeMargin()
                ? keepBack + 1 + (int) Math.ceil(Math.sqrt(settings.followStopDistanceSqr()))
                : 0;
        return new EdgeCare(keepBack, settings.waitWhenOwnerUnreachable(), nearOwner,
                settings.edgeMargin() ? settings.edgeCarefulSpeed() : 1.0,
                settings.waitWhenOwnerUnreachable() ? settings.unreachableDepthWeight() : 0.0,
                settings.edgeMargin() && settings.crossOneAtATime());
    }
}
