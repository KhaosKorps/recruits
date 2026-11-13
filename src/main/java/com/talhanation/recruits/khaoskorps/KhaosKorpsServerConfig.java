package com.talhanation.recruits.khaoskorps;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * KhaosKorps fork: the fork's own server settings, in their own file so upstream's
 * recruits-server.toml never collides with them (Kosta's Q2 rule 1, approved by the user,
 * DRFT-261113-Recruits_Follow_Pathfinding_Settings_And_Measurement section 5, answer 1).
 *
 * The file is <world>/serverconfig/recruits-khaoskorps-server.toml.
 */
public final class KhaosKorpsServerConfig {

    public static final String FILE_NAME = "recruits-khaoskorps-server.toml";

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue PreventCreeperBlockDamage;
    public static final ForgeConfigSpec.BooleanValue PreventCreeperDamage;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Creeper explosions. Both settings apply as soon as this file is saved.").push("creepers");
        PreventCreeperBlockDamage = builder
                .comment("Creeper explosions don't break blocks.",
                        "They still hurt and knock back players, recruits and animals unless PreventCreeperDamage is also true.")
                .define("PreventCreeperBlockDamage", true);
        PreventCreeperDamage = builder
                .comment("Creeper explosions don't hurt or knock back anything: players, recruits, animals or other entities.")
                .define("PreventCreeperDamage", false);
        builder.pop();

        SPEC = builder.build();
    }

    private KhaosKorpsServerConfig() {
    }

    /** Called once from Main's constructor. */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, FILE_NAME);
    }
}
