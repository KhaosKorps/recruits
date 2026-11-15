package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.pathfinding.AsyncPathNavigation;
import net.minecraft.world.entity.Mob;

/**
 * Puts edge care on the navigation of a recruit carrying out a move or hold order, and takes it off when the
 * order's goal stops, as the follow goal does while following (EdgeMargin and EdgeMarginOnOrders).
 */
public final class OrderEdgeCare {

    private OrderEdgeCare() {
    }

    /**
     * Before an order's goal asks for a path: edge care for orders when EdgeMargin and EdgeMarginOnOrders are on,
     * none otherwise.
     *
     * @param mob the recruit carrying out the order
     */
    public static void apply(Mob mob) {
        if (mob.getNavigation() instanceof AsyncPathNavigation navigation) {
            FollowSettings settings = FollowSettings.current();
            navigation.setEdgeCare(settings.edgeMargin() && settings.edgeMarginOnOrders() ? EdgeCare.forOrders(settings) : null);
        }
    }

    /**
     * When an order's goal stops: no edge care left on the navigation for whatever steers next.
     *
     * @param mob the recruit whose order ended
     */
    public static void clear(Mob mob) {
        if (mob.getNavigation() instanceof AsyncPathNavigation navigation) navigation.setEdgeCare(null);
    }
}
