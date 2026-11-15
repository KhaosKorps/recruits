package com.talhanation.recruits.khaoskorps.gametest;

import com.talhanation.recruits.Main;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Keeps wild mobs out of the GameTest world, so a test meets only the mobs it spawns itself. The test
 * world keeps its saved chunks between runs and was created with natural mob spawning on, so a
 * skeleton that spawned on an arena at night could shoot a test's recruit into its ravine. Acts only
 * on the GameTest server, never on a development client or server that loads these classes.
 */
@Mod.EventBusSubscriber(modid = Main.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class QuietTestWorld {

    private QuietTestWorld() {
    }

    /**
     * Turns natural mob spawning off when the GameTest server starts.
     *
     * @param event the server that started
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) return;
        GameRules.BooleanValue spawning = event.getServer().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING);
        if (spawning.get()) {
            spawning.set(false, event.getServer());
            Main.LOGGER.info("[KhaosKorps test world] natural mob spawning turned off, so each test meets only the mobs it spawns");
        }
    }

    /**
     * Keeps a hostile mob loaded from the saved world out of it: tests spawn their own mobs fresh, so
     * one that comes back from disk was left by natural spawning in an earlier run.
     *
     * @param event the entity joining a level
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || !(event.getEntity() instanceof Enemy)) return;
        if (!(event.getLevel().getServer() instanceof GameTestServer)) return;
        Main.LOGGER.info("[KhaosKorps test world] removed a {} left in the saved world at {}",
                event.getEntity().getType().getDescriptionId(), event.getEntity().blockPosition().toShortString());
        event.setCanceled(true);
    }
}
