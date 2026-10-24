package net.somewhatcity.boiler.core.client;

import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.stream.StreamProtocol.*;

public final class DisplayGeometry {
    private DisplayGeometry() {}
    /** Explicitly saved default angles do not change the composed display plane. */
    public static boolean hasNativeRotation(com.google.gson.JsonObject settings,org.bukkit.block.BlockFace facing) {
        if (!settings.has("rotation")) return true;
        if (!settings.get("rotation").isJsonObject()) return false;
        var rotation=settings.getAsJsonObject("rotation");
        double yaw=switch(facing) {case NORTH -> 180;case EAST -> 270;case WEST -> 90;default -> 0;};
        double pitch=switch(facing) {case UP -> -90;case DOWN -> 90;default -> 0;};
        try {
            double actualYaw=rotation.has("yaw") ? rotation.get("yaw").getAsDouble() : yaw;
            double actualPitch=rotation.has("pitch") ? rotation.get("pitch").getAsDouble() : pitch;
            return sameAngle(actualYaw,yaw)&&sameAngle(actualPitch,pitch);
        } catch(RuntimeException malformed) {return false;}
    }
    private static boolean sameAngle(double actual,double expected) {
        return Double.isFinite(actual)&&Math.abs(Math.IEEEremainder(actual-expected,360))<.0001;
    }
    public static Display snapshot(IBoilerDisplay display,long generation) {
        var box=display.mapDisplay().box();
        double x=box.getMinX(),y=box.getMaxY(),z=box.getMinZ();
        double w=display.mapDisplay().width(),h=display.mapDisplay().height(),offset=.0078125;
        Vec origin,right,down;
        switch(display.facing()) {
            case NORTH -> {origin=new Vec(box.getMaxX(),y,z-offset);right=new Vec(-w,0,0);down=new Vec(0,-h,0);}
            case SOUTH -> {origin=new Vec(x,y,z+offset);right=new Vec(w,0,0);down=new Vec(0,-h,0);}
            case EAST -> {origin=new Vec(x+offset,y,box.getMaxZ());right=new Vec(0,0,-w);down=new Vec(0,-h,0);}
            case WEST -> {origin=new Vec(x-offset,y,z);right=new Vec(0,0,w);down=new Vec(0,-h,0);}
            case UP -> {origin=new Vec(x,y+offset,z);right=new Vec(w,0,0);down=new Vec(0,0,h);}
            case DOWN -> {origin=new Vec(x,y-offset,box.getMaxZ());right=new Vec(w,0,0);down=new Vec(0,0,-h);}
            default -> throw new IllegalArgumentException("Unsupported facing");
        }
        return new Display(display.id(),generation,display.cornerA().getWorld().getKey().toString(),display.width(),display.height(),origin,right,down);
    }
}
