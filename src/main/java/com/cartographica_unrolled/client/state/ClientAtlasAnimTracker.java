package com.cartographica_unrolled.client.state;

import com.cartographica_unrolled.data.AtlasPhase;
import com.cartographica_unrolled.registry.ModAttachments;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

public final class ClientAtlasAnimTracker {
    private static final class Entry {
        float prevProgress;
        float progress;
        AtlasPhase lastPhase;

        Entry(AtlasPhase initialPhase) {
            this.lastPhase = initialPhase;
            float initial = (initialPhase == AtlasPhase.READING_UI || initialPhase == AtlasPhase.FOLDING) ? 1.0f : 0.0f;
            this.prevProgress = initial;
            this.progress = initial;
        }
    }

    private static final Int2ObjectOpenHashMap<Entry> ENTRIES = new Int2ObjectOpenHashMap<>();
    private static int pruneCounter = 0;

    private ClientAtlasAnimTracker() {
    }

    public static AtlasPhase getPhase(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entity.getId() == mc.player.getId()) {
            return AtlasStateManager.getPhase();
        }
        return entity.getData(ModAttachments.ATLAS_STATE);
    }

    public static float getProgress(Entity entity, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entity.getId() == mc.player.getId()) {
            return AtlasStateManager.getInterpolatedProgress(partialTick);
        }
        AtlasPhase phase = entity.getData(ModAttachments.ATLAS_STATE);
        Entry entry = ENTRIES.get(entity.getId());
        if (entry == null) {
            if (phase == AtlasPhase.CLOSED) {
                return 0.0f;
            }
            entry = new Entry(phase);
            ENTRIES.put(entity.getId(), entry);
        }
        return Mth.lerp(partialTick, entry.prevProgress, entry.progress);
    }

    public static void tick(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();

        for (AbstractClientPlayer player : level.players()) {
            if (mc.player != null && player.getId() == mc.player.getId()) {
                continue;
            }
            AtlasPhase phase = player.getData(ModAttachments.ATLAS_STATE);
            Entry entry = ENTRIES.get(player.getId());
            if (entry == null) {
                if (phase == AtlasPhase.CLOSED) {
                    continue;
                }
                entry = new Entry(phase);
                ENTRIES.put(player.getId(), entry);
            }
            entry.prevProgress = entry.progress;
            entry.lastPhase = phase;
            switch (phase) {
                case UNFOLDING -> entry.progress = Math.min(1.0f, entry.progress + (1.0f / AtlasPhase.UNFOLD_TICKS));
                case READING_UI -> entry.progress = Math.min(1.0f, entry.progress + (2.0f / AtlasPhase.UNFOLD_TICKS));
                case FOLDING -> entry.progress = Math.max(0.0f, entry.progress - (1.0f / AtlasPhase.FOLD_TICKS));
                case CLOSED -> entry.progress = Math.max(0.0f, entry.progress - 0.25f);
            }
        }

        if (++pruneCounter >= 100) {
            pruneCounter = 0;
            ObjectIterator<Int2ObjectMap.Entry<Entry>> it = ENTRIES.int2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                Int2ObjectMap.Entry<Entry> mapEntry = it.next();
                Entity entity = level.getEntity(mapEntry.getIntKey());
                Entry anim = mapEntry.getValue();
                if (entity == null || (anim.lastPhase == AtlasPhase.CLOSED && anim.progress <= 0.0f && anim.prevProgress <= 0.0f)) {
                    it.remove();
                }
            }
        }
    }

    public static boolean hasAnyActive() {
        return !ENTRIES.isEmpty();
    }

    public static void clear() {
        ENTRIES.clear();
        pruneCounter = 0;
    }
}
