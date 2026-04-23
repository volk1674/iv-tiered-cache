package io.github.iv.tieredcache;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

public interface ReactiveTieredCacheDataProvider<K, V> {

    Mono<Map<K, V>> load(Collection<K> keys);

    default boolean checkMetadataVersion(V v) {
        return true;
    }
}
