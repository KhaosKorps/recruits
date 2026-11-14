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
}
