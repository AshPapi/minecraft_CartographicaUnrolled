package com.cartographica_unrolled.mixin;

import com.cartographica_unrolled.inventory.AtlasContainer;
import com.cartographica_unrolled.inventory.AtlasSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin extends AbstractContainerMenu {
    protected InventoryMenuMixin(MenuType<?> menuType, int containerId) {
        super(menuType, containerId);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(Inventory playerInventory, boolean active, Player owner, CallbackInfo ci) {
        this.addSlot(new AtlasSlot(new AtlasContainer(owner), 0, 77, 44));
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void cartographica_unrolled$preventAtlasQuickMove(Player player, int slotIndex, CallbackInfoReturnable<ItemStack> cir) {
        if (slotIndex >= 0 && slotIndex < this.slots.size() && this.slots.get(slotIndex) instanceof AtlasSlot) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
