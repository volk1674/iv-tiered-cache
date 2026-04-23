package io.github.iv.tieredcache;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/**
 * Reactive tiered cache interface providing asynchronous cache operations.
 * Supports multi-level caching with reactive programming model.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
public interface ReactiveTieredCache<K, V> {

    /**
     * Retrieves a value from the cache by its key.
     * If the value is not found in the cache, it may be loaded from the data provider.
     *
     * @param key the key whose associated value is to be returned
     * @return a Mono emitting the cached value, or empty if not found
     */
    Mono<V> get(K key);

    /**
     * Retrieves multiple values from the cache by their keys.
     * Returns a map containing only the keys that were found in the cache.
     *
     * @param keys the collection of keys whose associated values are to be returned
     * @return a Mono emitting a map of keys to their cached values
     */
    Mono<Map<K, V>> getMany(Collection<K> keys);

    /**
     * Refreshes the cached value for the specified key.
     * Forces reload of the value from the data provider and updates the cache.
     *
     * @param key the key whose associated value is to be refreshed
     * @return a Mono emitting the refreshed value, or empty if refresh failed
     */
    Mono<V> refresh(K key);

    /**
     * Removes the mapping for a key from the cache.
     * Evicts the entry from all cache levels.
     *
     * @param key the key whose mapping is to be removed
     * @return a Mono emitting true if the key was present and removed, false otherwise
     */
    Mono<Boolean> evict(K key);

}
