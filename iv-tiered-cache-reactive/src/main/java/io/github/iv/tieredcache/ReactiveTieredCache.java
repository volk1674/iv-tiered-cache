package io.github.iv.tieredcache;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

public interface ReactiveTieredCache<K, V> {

    Mono<V> get(K key);

    Mono<Map<K, V>> getMany(Collection<K> keys);

    Mono<V> refresh(K key);

    Mono<Boolean> evict(K key);

}
