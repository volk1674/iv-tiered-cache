package io.github.iv.tieredcache.redis.reactive;

import io.github.iv.tieredcache.ReactiveTieredCacheInvalidationChannel;
import io.github.iv.tieredcache.TieredCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
public class ReactiveTieredCacheInvalidationChannelRedis<K> implements ReactiveTieredCacheInvalidationChannel<K> {
    protected static final String INVALIDATION_TOPIC_PREFIX = "cache:invalidation:";

    private final ReactiveRedisTemplate<String, K> redis;
    private final String invalidationTopic;


    public ReactiveTieredCacheInvalidationChannelRedis(ReactiveRedisTemplate<String, K> redis,
                                                       TieredCacheProperties cacheProperties) {
        this.redis = redis;
        this.invalidationTopic = INVALIDATION_TOPIC_PREFIX + cacheProperties.getName();
    }

    @Override
    public Flux<K> listen() {
        return redis.listenToChannel(invalidationTopic)
                .onErrorContinue((throwable, message) -> {
                    log.error("Failed to process or deserialize cache invalidation message from topic '{}'. Error: {}",
                            invalidationTopic, throwable.getMessage(), throwable);
                })
                .map(ReactiveSubscription.Message::getMessage);
    }

    @Override
    public Mono<Void> send(K key) {
        return redis.convertAndSend(invalidationTopic, key).then();
    }
}
