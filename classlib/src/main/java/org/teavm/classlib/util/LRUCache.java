package org.teavm.classlib.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic LRU cache backed by a LinkedHashMap with access-order eviction.
 * TeaVM JS is single-threaded. No synchronization is needed.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class LRUCache<K, V> {

    private final int maxSize;
    private final LinkedHashMap<K, V> map;

    public LRUCache(final int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be > 0");
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
                return size() > LRUCache.this.maxSize;
            }
        };
    }

    public V get(final K key) {
        return map.get(key);
    }

    public void put(final K key, final V value) {
        map.put(key, value);
    }

    public boolean containsKey(final K key) {
        return map.containsKey(key);
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
    }

}
