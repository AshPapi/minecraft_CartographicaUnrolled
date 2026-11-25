package com.cartographica_unrolled.client.atmosphere;

import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;

public final class AtlasLighting {
    private static final BlockPos.MutableBlockPos SKY_CHECK_POS = new BlockPos.MutableBlockPos();
    private static float nightFactor;

    private AtlasLighting() {
    }

    public static void reset() {
        nightFactor = 0.0f;
    }

    public static float getNightFactor() {
        return nightFactor;
    }

    public static int tint(int day, int night) {
        float t = nightFactor;
        if (t <= 0.0f) {
            return day;
        }
        if (t >= 1.0f) {
            return night;
        }
        int a = Math.round(Mth.lerp(t, (day >>> 24) & 255, (night >>> 24) & 255));
        int r = Math.round(Mth.lerp(t, (day >>> 16) & 255, (night >>> 16) & 255));
        int g = Math.round(Mth.lerp(t, (day >>> 8) & 255, (night >>> 8) & 255));
        int b = Math.round(Mth.lerp(t, day & 255, night & 255));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int dyeInkColor(int rgb) {
        int day = 0xFF000000 | (rgb & 0xFFFFFF);
        int night = 0xFF000000 | AtlasMath.darkenDyeInk(rgb);
        return tint(day, night);
    }

    public static int regionTint() {
        Minecraft mc = Minecraft.getInstance();
        boolean nether = mc.level != null && mc.level.dimensionType().hasCeiling();
        return tint(0xFFFFFFFF, nether ? 0xFF604252 : 0xFF43526E);
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            nightFactor = 0.0f;
            return;
        }
        float target;
        if (mc.level.dimensionType().hasCeiling()) {
            target = 0.75f;
        } else if (!mc.level.dimensionType().hasSkyLight()) {
            target = 0.85f;
        } else {
            target = Mth.clamp(mc.level.getSkyDarken() / 11.0f, 0.0f, 1.0f);
            if (mc.player.getY() < mc.level.getSeaLevel() - 8) {
                int px = mc.player.getBlockX();
                int py = mc.player.getBlockY() + 1;
                int pz = mc.player.getBlockZ();
                if (mc.level.getBrightness(LightLayer.SKY, SKY_CHECK_POS.set(px, py, pz)) == 0
                        && mc.level.getBrightness(LightLayer.SKY, SKY_CHECK_POS.set(px + 3, py, pz)) == 0
                        && mc.level.getBrightness(LightLayer.SKY, SKY_CHECK_POS.set(px - 3, py, pz)) == 0
                        && mc.level.getBrightness(LightLayer.SKY, SKY_CHECK_POS.set(px, py, pz + 3)) == 0
                        && mc.level.getBrightness(LightLayer.SKY, SKY_CHECK_POS.set(px, py, pz - 3)) == 0) {
                    target = Math.max(target, 0.85f);
                }
            }
        }
        nightFactor = Mth.lerp(0.12f, nightFactor, target);
        if (Math.abs(nightFactor - target) < 0.001f) {
            nightFactor = target;
        }
    }
}
