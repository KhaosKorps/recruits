package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossingRegistryTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final CrossingRegistry.Spot SPOT = CrossingRegistry.Spot.of(OWNER, 20L, 10L);
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    private final Map<UUID, CrossingRegistry.Status> where = new HashMap<>();
    private final CrossingRegistry registry = new CrossingRegistry();

    private void tick(long now) {
        this.registry.tick(now, (holder, spot) -> this.where.getOrDefault(holder, CrossingRegistry.Status.GONE));
    }

    private static CrossingRegistry.Status approaching(double distance) {
        return new CrossingRegistry.Status(true, false, 0, 0, 0, distance);
    }

    private static CrossingRegistry.Status on(double x) {
        return new CrossingRegistry.Status(true, true, x, 0, 0, 0);
    }

    private static CrossingRegistry.Status off(double x) {
        return new CrossingRegistry.Status(true, false, x, 0, 0, 5);
    }

    @Test
    void aSpotIsNamedByItsEndsInEitherOrder() {
        assertEquals(CrossingRegistry.Spot.of(OWNER, 10L, 20L), SPOT);
    }

    @Test
    void theFirstToAskHoldsTheSpotAndTheNextWaits() {
        assertTrue(this.registry.request(SPOT, this.a, 0, 5));
        assertFalse(this.registry.request(SPOT, this.b, 0, 6));
        assertTrue(this.registry.request(SPOT, this.a, 5, 5), "the holder keeps it");
        assertEquals(this.a, this.registry.holder(SPOT));
        assertEquals(1, this.registry.queued(SPOT));
    }

    @Test
    void onceTheHolderHasCrossedTheFirstInLineGetsItAndNotOneWhoAskedLater() {
        this.registry.request(SPOT, this.a, 0, 5);
        this.registry.request(SPOT, this.b, 1, 6);
        this.registry.request(SPOT, this.c, 2, 7);
        this.where.put(this.a, on(0));
        this.tick(10);
        this.where.put(this.a, off(6));
        this.tick(40);
        assertNull(this.registry.holder(SPOT), "the holder left the spot after being on it");
        assertFalse(this.registry.request(SPOT, this.c, 41, 7), "c asked after b, so b goes first");
        assertTrue(this.registry.request(SPOT, this.b, 42, 6));
    }

    @Test
    void aHolderThatMakesNoProgressForTenSecondsLosesTheSpotAndQueuesAgain() {
        this.registry.request(SPOT, this.a, 0, 5);
        this.registry.request(SPOT, this.b, 0, 6);
        this.where.put(this.a, on(0));
        for (long now = 1; now <= CrossingRegistry.STALL_TICKS; now += 10) {
            this.tick(now);
            this.registry.request(SPOT, this.b, now, 6);
        }
        assertEquals(this.a, this.registry.holder(SPOT), "still within ten seconds");
        this.tick(CrossingRegistry.STALL_TICKS + 2);
        assertNull(this.registry.holder(SPOT));
        assertFalse(this.registry.request(SPOT, this.a, CrossingRegistry.STALL_TICKS + 3, 0), "the stalled holder queues behind b");
        assertTrue(this.registry.request(SPOT, this.b, CrossingRegistry.STALL_TICKS + 4, 6));
    }

    @Test
    void aHolderMakingProgressKeepsTheSpotLongerThanTenSeconds() {
        this.registry.request(SPOT, this.a, 0, 30);
        for (long now = 1; now <= 3 * CrossingRegistry.STALL_TICKS; now++) {
            this.where.put(this.a, approaching(30 - now / 20.0));
            this.tick(now);
        }
        assertEquals(this.a, this.registry.holder(SPOT));
    }

    @Test
    void aHolderThatStopsFollowingLosesTheSpot() {
        this.registry.request(SPOT, this.a, 0, 5);
        this.where.put(this.a, CrossingRegistry.Status.GONE);
        this.tick(1);
        assertNull(this.registry.holder(SPOT));
    }

    @Test
    void aQueuedRecruitThatStopsAskingIsForgotten() {
        this.registry.request(SPOT, this.a, 0, 5);
        this.registry.request(SPOT, this.b, 0, 6);
        this.where.put(this.a, CrossingRegistry.Status.GONE);
        this.tick(CrossingRegistry.FORGET_TICKS + 1);
        assertTrue(this.registry.request(SPOT, this.c, CrossingRegistry.FORGET_TICKS + 2, 7), "b stopped asking, so c needn't wait for it");
    }
}
