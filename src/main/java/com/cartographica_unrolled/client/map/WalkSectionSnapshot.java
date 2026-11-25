package com.cartographica_unrolled.client.map;

import io.netty.buffer.ByteBufUtil;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

final class WalkSectionSnapshot implements BlockGetter {
    private static final BlockState AIR_STATE = Blocks.AIR.defaultBlockState();

    final int startWx;
    final int startWz;
    final int rawY;
    final double startY;
    final boolean highSubY;
    final int levelMinY;
    final int levelHeight;
    final int minX;
    final int maxX;
    final int minZ;
    final int maxZ;
    final int minChunkX;
    final int minChunkZ;
    final int chunkSpanX;
    final int chunkSpanZ;
    final int minSectionY;
    final int sectionSpanY;
    final int minSearchY;
    final int maxSearchY;
    final boolean[] chunkLoaded;
    final @Nullable PalettedContainer<BlockState>[] sections;

    WalkSectionSnapshot(
            int startWx,
            int startWz,
            int rawY,
            double startY,
            int levelMinY,
            int levelHeight,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int minChunkX,
            int minChunkZ,
            int chunkSpanX,
            int chunkSpanZ,
            int minSectionY,
            int sectionSpanY,
            int minSearchY,
            int maxSearchY,
            boolean[] chunkLoaded,
            @Nullable PalettedContainer<BlockState>[] sections
    ) {
        this.startWx = startWx;
        this.startWz = startWz;
        this.rawY = rawY;
        this.startY = startY;
        this.highSubY = (startY - rawY) >= 0.35;
        this.levelMinY = levelMinY;
        this.levelHeight = levelHeight;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunkSpanX = chunkSpanX;
        this.chunkSpanZ = chunkSpanZ;
        this.minSectionY = minSectionY;
        this.sectionSpanY = sectionSpanY;
        this.minSearchY = minSearchY;
        this.maxSearchY = maxSearchY;
        this.chunkLoaded = chunkLoaded;
        this.sections = sections;
    }

