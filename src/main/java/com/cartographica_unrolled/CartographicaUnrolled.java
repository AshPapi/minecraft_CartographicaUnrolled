package com.cartographica_unrolled;

import com.cartographica_unrolled.network.ModNetworking;
import com.cartographica_unrolled.registry.ModAttachments;
import com.cartographica_unrolled.registry.ModItems;
import com.cartographica_unrolled.registry.ModSounds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(CartographicaUnrolled.MOD_ID)
public class CartographicaUnrolled {
    public static final String MOD_ID = "cartographica_unrolled";

    public CartographicaUnrolled(IEventBus modEventBus) {
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);
        modEventBus.addListener(ModItems::addCreative);
        modEventBus.addListener(ModNetworking::register);
    }
}
