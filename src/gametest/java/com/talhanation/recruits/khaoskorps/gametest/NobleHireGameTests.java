package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.VillagerNobleEntity;
import com.talhanation.recruits.init.ModEntityTypes;
import com.talhanation.recruits.network.MessageDisbandGroup;
import com.talhanation.recruits.network.MessageHireFromNobleVillager;
import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsHireTrade;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static com.talhanation.recruits.khaoskorps.gametest.GameTestSupport.*;

/**
 * Hiring from a noble villager is decided on the server: it charges the trade's own price, uses up
 * the trade, and needs a villager to convert whenever the server config says so, whatever the
 * client claims.
 *
 * The trade's price is 7 and a plain recruit's own cost is 4 (RecruitCost's default), so the
 * price charged shows which one the server used. Emerald is RecruitCurrency's default.
 */
@GameTestHolder(Main.MOD_ID)
@PrefixGameTestTemplate(false)
public class NobleHireGameTests {

    private static final int TRADE_PRICE = 7;

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aNobleHireChargesTheTradesPrice(GameTestHelper helper) {
        Scenario s = new Scenario(helper, 20, 2, true);
        s.hire(true);
        int left = s.player.getInventory().countItem(Items.EMERALD);
        int hired = s.hiredRecruits().size();
        s.cleanUp();
        if (hired != 1) throw new GameTestAssertException("expected 1 hired recruit, found " + hired);
        if (left != 20 - TRADE_PRICE) {
            throw new GameTestAssertException("the noble's price is " + TRADE_PRICE + ", but the player paid " + (20 - left)
                    + " (a recruit's own cost is " + RecruitsServerConfig.RecruitCost.get() + ")");
        }
        helper.succeed();
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aFailedNobleHireLeavesTheNoblesOfferUnused(GameTestHelper helper) {
        Scenario s = new Scenario(helper, 0, 2, true);
        s.hire(true);
        int hired = s.hiredRecruits().size();
        int uses = s.usesLeft();
        s.cleanUp();
        if (hired != 0) throw new GameTestAssertException("a player with no currency hired " + hired + " recruit(s)");
        if (uses != 2) throw new GameTestAssertException("a hire that failed used up the noble's offer: uses went from 2 to " + uses);
        helper.succeed();
    }

    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void theServerConfigNotTheClientDecidesWhetherANobleHireNeedsAVillager(GameTestHelper helper) {
        if (!RecruitsServerConfig.NobleVillagerNeedsVillagers.get()) {
            throw new GameTestAssertException("precondition failed: NobleVillagerNeedsVillagers is true (its default)");
        }
        Scenario s = new Scenario(helper, 20, 2, false);
        s.hire(false); // the client claims no villager is needed
        int hired = s.hiredRecruits().size();
        int left = s.player.getInventory().countItem(Items.EMERALD);
        s.cleanUp();
        if (hired != 0 || left != 20) {
            throw new GameTestAssertException("the server needs a villager, but a request without one hired " + hired
                    + " recruit(s) and took " + (20 - left) + " emeralds");
        }
        helper.succeed();
    }

    /**
     * A noble hire's recruit is still its hirer's after it has lived for 100 ticks: owned by the player,
     * at the noble's price. The other tests look only at the tick of the hire, before the recruit has
     * ticked once, so they can't see anything that dismisses it afterwards.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 140)
    public static void aNobleHiresRecruitIsStillHiredAfterItHasTicked(GameTestHelper helper) {
        Scenario s = new Scenario(helper, 20, 2, true);
        s.hire(true);
        List<AbstractRecruitEntity> hired = s.hiredRecruits();
        if (hired.size() != 1) {
            s.cleanUp();
            throw new GameTestAssertException("setup: expected 1 hired recruit, found " + hired.size());
        }
        AbstractRecruitEntity recruit = hired.get(0);
        helper.runAfterDelay(100, () -> {
            boolean owned = recruit.isOwned();
            UUID owner = recruit.getOwnerUUID();
            int cost = recruit.getCost();
            boolean mine = s.player.getUUID().equals(owner);
            recruit.discard();
            s.cleanUp();
            if (!owned || !mine || cost != TRADE_PRICE) {
                throw new GameTestAssertException("100 ticks after the noble hire, the recruit is owned " + owned
                        + ", by the hirer " + mine + ", costing " + cost + " (the noble charged " + TRADE_PRICE + ")");
            }
            helper.succeed();
        });
    }

    /**
     * A noble hire into a group the player disbanded earlier is still hired 100 ticks later. Disbanding
     * dismisses the recruits in the group at that moment; the group stays in the player's list, so a
     * later hire can choose it.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 140)
    public static void aNobleHireIntoAGroupDisbandedEarlierStaysHired(GameTestHelper helper) {
        Scenario s = new Scenario(helper, 20, 2, true);
        new MessageDisbandGroup(s.player.getUUID(), s.group.getUUID(), false).executeServerSide(contextFrom(s.player));
        s.hire(true);
        List<AbstractRecruitEntity> hired = s.hiredRecruits();
        if (hired.size() != 1) {
            s.cleanUp();
            throw new GameTestAssertException("setup: expected 1 hired recruit, found " + hired.size());
        }
        AbstractRecruitEntity recruit = hired.get(0);
        helper.runAfterDelay(100, () -> {
            boolean owned = recruit.isOwned();
            int cost = recruit.getCost();
            recruit.discard();
            s.cleanUp();
            if (!owned || cost != TRADE_PRICE) {
                throw new GameTestAssertException("a noble hire into a group disbanded earlier was dismissed: owned " + owned
                        + ", costing " + cost + " after the noble charged " + TRADE_PRICE);
            }
            helper.succeed();
        });
    }

    /** A recruit that is in a group when the group is disbanded is dismissed. */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 60)
    public static void aRecruitInAGroupWhenItIsDisbandedIsDismissed(GameTestHelper helper) {
        Scenario s = new Scenario(helper, 20, 2, false);
        AbstractRecruitEntity recruit = helper.spawn(ModEntityTypes.RECRUIT.get(), 4, 2, 2);
        if (!recruit.hire(s.player, s.group, false)) {
            recruit.discard();
            s.cleanUp();
            throw new GameTestAssertException("setup: the recruit could not be hired");
        }
        helper.runAfterDelay(5, () -> new MessageDisbandGroup(s.player.getUUID(), s.group.getUUID(), false)
                .executeServerSide(contextFrom(s.player)));
        helper.runAfterDelay(40, () -> {
            boolean owned = recruit.isOwned();
            recruit.discard();
            s.cleanUp();
            if (owned) throw new GameTestAssertException("a recruit in the group when it was disbanded is still hired");
            helper.succeed();
        });
    }

