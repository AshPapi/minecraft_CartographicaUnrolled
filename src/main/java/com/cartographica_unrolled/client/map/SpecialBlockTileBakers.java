package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.Arrays;

final class SpecialBlockTileBakers {
    private static final int HI_PX = BlockTopTextureCache.HI_PX;

    private SpecialBlockTileBakers() {
    }

    static BlockTopTextureCache.BakedBlockTile bakeChest(
            BlockState state,
            BlockStateModelSet modelSet,
            boolean[] scratchHiTinted
    ) {
        int[] hiArgb = new int[HI_PX * HI_PX];
        Arrays.fill(scratchHiTinted, false);
        boolean ender = state.is(Blocks.ENDER_CHEST);
        boolean trapped = state.is(Blocks.TRAPPED_CHEST);
        int baseWood = ender ? 0x263E3A : 0xA87634;
        int trimDark = ender ? 0x162422 : 0x5C3C16;
        int latchCol = ender ? 0xD8D08A : (trapped ? 0xC84A32 : 0xD2D6DC);

        Material.Baked mat = modelSet.getParticleMaterial(state);
        TextureAtlasSprite sprite = mat != null ? mat.sprite() : null;

        Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        ChestType type = state.hasProperty(BlockStateProperties.CHEST_TYPE)
                ? state.getValue(BlockStateProperties.CHEST_TYPE) : ChestType.SINGLE;

        int minX = 1;
        int maxX = 14;
        int minZ = 1;
        int maxZ = 14;
        Direction connected = null;
        if (type != ChestType.SINGLE) {
            connected = type == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
            switch (connected) {
                case WEST -> minX = 0;
                case EAST -> maxX = 15;
                case NORTH -> minZ = 0;
                case SOUTH -> maxZ = 15;
                default -> {
                }
            }
        }

        for (int pz = minZ; pz <= maxZ; pz++) {
            for (int px = minX; px <= maxX; px++) {
                boolean border = (px == minX && minX > 0) || (px == maxX && maxX < 15)
                        || (pz == minZ && minZ > 0) || (pz == maxZ && maxZ < 15);
                int col = baseWood;
                if (sprite != null && sprite.contents().width() > 0 && sprite.contents().height() > 0) {
                    int sw = sprite.contents().width();
                    int sh = sprite.contents().height();
                    int sp = sprite.getPixelARGB(0, (px * sw) / HI_PX, (pz * sh) / HI_PX) & 0xFFFFFF;
                    col = AtlasMath.mixRgb(sp, baseWood, ender ? 0.55f : 0.30f);
                }
                if (border) {
                    col = trimDark;
                } else {
                    boolean innerEdgeHi = (px == minX + 1 && minX > 0) || (pz == minZ + 1 && minZ > 0);
                    boolean innerEdgeLo = (px == maxX - 1 && maxX < 15) || (pz == maxZ - 1 && maxZ < 15);
                    if (innerEdgeHi) {
                        col = AtlasMath.scaleRgb(col, 1.10f);
                    } else if (innerEdgeLo) {
                        col = AtlasMath.scaleRgb(col, 0.90f);
                    }
                }
                hiArgb[pz * HI_PX + px] = 0xFF000000 | col;
            }
        }

        int lx0 = 7;
        int lx1 = 8;
        int lz0 = 7;
        int lz1 = 8;
        if (connected != null) {
            switch (connected) {
                case WEST -> {
                    lx0 = 0;
                    lx1 = 1;
                }
                case EAST -> {
                    lx0 = 14;
                    lx1 = 15;
                }
                case NORTH -> {
                    lz0 = 0;
                    lz1 = 1;
                }
                case SOUTH -> {
                    lz0 = 14;
                    lz1 = 15;
                }
                default -> {
                }
            }
        }
        switch (facing) {
            case NORTH -> {
                lz0 = 0;
                lz1 = 2;
            }
            case SOUTH -> {
                lz0 = 13;
                lz1 = 15;
            }
            case WEST -> {
                lx0 = 0;
                lx1 = 2;
            }
            case EAST -> {
                lx0 = 13;
                lx1 = 15;
            }
            default -> {
            }
        }
        int latchRim = AtlasMath.scaleRgb(latchCol, 0.72f);
        for (int pz = lz0; pz <= lz1; pz++) {
            for (int px = lx0; px <= lx1; px++) {
                boolean rim = (facing == Direction.NORTH && pz == lz1)
                        || (facing == Direction.SOUTH && pz == lz0)
                        || (facing == Direction.WEST && px == lx1)
                        || (facing == Direction.EAST && px == lx0);
                hiArgb[pz * HI_PX + px] = 0xFF000000 | (rim ? latchRim : latchCol);
            }
        }
        return BlockTopTextureCache.downsampleToTile(hiArgb, scratchHiTinted, 0xFFFFFF);
    }

