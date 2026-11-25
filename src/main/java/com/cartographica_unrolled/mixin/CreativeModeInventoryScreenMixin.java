package com.cartographica_unrolled.mixin;

import com.cartographica_unrolled.client.state.AtlasStateManager;
import com.cartographica_unrolled.inventory.AtlasSlot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {
    @Shadow
    private static CreativeModeTab selectedTab;

    protected CreativeModeInventoryScreenMixin(CreativeModeInventoryScreen.ItemPickerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @ModifyArgs(
            method = "selectTab",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen$SlotWrapper;<init>(Lnet/minecraft/world/inventory/Slot;III)V"
            )
    )
    private void cartographica_unrolled$repositionAtlasSlot(Args args) {
        Slot target = args.get(0);
        if (target instanceof AtlasSlot) {
            args.set(2, 127);
            args.set(3, 20);
        }
    }

    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void cartographica_unrolled$renderAtlasSlotBg(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (selectedTab != null && selectedTab.getType() == CreativeModeTab.Type.INVENTORY) {
            guiGraphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    AbstractContainerScreen.INVENTORY_LOCATION,
                    this.leftPos + 126,
                    this.topPos + 19,
                    76.0F,
                    61.0F,
                    18,
                    18,
                    256,
                    256
            );
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void cartographica_unrolled$onMouseClicked(MouseButtonEvent event, boolean isDoubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (selectedTab != null && selectedTab.getType() == CreativeModeTab.Type.INVENTORY
                && (event.button() == 0 || event.button() == 1)
                && this.menu.getCarried().isEmpty()
                && AtlasSlot.isAtlasSlot(this.hoveredSlot)) {
            if (AtlasStateManager.tryStartUnfolding()) {
                this.onClose();
            }
            cir.setReturnValue(true);
        }
    }
}
