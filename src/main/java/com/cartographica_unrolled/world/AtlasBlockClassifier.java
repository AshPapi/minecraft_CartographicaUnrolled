package com.cartographica_unrolled.world;

import com.cartographica_unrolled.data.AtlasChunkTerrain;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.MapColor;

import java.util.concurrent.ConcurrentHashMap;

public final class AtlasBlockClassifier {
    private static final int BF_TRANSLUCENT = 1;
    private static final int BF_STRUCTURE_OR_ROAD = 1 << 1;
    private static final int BF_FLORA_CANDIDATE = 1 << 2;
    private static final int BF_CANOPY = 1 << 3;
    private static final int BF_OUTDOOR_SPECIAL = 1 << 4;
    private static final int BF_NATURAL_STONE = 1 << 5;
    private static final int BF_OUTDOOR_NATURAL_GROUND = 1 << 6;
    private static final int BF_CROP_OR_FARMLAND = 1 << 7;
    private static final int BF_BIOME_FOLIAGE_TINTED = 1 << 8;
    private static final int BF_INDOOR_FURNITURE = 1 << 9;
    private static final int BF_INDOOR_PASSABLE_FAST = 1 << 10;
    private static final int BF_STAINED_GLASS_PANE = 1 << 11;

    private static final ConcurrentHashMap<Block, Integer> BLOCK_FLAGS_CACHE = new ConcurrentHashMap<>(512);
    private static final Reference2IntOpenHashMap<Block> FLORA_CODE_MAP = new Reference2IntOpenHashMap<>(64);

