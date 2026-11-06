package com.trainrecruits;

import net.minecraftforge.common.ForgeConfigSpec;

public class TrainConfig {
    public enum AfterDisembark { HOLD, FOLLOW, WANDER }

    public static final ForgeConfigSpec.BooleanValue VILLAGE_GUNS;
    public static final ForgeConfigSpec.IntValue VILLAGE_GUN_POP;
    public static final ForgeConfigSpec.IntValue VILLAGE_GUN_RETRY_MIN;
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue SEARCH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue TRAIN_RADIUS;
    public static final ForgeConfigSpec.DoubleValue SEAT_REACH;
    public static final ForgeConfigSpec.IntValue BOARDING_SECONDS;
    public static final ForgeConfigSpec.IntValue WALK_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_STOPPED_TRAIN;
    public static final ForgeConfigSpec.BooleanValue RESTORE_LEADER_STATE;
    public static final ForgeConfigSpec.EnumValue<AfterDisembark> AFTER_DISEMBARK;
    public static final ForgeConfigSpec.IntValue COMMANDER_COST_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue GUNNER_COST;
    public static final ForgeConfigSpec.IntValue GUN_BIND_RADIUS;
    public static final ForgeConfigSpec.DoubleValue GUN_OPERATE_REACH;
    public static final ForgeConfigSpec.DoubleValue GUN_MUZZLE_SPEED;
    public static final ForgeConfigSpec.DoubleValue GUN_GRAVITY;
    public static final ForgeConfigSpec.DoubleValue GUN_DRAG;
    public static final ForgeConfigSpec.DoubleValue GUN_SPREAD;
    public static final ForgeConfigSpec.DoubleValue GUN_TURN_SPEED;
    public static final ForgeConfigSpec.IntValue GUN_RELOAD_SECONDS;
    public static final ForgeConfigSpec.IntValue GUN_SHOTS_PER_ORDER;
    public static final ForgeConfigSpec.IntValue GUN_MIN_RANGE;
    public static final ForgeConfigSpec.IntValue GUN_MAX_RANGE;
    public static final ForgeConfigSpec.BooleanValue GUN_AUTO;
    public static final ForgeConfigSpec.BooleanValue GUN_AUTO_FACTIONS;
    public static final ForgeConfigSpec.BooleanValue GUN_AUTO_INFECTION;
    public static final ForgeConfigSpec.BooleanValue GUN_AUTO_BARBARIANS;
    public static final ForgeConfigSpec.IntValue GUN_FRIENDLY_CLEARANCE;
    public static final ForgeConfigSpec.ConfigValue<String> GUN_AMMO;
    public static final ForgeConfigSpec.BooleanValue DARKNESS;
    public static final ForgeConfigSpec.IntValue DARK_LEVEL;
    public static final ForgeConfigSpec.IntValue PITCH_LEVEL;
    public static final ForgeConfigSpec.IntValue DARK_MARGIN;
    public static final ForgeConfigSpec.IntValue DARK_MISS_PERCENT;
    public static final ForgeConfigSpec.BooleanValue SUPPLY;
    public static final ForgeConfigSpec.IntValue SUPPLY_MIN;
    public static final ForgeConfigSpec.IntValue SUPPLY_GOLEMS_PER_FIVE;
    public static final ForgeConfigSpec.IntValue SUPPLY_GOLEM_COOLDOWN;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("stations");
        SEARCH_RADIUS = b.comment("How far from a Boarding Station (blocks) recruits are called from.")
                .defineInRange("searchRadius", 48.0, 4.0, 256.0);
        TRAIN_RADIUS = b.comment("How far from a station (blocks) a train carriage may be to count as 'at the station'.")
                .defineInRange("trainRadius", 12.0, 2.0, 64.0);
        SEAT_REACH = b.comment("Recruit sits down when closer than this (blocks) to the seat it was sent to.")
                .defineInRange("seatReach", 5.0, 1.5, 16.0);
        BOARDING_SECONDS = b.comment("How long a boarding call lasts before giving up (seconds).")
                .defineInRange("boardingSeconds", 60, 5, 600);
        WALK_TIMEOUT_SECONDS = b.comment("If a recruit cannot path to its seat, it is seated anyway after this many seconds (if within 16 blocks).")
                .defineInRange("walkTimeoutSeconds", 15, 3, 120);
        REQUIRE_STOPPED_TRAIN = b.comment("Only board / disembark while the Create train is standing still.")
                .define("requireStoppedTrain", true);
        AFTER_DISEMBARK = b.comment("What recruits do after leaving the train: HOLD (stand at the station), FOLLOW (follow owner), WANDER.")
                .defineEnum("afterDisembark", AfterDisembark.HOLD);
        RESTORE_LEADER_STATE = b.comment("Commanders are PAUSED while riding. If true, their previous patrol state is restored on disembark.")
                .define("restoreLeaderState", false);
        b.pop();
        b.push("commanders");
        COMMANDER_COST_MULTIPLIER = b.comment("Promoting a recruit to commander costs (his hire price x this) in the Recruits currency.")
                .defineInRange("costMultiplier", 2, 0, 50);
        b.pop();
        b.push("artillery");
        GUNNER_COST = b.comment("Mobilising a villager as a gun crew costs this many units of the Recruits currency (0 = free).")
                .defineInRange("gunnerCost", 0, 0, 1000);
        GUN_BIND_RADIUS = b.comment("A crew binds itself to the nearest assembled Create Big Cannons mount within this many blocks.")
                .defineInRange("bindRadius", 24, 4, 96);
        GUN_OPERATE_REACH = b.comment("The crew can work the gun when it stands within this many blocks of the mount.")
                .defineInRange("operateReach", 4.5D, 2.0D, 12.0D);
        GUN_MUZZLE_SPEED = b.comment("Muzzle speed (blocks per tick) of the (endless) shells a crew fires.")
                .defineInRange("muzzleSpeed", 4.0D, 0.5D, 12.0D);
        GUN_GRAVITY = b.comment("Shell gravity per tick (negative) for the aiming calculation. HE shell: -0.05.")
                .defineInRange("gravity", -0.05D, -1.0D, 0.0D);
        GUN_DRAG = b.comment("Shell drag factor for the aiming calculation. HE shell: 0.01.")
                .defineInRange("drag", 0.01D, 0.0D, 0.2D);
        GUN_SPREAD = b.comment("Random shell spread (degrees).")
                .defineInRange("spread", 0.8D, 0.0D, 10.0D);
        GUN_TURN_SPEED = b.comment("How fast the crew turns the gun (degrees per tick).")
                .defineInRange("turnSpeed", 2.5D, 0.2D, 20.0D);
        GUN_RELOAD_SECONDS = b.comment("Seconds between two shots (ammunition itself is endless).")
                .defineInRange("reloadSeconds", 12, 1, 600);
        GUN_SHOTS_PER_ORDER = b.comment("Shots fired at a point you click on the map.")
                .defineInRange("shotsPerOrder", 3, 1, 50);
        GUN_MIN_RANGE = b.comment("Closest target (blocks) a crew will shell.")
                .defineInRange("minRange", 20, 5, 200);
        GUN_MAX_RANGE = b.comment("Farthest target (blocks) a crew will shell.")
                .defineInRange("maxRange", 200, 30, 600);
        GUN_AUTO = b.comment("Crews pick targets themselves (enemy factions, infection nests, barbarian structures) unless switched off on the map.")
                .define("autoTarget", true);
        GUN_AUTO_FACTIONS = b.comment("Auto targets: villages of factions at war with yours.").define("autoEnemyFactions", true);
        GUN_AUTO_INFECTION = b.comment("Auto targets: Flesh That Hates nests (infection zones).").define("autoInfection", true);
        GUN_AUTO_BARBARIANS = b.comment("Auto targets: Barbarian Raids settlements.").define("autoBarbarians", true);
        GUN_FRIENDLY_CLEARANCE = b.comment("Never fire if you, your recruits or villagers of your faction are within this many blocks of the impact point.")
                .defineInRange("friendlyClearance", 24, 0, 96);
        GUN_AMMO = b.comment("Entity type of the shell fired (endless supply).")
                .define("ammo", "createbigcannons:he_shell");
        b.pop();
        b.push("villageArtillery");
        VILLAGE_GUNS = b.comment("Villages (not player factions) build a simple Big Cannons gun and man it with a villager once they are big enough. Needs Create Big Cannons.")
                .define("enabled", true);
        VILLAGE_GUN_POP = b.comment("A village builds guns when recruits + villagers reach this number (then about 10% of the population, rounded up, as posts on hills or towers).")
                .defineInRange("minPopulation", 12, 1, 500);
        VILLAGE_GUN_RETRY_MIN = b.comment("Minutes between attempts to build a gun in the same village.")
                .defineInRange("retryMinutes", 3, 1, 600);
        b.pop();
        b.push("darkness");
        DARKNESS = b.comment("Soldiers (Recruits) get lost in the dark: they flee to light, lose sight of targets, miss and hit at random.")
                .define("enabled", true);
        DARK_LEVEL = b.comment("Light level at or below which it is dark for a soldier. Night in the open is 4, so unlit places at night count.")
                .defineInRange("nightDarkLevel", 4, 0, 14);
        PITCH_LEVEL = b.comment("Light level at or below which it is pitch black (blind, many misses).")
                .defineInRange("pitchLevel", 0, 0, 14);
        DARK_MARGIN = b.comment("In a fight soldiers may step this many blocks out of the light before they retreat.")
                .defineInRange("fightMargin", 6, 1, 24);
        DARK_MISS_PERCENT = b.comment("Chance (percent) that a soldier cornered in the dark misses; pitch black is 1.5 times higher.")
                .defineInRange("missPercent", 40, 0, 100);
        b.pop();
        b.push("supply");
        SUPPLY = b.comment("Supply company (baton mode 5): torches, golem repair and building, winter campfires, food and regeneration for your recruits.")
                .define("enabled", true);
        SUPPLY_MIN = b.comment("Villagers needed for a company to work.")
                .defineInRange("minMembers", 3, 1, 50);
        SUPPLY_GOLEM_COOLDOWN = b.comment("Seconds between two golems built by the same company.")
                .defineInRange("golemCooldownSeconds", 180, 10, 3600);
        SUPPLY_GOLEMS_PER_FIVE = b.comment("Golems a company may keep: 1 from the minimum size, plus this many for every full 5 members.")
                .defineInRange("golemsPerFive", 1, 0, 10);
        b.pop();
        SPEC = b.build();
    }
}
