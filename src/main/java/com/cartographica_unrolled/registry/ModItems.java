package com.cartographica_unrolled.registry;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.item.RolledAtlasItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CartographicaUnrolled.MOD_ID);

    public static final DeferredItem<RolledAtlasItem> ROLLED_ATLAS = ITEMS.registerItem(
            "rolled_atlas",
            RolledAtlasItem::new,
            props -> props.stacksTo(1)
    );

    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ROLLED_ATLAS);
        }
    }
}