    static {
        FLORA_CODE_MAP.defaultReturnValue(-1);
        regFlora(Blocks.SUNFLOWER, AtlasChunkTerrain.DETAIL_FLOWER_CLUSTER, AtlasChunkTerrain.ACCENT_GOLD_YELLOW);
        regFlora(Blocks.LILAC, AtlasChunkTerrain.DETAIL_FLOWER_CLUSTER, AtlasChunkTerrain.ACCENT_LILAC_PURPLE);
        regFlora(Blocks.ROSE_BUSH, AtlasChunkTerrain.DETAIL_FLOWER_CLUSTER, AtlasChunkTerrain.ACCENT_RED);
        regFlora(Blocks.PEONY, AtlasChunkTerrain.DETAIL_FLOWER_CLUSTER, AtlasChunkTerrain.ACCENT_PINK);
        regFlora(Blocks.PITCHER_PLANT, AtlasChunkTerrain.DETAIL_FLOWER_CLUSTER, AtlasChunkTerrain.ACCENT_ORCHID_CYAN);

        regFlora(Blocks.PINK_PETALS, AtlasChunkTerrain.DETAIL_PETALS_LITTER, AtlasChunkTerrain.ACCENT_PINK);
        regFlora(Blocks.WILDFLOWERS, AtlasChunkTerrain.DETAIL_PETALS_LITTER, AtlasChunkTerrain.ACCENT_GOLD_YELLOW);
        regFlora(Blocks.LEAF_LITTER, AtlasChunkTerrain.DETAIL_PETALS_LITTER, AtlasChunkTerrain.ACCENT_BROWN_OCHRE);
        regFlora(Blocks.SPORE_BLOSSOM, AtlasChunkTerrain.DETAIL_PETALS_LITTER, AtlasChunkTerrain.ACCENT_SPORE_MAGENTA);

        regFlora(Blocks.POPPY, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_RED);
        regFlora(Blocks.RED_TULIP, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_RED);
        regFlora(Blocks.DANDELION, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_GOLD_YELLOW);
        regFlora(Blocks.CORNFLOWER, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_CORNFLOWER);
        regFlora(Blocks.BLUE_ORCHID, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_ORCHID_CYAN);
        regFlora(Blocks.ALLIUM, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_LILAC_PURPLE);
        regFlora(Blocks.PINK_TULIP, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_PINK);
        regFlora(Blocks.ORANGE_TULIP, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_ORANGE);
        regFlora(Blocks.TORCHFLOWER, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_ORANGE);
        regFlora(Blocks.OPEN_EYEBLOSSOM, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_ORANGE);
        regFlora(Blocks.OXEYE_DAISY, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_WHITE_CREAM);
        regFlora(Blocks.AZURE_BLUET, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_WHITE_CREAM);
        regFlora(Blocks.LILY_OF_THE_VALLEY, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_WHITE_CREAM);
        regFlora(Blocks.WHITE_TULIP, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_WHITE_CREAM);
        regFlora(Blocks.WITHER_ROSE, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_DARK_WITHER);
        regFlora(Blocks.CLOSED_EYEBLOSSOM, AtlasChunkTerrain.DETAIL_FLOWER_SINGLE, AtlasChunkTerrain.ACCENT_DARK_WITHER);

        regFlora(Blocks.RED_MUSHROOM, AtlasChunkTerrain.DETAIL_MUSHROOM, AtlasChunkTerrain.ACCENT_RED);
        regFlora(Blocks.CRIMSON_FUNGUS, AtlasChunkTerrain.DETAIL_MUSHROOM, AtlasChunkTerrain.ACCENT_RED);
        regFlora(Blocks.BROWN_MUSHROOM, AtlasChunkTerrain.DETAIL_MUSHROOM, AtlasChunkTerrain.ACCENT_BROWN_OCHRE);
        regFlora(Blocks.WARPED_FUNGUS, AtlasChunkTerrain.DETAIL_MUSHROOM, AtlasChunkTerrain.ACCENT_ORCHID_CYAN);

        regFlora(Blocks.SWEET_BERRY_BUSH, AtlasChunkTerrain.DETAIL_BERRY_BUSH, AtlasChunkTerrain.ACCENT_BERRY_CRIMSON);
        regFlora(Blocks.FIREFLY_BUSH, AtlasChunkTerrain.DETAIL_BERRY_BUSH, AtlasChunkTerrain.ACCENT_FIREFLY_AMBER);
        regFlora(Blocks.CAVE_VINES, AtlasChunkTerrain.DETAIL_BERRY_BUSH, AtlasChunkTerrain.ACCENT_FIREFLY_AMBER);
        regFlora(Blocks.CAVE_VINES_PLANT, AtlasChunkTerrain.DETAIL_BERRY_BUSH, AtlasChunkTerrain.ACCENT_FIREFLY_AMBER);

        regFlora(Blocks.SUGAR_CANE, AtlasChunkTerrain.DETAIL_REEDS_BAMBOO, AtlasChunkTerrain.ACCENT_MELON_GREEN);
        regFlora(Blocks.BAMBOO_SAPLING, AtlasChunkTerrain.DETAIL_REEDS_BAMBOO, AtlasChunkTerrain.ACCENT_MELON_GREEN);
        regFlora(Blocks.SMALL_DRIPLEAF, AtlasChunkTerrain.DETAIL_REEDS_BAMBOO, AtlasChunkTerrain.ACCENT_MELON_GREEN);
        regFlora(Blocks.BIG_DRIPLEAF, AtlasChunkTerrain.DETAIL_REEDS_BAMBOO, AtlasChunkTerrain.ACCENT_MELON_GREEN);
        regFlora(Blocks.BIG_DRIPLEAF_STEM, AtlasChunkTerrain.DETAIL_REEDS_BAMBOO, AtlasChunkTerrain.ACCENT_MELON_GREEN);

        regFlora(Blocks.DEAD_BUSH, AtlasChunkTerrain.DETAIL_ARID_FLORA, AtlasChunkTerrain.ACCENT_BROWN_OCHRE);
        regFlora(Blocks.SHORT_DRY_GRASS, AtlasChunkTerrain.DETAIL_ARID_FLORA, AtlasChunkTerrain.ACCENT_BROWN_OCHRE);
        regFlora(Blocks.TALL_DRY_GRASS, AtlasChunkTerrain.DETAIL_ARID_FLORA, AtlasChunkTerrain.ACCENT_BROWN_OCHRE);

        regFlora(Blocks.MOSS_CARPET, AtlasChunkTerrain.DETAIL_MOSS_LICHEN, AtlasChunkTerrain.ACCENT_MELON_GREEN);
        regFlora(Blocks.GLOW_LICHEN, AtlasChunkTerrain.DETAIL_MOSS_LICHEN, AtlasChunkTerrain.ACCENT_MELON_GREEN);
        regFlora(Blocks.PALE_MOSS_CARPET, AtlasChunkTerrain.DETAIL_MOSS_LICHEN, AtlasChunkTerrain.ACCENT_WHITE_CREAM);
        regFlora(Blocks.PALE_HANGING_MOSS, AtlasChunkTerrain.DETAIL_MOSS_LICHEN, AtlasChunkTerrain.ACCENT_WHITE_CREAM);

        regFlora(Blocks.FERN, AtlasChunkTerrain.DETAIL_FERN_TALLGRASS, AtlasChunkTerrain.ACCENT_MELON_GREEN);
        regFlora(Blocks.LARGE_FERN, AtlasChunkTerrain.DETAIL_FERN_TALLGRASS, AtlasChunkTerrain.ACCENT_MELON_GREEN);
        regFlora(Blocks.TALL_GRASS, AtlasChunkTerrain.DETAIL_FERN_TALLGRASS, AtlasChunkTerrain.ACCENT_MELON_GREEN);
        regFlora(Blocks.BUSH, AtlasChunkTerrain.DETAIL_FERN_TALLGRASS, AtlasChunkTerrain.ACCENT_MELON_GREEN);
    }

