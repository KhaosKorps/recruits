package com.talhanation.recruits.khaoskorps;

import com.talhanation.recruits.Main;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * The fork's own server settings, in their own file so upstream's recruits-server.toml never
 * collides with them.
 *
 * The file is <world>/serverconfig/recruits-khaoskorps-server.toml. Pathfinding values are
 * copied into an immutable {@link FollowSettings} snapshot whenever the file loads or is saved,
 * because the path worker thread must not read these config objects.
 */
public final class KhaosKorpsServerConfig {

    public static final String FILE_NAME = "recruits-khaoskorps-server.toml";

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue PreventCreeperBlockDamage;
    public static final ForgeConfigSpec.BooleanValue PreventCreeperDamage;
    public static final ForgeConfigSpec.BooleanValue CreepersTargetRecruits;

    public static final ForgeConfigSpec.IntValue MaxFallDistance;
    public static final ForgeConfigSpec.IntValue MountedMaxFallDistance;
    public static final ForgeConfigSpec.DoubleValue FollowStartDistance;
    public static final ForgeConfigSpec.DoubleValue FollowStopDistance;
    public static final ForgeConfigSpec.IntValue FollowRepathTicks;
    public static final ForgeConfigSpec.IntValue FollowRepathTicksRiding;
    public static final ForgeConfigSpec.IntValue FollowStartCheckTicks;
    public static final ForgeConfigSpec.DoubleValue FollowSpeed;
    public static final ForgeConfigSpec.IntValue FollowMaxPathLength;
    public static final ForgeConfigSpec.IntValue SearchNodesPerBlock;
    public static final ForgeConfigSpec.EnumValue<FollowSettings.DirtPathPreference> DirtPathPreference;
    public static final ForgeConfigSpec.BooleanValue KeepWalkingWhileRepathing;
    public static final ForgeConfigSpec.BooleanValue StaggerFollowRepaths;
    public static final ForgeConfigSpec.BooleanValue EdgeMargin;
    public static final ForgeConfigSpec.IntValue EdgeMarginBlocks;
    public static final ForgeConfigSpec.DoubleValue EdgeCarefulSpeed;
    public static final ForgeConfigSpec.BooleanValue WaitWhenOwnerUnreachable;
    public static final ForgeConfigSpec.BooleanValue DetourSearch;
    public static final ForgeConfigSpec.DoubleValue MovementSpeedMultiplier;
    public static final ForgeConfigSpec.DoubleValue SwimSpeedMultiplier;
    public static final ForgeConfigSpec.BooleanValue PathLog;

