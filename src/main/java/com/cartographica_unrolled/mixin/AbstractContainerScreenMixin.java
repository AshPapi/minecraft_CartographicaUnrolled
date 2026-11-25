package com.cartographica_unrolled.mixin;

import com.cartographica_unrolled.client.state.AtlasStateManager;
import com.cartographica_unrolled.inventory.AtlasSlot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin<T extends AbstractContainerMenu> {
    @Shadow
    protected Slot hoveredSlot;

    @Shadow
    public abstract T getMenu();

    @Shadow
    public abstract void onClose();

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cartographica_unrolled$onMouseClicked(MouseButtonEvent event, boolean isDoubleClick, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof InventoryScreen
                && (event.button() == 0 || event.button() == 1)
                && this.getMenu().getCarried().isEmpty()
                && AtlasSlot.isAtlasSlot(this.hoveredSlot)) {
            if (AtlasStateManager.tryStartUnfolding()) {
                this.onClose();
            }
            cir.setReturnValue(true);
        }
    }
}
