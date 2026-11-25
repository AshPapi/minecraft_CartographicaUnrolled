package com.cartographica_unrolled.util;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongConsumer;

public final class StripedLong2ObjectMap<V> {
    private static final int STRIPE_BITS = 5;
    private static final int STRIPE_COUNT = 1 << STRIPE_BITS;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;

    private final ReentrantReadWriteLock[] locks = new ReentrantReadWriteLock[STRIPE_COUNT];
    private final Long2ObjectOpenHashMap<V>[] maps;
    private final AtomicInteger totalSize = new AtomicInteger(0);

    @SuppressWarnings("unchecked")
    public StripedLong2ObjectMap(int initialTotalCapacity) {
        this.maps = (Long2ObjectOpenHashMap<V>[]) new Long2ObjectOpenHashMap<?>[STRIPE_COUNT];
        int perStripe = Math.max(16, initialTotalCapacity >>> STRIPE_BITS);
        for (int i = 0; i < STRIPE_COUNT; i++) {
            this.locks[i] = new ReentrantReadWriteLock();
            this.maps[i] = new Long2ObjectOpenHashMap<>(perStripe);
        }
    }

    private static int stripe(long key) {
        return (int) (HashCommon.mix(key) & STRIPE_MASK);
    }

    public @Nullable V get(long key) {
        int s = stripe(key);
        ReentrantReadWriteLock.ReadLock rl = this.locks[s].readLock();
        rl.lock();
        try {
            return this.maps[s].get(key);
        } finally {
            rl.unlock();
        }
    }

    public boolean containsKey(long key) {
        int s = stripe(key);
        ReentrantReadWriteLock.ReadLock rl = this.locks[s].readLock();
        rl.lock();
        try {
            return this.maps[s].containsKey(key);
        } finally {
            rl.unlock();
        }
    }

    public @Nullable V put(long key, V value) {
        int s = stripe(key);
        ReentrantReadWriteLock.WriteLock wl = this.locks[s].writeLock();
        wl.lock();
        try {
            V prev = this.maps[s].put(key, value);
            if (prev == null) {
                this.totalSize.incrementAndGet();
            }
            return prev;
        } finally {
            wl.unlock();
        }
    }

    public @Nullable V putIfAbsent(long key, V value) {
        int s = stripe(key);
        ReentrantReadWriteLock.WriteLock wl = this.locks[s].writeLock();
        wl.lock();
        try {
            Long2ObjectOpenHashMap<V> m = this.maps[s];
            V prev = m.get(key);
            if (prev != null) {
                return prev;
            }
            m.put(key, value);
            this.totalSize.incrementAndGet();
            return null;
        } finally {
            wl.unlock();
        }
    }

    public @Nullable V remove(long key) {
        int s = stripe(key);
        ReentrantReadWriteLock.WriteLock wl = this.locks[s].writeLock();
        wl.lock();
        try {
            V prev = this.maps[s].remove(key);
            if (prev != null) {
                this.totalSize.decrementAndGet();
            }
            return prev;
        } finally {
            wl.unlock();
        }
    }

    public boolean isEmpty() {
        return this.totalSize.get() == 0;
    }

    public int size() {
        return this.totalSize.get();
    }

    public void clear() {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            ReentrantReadWriteLock.WriteLock wl = this.locks[i].writeLock();
            wl.lock();
            try {
                int removed = this.maps[i].size();
                if (removed > 0) {
                    this.maps[i].clear();
                    this.totalSize.addAndGet(-removed);
                }
            } finally {
                wl.unlock();
            }
        }
    }

    public long[] toKeyArray() {
        int approx = Math.max(0, this.totalSize.get());
        if (approx == 0) {
            return new long[0];
        }
        LongArrayList out = new LongArrayList(approx);
        for (int i = 0; i < STRIPE_COUNT; i++) {
            ReentrantReadWriteLock.ReadLock rl = this.locks[i].readLock();
            rl.lock();
            try {
                out.addAll(this.maps[i].keySet());
            } finally {
                rl.unlock();
            }
        }
        return out.toLongArray();
    }

    public void forEachKey(LongConsumer consumer) {
        long[] keys = toKeyArray();
        for (long key : keys) {
            consumer.accept(key);
        }
    }

    public Long2ObjectOpenHashMap<V> snapshot() {
        int approx = Math.max(16, this.totalSize.get());
        Long2ObjectOpenHashMap<V> copy = new Long2ObjectOpenHashMap<>(approx);
        for (int i = 0; i < STRIPE_COUNT; i++) {
            ReentrantReadWriteLock.ReadLock rl = this.locks[i].readLock();
            rl.lock();
            try {
                for (Long2ObjectMap.Entry<V> e : Long2ObjectMaps.fastIterable(this.maps[i])) {
                    copy.put(e.getLongKey(), e.getValue());
                }
            } finally {
                rl.unlock();
            }
        }
        return copy;
    }
}