    static {
        FollowSettings d = FollowSettings.DEFAULTS;
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Creepers. Each setting in use applies as soon as this file is saved.").push("creepers");
        PreventCreeperBlockDamage = builder
                .comment("Creeper explosions don't break blocks.",
                        "They still hurt and knock back players, recruits and animals unless PreventCreeperDamage is also true.")
                .define("PreventCreeperBlockDamage", true);
        PreventCreeperDamage = builder
                .comment("Creeper explosions don't hurt or knock back anything: players, recruits, animals or other entities.")
                .define("PreventCreeperDamage", false);
        CreepersTargetRecruits = builder
                .comment("Creepers hunt recruits as other monsters do. Applies to creepers that appear after the change.", "NOT USED YET: changing it has no effect in this build.")
                .define("CreepersTargetRecruits", d.creepersTargetRecruits());
        builder.pop();

        builder.comment("Following and pathfinding. Defaults reproduce Recruits 1.15.2, except the edge care settings",
                "(EdgeMargin, EdgeCarefulSpeed, WaitWhenOwnerUnreachable) and DetourSearch. Edge care applies while",
                "following only, never to hold or move orders. Each setting in use applies as soon as this file is saved.").push("pathfinding");
        MaxFallDistance = builder
                .comment("Highest drop, in blocks, a recruit on foot will path down. 1 in Recruits 1.15.2; vanilla mobs use 3.",
                        "Overrides each recruit's own saved value.")
                .defineInRange("MaxFallDistance", d.maxFallDistance(), 0, 16);
        MountedMaxFallDistance = builder
                .comment("Highest drop, in blocks, a horse ridden by a recruit will path down. 1 in Recruits 1.15.2.",
                        "A horse takes no fall damage from drops of up to 6 blocks.")
                .defineInRange("MountedMaxFallDistance", d.mountedMaxFallDistance(), 0, 16);
        FollowStartDistance = builder
                .comment("Distance, in blocks, beyond which a recruit in Follow Me starts walking to its owner. 3.0 in 1.15.2.")
                .defineInRange("FollowStartDistance", Math.sqrt(d.followStartDistanceSqr()), 1.0, 32.0);
        FollowStopDistance = builder
                .comment("Distance, in blocks, at which a following recruit stops. About 2.65 in 1.15.2.")
                .defineInRange("FollowStopDistance", Math.sqrt(d.followStopDistanceSqr()), 0.5, 32.0);
        FollowRepathTicks = builder
                .comment("Ticks between new paths while following on foot. 10 in 1.15.2 (20 ticks = 1 second).")
                .defineInRange("FollowRepathTicks", d.followRepathTicks(), 1, 200);
        FollowRepathTicksRiding = builder
                .comment("Ticks between new paths while following mounted. 5 in 1.15.2.")
                .defineInRange("FollowRepathTicksRiding", d.followRepathTicksRiding(), 1, 200);
        FollowStartCheckTicks = builder
                .comment("Ticks between checks of whether to start following. 10 in 1.15.2.")
                .defineInRange("FollowStartCheckTicks", d.followStartCheckTicks(), 1, 200);
        FollowSpeed = builder
                .comment("Speed while following, as a multiple of the recruit's own speed. 1.05 in 1.15.2.")
                .defineInRange("FollowSpeed", d.followSpeed(), 0.1, 3.0);
        FollowMaxPathLength = builder
                .comment("Longest path, in blocks of walking, a following recruit may take.",
                        "0 = each recruit's own follow range, as in 1.15.2 (32, or 64 for ranged and mounted units).")
                .defineInRange("FollowMaxPathLength", d.followMaxPathLength(), 0, 256);
        SearchNodesPerBlock = builder
                .comment("Search budget: path points a search may examine per block of its range (the follow range,",
                        "or FollowMaxPathLength while following). 16 in 1.15.2.")
                .defineInRange("SearchNodesPerBlock", d.searchNodesPerBlock(), 1, 256);
        DirtPathPreference = builder
                .comment("How strongly paths prefer dirt paths.",
                        "ORIGINAL = 1.15.2, which also makes the search run out of budget early;",
                        "ONCE = the same preference, counted once per path point; OFF = no preference.",
                        "NOT USED YET: changing it has no effect in this build.")
                .defineEnum("DirtPathPreference", d.dirtPathPreference());
        KeepWalkingWhileRepathing = builder
                .comment("Keep walking the current path while the next one is calculated, instead of stopping (the stop-and-go).", "NOT USED YET: changing it has no effect in this build.")
                .define("KeepWalkingWhileRepathing", d.keepWalkingWhileRepathing());
        StaggerFollowRepaths = builder
                .comment("Spread followers' new paths across ticks, so a group doesn't search all at once.", "NOT USED YET: changing it has no effect in this build.")
                .define("StaggerFollowRepaths", d.staggerFollowRepaths());
        EdgeMargin = builder
                .comment("While following, keep paths back from drops deeper than the fall distance, and never take a drop of two",
                        "or more blocks onto a spot beside such a drop. Recruits on horses aren't covered yet. On by default.")
                .define("EdgeMargin", d.edgeMargin());
        EdgeMarginBlocks = builder
                .comment("How many blocks back from such a drop the margin reaches.")
                .defineInRange("EdgeMarginBlocks", d.edgeMarginBlocks(), 1, 4);
        EdgeCarefulSpeed = builder
                .comment("With EdgeMargin on, a recruit walking toward a path point right beside such a drop moves at this",
                        "fraction of its speed, so it can't carry over the edge. 1.0 = no slowdown.")
                .defineInRange("EdgeCarefulSpeed", d.edgeCarefulSpeed(), 0.1, 1.0);
        WaitWhenOwnerUnreachable = builder
                .comment("When no path reaches the owner and the best one ends at a drop, stop short of the edge and wait",
                        "instead of crowding it. On by default.")
                .define("WaitWhenOwnerUnreachable", d.waitWhenOwnerUnreachable());
        DetourSearch = builder
                .comment("When following recruits have been stuck about 3 seconds with no path to their owner, and the owner",
                        "is on the ground, plan a way round in steps of up to 16 blocks, at most 4 times the straight-line",
                        "distance, through loaded chunks only. One plan per owner, shared by its followers. On by default.")
                .define("DetourSearch", d.detourSearch());
        MovementSpeedMultiplier = builder
                .comment("Multiplies every recruit's movement speed. 1.0 = unchanged.", "NOT USED YET: changing it has no effect in this build.")
                .defineInRange("MovementSpeedMultiplier", d.movementSpeedMultiplier(), 0.1, 3.0);
        SwimSpeedMultiplier = builder
                .comment("Multiplies every recruit's swimming speed. 1.0 = unchanged.", "NOT USED YET: changing it has no effect in this build.")
                .defineInRange("SwimSpeedMultiplier", d.swimSpeedMultiplier(), 0.1, 3.0);
        PathLog = builder
                .comment("Write the path measurement log to logs/recruits-paths/. Off by default.", "NOT USED YET: changing it has no effect in this build.")
                .define("PathLog", d.pathLog());
        builder.pop();

        SPEC = builder.build();
    }

