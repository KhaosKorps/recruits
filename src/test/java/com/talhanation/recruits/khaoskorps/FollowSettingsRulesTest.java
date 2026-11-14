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
}
