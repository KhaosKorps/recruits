package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetourGeometryTest {

    private static final DetourGeometry.Offset SOUTH_WEST = new DetourGeometry.Offset(-11, 11);
    private static final DetourGeometry.Offset SOUTH_EAST = new DetourGeometry.Offset(11, 11);

    @Test
    void withTheGoalDueSouthTheLeftSideTriesSouthWestThenWestThenNorthWest() {
        List<DetourGeometry.Offset> left = DetourGeometry.candidates(0, 30, DetourGeometry.LEFT, 16);
        assertEquals(List.of(SOUTH_WEST, new DetourGeometry.Offset(-6, 6),
                new DetourGeometry.Offset(-16, 0), new DetourGeometry.Offset(-8, 0),
                new DetourGeometry.Offset(-11, -11), new DetourGeometry.Offset(-6, -6)), left);
    }

    @Test
    void theRightSideMirrorsTheLeft() {
        List<DetourGeometry.Offset> right = DetourGeometry.candidates(0, 30, DetourGeometry.RIGHT, 16);
        assertEquals(SOUTH_EAST, right.get(0));
        assertEquals(new DetourGeometry.Offset(16, 0), right.get(2), "straight across: east");
        assertEquals(new DetourGeometry.Offset(11, -11), right.get(4), "slightly back: north-east");
    }

    @Test
    void eachDirectionIsTriedAtAFullStepThenAHalfStep() {
        List<DetourGeometry.Offset> right = DetourGeometry.candidates(30, 0, DetourGeometry.RIGHT, 16);
        assertEquals(6, right.size());
        assertEquals(new DetourGeometry.Offset(11, -11), right.get(0), "goal due east: right turns north-east first");
        assertEquals(new DetourGeometry.Offset(6, -6), right.get(1));
    }

    @Test
    void aWaypointAtTheGoalHasNoCandidates() {
        assertTrue(DetourGeometry.candidates(0, 0, DetourGeometry.LEFT, 16).isEmpty());
    }

    @Test
    void aSpotNearAVisitedOneIsReported() {
        List<int[]> visited = List.of(new int[]{10, 10}, new int[]{40, 16});
        assertTrue(DetourGeometry.nearAny(14, 12, visited, 6));
        assertFalse(DetourGeometry.nearAny(24, 24, visited, 6));
    }

    @Test
    void theBudgetIsTheFactorTimesTheStraightDistance() {
        assertEquals(86.0, DetourGeometry.budget(21.5, 4.0), 1e-9);
    }
}
