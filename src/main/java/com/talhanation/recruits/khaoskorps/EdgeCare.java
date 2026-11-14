package com.talhanation.recruits.khaoskorps;

/**
 * The edge care a navigation applies to the paths it walks, taken from the settings each time the
 * follow goal asks for a path.
 *
 * @param keepBackBlocks  how far from a dangerous drop a path cut back for an unreachable owner ends
 * @param trimUnreachable whether a path that can't reach the owner past a drop is cut back (WaitWhenOwnerUnreachable)
 * @param carefulSpeed    the fraction of its speed a mob keeps while walking toward a path point right
 *                        beside a dangerous drop; 1.0 is no slowdown
 */
public record EdgeCare(int keepBackBlocks, boolean trimUnreachable, double carefulSpeed) {

    /** The care a following recruit's path gets under {@code settings}. Careful steps belong to EdgeMargin. */
    public static EdgeCare forFollowing(FollowSettings settings) {
        return new EdgeCare(settings.edgeMarginBlocks(), settings.waitWhenOwnerUnreachable(),
                settings.edgeMargin() ? settings.edgeCarefulSpeed() : 1.0);
    }
}
