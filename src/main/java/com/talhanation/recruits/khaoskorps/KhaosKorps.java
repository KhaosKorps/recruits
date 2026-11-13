package com.talhanation.recruits.khaoskorps;

import net.minecraftforge.common.MinecraftForge;

/**
 * KhaosKorps fork: the one entry point for the fork's own code, so upstream's Main carries a
 * single line for it (Kosta's Q2 rule 2: new behaviour in new classes, reached by one-line hooks).
 */
public final class KhaosKorps {

    private KhaosKorps() {
    }

    /** Called once from Main's constructor. */
    public static void init() {
        KhaosKorpsServerConfig.register();
        MinecraftForge.EVENT_BUS.register(new CreeperExplosionRules());
    }
}
