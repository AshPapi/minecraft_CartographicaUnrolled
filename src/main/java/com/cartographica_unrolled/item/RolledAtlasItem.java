package com.cartographica_unrolled.item;

import com.cartographica_unrolled.data.AtlasPhase;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class RolledAtlasItem extends Item {
    public static Runnable clientUnfoldHook = () -> {};

    public RolledAtlasItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean canFitInsideContainerItems(ItemStack stack) {
        return false;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (AtlasPhase.canStartUnfolding(player)) {
            if (level.isClientSide()) {
                clientUnfoldHook.run();
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