    private KhaosKorpsServerConfig() {
    }

    /** Called once from KhaosKorps.init(). */
    static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, FILE_NAME);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(KhaosKorpsServerConfig::onConfigEvent);
    }

    private static void onConfigEvent(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        if (event instanceof ModConfigEvent.Unloading) {
            FollowSettings.publish(FollowSettings.DEFAULTS);
        } else {
            FollowSettings.publish(snapshot());
        }
    }

    /** Republishes the snapshot after a value was changed in code (tests, commands). Server thread only. */
    public static void republish() {
        FollowSettings.publish(snapshot());
    }

    /** Copies the loaded config into an immutable snapshot. Forge calls it on load and on reload, a reload from its file-watcher thread. */
    static FollowSettings snapshot() {
        double start = FollowStartDistance.get();
        double stop = FollowStopDistance.get();
        double stopSqr = FollowSettings.stopDistanceSqrWithinStart(start * start, stop * stop);
        if (stopSqr != stop * stop) {
            Main.LOGGER.warn("[KhaosKorps] {}: FollowStopDistance {} is beyond FollowStartDistance {}; using {}, "
                    + "or a following recruit would start and stop on every check without moving", FILE_NAME, stop, start, start);
        }
        return new FollowSettings(
                MaxFallDistance.get(), MountedMaxFallDistance.get(),
                start * start, stopSqr,
                FollowRepathTicks.get(), FollowRepathTicksRiding.get(), FollowStartCheckTicks.get(),
                FollowSpeed.get(),
                FollowMaxPathLength.get(), SearchNodesPerBlock.get(),
                DirtPathPreference.get(),
                KeepWalkingWhileRepathing.get(), StaggerFollowRepaths.get(),
                EdgeMargin.get(), EdgeMarginBlocks.get(), EdgeCarefulSpeed.get(),
                WaitWhenOwnerUnreachable.get(), DetourSearch.get(),
                MovementSpeedMultiplier.get(), SwimSpeedMultiplier.get(),
                CreepersTargetRecruits.get(),
                PathLog.get());
    }
}
