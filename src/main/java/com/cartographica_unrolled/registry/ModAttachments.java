package com.cartographica_unrolled.registry;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.data.AtlasPhase;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.AttachmentSyncHandler;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.Nullable;

public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, CartographicaUnrolled.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ItemStack>> ATLAS_SLOT =
            ATTACHMENT_TYPES.register("atlas_slot", () -> AttachmentType.builder(() -> ModItems.ROLLED_ATLAS.toStack())
                    .serialize(ItemStack.OPTIONAL_CODEC.fieldOf("item"))
                    .copyOnDeath()
                    .sync(new AttachmentSyncHandler<ItemStack>() {
                        @Override
                        public void write(RegistryFriendlyByteBuf buf, ItemStack attachment, boolean initialSync) {
                            ItemStack toWrite = attachment.isEmpty() ? ModItems.ROLLED_ATLAS.toStack() : attachment;
                            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, toWrite);
                        }

                        @Override
                        public @Nullable ItemStack read(IAttachmentHolder holder, RegistryFriendlyByteBuf buf, @Nullable ItemStack previousValue) {
                            ItemStack decoded = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                            return decoded.isEmpty() ? ModItems.ROLLED_ATLAS.toStack() : decoded;
                        }
                    })
                    .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<AtlasPhase>> ATLAS_STATE =
            ATTACHMENT_TYPES.register("atlas_state", () -> AttachmentType.builder(() -> AtlasPhase.CLOSED)
                    .sync(new AttachmentSyncHandler<AtlasPhase>() {
                        @Override
                        public void write(RegistryFriendlyByteBuf buf, AtlasPhase attachment, boolean initialSync) {
                            AtlasPhase.STREAM_CODEC.encode(buf, attachment);
                        }

                        @Override
                        public @Nullable AtlasPhase read(IAttachmentHolder holder, RegistryFriendlyByteBuf buf, @Nullable AtlasPhase previousValue) {
                            return AtlasPhase.STREAM_CODEC.decode(buf);
                        }
                    })
                    .build());
}
