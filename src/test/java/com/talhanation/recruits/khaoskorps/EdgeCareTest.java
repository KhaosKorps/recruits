package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeCareTest {

    @Test
    void byDefaultAFollowerWaitsTwoBlocksBackFromADropItCantCross() {
        EdgeCare care = EdgeCare.forFollowing(FollowSettings.DEFAULTS);
        assertTrue(care.trimUnreachable());
        assertEquals(2, care.keepBackBlocks());
    }

    @Test
    void byDefaultAFollowerWalksAtHalfSpeedTowardAPointBesideADrop() {
        assertEquals(0.5, EdgeCare.forFollowing(FollowSettings.DEFAULTS).carefulSpeed(), 0.0);
    }

    @Test
    void withEdgeMarginOffThereIsNoSlowdown() {
        assertEquals(1.0, EdgeCare.forFollowing(withEdgeMargin(false)).carefulSpeed(), 0.0);
    }

    private static FollowSettings withEdgeMargin(boolean on) {
        FollowSettings d = FollowSettings.DEFAULTS;
        return new FollowSettings(d.maxFallDistance(), d.mountedMaxFallDistance(),
                d.followStartDistanceSqr(), d.followStopDistanceSqr(),
                d.followRepathTicks(), d.followRepathTicksRiding(), d.followStartCheckTicks(),
                d.followSpeed(), d.followMaxPathLength(), d.searchNodesPerBlock(), d.dirtPathPreference(),
                d.keepWalkingWhileRepathing(), d.staggerFollowRepaths(),
                on, d.edgeMarginBlocks(), d.edgeCarefulSpeed(), d.waitWhenOwnerUnreachable(),
                d.movementSpeedMultiplier(), d.swimSpeedMultiplier(), d.creepersTargetRecruits(), d.pathLog());
    }
}
