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
    public static final ForgeConfigSpec.BooleanValue EdgeMarginOnOrders;
    public static final ForgeConfigSpec.IntValue EdgeMarginBlocks;
    public static final ForgeConfigSpec.DoubleValue EdgeCarefulSpeed;
    public static final ForgeConfigSpec.IntValue DeadEndSearchNodes;
    public static final ForgeConfigSpec.DoubleValue DropCostPerBlock;
    public static final ForgeConfigSpec.DoubleValue UnreachableDepthWeight;
    public static final ForgeConfigSpec.BooleanValue CrossOneAtATime;
    public static final ForgeConfigSpec.BooleanValue WaitWhenOwnerUnreachable;
    public static final ForgeConfigSpec.BooleanValue DetourSearch;
    public static final ForgeConfigSpec.IntValue FailedPlanMemorySeconds;
    public static final ForgeConfigSpec.DoubleValue FailedSearchSameWithin;
    public static final ForgeConfigSpec.DoubleValue MovementSpeedMultiplier;
    public static final ForgeConfigSpec.DoubleValue SwimSpeedMultiplier;
    public static final ForgeConfigSpec.DoubleValue FlowingWaterCost;
    public static final ForgeConfigSpec.BooleanValue PathLog;
    public static final ForgeConfigSpec.IntValue FireCheckTicks;
    public static final ForgeConfigSpec.IntValue TntCheckTicks;
    public static final ForgeConfigSpec.IntValue VillageDefenceCheckTicks;

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
                .comment("Creepers hunt recruits as other monsters do. Applies to creepers that appear after the change.")
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
                .comment("How strongly paths prefer dirt paths. Plain ground costs 2 more per side without a dirt path.",
                        "ORIGINAL = 1.15.2: that cost is added again each time the search looks at a point, so plain ground",
                        "grows dearer and searches spread out and run out of budget early;",
                        "ONCE = the same preference, counted once per point (upstream's fix); searches still spread on open ground;",
                        "OFF = no preference: a step costs what its block costs, and searches head straight for their target.")
                .defineEnum("DirtPathPreference", d.dirtPathPreference());
        KeepWalkingWhileRepathing = builder
                .comment("Keep walking the current path while the next one is calculated on the path thread, instead of stopping",
                        "for it (the stop-and-go). Only matters with UseAsyncPathfinding on.")
                .define("KeepWalkingWhileRepathing", d.keepWalkingWhileRepathing());
        StaggerFollowRepaths = builder
                .comment("Spread followers' new paths across ticks, by entity id, so a group told to follow together doesn't search all at once.")
                .define("StaggerFollowRepaths", d.staggerFollowRepaths());
        EdgeMargin = builder
                .comment("While following, keep paths back from drops deeper than the fall distance, and never take a drop of two",
                        "or more blocks onto a spot beside such a drop. Recruits on horses aren't covered yet. On by default.")
                .define("EdgeMargin", d.edgeMargin());
        EdgeMarginOnOrders = builder
                .comment("With EdgeMargin on, keep move and hold orders' paths (formations included) back from such drops too,",
                        "and stop short of the rim when the ordered spot can't be reached past one. The ordered spot itself may",
                        "lie at an edge, on a wall or a tower: nothing is kept back near it. On by default.")
                .define("EdgeMarginOnOrders", d.edgeMarginOnOrders());
        EdgeMarginBlocks = builder
                .comment("How many blocks back from such a drop the margin reaches.")
                .defineInRange("EdgeMarginBlocks", d.edgeMarginBlocks(), 1, 4);
        EdgeCarefulSpeed = builder
                .comment("With EdgeMargin on, a recruit walking toward a path point right beside such a drop moves at this",
                        "fraction of its speed, so it can't carry over the edge. 1.0 = no slowdown.")
                .defineInRange("EdgeCarefulSpeed", d.edgeCarefulSpeed(), 0.1, 1.0);
        DeadEndSearchNodes = builder
                .comment("With EdgeMargin on, a following recruit won't drop two or more blocks into a dead end: a closed area",
                        "with no way back up, such as a pit, a small island or a ledge below a cliff. Before such a drop, it",
                        "searches the landing area for a way out, up to this many walkable spots; an area that big, or with its",
                        "owner in it, isn't a dead end. 0 turns the check off.")
                .defineInRange("DeadEndSearchNodes", d.deadEndSearchNodes(), 0, 1024);
        DropCostPerBlock = builder
                .comment("With EdgeMargin on, while following: extra search cost for each block of a drop of two or more,",
                        "so paths prefer stairs and slopes and avoid falls they don't need. 0 is off (the default). Works",
                        "with UseAsyncPathfinding on only.")
                .defineInRange("DropCostPerBlock", d.dropCostPerBlock(), 0.0, 16.0);
        WaitWhenOwnerUnreachable = builder
                .comment("When no path reaches the owner and the best one ends at a drop, stop short of the edge and wait",
                        "instead of crowding it. On by default.")
                .define("WaitWhenOwnerUnreachable", d.waitWhenOwnerUnreachable());
        UnreachableDepthWeight = builder
                .comment("With WaitWhenOwnerUnreachable on: where a following recruit's path ends when its owner can't be",
                        "reached, each block below both the recruit's starting height and its owner's counts as this many",
                        "blocks of extra distance, so it waits up on the rim instead of going down into a ravine just because",
                        "the bottom is nearer its owner in a straight line. 0 is off.")
                .defineInRange("UnreachableDepthWeight", d.unreachableDepthWeight(), 0.0, 16.0);
        CrossOneAtATime = builder
                .comment("With EdgeMargin on: a following group crosses a risky narrow spot (a one-block bridge, a one-wide",
                        "ridge, with a deadly drop on both sides) one recruit at a time. The rest wait back on safe ground,",
                        "in the order they came; a recruit stalled on the spot for 10 seconds lets the next go.")
                .define("CrossOneAtATime", d.crossOneAtATime());
        DetourSearch = builder
                .comment("When following recruits have been stuck about 3 seconds with no path to their owner, and the owner",
                        "is on the ground, plan a way round in steps of up to 16 blocks, at most 4 times the straight-line",
                        "distance, through loaded chunks only. One plan per owner, shared by its followers. On by default.")
                .define("DetourSearch", d.detourSearch());
        FailedPlanMemorySeconds = builder
                .comment("How long a failed detour plan is remembered, in seconds, before the recruits following that owner may",
                        "plan again from the same place. A recruit stuck more than three times FailedSearchSameWithin from where",
                        "the failed plan started plans at once; so does any after the owner moves 8 blocks or a player places",
                        "or breaks a block near the ground the plan searched. 10 = as before this setting.")
                .defineInRange("FailedPlanMemorySeconds", d.failedPlanMemoryTicks() / 20, 10, 1800);
        FailedSearchSameWithin = builder
                .comment("How near, in blocks, a recruit must be stuck to where a failed detour plan started, divided by three, for",
                        "that failure to count for it: with 2.0, a recruit stuck more than 6 blocks from it plans at once.")
                .defineInRange("FailedSearchSameWithin", d.failedSearchSameWithin(), 0.5, 16.0);
        MovementSpeedMultiplier = builder
                .comment("Multiplies every recruit's movement speed. 1.0 = unchanged. Never saved: a recruit's own speed stays as it was.")
                .defineInRange("MovementSpeedMultiplier", d.movementSpeedMultiplier(), 0.1, 3.0);
        SwimSpeedMultiplier = builder
                .comment("Multiplies every recruit's swimming speed. 1.0 = unchanged. Never saved: a recruit's own speed stays as it was.",
                        "Recruits swim at 0.3, under a third of a normal mob's 1.0. Measured against a full current in a shallow stream:",
                        "x8 just holds its place, x10 gains about 1.6 blocks every 5 seconds.")
                .defineInRange("SwimSpeedMultiplier", d.swimSpeedMultiplier(), 0.1, 20.0);
        FlowingWaterCost = builder
                .comment("Extra search cost of a path point in flowing water, on top of the 128 any water costs, so recruits",
                        "cross still water rather than a current when they can. 0 = flowing water costs what still water does.",
                        "With EdgeMargin on, a following recruit never paths through any water within the edge margin of a deadly drop.")
                .defineInRange("FlowingWaterCost", d.flowingWaterCost(), 0.0, 512.0);
        PathLog = builder
                .comment("Write the path measurement log to logs/recruits-paths/ in the server folder: one CSV row per path search, one per",
                        "following recruit every 100 ticks, and one per settings change, in three files named by the time logging",
                        "started. The server log gets one line when it starts and one when it stops. Applies when this file is saved.",
                        "Off by default.")
                .define("PathLog", d.pathLog());
        builder.pop();

        builder.comment("How often each recruit runs its always-on checks, in ticks (20 = 1 second), staggered by recruit so",
                "a crowd doesn't check all at once. 2 = every time the check's goal runs, as in Recruits 1.15.2. Longer",
                "means less server work per recruit and a slower reaction. Each applies as soon as this file is saved.").push("checks");
        FireCheckTicks = builder
                .comment("Ticks between a recruit's checks for nearby fire and lava to flee from. Once it finds some, it checks",
                        "every time until none is left, so it keeps moving away.")
                .defineInRange("FireCheckTicks", d.fireCheckTicks(), 2, 200);
        TntCheckTicks = builder
                .comment("Ticks between a recruit's checks for lit TNT to flee from. Once it finds some, it checks every time",
                        "until none is left, so it keeps moving away.")
                .defineInRange("TntCheckTicks", d.tntCheckTicks(), 2, 200);
        VillageDefenceCheckTicks = builder
                .comment("Ticks between a recruit's checks for players that villagers on its side near it hate (reputation -100",
                        "or lower), to make one its target.")
                .defineInRange("VillageDefenceCheckTicks", d.villageDefenceCheckTicks(), 2, 200);
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
                EdgeMargin.get(), EdgeMarginOnOrders.get(), EdgeMarginBlocks.get(), EdgeCarefulSpeed.get(), DeadEndSearchNodes.get(), DropCostPerBlock.get(),
                WaitWhenOwnerUnreachable.get(), UnreachableDepthWeight.get(), CrossOneAtATime.get(), DetourSearch.get(),
                FailedPlanMemorySeconds.get() * 20, FailedSearchSameWithin.get(),
                MovementSpeedMultiplier.get(), SwimSpeedMultiplier.get(), FlowingWaterCost.get(),
                CreepersTargetRecruits.get(),
                FireCheckTicks.get(), TntCheckTicks.get(), VillageDefenceCheckTicks.get(),
                PathLog.get());
    }
}