    /**
     * Paying a noble takes the price from the smallest stacks of currency first and shrinks them where
     * they lie: stacks of 10, 3 and 64 paying 7 leave 6, nothing and 64 in the same slots.
     */
    @GameTest(template = HarnessGameTests.EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void aNobleHireTakesTheSmallestStacksFirstAndMovesNone(GameTestHelper helper) {
        Scenario s = new Scenario(helper, 0, 2, true);
        s.player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 10));
        s.player.getInventory().setItem(5, new ItemStack(Items.EMERALD, 3));
        s.player.getInventory().setItem(8, new ItemStack(Items.EMERALD, 64));
        s.hire(true);
        int hired = s.hiredRecruits().size();
        StringBuilder emeralds = new StringBuilder();
        for (int i = 0; i < s.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = s.player.getInventory().getItem(i);
            if (stack.is(Items.EMERALD)) emeralds.append(" slot ").append(i).append(": ").append(stack.getCount());
        }
        s.cleanUp();
        String expected = " slot 0: 6 slot 8: 64";
        if (hired != 1) throw new GameTestAssertException("setup: expected 1 hired recruit, found " + hired);
        if (!emeralds.toString().equals(expected)) {
            throw new GameTestAssertException("expected emeralds" + expected + " after paying 7 from 10, 3 and 64 in slots 0, 5 and 8; found" + emeralds);
        }
        helper.succeed();
    }

    /** A player with a group and some emeralds, a noble with one recruit trade, and optionally a villager to convert. */
    private static final class Scenario {
        final GameTestHelper helper;
        final ServerPlayer player;
        final RecruitsGroup group;
        final VillagerNobleEntity noble;
        final RecruitsHireTrade trade;
        final Villager villager;

        Scenario(GameTestHelper helper, int emeralds, int uses, boolean withVillager) {
            this.helper = helper;
            walls(helper);
            ground(helper, CORRIDOR_X_MIN, CORRIDOR_X_MAX, 2);
            player = owner(helper, 3.5, 2, 2.5);
            if (emeralds > 0) player.getInventory().add(new ItemStack(Items.EMERALD, emeralds));
            group = new RecruitsGroup("kk-test-group", player, 0);
            RecruitEvents.recruitsGroupsManager.addOrUpdateGroup(helper.getLevel(), player, group);
            noble = helper.spawn(ModEntityTypes.VILLAGER_NOBLE.get(), 6, 2, 2);
            trade = new RecruitsHireTrade(EntityType.getKey(ModEntityTypes.RECRUIT.get()), TRADE_PRICE);
            trade.maxUses = uses;
            trade.uses = uses;
            noble.setTrades(new java.util.ArrayList<>(List.of(trade)));
            villager = withVillager ? helper.spawn(EntityType.VILLAGER, 8, 2, 2) : null;
        }

        void hire(boolean clientSaysVillagerNeeded) {
            UUID villagerId = villager != null ? villager.getUUID() : UUID.randomUUID();
            new MessageHireFromNobleVillager(noble.getUUID(), villagerId, trade, group, clientSaysVillagerNeeded, false)
                    .executeServerSide(contextFrom(player));
        }

        /** The noble's own count: its trades live in its synced data, and getTrades() returns fresh copies. */
        int usesLeft() {
            for (RecruitsHireTrade t : noble.getTrades()) {
                if (t.resourceLocation.equals(trade.resourceLocation)) return t.uses;
            }
            throw new GameTestAssertException("the noble no longer offers " + trade.resourceLocation);
        }

        List<AbstractRecruitEntity> hiredRecruits() {
            AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(24);
            return helper.getLevel().getEntitiesOfClass(AbstractRecruitEntity.class, area,
                    r -> player.getUUID().equals(r.getOwnerUUID()));
        }

        void cleanUp() {
            hiredRecruits().forEach(AbstractRecruitEntity::discard);
            noble.discard();
            if (villager != null) villager.discard();
            RecruitEvents.recruitsGroupsManager.removeGroup(group.getUUID());
            removeOwner(helper, player);
        }
    }
}
