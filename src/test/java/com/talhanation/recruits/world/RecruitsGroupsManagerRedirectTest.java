package com.talhanation.recruits.world;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * A group redirect saved by 1.15.2 can point a group at itself, and 1.15.2's resolveGroup then
 * loops forever on the server thread. These run the real
 * manager; each lookup must return within a second.
 */
class RecruitsGroupsManagerRedirectTest {

    private static final Duration LIMIT = Duration.ofSeconds(1);

    @Test
    void resolveGroupReturnsForAGroupRedirectedToItself() {
        RecruitsGroupsManager manager = new RecruitsGroupsManager();
        UUID group = UUID.randomUUID();
        manager.addRedirect(group, group);

        UUID resolved = assertTimeoutPreemptively(LIMIT, () -> manager.resolveGroup(group),
                "resolveGroup must return for a group redirected to itself");
        assertEquals(group, resolved);
    }

    @Test
    void resolveGroupReturnsForTwoGroupsRedirectedToEachOther() {
        RecruitsGroupsManager manager = new RecruitsGroupsManager();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        manager.addRedirect(a, b);
        manager.addRedirect(b, a);

        assertTimeoutPreemptively(LIMIT, () -> manager.resolveGroup(a),
                "resolveGroup must return for two groups redirected to each other");
    }

    @Test
    void resolveGroupFollowsAChainOfMergesToTheLastGroup() {
        RecruitsGroupsManager manager = new RecruitsGroupsManager();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        manager.addRedirect(a, b);
        manager.addRedirect(b, c);

        assertEquals(c, assertTimeoutPreemptively(LIMIT, () -> manager.resolveGroup(a)),
                "a group merged into one that was later merged again resolves to the last group");
    }
}
