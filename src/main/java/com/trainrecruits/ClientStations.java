package com.trainrecruits;

import java.util.List;

/** Client-side copy of the player's stations and squads (filled while the train map is open). */
public final class ClientStations {
    private static volatile List<StationEntry> stations = List.of();
    private static volatile List<SquadEntry> squads = List.of();
    private static volatile List<RecruitEntry> recruits = List.of();

    private ClientStations() {}

    public static void set(List<StationEntry> list) {
        stations = List.copyOf(list);
    }

    public static List<StationEntry> get() {
        return stations;
    }

    public static void setSquads(List<SquadEntry> list) {
        squads = List.copyOf(list);
    }

    public static List<SquadEntry> getSquads() {
        return squads;
    }

    public static void setRecruits(List<RecruitEntry> list) {
        recruits = List.copyOf(list);
    }

    public static List<RecruitEntry> getRecruits() {
        return recruits;
    }
}
