package com.talhanation.recruits.khaoskorps;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Takes a payment in currency out of a player's inventory: the smallest stacks first, each shrunk in
 * its own slot, so the stacks left over stay where the player put them.
 */
public final class CurrencyPayment {

    private CurrencyPayment() {
    }

    /**
     * Takes {@code amount} of {@code currency} from {@code inventory}, emptying its smallest stacks
     * first (the lower slot first between equal stacks), or takes nothing if it holds less than that.
     *
     * @param inventory the inventory to pay from
     * @param currency the item paid with
     * @param amount how many to take; zero or less takes nothing
     * @return true if the amount was taken, false if the inventory held too little and nothing was taken
     */
    public static boolean take(Inventory inventory, Item currency, int amount) {
        if (amount <= 0) return true;
        List<Integer> slots = new ArrayList<>();
        int held = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(currency)) {
                slots.add(i);
                held += stack.getCount();
            }
        }
        if (held < amount) return false;
        slots.sort(Comparator.comparingInt((Integer i) -> inventory.getItem(i).getCount()).thenComparingInt(i -> i));
        int left = amount;
        for (int slot : slots) {
            ItemStack stack = inventory.getItem(slot);
            int taken = Math.min(left, stack.getCount());
            stack.shrink(taken);
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            left -= taken;
            if (left == 0) break;
        }
        inventory.setChanged();
        return true;
    }
}
