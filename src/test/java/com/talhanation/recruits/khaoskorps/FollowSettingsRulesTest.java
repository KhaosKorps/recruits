package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * KhaosKorps fork: the rules the settings snapshot applies to the values it reads.
 */
class FollowSettingsRulesTest {

    @Test
    void aStopDistanceBeyondTheStartDistanceIsHeldToTheStartDistance() {
        // Stop 4 blocks, start 3: the goal would start and stop on every check without moving.
        assertEquals(9.0, FollowSettings.stopDistanceSqrWithinStart(9.0, 16.0), 0.0);
    }

    @Test
    void aStopDistanceWithinTheStartDistanceIsKept() {
        assertEquals(7.0, FollowSettings.stopDistanceSqrWithinStart(9.0, 7.0), 0.0);
    }

    @Test
    void theDefaultBudgetLeavesEverySearchExactlyAsItWas() {
        for (int followRange : new int[]{32, 64, 128}) {
            assertEquals(1.0F, FollowSettings.searchBudgetMultiplier(16, followRange, followRange * 16), 0.0F,
                    "follow range " + followRange);
        }
    }

    @Test
    void oneNodePerBlockGivesThirtyTwoNodesOverAThirtyTwoBlockRange() {
        float multiplier = FollowSettings.searchBudgetMultiplier(1, 32, 512);
        assertEquals(32, (int) (512 * multiplier), "the path finder's own rounding: (int) (budget * multiplier)");
    }

    @Test
    void aLongerFollowPathGetsABudgetToMatchItsLength() {
        // A follow path length of 64 for a recruit whose follow range is 32: twice the path points.
        assertEquals(2.0F, FollowSettings.searchBudgetMultiplier(16, 64, 512), 0.0F);
    }

    @Test
    void aNavigationBuiltWithNoBudgetIsLeftAsItIs() {
        assertEquals(1.0F, FollowSettings.searchBudgetMultiplier(16, 32, 0), 0.0F);
    }
}
