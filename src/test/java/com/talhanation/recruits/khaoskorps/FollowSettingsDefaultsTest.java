package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every pathfinding setting's default reproduces the 1.15.2 release, except two safety behaviours
 * that are on by default: waiting back from the edge when the owner is out of reach, and the edge
 * margin.
 */
class FollowSettingsDefaultsTest {

    private final FollowSettings d = FollowSettings.DEFAULTS;

    @Test
    void fallDistancesDefaultToTheReleasesOne() {
        assertEquals(1, d.maxFallDistance(), "1.15.2 gives every recruit a fall distance of 1");
        assertEquals(1, d.mountedMaxFallDistance(), "1.15.2 gives a horse ridden by a recruit a fall distance of 1");
    }

    @Test
    void followDistancesDefaultToTheReleasesSquaredNineAndSeven() {
        assertEquals(9.0, d.followStartDistanceSqr(), 1e-9, "1.15.2 starts following beyond 3 blocks (9 squared)");
        assertEquals(7.0, d.followStopDistanceSqr(), 1e-9, "1.15.2 stops within about 2.65 blocks (7 squared)");
    }

    @Test
    void followTimingAndSpeedDefaultToTheRelease() {
        assertEquals(10, d.followRepathTicks(), "1.15.2 re-plans every 10 ticks on foot");
        assertEquals(5, d.followRepathTicksRiding(), "1.15.2 re-plans every 5 ticks when mounted");
        assertEquals(10, d.followStartCheckTicks(), "1.15.2 checks whether to start following every 10 ticks");
        assertEquals(1.05, d.followSpeed(), 1e-9, "1.15.2 follows at 1.05 times the recruit's speed");
    }

    @Test
    void searchDefaultsToTheRelease() {
        assertEquals(0, d.followMaxPathLength(), "0 means each recruit's own follow range, which 1.15.2 always used");
        assertEquals(16, d.searchNodesPerBlock(), "1.15.2 examines 16 path points per block of follow range");
        assertEquals(FollowSettings.DirtPathPreference.ORIGINAL, d.dirtPathPreference(), "1.15.2's dirt-path preference");
        assertFalse(d.keepWalkingWhileRepathing(), "1.15.2 swaps to the new path at once");
        assertFalse(d.staggerFollowRepaths(), "1.15.2 starts every follower's countdown at 0");
    }

    @Test
    void speedMultipliersDefaultToOne() {
        assertEquals(1.0, d.movementSpeedMultiplier(), 1e-9);
        assertEquals(1.0, d.swimSpeedMultiplier(), 1e-9);
    }

    @Test
    void creepersStillIgnoreRecruitsByDefault() {
        assertFalse(d.creepersTargetRecruits(), "1.15.2 gives every monster but creepers and endermen a recruit target");
    }

    @Test
    void theTwoSafetyBehavioursAreOnByDefault() {
        assertTrue(d.edgeMargin(), "the edge margin is on by default");
        assertTrue(d.waitWhenOwnerUnreachable(), "waiting back from the edge when the owner is out of reach is on by default");
        assertEquals(0.5, d.edgeCarefulSpeed(), 0.0, "half speed toward a point beside a drop");
        assertTrue(d.detourSearch(), "planning a way round a large obstacle is on by default");
    }

    @Test
    void theAlwaysOnChecksRunAsOftenAsIn1152() {
        assertEquals(2, d.fireCheckTicks(), "1.15.2 checks for fire every time the goal runs, every second tick");
        assertEquals(2, d.tntCheckTicks(), "1.15.2 checks for lit TNT every time the goal runs");
        assertEquals(2, d.villageDefenceCheckTicks(), "1.15.2 checks for village attackers every goal selection pass");
    }

    @Test
    void theDeadEndCheckSearchesSixtyFourSpotsByDefault() {
        assertEquals(64, d.deadEndSearchNodes(), "a closed area of about 8 by 8 or less counts as a dead end");
    }

    @Test
    void depthWeighsTwoBlocksPerBlockBelowAndDropsCostNothingByDefault() {
        assertEquals(2.0, d.unreachableDepthWeight(), "each block below its start counts as 2 blocks of distance for an unreachable path's end");
        assertEquals(0.0, d.dropCostPerBlock(), "no extra cost for drops, as in 1.15.2");
    }

    @Test
    void followersCrossRiskyNarrowSpotsOneAtATimeByDefault() {
        assertTrue(d.crossOneAtATime(), "the user's choice for one-block bridges, design A");
    }

    @Test
    void pathLoggingIsOffByDefault() {
        assertFalse(d.pathLog());
    }
}