    @SuppressWarnings("unchecked")
    static @Nullable WalkSectionSnapshot capture(ClientLevel level, double startX, double startY, double startZ) {
        int startWx = Mth.floor(startX);
        int startWz = Mth.floor(startZ);
        int rawY = Mth.floor(startY);
        int levelMinY = level.getMinY();
        int levelHeight = level.getHeight();
        int worldMinY = levelMinY + 1;
        int worldMaxY = levelMinY + levelHeight - 3;
        if (rawY < worldMinY || rawY > worldMaxY) {
            return null;
        }

        int minX = startWx - LocalWalkGraph.LOCAL_RADIUS;
        int maxX = startWx + LocalWalkGraph.LOCAL_RADIUS;
        int minZ = startWz - LocalWalkGraph.LOCAL_RADIUS;
        int maxZ = startWz + LocalWalkGraph.LOCAL_RADIUS;

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        int chunkSpanX = maxChunkX - minChunkX + 1;
        int chunkSpanZ = maxChunkZ - minChunkZ + 1;
        int spanCount = chunkSpanX * chunkSpanZ;

        LevelChunk startChunk = level.getChunkSource().getChunkNow(startWx >> 4, startWz >> 4);
        if (startChunk == null || startChunk.isEmpty()) {
            return null;
        }

        int minSearchY = Math.max(worldMinY, rawY - LocalWalkGraph.LOCAL_MAX_DY);
        int maxSearchY = Math.min(worldMaxY, rawY + LocalWalkGraph.LOCAL_MAX_DY);
        int minSectionY = (minSearchY - 3) >> 4;
        int maxSectionY = (maxSearchY + 3) >> 4;
        int sectionSpanY = maxSectionY - minSectionY + 1;

        boolean[] chunkLoaded = new boolean[spanCount];
        PalettedContainer<BlockState>[] sections =
                (PalettedContainer<BlockState>[]) new PalettedContainer<?>[spanCount * sectionSpanY];

        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                int chunkIdx = (cz - minChunkZ) * chunkSpanX + (cx - minChunkX);
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                chunkLoaded[chunkIdx] = true;
                LevelChunkSection[] chunkSecs = chunk.getSections();
                int baseOut = chunkIdx * sectionSpanY;
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    int secIdx = chunk.getSectionIndexFromSectionY(sy);
                    if (secIdx >= 0 && secIdx < chunkSecs.length) {
                        LevelChunkSection sec = chunkSecs[secIdx];
                        if (!sec.hasOnlyAir()) {
                            sections[baseOut + (sy - minSectionY)] = sec.getStates().copy();
                        }
                    }
                }
            }
        }

        return new WalkSectionSnapshot(
                startWx, startWz, rawY, startY,
                levelMinY, levelHeight,
                minX, maxX, minZ, maxZ,
                minChunkX, minChunkZ, chunkSpanX, chunkSpanZ,
                minSectionY, sectionSpanY,
                minSearchY, maxSearchY,
                chunkLoaded, sections
        );
    }

    boolean isChunkLoadedAt(int wx, int wz) {
        int cx = (wx >> 4) - this.minChunkX;
        int cz = (wz >> 4) - this.minChunkZ;
        if (cx < 0 || cx >= this.chunkSpanX || cz < 0 || cz >= this.chunkSpanZ) {
            return false;
        }
        return this.chunkLoaded[cz * this.chunkSpanX + cx];
    }

    BlockState getStateFast(int wx, int wy, int wz) {
        int cx = (wx >> 4) - this.minChunkX;
        int cz = (wz >> 4) - this.minChunkZ;
        if (cx < 0 || cx >= this.chunkSpanX || cz < 0 || cz >= this.chunkSpanZ) {
            return AIR_STATE;
        }
        int chunkIdx = cz * this.chunkSpanX + cx;
        if (!this.chunkLoaded[chunkIdx]) {
            return AIR_STATE;
        }
        int sy = (wy >> 4) - this.minSectionY;
        if (sy < 0 || sy >= this.sectionSpanY) {
            return AIR_STATE;
        }
        PalettedContainer<BlockState> container = this.sections[chunkIdx * this.sectionSpanY + sy];
        return container != null ? container.get(wx & 15, wy & 15, wz & 15) : AIR_STATE;
    }

    boolean equalsContent(
            @Nullable WalkSectionSnapshot other,
            FriendlyByteBuf scratchA,
            FriendlyByteBuf scratchB
    ) {
        if (other == null
                || this.startWx != other.startWx
                || this.startWz != other.startWz
                || this.rawY != other.rawY
                || this.highSubY != other.highSubY
                || this.minChunkX != other.minChunkX
                || this.minChunkZ != other.minChunkZ
                || this.chunkSpanX != other.chunkSpanX
                || this.chunkSpanZ != other.chunkSpanZ
                || this.minSectionY != other.minSectionY
                || this.sectionSpanY != other.sectionSpanY
                || !Arrays.equals(this.chunkLoaded, other.chunkLoaded)
                || this.sections.length != other.sections.length) {
            return false;
        }
        for (int i = 0; i < this.sections.length; i++) {
            PalettedContainer<BlockState> a = this.sections[i];
            PalettedContainer<BlockState> b = other.sections[i];
            if (a == b) {
                continue;
            }
            if (a == null || b == null) {
                return false;
            }
            if (a.bitsPerEntry() != b.bitsPerEntry() || a.getSerializedSize() != b.getSerializedSize()) {
                return false;
            }
            scratchA.clear();
            scratchB.clear();
            a.write(scratchA);
            b.write(scratchB);
            if (!ByteBufUtil.equals(scratchA, scratchB)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return getStateFast(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getStateFast(pos.getX(), pos.getY(), pos.getZ()).getFluidState();
    }

    @Override
    public int getMinY() {
        return this.levelMinY;
    }

    @Override
    public int getHeight() {
        return this.levelHeight;
    }
}
