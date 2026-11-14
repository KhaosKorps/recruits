package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Following group redirects to their end, and breaking redirect loops, on plain maps. */
class GroupRedirectsTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void resolveReturnsTheGroupItselfWhenNothingRedirectsIt() {
        AtomicInteger loops = new AtomicInteger();
        assertEquals(a, GroupRedirects.resolve(new HashMap<>(), a, loops::incrementAndGet));
        assertEquals(0, loops.get(), "no loop reported");
    }

    @Test
    void resolveFollowsAChainToItsEnd() {
        Map<UUID, UUID> redirects = new HashMap<>(Map.of(a, b, b, c));
        AtomicInteger loops = new AtomicInteger();
        assertEquals(c, GroupRedirects.resolve(redirects, a, loops::incrementAndGet));
        assertEquals(0, loops.get(), "no loop reported");
    }

    @Test
    void resolveReportsASelfRedirectOnceAndReturnsTheStart() {
        Map<UUID, UUID> redirects = new HashMap<>(Map.of(a, a));
        AtomicInteger loops = new AtomicInteger();
        assertEquals(a, GroupRedirects.resolve(redirects, a, loops::incrementAndGet));
        assertEquals(1, loops.get(), "the loop is reported once");
    }

    @Test
    void resolveReportsATwoGroupLoopAndReturnsTheStart() {
        Map<UUID, UUID> redirects = new HashMap<>(Map.of(a, b, b, a));
        AtomicInteger loops = new AtomicInteger();
        assertEquals(a, GroupRedirects.resolve(redirects, a, loops::incrementAndGet));
        assertEquals(1, loops.get(), "the loop is reported once");
    }

    @Test
    void breakLoopsRemovesASelfRedirect() {
        Map<UUID, UUID> redirects = new HashMap<>(Map.of(a, a, b, c));
        assertEquals(1, GroupRedirects.breakLoops(redirects));
        assertFalse(redirects.containsKey(a), "the self-redirect is gone");
        assertEquals(c, redirects.get(b), "the loop-free redirect is kept");
    }

    @Test
    void breakLoopsBreaksATwoGroupLoopAndKeepsTheChainIntoIt() {
        Map<UUID, UUID> redirects = new HashMap<>(Map.of(c, a, a, b, b, a));
        assertEquals(1, GroupRedirects.breakLoops(redirects), "one redirect breaks the loop");
        assertTrue(redirects.containsKey(c), "the chain leading into the loop is kept");
        AtomicInteger loops = new AtomicInteger();
        UUID end = GroupRedirects.resolve(redirects, c, loops::incrementAndGet);
        assertEquals(0, loops.get(), "no loop left");
        assertTrue(end.equals(a) || end.equals(b), "the chain now ends at one of the former loop's groups");
    }

    @Test
    void breakLoopsLeavesLoopFreeRedirectsAlone() {
        Map<UUID, UUID> redirects = new HashMap<>(Map.of(a, b, b, c));
        assertEquals(0, GroupRedirects.breakLoops(redirects));
        assertEquals(Map.of(a, b, b, c), redirects);
    }
}
