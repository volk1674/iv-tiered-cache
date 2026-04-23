package io.github.iv.tieredcache.redis.reactive;

import io.github.iv.tieredcache.ManyResult;
import io.github.iv.tieredcache.ReactiveTieredCacheL2;
import io.github.iv.tieredcache.TieredCacheProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

@Slf4j
public class ReactiveTieredCacheRedis<K, V> implements ReactiveTieredCacheL2<K, V> {
    private static final String CACHE_GETS_TOTAL = "cache_gets_total";
    private static final String CACHE_PUTS_TOTAL = "cache_puts_total";
    private static final String CACHE_EVICTIONS_TOTAL = "cache_evictions_total";

    private final ReactiveRedisTemplate<String, V> redis;
    private final String keyPrefix;
    private final String cacheName;

    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter putsCounter;
    private final Counter evictionsCounter;
    private final Predicate<V> metadataVersionValidator;

    public ReactiveTieredCacheRedis(ReactiveRedisTemplate<String, V> redis,
                                    TieredCacheProperties cacheProperties,
                                    Predicate<V> metadataVersionValidator,
                                    MeterRegistry meterRegistry) {
        this.redis = redis;
        this.keyPrefix = cacheProperties.getName() + ":";

        var cacheTag = Tag.of("cache", cacheProperties.getName());
        this.hitCounter = meterRegistry.counter(CACHE_GETS_TOTAL, List.of(cacheTag, Tag.of("result", "hit")));
        this.missCounter = meterRegistry.counter(CACHE_GETS_TOTAL, List.of(cacheTag, Tag.of("result", "miss")));
        this.putsCounter = meterRegistry.counter(CACHE_PUTS_TOTAL, List.of(cacheTag));
        this.evictionsCounter = meterRegistry.counter(CACHE_EVICTIONS_TOTAL, List.of(cacheTag));
        this.metadataVersionValidator = metadataVersionValidator;
        this.cacheName = cacheProperties.getName();
    }

    @Override
    public Mono<V> get(K key) {
        if (log.isDebugEnabled()) {
            log.debug("Кеш [{}]: Пытаемся получить значение ключа {} из redis", cacheName, key);
        }

        return redis.opsForValue()
                .get(redisKey(key))
                .filter(metadataVersionValidator)
                .doOnNext(v -> {
                    hitCounter.increment();
                    if (log.isDebugEnabled()) {
                        log.debug("Кеш [{}]: Значение для ключа {} успешно получено из redis", cacheName, key);
                    }
                }).switchIfEmpty(Mono.defer(() -> {
                    missCounter.increment();
                    if (log.isDebugEnabled()) {
                        log.debug("Кеш [{}]: Значение для ключа {} не найдено в redis", cacheName, key);
                    }
                    return Mono.empty();
                }));
    }

    @Override
    public Mono<ManyResult<K, V>> multiGet(Collection<K> keys) {
        var redisKeys = redisKeys(keys);

        if (log.isDebugEnabled()) {
            log.debug("Кеш [{}]: Пытаемся получить значение ключей {} из redis", cacheName, keys);
        }

        return redis.opsForValue().multiGet(redisKeys)
                .map(vs -> {
                    List<K> missingKeys = new ArrayList<>();
                    Map<K, V> values = new HashMap<>(keys.size());
                    int i = 0;
                    for (K key : keys) {
                        var val = vs.get(i++);
                        if (val == null) {
                            missingKeys.add(key);
                        } else {
                            values.put(key, val);
                        }
                    }
                    return new ManyResult<>(values, missingKeys);
                }).doOnNext(res -> {
                    hitCounter.increment(res.values().size());
                    missCounter.increment(res.missingKeys().size());

                    if (log.isDebugEnabled()) {
                        log.debug("Кеш [{}]: Успешно получили значения для {}/{} ключей из L2 кеша.", cacheName,
                                res.values().size(), keys.size());
                    }
                })
                .onErrorResume(throwable -> {
                    log.error("Кеш [{}]: Ошибка при обращении к L2 для ключей {}. Сообщение: {}",
                            cacheName, keys, throwable.getMessage());

                    // Возвращаем пустой результат, отсутсвие данных в кеше не должно приовдить к ошибке вызова.
                    return Mono.just(new ManyResult<>(Map.of(), keys));
                });
    }

    @Override
    public Mono<Boolean> evict(K key) {
        return redis.delete(redisKey(key))
                .doOnNext(evictionsCounter::increment)
                .map(cnt -> cnt > 0);
    }

    @Override
    public Mono<V> put(K key, V value, Duration duration) {
        return redis.opsForValue()
                .set(redisKey(key), value, duration)
                .doOnNext(dummy -> {
                    putsCounter.increment();

                    if (log.isDebugEnabled()) {
                        log.debug("Кеш [{}]: Значение для ключа {} успешно сохранено в redis", cacheName, key);
                    }
                })
                .then(Mono.just(value));
    }

    private String redisKey(K key) {
        return keyPrefix + key;
    }

    private List<String> redisKeys(Collection<K> keys) {
        List<String> result = new ArrayList<>(keys.size());
        for (K key : keys) {
            result.add(redisKey(key));
        }
        return result;
    }
}
