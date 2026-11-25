package com.cartographica_unrolled.inventory;

import com.cartographica_unrolled.item.RolledAtlasItem;
import com.cartographica_unrolled.registry.ModAttachments;
import com.cartographica_unrolled.registry.ModItems;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class AtlasContainer implements Container {
    private final Player player;
    private ItemStack fallbackStack = ItemStack.EMPTY;

    public AtlasContainer(Player player) {
        this.player = player;
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot != 0) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = player.getData(ModAttachments.ATLAS_SLOT);
        if (stack.isEmpty() || !(stack.getItem() instanceof RolledAtlasItem)) {
            if (this.fallbackStack.isEmpty()) {
                this.fallbackStack = ModItems.ROLLED_ATLAS.toStack();
            }
            if (!player.level().isClientSide()) {
                player.setData(ModAttachments.ATLAS_SLOT, this.fallbackStack.copy());
            }
            return this.fallbackStack;
        }
        return stack;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == 0 && !stack.isEmpty() && stack.getItem() instanceof RolledAtlasItem) {
            player.setData(ModAttachments.ATLAS_SLOT, stack.copyWithCount(1));
        }
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(Player player) {
        return this.player == player;
    }

    @Override
    public void clearContent() {
    }
}
