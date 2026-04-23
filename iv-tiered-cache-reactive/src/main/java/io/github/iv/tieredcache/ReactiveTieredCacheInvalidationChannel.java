package io.github.iv.tieredcache;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ReactiveTieredCacheInvalidationChannel<K> {

    Flux<K> listen();

    Mono<Void> send(K key);

}
