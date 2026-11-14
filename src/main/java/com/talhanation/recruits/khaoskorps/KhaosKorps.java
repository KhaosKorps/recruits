package com.talhanation.recruits.khaoskorps;

import net.minecraftforge.common.MinecraftForge;

/**
 * The one entry point for the fork's own code, so upstream's Main carries a single line for it.
 * New behaviour lives in this package and reaches upstream code through one-line hooks, which
 * keeps merges from upstream small.
 */
public final class KhaosKorps {

    private KhaosKorps() {
    }

    /** Called once from Main's constructor. */
    public static void init() {
        KhaosKorpsServerConfig.register();
        MinecraftForge.EVENT_BUS.register(new CreeperExplosionRules());
        MinecraftForge.EVENT_BUS.register(DetourRoutes.class);
        MinecraftForge.EVENT_BUS.register(RecruitSpeedSettings.class);
        MinecraftForge.EVENT_BUS.register(ServerTickTasks.class);
    }
}
