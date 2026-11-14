package com.talhanation.recruits.khaoskorps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Safe handling of group merge redirects.
 *
 * When a group is merged into another, Recruits stores a redirect from the old group's ID to the
 * new one and saves it. At the 1.15.2 release a group could be merged into itself, which saves a
 * redirect from a group to itself, and the release's lookup then loops forever on the server
 * thread. The merge itself is guarded now, but such a redirect may already be on disk.
 *
 * Plain Java with no Minecraft types, so it is covered by JUnit.
 */
public final class GroupRedirects {

    private GroupRedirects() {
    }

    /**
     * Follows redirects from {@code start} to the group they end at.
     *
     * A chain without a loop takes at most {@code redirects.size()} hops, so needing more proves
     * a loop. Then {@code onLoop} runs once and {@code start} is returned. The check costs one
     * counter, and nothing is allocated.
     */
    public static UUID resolve(Map<UUID, UUID> redirects, UUID start, Runnable onLoop) {
        UUID current = start;
        int limit = redirects.size();
        for (int hops = 0; hops <= limit; hops++) {
            UUID next = redirects.get(current);
            if (next == null) return current;
            current = next;
        }
        onLoop.run();
        return start;
    }

    /**
     * Removes every redirect that sits on a loop, and returns how many were removed.
     *
     * For each loop, the first redirect found on it is removed, which breaks the loop. A chain
     * that only leads into a loop keeps its redirects, so after the loop is broken it resolves
     * to a group that ends it. Meant to run once, when the saved redirects are loaded.
     */
    public static int breakLoops(Map<UUID, UUID> redirects) {
        int removed = 0;
        for (UUID start : new ArrayList<>(redirects.keySet())) {
            Set<UUID> seen = new HashSet<>();
            UUID current = start;
            while (current != null && redirects.containsKey(current)) {
                if (!seen.add(current)) {
                    redirects.remove(current);
                    removed++;
                    break;
                }
                current = redirects.get(current);
            }
        }
        return removed;
    }
}
