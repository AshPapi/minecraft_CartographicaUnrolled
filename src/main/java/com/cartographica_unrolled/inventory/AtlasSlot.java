package com.cartographica_unrolled.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

public class AtlasSlot extends Slot {
    public AtlasSlot(Container container, int slot, int x, int y) {
        super(container, slot, x, y);
    }

    public static boolean isAtlasSlot(@Nullable Slot slot) {
        return slot instanceof AtlasSlot || (slot != null && slot.container instanceof AtlasContainer);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return false;
    }

    @Override
    public boolean allowModification(Player player) {
        return false;
    }

    @Override
    public ItemStack safeClone(Player player) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }
}