    private static void regFlora(Block block, int detail, int accent) {
        FLORA_CODE_MAP.put(block, (detail << 8) | accent);
    }

    private AtlasBlockClassifier() {
    }

    public static void clearCache() {
        BLOCK_FLAGS_CACHE.clear();
    }

    private static int blockFlags(BlockState state) {
        if (state.isAir()) {
            return BF_INDOOR_PASSABLE_FAST;
        }
        return BLOCK_FLAGS_CACHE.computeIfAbsent(state.getBlock(), AtlasBlockClassifier::computeBlockFlags);
    }

    public static boolean isTranslucentBlock(BlockState state) {
        return (blockFlags(state) & BF_TRANSLUCENT) != 0;
    }

    public static boolean isWindowOrBars(BlockState state) {
        return isTranslucentBlock(state) || state.is(Blocks.IRON_BARS);
    }

    public static float getTranslucentAlpha(BlockState state) {
        if (state.is(Blocks.TINTED_GLASS)) {
            return 0.72f;
        }
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) {
            return 0.16f;
        }
        if (state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK)) {
            return 0.65f;
        }
        if ((blockFlags(state) & BF_STAINED_GLASS_PANE) != 0) {
            return 0.42f;
        }
        return 0.55f;
    }

    public static boolean isIndoorPassable(Level level, BlockPos pos, BlockState state) {
        int bf = blockFlags(state);
        if ((bf & BF_INDOOR_PASSABLE_FAST) != 0) {
            return true;
        }
        if (!state.getFluidState().isEmpty() || (bf & BF_CANOPY) != 0) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    public static boolean isIndoorFurnitureOrFixture(BlockState state) {
        return (blockFlags(state) & BF_INDOOR_FURNITURE) != 0;
    }

    public static boolean isGroundFloraToSkip(BlockState state) {
        return (blockFlags(state) & BF_FLORA_CANDIDATE) != 0 && state.getFluidState().isEmpty();
    }

    public static boolean isCanopyBlock(BlockState state) {
        return (blockFlags(state) & BF_CANOPY) != 0;
    }

    public static boolean isCanopyOrFoliage(BlockState state) {
        return isCanopyBlock(state) || state.is(Blocks.VINE) || state.is(Blocks.HANGING_ROOTS);
    }

    public static boolean isStructureOrRoad(BlockState state) {
        return (blockFlags(state) & BF_STRUCTURE_OR_ROAD) != 0;
    }

    public static boolean isCropOrFarmland(BlockState state) {
        return (blockFlags(state) & BF_CROP_OR_FARMLAND) != 0;
    }

    public static boolean isOutdoorSpecialProp(BlockState state) {
        return (blockFlags(state) & BF_OUTDOOR_SPECIAL) != 0;
    }

    public static boolean isOutdoorNaturalGround(BlockState state) {
        return (blockFlags(state) & BF_OUTDOOR_NATURAL_GROUND) != 0;
    }

    public static boolean isNaturalStone(BlockState state) {
        return (blockFlags(state) & BF_NATURAL_STONE) != 0;
    }

    public static boolean isBiomeFoliageTinted(BlockState state) {
        return (blockFlags(state) & BF_BIOME_FOLIAGE_TINTED) != 0;
    }

    public static boolean isBiomeGrassTinted(BlockState state, MapColor mapColor) {
        return state.is(Blocks.GRASS_BLOCK) || mapColor == MapColor.GRASS;
    }

    public static boolean isSnowOrIce(BlockState state, MapColor mapColor) {
        return mapColor == MapColor.SNOW
                || mapColor == MapColor.ICE
                || state.is(BlockTags.SNOW)
                || state.is(BlockTags.ICE);
    }

    public static boolean isSandOrArid(BlockState state, MapColor mapColor) {
        return mapColor == MapColor.SAND || mapColor == MapColor.COLOR_ORANGE
                || state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA) || state.is(Blocks.GRAVEL);
    }

    public static boolean isNaturalTerrain(BlockState state) {
        return isNaturalStone(state)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.CLAY)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.SNOW)
                || state.is(BlockTags.ICE)
                || state.is(Blocks.BEDROCK)
                || isCanopyBlock(state)
                || isGroundFloraToSkip(state);
    }

    public static boolean isCoralOrPickle(BlockState state) {
        return state.is(BlockTags.CORALS)
                || state.is(BlockTags.CORAL_BLOCKS)
                || state.is(BlockTags.WALL_CORALS)
                || state.is(Blocks.SEA_PICKLE);
    }

    public static boolean isUnderwaterFlora(BlockState state) {
        return state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS);
    }

    public static boolean isOutdoorLanternOrTorch(BlockState state) {
        return state.is(Blocks.LANTERN)
                || state.is(Blocks.SOUL_LANTERN)
                || state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH)
                || state.is(Blocks.SOUL_WALL_TORCH);
    }

    public static boolean isBridgeDeckCandidate(BlockState state) {
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.SLABS)
                || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.STONE_BRICKS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.BRICKS)
                || state.is(Blocks.CAMPFIRE);
    }

    public static boolean isWoodBridgeMaterial(BlockState state) {
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.LOGS)
                || state.is(Blocks.CAMPFIRE);
    }

    public static int classifyGroundFlora(BlockState state) {
        int code = FLORA_CODE_MAP.getInt(state.getBlock());
        if (code >= 0) {
            return code;
        }
        if (state.is(BlockTags.FLOWERS)) {
            return (AtlasChunkTerrain.DETAIL_FLOWER_SINGLE << 8) | AtlasChunkTerrain.ACCENT_GOLD_YELLOW;
        }
        if (state.is(BlockTags.SAPLINGS)) {
            return (AtlasChunkTerrain.DETAIL_FERN_TALLGRASS << 8) | AtlasChunkTerrain.ACCENT_MELON_GREEN;
        }
        return AtlasChunkTerrain.DETAIL_NONE;
    }

    public static int classifyCropStage(BlockState state) {
        if (state.is(Blocks.FARMLAND)) {
            return AtlasChunkTerrain.DETAIL_NONE;
        }
        float ratio = 0.0f;
        if (state.getBlock() instanceof CropBlock crop) {
            int maxAge = crop.getMaxAge();
            ratio = maxAge > 0 ? crop.getAge(state) / (float) maxAge : 1.0f;
        } else if (state.is(Blocks.NETHER_WART) && state.hasProperty(NetherWartBlock.AGE)) {
            ratio = state.getValue(NetherWartBlock.AGE) / 3.0f;
        } else if (state.hasProperty(BlockStateProperties.AGE_7)) {
            ratio = state.getValue(BlockStateProperties.AGE_7) / 7.0f;
        } else if (state.hasProperty(BlockStateProperties.AGE_3)) {
            ratio = state.getValue(BlockStateProperties.AGE_3) / 3.0f;
        }
        if (ratio >= 0.85f) {
            int accent;
            if (state.is(Blocks.CARROTS)) {
                accent = AtlasChunkTerrain.ACCENT_ORANGE;
            } else if (state.is(Blocks.BEETROOTS)) {
                accent = AtlasChunkTerrain.ACCENT_BERRY_CRIMSON;
            } else if (state.is(Blocks.POTATOES)) {
                accent = AtlasChunkTerrain.ACCENT_BROWN_OCHRE;
            } else if (state.is(Blocks.NETHER_WART)) {
                accent = AtlasChunkTerrain.ACCENT_SPORE_MAGENTA;
            } else if (state.is(Blocks.TORCHFLOWER_CROP) || state.is(Blocks.PITCHER_CROP)) {
                accent = AtlasChunkTerrain.ACCENT_ORCHID_CYAN;
            } else {
                accent = AtlasChunkTerrain.ACCENT_GOLD_YELLOW;
            }
            return (AtlasChunkTerrain.DETAIL_OUTDOOR_CROP_STAGE << 8) | accent;
        }
        if (ratio >= 0.35f) {
            return (AtlasChunkTerrain.DETAIL_OUTDOOR_CROP_STAGE << 8) | AtlasChunkTerrain.ACCENT_MELON_GREEN;
        }
        return AtlasChunkTerrain.DETAIL_NONE;
    }

    public static int classifyBannerAccent(BlockState state) {
        if (state.getBlock() instanceof AbstractBannerBlock banner) {
            return switch (banner.getColor()) {
                case RED -> AtlasChunkTerrain.ACCENT_RED;
                case YELLOW -> AtlasChunkTerrain.ACCENT_GOLD_YELLOW;
                case BLUE, LIGHT_BLUE -> AtlasChunkTerrain.ACCENT_CORNFLOWER;
                case CYAN -> AtlasChunkTerrain.ACCENT_ORCHID_CYAN;
                case PURPLE -> AtlasChunkTerrain.ACCENT_LILAC_PURPLE;
                case MAGENTA -> AtlasChunkTerrain.ACCENT_SPORE_MAGENTA;
                case PINK -> AtlasChunkTerrain.ACCENT_PINK;
                case ORANGE -> AtlasChunkTerrain.ACCENT_ORANGE;
                case WHITE -> AtlasChunkTerrain.ACCENT_WHITE_CREAM;
                case BLACK -> AtlasChunkTerrain.ACCENT_DARK_WITHER;
                case BROWN -> AtlasChunkTerrain.ACCENT_BROWN_OCHRE;
                case GREEN, LIME -> AtlasChunkTerrain.ACCENT_MELON_GREEN;
                default -> AtlasChunkTerrain.ACCENT_STONE_GRAY;
            };
        }
        return AtlasChunkTerrain.ACCENT_STONE_GRAY;
    }

    private static int computeBlockFlags(Block block) {
        if (block == Blocks.AIR) {
            return BF_INDOOR_PASSABLE_FAST;
        }
        BlockState def = block.defaultBlockState();
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
        int f = 0;
        if (id.endsWith("_stained_glass_pane")) {
            f |= BF_TRANSLUCENT | BF_STAINED_GLASS_PANE;
        } else if (block == Blocks.GLASS || block == Blocks.GLASS_PANE || block == Blocks.TINTED_GLASS
                || block == Blocks.SLIME_BLOCK || block == Blocks.HONEY_BLOCK
                || id.endsWith("_stained_glass") || id.equals("glass") || id.equals("glass_pane")) {
            f |= BF_TRANSLUCENT;
        }
        if (isStructureOrRoadBlock(block, id)
                || def.is(BlockTags.BEDS)
                || def.is(BlockTags.WOOL_CARPETS)
                || def.is(BlockTags.PLANKS)
                || def.is(BlockTags.LOGS)
                || def.is(BlockTags.STONE_BRICKS)
                || def.is(BlockTags.WALLS)
                || def.is(BlockTags.FENCES)
                || def.is(BlockTags.FENCE_GATES)
                || def.is(BlockTags.STAIRS)
                || def.is(BlockTags.SLABS)
                || def.is(BlockTags.WOOL)
                || def.is(BlockTags.RAILS)
                || def.is(BlockTags.DOORS)
                || def.is(BlockTags.TRAPDOORS)) {
            f |= BF_STRUCTURE_OR_ROAD;
        }
        boolean cropOrFarmland = def.is(BlockTags.CROPS) || block == Blocks.FARMLAND || block == Blocks.NETHER_WART;
        if (cropOrFarmland) {
            f |= BF_CROP_OR_FARMLAND;
        } else if (isGroundFloraCandidateBlock(block, def)) {
            f |= BF_FLORA_CANDIDATE;
        }
        if (def.is(BlockTags.LEAVES)
                || def.is(BlockTags.WART_BLOCKS)
                || block == Blocks.BROWN_MUSHROOM_BLOCK
                || block == Blocks.RED_MUSHROOM_BLOCK
                || block == Blocks.MANGROVE_ROOTS) {
            f |= BF_CANOPY;
        }
        if (block == Blocks.NETHER_PORTAL
                || isOutdoorLanternOrTorch(def)
                || block == Blocks.CAMPFIRE
                || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.BELL
                || block == Blocks.CAULDRON
                || block == Blocks.WATER_CAULDRON
                || block == Blocks.LAVA_CAULDRON
                || block == Blocks.POWDER_SNOW_CAULDRON
                || def.is(BlockTags.BANNERS)) {
            f |= BF_OUTDOOR_SPECIAL;
        }
        if (block == Blocks.STONE || block == Blocks.DIORITE || block == Blocks.CALCITE
                || block == Blocks.GRANITE || block == Blocks.ANDESITE || block == Blocks.TUFF
                || block == Blocks.DEEPSLATE || block == Blocks.GRAVEL || def.is(BlockTags.ORES)) {
            f |= BF_NATURAL_STONE;
        }
        if (block == Blocks.GRASS_BLOCK
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT
                || block == Blocks.PODZOL
                || block == Blocks.FARMLAND
                || block == Blocks.DIRT_PATH
                || def.is(BlockTags.SAND)
                || block == Blocks.GRAVEL
                || block == Blocks.SNOW_BLOCK
                || block == Blocks.MUD) {
            f |= BF_OUTDOOR_NATURAL_GROUND;
        }
        if (def.is(BlockTags.LEAVES)
                && block != Blocks.BIRCH_LEAVES
                && block != Blocks.SPRUCE_LEAVES
                && block != Blocks.CHERRY_LEAVES
                && block != Blocks.AZALEA_LEAVES
                && block != Blocks.FLOWERING_AZALEA_LEAVES
                && block != Blocks.PALE_OAK_LEAVES) {
            f |= BF_BIOME_FOLIAGE_TINTED;
        }
        if (isIndoorFurnitureOrFixtureBlock(block, def)) {
            f |= BF_INDOOR_FURNITURE;
        }
        if (def.is(BlockTags.DOORS) || def.is(BlockTags.FENCE_GATES)
                || def.is(BlockTags.TRAPDOORS) || def.is(BlockTags.WOOL_CARPETS)
                || block == Blocks.MOSS_CARPET || block == Blocks.PALE_MOSS_CARPET
                || block == Blocks.LADDER) {
            f |= BF_INDOOR_PASSABLE_FAST;
        }
        return f;
    }

    private static boolean isIndoorFurnitureOrFixtureBlock(Block block, BlockState def) {
        return def.is(BlockTags.BEDS)
                || def.is(BlockTags.WOOL_CARPETS)
                || block == Blocks.MOSS_CARPET
                || block == Blocks.PALE_MOSS_CARPET
                || block == Blocks.CHEST
                || block == Blocks.TRAPPED_CHEST
                || block == Blocks.ENDER_CHEST
                || block == Blocks.BARREL
                || block == Blocks.CRAFTING_TABLE
                || block == Blocks.CARTOGRAPHY_TABLE
                || block == Blocks.FLETCHING_TABLE
                || block == Blocks.SMITHING_TABLE
                || block == Blocks.LOOM
                || block == Blocks.STONECUTTER
                || block == Blocks.GRINDSTONE
                || block == Blocks.FURNACE
                || block == Blocks.SMOKER
                || block == Blocks.BLAST_FURNACE
                || block == Blocks.CAMPFIRE
                || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.CAULDRON
                || block == Blocks.WATER_CAULDRON
                || block == Blocks.LAVA_CAULDRON
                || block == Blocks.POWDER_SNOW_CAULDRON
                || block == Blocks.BREWING_STAND
                || block == Blocks.ENCHANTING_TABLE
                || block == Blocks.LECTERN
                || block == Blocks.COMPOSTER
                || block == Blocks.ANVIL
                || block == Blocks.CHIPPED_ANVIL
                || block == Blocks.DAMAGED_ANVIL
                || block == Blocks.BELL
                || block == Blocks.LANTERN
                || block == Blocks.SOUL_LANTERN
                || block == Blocks.CAKE
                || block == Blocks.DECORATED_POT
                || def.is(BlockTags.FLOWER_POTS)
                || def.is(BlockTags.CANDLES)
                || block == Blocks.SCAFFOLDING;
    }

    private static boolean isGroundFloraCandidateBlock(Block block, BlockState def) {
        return block instanceof VegetationBlock
                || block == Blocks.SHORT_GRASS
                || block == Blocks.TALL_GRASS
                || block == Blocks.SUGAR_CANE
                || block == Blocks.BAMBOO_SAPLING
                || block == Blocks.SMALL_DRIPLEAF
                || block == Blocks.BIG_DRIPLEAF
                || block == Blocks.BIG_DRIPLEAF_STEM
                || block == Blocks.POINTED_DRIPSTONE
                || block == Blocks.KELP
                || block == Blocks.KELP_PLANT
                || block == Blocks.SEAGRASS
                || block == Blocks.TALL_SEAGRASS
                || block == Blocks.VINE
                || block == Blocks.CAVE_VINES
                || block == Blocks.CAVE_VINES_PLANT
                || block == Blocks.WEEPING_VINES
                || block == Blocks.WEEPING_VINES_PLANT
                || block == Blocks.TWISTING_VINES
                || block == Blocks.TWISTING_VINES_PLANT
                || block == Blocks.HANGING_ROOTS
                || block == Blocks.GLOW_LICHEN
                || block == Blocks.SPORE_BLOSSOM
                || block == Blocks.MOSS_CARPET
                || block == Blocks.PALE_MOSS_CARPET
                || block == Blocks.PALE_HANGING_MOSS
                || block == Blocks.LILY_PAD
                || block == Blocks.FROGSPAWN
                || block == Blocks.FERN
                || block == Blocks.LARGE_FERN
                || block == Blocks.DEAD_BUSH
                || block == Blocks.BUSH
                || block == Blocks.SHORT_DRY_GRASS
                || block == Blocks.TALL_DRY_GRASS
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.FIREFLY_BUSH
                || block == Blocks.BROWN_MUSHROOM
                || block == Blocks.RED_MUSHROOM
                || block == Blocks.PINK_PETALS
                || block == Blocks.WILDFLOWERS
                || block == Blocks.LEAF_LITTER
                || def.is(BlockTags.FLOWERS)
                || def.is(BlockTags.SAPLINGS);
    }

    private static boolean isStructureOrRoadBlock(Block block, String id) {
        if (block == Blocks.BOOKSHELF || block == Blocks.CHISELED_BOOKSHELF
                || block == Blocks.CRAFTING_TABLE || block == Blocks.FURNACE
                || block == Blocks.SMOKER || block == Blocks.BLAST_FURNACE || block == Blocks.LECTERN
                || block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.GOLD_BLOCK || block == Blocks.IRON_BLOCK || block == Blocks.DIAMOND_BLOCK
                || block == Blocks.EMERALD_BLOCK || block == Blocks.LAPIS_BLOCK || block == Blocks.REDSTONE_BLOCK
                || block == Blocks.POLISHED_DEEPSLATE || block == Blocks.MUD_BRICKS
                || block == Blocks.LANTERN || block == Blocks.SOUL_LANTERN
                || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST
                || block == Blocks.BARREL || block == Blocks.BELL
                || block == Blocks.IRON_CHAIN || block == Blocks.LADDER || block == Blocks.SCAFFOLDING
                || block == Blocks.DIRT_PATH
                || block == Blocks.COBBLESTONE
                || block == Blocks.MOSSY_COBBLESTONE
                || block == Blocks.BRICKS
                || block == Blocks.FARMLAND
                || block == Blocks.HAY_BLOCK
                || block == Blocks.SMOOTH_STONE) {
            return true;
        }
        return ((id.contains("copper") || id.contains("quartz")) && !id.endsWith("_ore")) || id.contains("prismarine")
                || id.contains("deepslate_brick") || id.contains("deepslate_tile")
                || id.contains("polished_blackstone") || id.contains("gilded_blackstone")
                || id.contains("polished_granite") || id.contains("polished_diorite") || id.contains("polished_andesite")
                || id.contains("polished_tuff") || id.contains("tuff_brick")
                || id.contains("cut_sandstone") || id.contains("smooth_sandstone") || id.contains("chiseled_")
                || id.contains("nether_brick") || id.contains("end_stone_brick") || id.contains("purpur")
                || id.contains("concrete") || id.equals("glass") || id.equals("glass_pane")
                || id.endsWith("_glass") || id.endsWith("_glass_pane") || id.equals("iron_bars")
                || id.endsWith("_carpet") || id.endsWith("_glazed_terracotta");
    }
}
