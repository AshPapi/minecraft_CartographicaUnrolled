package com.cartographica_unrolled.registry;

import com.cartographica_unrolled.CartographicaUnrolled;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, CartographicaUnrolled.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> BRUSH_PAINT = register("brush_paint");
    public static final DeferredHolder<SoundEvent, SoundEvent> ERASER_RUB = register("eraser_rub");
    public static final DeferredHolder<SoundEvent, SoundEvent> MARKER_PLACE = register("marker_place");
    public static final DeferredHolder<SoundEvent, SoundEvent> MARKER_REMOVE = register("marker_remove");

    private ModSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(
                Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, name)));
    }
}
