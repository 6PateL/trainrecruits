package com.trainrecruits;

import net.minecraftforge.common.ForgeConfigSpec;

public class TrainConfig {
    public enum AfterDisembark { HOLD, FOLLOW, WANDER }

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
        SPEC = b.build();
    }
}
