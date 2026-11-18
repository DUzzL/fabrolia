package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla's guarded entity loop calls {@code checkDespawn} for every tick-list
 * entity before it decides whether the entity rides a vehicle. The region
 * owner's visit must keep that for riders: {@code Mob.checkDespawn} resets
 * {@code noActionTime} near a player, and raiders held in boats rely on it to
 * stay eligible for a raid.
 */
final class PassengerCheckDespawnWiringTest {

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/com/axalotl/fabrolia/common/regions/RegionizedServer.java"));
    }

    private static String riderBranch() throws IOException {
        String source = source();
        int method = source.indexOf("private void tickOneAdmitted(");
        assertTrue(method >= 0, "the region owner's entity visit");
        int vehicle = source.indexOf("Entity vehicle = e.getVehicle();", method);
        assertTrue(vehicle >= 0, "the vehicle decision");
        int branch = source.indexOf("if (!vehicle.isRemoved() && vehicle.hasPassenger(e)) {", vehicle);
        int dismount = source.indexOf("e.stopRiding();", vehicle);
        assertTrue(branch >= 0 && dismount > branch, "a live rider is left to its vehicle's tick");
        return source.substring(branch, dismount);
    }

    @Test
    void aRiderGetsItsDespawnCheckBeforeItIsLeftToTheVehicle() throws IOException {
        String branch = riderBranch();
        int despawn = branch.indexOf("e.checkDespawn();");
        int leave = branch.lastIndexOf("return;");
        assertTrue(despawn >= 0, "a riding mob runs checkDespawn every tick, as in vanilla");
        assertTrue(leave > despawn, "the check precedes the return to the vehicle's passenger tick");
        assertTrue(branch.indexOf("dropFromRegion(region, e)") > despawn,
                "a rider the check discarded leaves the region index like a root does");
    }

    @Test
    void playersKeepTheirOwnVenue() throws IOException {
        String branch = riderBranch();
        int guard = branch.indexOf("if (!directPlayer) {");
        assertTrue(guard >= 0 && guard < branch.indexOf("e.checkDespawn();"),
                "a riding player may tick on Main; Entity.checkDespawn is empty for it");
    }
}
