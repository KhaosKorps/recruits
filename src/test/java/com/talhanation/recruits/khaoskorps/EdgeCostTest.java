package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EdgeCostTest {

    @Test
    void aPointBesideADropCostsTheFullAmount() {
        assertEquals(EdgeCost.BESIDE_A_DROP, EdgeCost.malus(1, 2), 0.0F);
    }

    @Test
    void theCostFallsOffEvenlyToTheEdgeOfTheMargin() {
        assertEquals(EdgeCost.BESIDE_A_DROP / 2, EdgeCost.malus(2, 2), 0.0F);
        assertEquals(EdgeCost.BESIDE_A_DROP / 4, EdgeCost.malus(4, 4), 0.0F);
    }

    @Test
    void noDropWithinTheMarginCostsNothing() {
        assertEquals(0.0F, EdgeCost.malus(EdgeProbe.NONE, 2), 0.0F);
        assertEquals(0.0F, EdgeCost.malus(3, 2), 0.0F);
        assertEquals(0.0F, EdgeCost.malus(1, 0), 0.0F, "a margin of 0 is edge care turned off");
    }
}