    static BlockTopTextureCache.BakedBlockTile bakeBed(
            BlockState state,
            BedBlock bed,
            boolean[] scratchHiTinted
    ) {
        int[] hiArgb = new int[HI_PX * HI_PX];
        Arrays.fill(scratchHiTinted, false);
        int woolRgb = bed.getColor().getTextureDiffuseColor() & 0xFFFFFF;
        int woolShade = AtlasMath.scaleRgb(woolRgb, 0.84f);
        int woolHi = AtlasMath.scaleRgb(woolRgb, 1.07f);
        int pillowRgb = 0xF4EFE4;
        int pillowShade = 0xCEC5B4;
        int sheetRgb = 0xEBE4D5;
        int woodRgb = 0x8B6336;
        int woodDark = 0x5E4020;
        int woodHi = 0xA47846;
        Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        boolean head = state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;

        for (int pz = 0; pz < HI_PX; pz++) {
            for (int px = 0; px < HI_PX; px++) {
                int along = switch (facing) {
                    case NORTH -> HI_PX - 1 - pz;
                    case SOUTH -> pz;
                    case WEST -> HI_PX - 1 - px;
                    case EAST -> px;
                    default -> pz;
                };
                int across = (facing == Direction.NORTH || facing == Direction.SOUTH) ? px : pz;
                int col;
                if (across == 0 || across == HI_PX - 1) {
                    col = across == 0 ? woodRgb : woodDark;
                } else if (head) {
                    if (along == 15) {
                        col = woodDark;
                    } else if (along == 14) {
                        col = (across <= 2 || across >= 13) ? woodDark : woodHi;
                    } else if (along >= 7 && along <= 13 && across >= 2 && across <= 13) {
                        boolean corner = (across == 2 || across == 13) && (along == 7 || along == 13);
                        if (corner) {
                            col = sheetRgb;
                        } else if (across == 2 || across == 13 || along == 7 || along == 13) {
                            col = pillowShade;
                        } else {
                            float dx = (across - 7.5f) / 5.5f;
                            float dy = (along - 10.0f) / 3.0f;
                            float puff = Math.max(0.0f, 1.0f - (dx * dx + dy * dy) * 0.45f);
                            col = AtlasMath.mixRgb(pillowShade, pillowRgb, 0.35f + puff * 0.65f);
                        }
                    } else if (along == 6 && across >= 2 && across <= 13) {
                        col = AtlasMath.mixRgb(sheetRgb, pillowShade, 0.45f);
                    } else if (along >= 3) {
                        col = along == 3 ? AtlasMath.mixRgb(sheetRgb, pillowShade, 0.30f) : sheetRgb;
                    } else if (along == 2) {
                        col = AtlasMath.mixRgb(woolHi, sheetRgb, 0.25f);
                    } else {
                        col = (across <= 2 || across >= 13) ? woolShade : woolRgb;
                    }
                } else {
                    if (along == 0) {
                        col = woodDark;
                    } else if (along == 1) {
                        col = (across <= 2 || across >= 13) ? woodDark : woodRgb;
                    } else if (along == 2) {
                        col = woolShade;
                    } else {
                        float edgeShade = (across <= 2 || across >= 13) ? 0.32f : 0.0f;
                        col = edgeShade > 0.0f ? AtlasMath.mixRgb(woolRgb, woolShade, edgeShade) : woolRgb;
                        if (along == 15) {
                            col = AtlasMath.mixRgb(col, woolHi, 0.25f);
                        } else if (along == 9 || along == 10) {
                            col = along == 9
                                    ? AtlasMath.mixRgb(col, woolShade, 0.16f)
                                    : AtlasMath.mixRgb(col, woolHi, 0.14f);
                        }
                    }
                }
                hiArgb[pz * HI_PX + px] = 0xFF000000 | col;
            }
        }
        return BlockTopTextureCache.downsampleToTile(hiArgb, scratchHiTinted, 0xFFFFFF);
    }

    static BlockTopTextureCache.BakedBlockTile bakeBanner(
            AbstractBannerBlock banner,
            boolean[] scratchHiTinted
    ) {
        int[] hiArgb = new int[HI_PX * HI_PX];
        Arrays.fill(scratchHiTinted, false);
        int bannerRgb = banner.getColor().getTextureDiffuseColor() & 0xFFFFFF;
        int woodRgb = 0x7A5730;
        for (int pz = 5; pz <= 10; pz++) {
            for (int px = 3; px <= 12; px++) {
                int col = (pz == 5 || pz == 10 || px == 3 || px == 12) ? woodRgb : bannerRgb;
                hiArgb[pz * HI_PX + px] = 0xFF000000 | col;
            }
        }
        return BlockTopTextureCache.downsampleToTile(hiArgb, scratchHiTinted, 0xFFFFFF);
    }
}
