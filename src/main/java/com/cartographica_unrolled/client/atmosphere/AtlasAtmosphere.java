package com.cartographica_unrolled.client.atmosphere;

import com.cartographica_unrolled.client.state.AtlasStateManager;
import com.cartographica_unrolled.client.state.ClientAtlasAnimTracker;
import com.cartographica_unrolled.data.AtlasPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class AtlasAtmosphere {
    public record SmokeEmitter(double x, double z, boolean soul, boolean furnace, boolean signal, int seed) {
    }

    public static final class ParchmentDrop {
        public static final int MAX_TRAIL = 96;
        public float u;
        public float v;
        public float prevU;
        public float prevV;
        public float impactU;
        public float impactV;
        public float initialRadius;
        public float age;
        public float prevAge;
        public float maxAge;
        public float radius;
        public float velV;
        public float velU;
        public float terminalSpeed;
        public float slipDelay;
        public float phaseTimer;
        public boolean stalled;
        public boolean runner;
        public final float[] trailU = new float[MAX_TRAIL];
        public final float[] trailV = new float[MAX_TRAIL];
        public final float[] trailAge = new float[MAX_TRAIL];
        public final float[] trailRad = new float[MAX_TRAIL];
        public int trailCount;
        public int seed;
        public boolean active;
    }

    private static final int MAX_DROPS = 18;
    private static final int MAX_SMOKE_EMITTERS = 96;
    private static final int SMOKE_SCAN_RADIUS_CHUNKS = 6;
    private static final int SMOKE_LUT_STEPS = 256;
    private static final ParchmentDrop[] DROPS = new ParchmentDrop[MAX_DROPS];
    private static final int[] RADIAL_CHUNK_OFFSETS;
    private static final float[] SMOKE_FADE_LUT = new float[SMOKE_LUT_STEPS + 1];
    private static final List<SmokeEmitter> SCAN_BUFFER = new ArrayList<>(MAX_SMOKE_EMITTERS);
    private static volatile List<SmokeEmitter> smokeEmitters = List.of();

    private static float rainFactor;
    private static float snowFactor;
    private static float thunderFlash;
    private static int scanCooldown;
    private static int dropSpawnCooldown;

    static {
        for (int i = 0; i < MAX_DROPS; i++) {
            DROPS[i] = new ParchmentDrop();
        }
        for (int i = 0; i <= SMOKE_LUT_STEPS; i++) {
            float cycle = i / (float) SMOKE_LUT_STEPS;
            float fadeIn = Mth.clamp(cycle / 0.14f, 0.0f, 1.0f);
            float fadeOut = (float) Math.pow(1.0f - cycle, 0.85);
            SMOKE_FADE_LUT[i] = fadeIn * fadeOut;
        }
        int span = SMOKE_SCAN_RADIUS_CHUNKS * 2 + 1;
        Integer[] packed = new Integer[span * span];
        int idx = 0;
        for (int dz = -SMOKE_SCAN_RADIUS_CHUNKS; dz <= SMOKE_SCAN_RADIUS_CHUNKS; dz++) {
            for (int dx = -SMOKE_SCAN_RADIUS_CHUNKS; dx <= SMOKE_SCAN_RADIUS_CHUNKS; dx++) {
                int distSq = dx * dx + dz * dz;
                packed[idx++] = (distSq << 16) | ((dx & 0xFF) << 8) | (dz & 0xFF);
            }
        }
        Arrays.sort(packed);
        RADIAL_CHUNK_OFFSETS = new int[packed.length * 2];
        for (int i = 0; i < packed.length; i++) {
            int p = packed[i];
            RADIAL_CHUNK_OFFSETS[i * 2] = (byte) ((p >>> 8) & 0xFF);
            RADIAL_CHUNK_OFFSETS[i * 2 + 1] = (byte) (p & 0xFF);
        }
    }

    private AtlasAtmosphere() {
    }

    public static float getRainFactor() {
        return rainFactor;
    }

    public static float getSnowFactor() {
        return snowFactor;
    }

    public static float getThunderFlash() {
        return thunderFlash;
    }

    public static ParchmentDrop[] getDrops() {
        return DROPS;
    }

    public static List<SmokeEmitter> getSmokeEmitters() {
        return smokeEmitters;
    }

    public static float sampleSmokeFade(float cycle) {
        float scaled = Mth.clamp(cycle, 0.0f, 1.0f) * SMOKE_LUT_STEPS;
        int idx = (int) scaled;
        if (idx >= SMOKE_LUT_STEPS) {
            return SMOKE_FADE_LUT[SMOKE_LUT_STEPS];
        }
        float frac = scaled - idx;
        return SMOKE_FADE_LUT[idx] + (SMOKE_FADE_LUT[idx + 1] - SMOKE_FADE_LUT[idx]) * frac;
    }

    public static void clear() {
        rainFactor = 0.0f;
        snowFactor = 0.0f;
        thunderFlash = 0.0f;
        scanCooldown = 0;
        dropSpawnCooldown = 6;
        SCAN_BUFFER.clear();
        smokeEmitters = List.of();
        for (ParchmentDrop drop : DROPS) {
            drop.active = false;
        }
    }

    public static void tick(Minecraft mc, boolean playerIndoors) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            clear();
            return;
        }

        float targetRain = 0.0f;
        float targetSnow = 0.0f;
        if (level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()) {
            float rainLvl = level.getRainLevel(1.0f);
            if (rainLvl > 0.05f) {
                BlockPos pos = player.blockPosition();
                Biome biome = level.getBiome(pos).value();
                Biome.Precipitation precip = biome.getPrecipitationAt(pos, level.getSeaLevel());
                float shelterScale = playerIndoors ? 0.30f : 1.0f;
                if (precip == Biome.Precipitation.RAIN) {
                    targetRain = rainLvl * shelterScale;
                } else if (precip == Biome.Precipitation.SNOW) {
                    targetSnow = rainLvl * shelterScale;
                }
            }
            if (level.getThunderLevel(1.0f) > 0.25f && !playerIndoors) {
                for (Entity entity : level.entitiesForRendering()) {
                    if (entity instanceof LightningBolt) {
                        thunderFlash = Math.max(thunderFlash, 0.75f);
                        break;
                    }
                }
            }
        }

        rainFactor = Mth.lerp(0.05f, rainFactor, targetRain);
        snowFactor = Mth.lerp(0.05f, snowFactor, targetSnow);
        thunderFlash = Math.max(0.0f, thunderFlash - 0.09f);
        if (rainFactor < 0.004f) rainFactor = 0.0f;
        if (snowFactor < 0.004f) snowFactor = 0.0f;

        tickDrops(playerIndoors);

        if (AtlasStateManager.getPhase() == AtlasPhase.CLOSED && !ClientAtlasAnimTracker.hasAnyActive()) {
            scanCooldown = 0;
        } else if (--scanCooldown <= 0) {
            scanCooldown = 15;
            scanSmokeEmitters(level, player);
        }
    }

    private static void tickDrops(boolean playerIndoors) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (ParchmentDrop drop : DROPS) {
            if (!drop.active) {
                continue;
            }
            drop.prevU = drop.u;
            drop.prevV = drop.v;
            drop.prevAge = drop.age;
            drop.age += 0.05f;
            if (drop.age >= drop.maxAge) {
                drop.active = false;
                continue;
            }
            if (drop.runner && drop.age >= drop.slipDelay) {
                drop.phaseTimer -= 0.05f;
                if (drop.phaseTimer <= 0.0f) {
                    if (drop.stalled) {
                        drop.stalled = false;
                        drop.phaseTimer = 0.7f + rng.nextFloat() * 1.4f;
                    } else if (rng.nextFloat() < 0.35f) {
                        drop.stalled = true;
                        drop.phaseTimer = 0.20f + rng.nextFloat() * 0.45f;
                    } else {
                        drop.phaseTimer = 0.6f + rng.nextFloat() * 1.0f;
                    }
                }

                float waterLeft = Mth.clamp(1.0f - (drop.age / drop.maxAge) * 0.78f, 0.15f, 1.0f);
                float targetV = drop.stalled ? (drop.terminalSpeed * 0.08f) : (drop.terminalSpeed * waterLeft);
                float accel = drop.stalled ? 0.32f : 0.14f;
                drop.velV = Mth.lerp(accel, drop.velV, targetV);

                if (!drop.stalled && rng.nextFloat() < 0.16f) {
                    float kick = (rng.nextFloat() - 0.5f) * 0.00055f;
                    drop.velU = Mth.clamp(drop.velU * 0.75f + kick, -0.00048f, 0.00048f);
                } else {
                    drop.velU *= 0.90f;
                }

                drop.v += drop.velV;
                drop.u = Mth.clamp(drop.u + drop.velU * (drop.velV / Math.max(0.001f, drop.terminalSpeed)), 0.005f, 0.995f);
                drop.radius = Math.max(drop.initialRadius * 0.58f, drop.radius - drop.velV * 1.6f);

                if (drop.trailCount > 0 && drop.trailCount < ParchmentDrop.MAX_TRAIL) {
                    int last = drop.trailCount - 1;
                    float du = drop.u - drop.trailU[last];
                    float dv = drop.v - drop.trailV[last];
                    if (du * du + dv * dv >= 0.00009f) {
                        drop.trailU[drop.trailCount] = drop.u;
                        drop.trailV[drop.trailCount] = drop.v;
                        drop.trailAge[drop.trailCount] = drop.age;
                        drop.trailRad[drop.trailCount] = drop.radius;
                        drop.trailCount++;
                    }
                }
                if (drop.v > 1.04f) {
                    drop.age = Math.max(drop.age, drop.maxAge - 0.45f);
                }
            }
        }

        if (!playerIndoors && rainFactor > 0.20f) {
            if (dropSpawnCooldown > 0) {
                dropSpawnCooldown--;
            } else {
                ParchmentDrop freeSlot = null;
                for (ParchmentDrop drop : DROPS) {
                    if (!drop.active) {
                        freeSlot = drop;
                        break;
                    }
                }
                if (freeSlot != null) {
                    float u = 0.5f;
                    float v = 0.5f;
                    float edgeDist = 0.0f;
                    boolean picked = false;
                    for (int tries = 0; tries < 8; tries++) {
                        float cu = 0.015f + rng.nextFloat() * 0.97f;
                        float cv = 0.015f + rng.nextFloat() * 0.97f;
                        float dxC = Math.abs(cu - 0.5f) * 2.0f;
                        float dyC = Math.abs(cv - 0.5f) * 2.0f;
                        float d = Math.max(dxC, dyC);
                        float acceptProb = 0.14f + 0.86f * d * d * d;
                        if (rng.nextFloat() > acceptProb) {
                            continue;
                        }
                        boolean tooClose = false;
                        for (ParchmentDrop other : DROPS) {
                            if (other.active) {
                                float du = other.u - cu;
                                float dv = other.v - cv;
                                if (du * du + dv * dv < 0.0025f) {
                                    tooClose = true;
                                    break;
                                }
                            }
                        }
                        if (!tooClose) {
                            u = cu;
                            v = cv;
                            edgeDist = d;
                            picked = true;
                            break;
                        }
                    }
                    if (picked) {
                        float runnerChance = edgeDist > 0.55f ? 0.62f : 0.28f;
                        boolean isRunner = rng.nextFloat() < runnerChance;
                        freeSlot.active = true;
                        freeSlot.runner = isRunner;
                        freeSlot.u = u;
                        freeSlot.v = v;
                        freeSlot.prevU = u;
                        freeSlot.prevV = v;
                        freeSlot.impactU = u;
                        freeSlot.impactV = v;
                        freeSlot.age = 0.0f;
                        freeSlot.prevAge = 0.0f;
                        freeSlot.maxAge = isRunner ? (3.4f + rng.nextFloat() * 3.0f) : (2.2f + rng.nextFloat() * 2.2f);
                        freeSlot.radius = isRunner ? (2.6f + rng.nextFloat() * 2.0f) : (1.7f + rng.nextFloat() * 1.5f);
                        freeSlot.initialRadius = freeSlot.radius;
                        freeSlot.velV = 0.0f;
                        freeSlot.velU = (rng.nextFloat() - 0.5f) * 0.00022f;
                        freeSlot.terminalSpeed = isRunner ? (0.0022f + rng.nextFloat() * 0.0038f) : 0.0f;
                        freeSlot.slipDelay = isRunner ? (0.12f + rng.nextFloat() * 0.38f) : 99.0f;
                        freeSlot.phaseTimer = 0.6f + rng.nextFloat() * 1.2f;
                        freeSlot.stalled = false;
                        freeSlot.trailU[0] = u;
                        freeSlot.trailV[0] = v;
                        freeSlot.trailAge[0] = 0.0f;
                        freeSlot.trailRad[0] = freeSlot.radius;
                        freeSlot.trailCount = 1;
                        freeSlot.seed = rng.nextInt();
                        int minGap = Math.round(Mth.lerp(rainFactor, 16.0f, 5.0f));
                        dropSpawnCooldown = minGap + rng.nextInt(10);
                    }
                }
            }
        }
    }

    private static void scanSmokeEmitters(ClientLevel level, LocalPlayer player) {
        SCAN_BUFFER.clear();
        int pcx = player.chunkPosition().x();
        int pcz = player.chunkPosition().z();
        for (int i = 0; i < RADIAL_CHUNK_OFFSETS.length && SCAN_BUFFER.size() < MAX_SMOKE_EMITTERS; i += 2) {
            int dx = RADIAL_CHUNK_OFFSETS[i];
            int dz = RADIAL_CHUNK_OFFSETS[i + 1];
            LevelChunk chunk = level.getChunkSource().getChunkNow(pcx + dx, pcz + dz);
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockState state = be.getBlockState();
                boolean isCampfire = state.getBlock() instanceof CampfireBlock;
                boolean isFurnace = state.getBlock() instanceof AbstractFurnaceBlock;
                if (!isCampfire && !isFurnace) {
                    continue;
                }
                if (!state.hasProperty(BlockStateProperties.LIT) || !state.getValue(BlockStateProperties.LIT)) {
                    continue;
                }
                BlockPos p = be.getBlockPos();
                boolean soul = state.is(Blocks.SOUL_CAMPFIRE);
                boolean signal = isCampfire
                        && state.hasProperty(CampfireBlock.SIGNAL_FIRE)
                        && state.getValue(CampfireBlock.SIGNAL_FIRE);
                int seed = p.getX() * 374761393 ^ p.getZ() * 668265263 ^ p.getY() * 1442695041;
                SCAN_BUFFER.add(new SmokeEmitter(p.getX() + 0.5, p.getZ() + 0.5, soul, isFurnace, signal, seed));
                if (SCAN_BUFFER.size() >= MAX_SMOKE_EMITTERS) {
                    break;
                }
            }
        }
        smokeEmitters = SCAN_BUFFER.isEmpty() ? List.of() : List.copyOf(SCAN_BUFFER);
    }
}
