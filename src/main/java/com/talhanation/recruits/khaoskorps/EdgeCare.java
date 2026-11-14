package com.talhanation.recruits.khaoskorps;

/**
 * The edge care a navigation applies to one follow path, taken from the settings when the path is
 * asked for.
 *
 * @param keepBackBlocks  how far from a dangerous drop a path cut back for an unreachable owner ends
 * @param trimUnreachable whether a path that can't reach the owner past a drop is cut back (WaitWhenOwnerUnreachable)
 */
public record EdgeCare(int keepBackBlocks, boolean trimUnreachable) {

    /** The care a following recruit's path gets under {@code settings}. */
    public static EdgeCare forFollowing(FollowSettings settings) {
        return new EdgeCare(settings.edgeMarginBlocks(), settings.waitWhenOwnerUnreachable());
    }
}
