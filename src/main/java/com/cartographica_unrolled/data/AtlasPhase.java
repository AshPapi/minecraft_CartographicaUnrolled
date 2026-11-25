package com.cartographica_unrolled.data;

import com.cartographica_unrolled.registry.ModAttachments;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

public enum AtlasPhase {
    CLOSED,
    UNFOLDING,
    READING_UI,
    FOLDING;

    public static final int UNFOLD_TICKS = 17;
    public static final int FOLD_TICKS = 11;

    private static final AtlasPhase[] VALUES = values();

    public static final StreamCodec<ByteBuf, AtlasPhase> STREAM_CODEC = ByteBufCodecs.idMapper(
            id -> VALUES[Mth.clamp(id, 0, VALUES.length - 1)],
            Enum::ordinal
    );

    public static boolean canStartUnfolding(Player player) {
        return canKeepUsing(player) && !player.isUsingItem();
    }

    public static boolean canKeepUsing(Player player) {
        if (player == null || !player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (player.getData(ModAttachments.ATLAS_SLOT).isEmpty()) {
            return false;
        }
        if (player.isInWater() || player.isInLava() || player.isUnderWater()
                || player.isPassenger() || player.isFallFlying() || player.isSleeping()) {
            return false;
        }
        return true;
    }
}
