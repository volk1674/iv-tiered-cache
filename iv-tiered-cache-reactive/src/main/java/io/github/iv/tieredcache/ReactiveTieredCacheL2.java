package io.github.iv.tieredcache;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;

public interface ReactiveTieredCacheL2<K, V> {

    Mono<V> get(K key);

    Mono<ManyResult<K, V>> multiGet(Collection<K> keys);

    Mono<Boolean> evict(K key);

    Mono<V> put(K key, V value, Duration duration);
}
