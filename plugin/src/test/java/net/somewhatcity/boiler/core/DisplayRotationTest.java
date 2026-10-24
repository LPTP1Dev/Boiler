package net.somewhatcity.boiler.core;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.core.client.DisplayGeometry;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisplayRotationTest {
    @Test void savedDefaultAnglesMatchEachFacingIncludingWrappedYaw() {
        assertNative(BlockFace.SOUTH,0,0);assertNative(BlockFace.NORTH,180,0);
        assertNative(BlockFace.EAST,-90,0);assertNative(BlockFace.WEST,90,0);
        assertNative(BlockFace.UP,0,-90);assertNative(BlockFace.DOWN,0,90);
        assertNative(BlockFace.SOUTH,360,0);
        assertFalse(DisplayGeometry.hasNativeRotation(settings(45,0),BlockFace.SOUTH));
        assertFalse(DisplayGeometry.hasNativeRotation(settings(0,0),BlockFace.NORTH));
        assertFalse(DisplayGeometry.hasNativeRotation(settings(Double.NaN,0),BlockFace.SOUTH));
        assertTrue(DisplayGeometry.hasNativeRotation(new JsonObject(),BlockFace.NORTH));
    }
    private static void assertNative(BlockFace face,double yaw,double pitch) {assertTrue(DisplayGeometry.hasNativeRotation(settings(yaw,pitch),face));}
    private static JsonObject settings(double yaw,double pitch) {
        JsonObject settings=new JsonObject(),rotation=new JsonObject();rotation.addProperty("yaw",yaw);rotation.addProperty("pitch",pitch);settings.add("rotation",rotation);return settings;
    }
}
