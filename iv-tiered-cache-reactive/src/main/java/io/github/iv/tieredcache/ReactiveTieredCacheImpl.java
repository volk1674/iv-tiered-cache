package io.github.iv.tieredcache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ReactiveTieredCacheImpl<K, V> implements ReactiveTieredCache<K, V>, SmartLifecycle {
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final ReactiveTieredCacheDataProvider<K, V> dataProvider;
    private final Cache<K, CacheValue<V>> nearCache;

    private final Map<K, Sinks.One<Tuple2<K, V>>> inFlight;
    private final Sinks.Many<K> batchSink;
    private final AtomicInteger activeBatches;
    private final String cacheName;
    private volatile boolean running = false;


    private final ReactiveTieredCacheL2<K, V> cacheL2;
    private final ReactiveTieredCacheInvalidationChannel<K> invalidationChannel;

    private final long cacheTtlMillisMin;
    private final long cacheTtlMillisMax;

    private final Disposable loadBatchProcessorDisposable;
    private Disposable invalidationNotificationDisposable;

    public ReactiveTieredCacheImpl(
            ReactiveTieredCacheL2<K, V> cacheL2,
            ReactiveTieredCacheInvalidationChannel<K> invalidationChannel,
            ReactiveTieredCacheDataProvider<K, V> dataProvider,
            MeterRegistry meterRegistry,
            TieredCacheProperties cacheProperties) {

        this.cacheName = cacheProperties.getName();
        this.dataProvider = dataProvider;

        this.nearCache = createNearCache(cacheProperties);

        CaffeineCacheMetrics.monitor(meterRegistry, nearCache, cacheName + "_near");

        this.inFlight = new ConcurrentHashMap<>();
        this.activeBatches = new AtomicInteger(0);


        this.batchSink = Sinks.many().multicast().onBackpressureBuffer();

        this.cacheL2 = cacheL2;
        this.invalidationChannel = invalidationChannel;

        this.cacheTtlMillisMin = cacheProperties.getCacheTtl().toMillis();
        this.cacheTtlMillisMax = cacheTtlMillisMin + (long) (cacheTtlMillisMin * cacheProperties.getJitter());

        this.loadBatchProcessorDisposable = startLoadBatchProcessor(cacheProperties);
        this.subscribeInvalidationNotification();

        Gauge.builder("cache.in_flight_keys", inFlight::size)
                .description("Current count of the in flight keys")
                .tag("cache", cacheProperties.getName())
                .register(meterRegistry);

        Gauge.builder("cache.load_batches_active", activeBatches::get)
                .description("Number of concurrently processing batch loads")
                .tag("cache", cacheProperties.getName())
                .register(meterRegistry);
    }

    protected @NonNull Cache<K, CacheValue<V>> createNearCache(TieredCacheProperties cacheProperties) {
        return Caffeine.newBuilder()
                .maximumSize(cacheProperties.getNearCacheMaximumSize())
                .expireAfterWrite(cacheProperties.getNearCacheTtl())
                .recordStats()
                .build();
    }

    private void subscribeInvalidationNotification() {
        if (invalidationChannel != null) {
            invalidationNotificationDisposable = invalidationChannel.listen()
                    .subscribe(key -> {
                        nearCache.invalidate(key);

                        if (log.isDebugEnabled()) {
                            log.debug("Кеш [{}]: Получено уведомление об инвалидации: key={}", cacheName, key);
                        }
                    }, throwable -> {
                        log.error("Кеш [{}]: Ошибка в потоке инвалидации", cacheName, throwable);
                    }, () -> {
                        if (log.isDebugEnabled()) {
                            log.debug("Кеш [{}]: Поток инвалидации остановлен", cacheName);
                        }
                    });
        }
    }

    @Override
    public Mono<V> get(K key) {
        return Mono.defer(() -> {
            var res = nearCache.getIfPresent(key);

            if (res != null) {
                if (res.value() == null) {
                    return Mono.empty();
                } else {
                    return Mono.just(res.value());
                }
            }

            return cacheL2.get(key)
                    .doOnNext(t -> nearCache.put(key, new CacheValue<>(t)))
                    .switchIfEmpty(Mono.defer(() -> getOrLoad(key).map(Tuple2::getT2)));
        });
    }

    private Mono<Tuple2<K, V>> getOrLoad(K key) {
        return Mono.defer(() -> {
            // Создаём новый sink для этого запроса
            Sinks.One<Tuple2<K, V>> newSink = Sinks.one();

            // Пытаемся добавить в inFlight. Если уже есть — используем существующий.
            var sink = inFlight.computeIfAbsent(key, k -> newSink);

            // Если использовали существующий sink, просто возвращаем его mono.
            if (sink != newSink) {
                return sink.asMono();
            }

            if (log.isDebugEnabled()) {
                log.debug("Пытаемся отправить ключ {} в batchSink", key);
            }
            var result = batchSink.tryEmitNext(key);

            if (result.isFailure()) {
                log.error("Переполнение буфера команд! Не удалось отправить {} ключ в batchSink", key);
                inFlight.remove(key, newSink);
                newSink.tryEmitError(new CacheOverloadException("Batch overflow: " + result));
                return newSink.asMono();
            }

            return newSink.asMono()
                    .doFinally(sig -> {
                        boolean removed = inFlight.remove(key, newSink);
                        if (log.isDebugEnabled()) {
                            log.debug("Завершили обработку для {}. inFlight.remove вернул {}", key, removed);
                        }
                    });
        });
    }


    @Override
    public Mono<Map<K, V>> getMany(Collection<K> keys) {
        return Mono.defer(() -> {
            Map<K, V> result = new HashMap<>(keys.size());
            List<K> missingKeys = new ArrayList<>(keys.size());

            for (K key : keys) {
                var t = nearCache.getIfPresent(key);
                if (t == null) {
                    missingKeys.add(key);
                } else if (t.value() != null) {
                    result.put(key, t.value());
                }
            }

            if (missingKeys.isEmpty()) {
                return Mono.just(result);
            }

            return cacheL2.multiGet(missingKeys)
                    .doOnNext(r -> {
                        for (var e : r.values().entrySet()) {
                            nearCache.put(e.getKey(), new CacheValue<>(e.getValue()));
                        }
                    })
                    .flatMap(r -> {
                        result.putAll(r.values());

                        if (r.missingKeys().isEmpty()) {
                            return Mono.just(result);
                        }
                        return Flux.fromIterable(r.missingKeys())
                                .flatMap(this::getOrLoad)
                                .collectMap(Tuple2::getT1, Tuple2::getT2)
                                .map(loaded -> {
                                    result.putAll(loaded);
                                    return result;
                                });
                    });
        });
    }


    @Override
    public Mono<V> refresh(K key) {
        return cacheL2.get(key)
                .flatMap(sapGroup -> this.getOrLoad(key))
                .flatMap(objects -> sendInvalidationNotification(key).then(Mono.just(objects.getT2())));
    }

    @Override
    public Mono<Boolean> evict(K key) {

        return Mono.defer(() -> {
            if (log.isDebugEnabled()) {
                log.debug("Кеш [{}]: пытаемся удалить ключ {}", cacheName, key);
            }
            return cacheL2.evict(key)
                    .flatMap(exists -> sendInvalidationNotification(key)
                            .then(Mono.just(exists)))
                    .doFinally(signalType -> {
                        if (signalType == SignalType.ON_COMPLETE && log.isDebugEnabled()) {
                            log.debug("Кеш [{}]: Ключ {} удален", cacheName, key);
                        }
                    });
        });
    }

    /**
     * Отправляет уведомление об инвалидации в Redis Pub/Sub.
     * Все подписанные инстансы получат это сообщение и обновят свой L1 кеш.
     */
    private Mono<Void> sendInvalidationNotification(K key) {
        if (invalidationChannel != null) {
            if (log.isDebugEnabled()) {
                log.debug("Кеш [{}]: Отправка уведомления об инвалидации: key={}", cacheName, key);
            }

            return invalidationChannel.send(key);
        }
        return Mono.empty();
    }

    private Duration randomCacheL2Ttl() {
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(this.cacheTtlMillisMin, this.cacheTtlMillisMax));
    }

    private Disposable startLoadBatchProcessor(TieredCacheProperties cacheProperties) {
        return Flux.defer(() -> batchSink.asFlux()
                        .bufferTimeout(cacheProperties.getLoadBatchSize(), cacheProperties.getLoadBufferTimeout())
                        .filter(batch -> !batch.isEmpty())
                        .flatMap(keys -> this.processBatch(keys).onErrorResume(e -> {
                            log.error("Кеш [{}]: ошибка обработки батча", cacheName, e);
                            return Mono.empty();
                        }), cacheProperties.getLoadConcurrency()))
                .subscribe(keys -> {
                    if (log.isDebugEnabled()) {
                        log.debug("Кеш [{}]: обработано {} ключей", cacheName, keys.size());
                    }
                }, throwable -> {
                    if (log.isErrorEnabled()) {
                        log.error("Кеш [{}]: неустранимая ошибка в потоке загрузки данных в кеш", cacheName, throwable);
                    }
                }, () -> {
                    if (log.isInfoEnabled()) {
                        log.info("Кеш [{}]: поток загрузки данных завершён", cacheName);
                    }
                });
    }

    private Mono<Map<K, V>> putL2Many(Map<K, V> map) {
        if (map.isEmpty()) {
            return Mono.just(map);
        }

        if (log.isDebugEnabled()) {
            log.debug("Кеш [{}]: сохраняем результат в L2 кеш", cacheName);
        }

        return Flux.fromIterable(map.entrySet())
                .flatMap(e -> cacheL2.put(e.getKey(), e.getValue(), randomCacheL2Ttl()))
                .then(Mono.just(map))
                .doOnError(throwable -> {
                    if (log.isErrorEnabled()) {
                        log.error("Кеш [{}]: не удалось сохранить результат в L2 кеш", cacheName, throwable);
                    }
                })
                .onErrorResume(throwable -> Mono.just(map));
    }


    private Mono<Collection<K>> processBatch(Collection<K> keys) {
        activeBatches.incrementAndGet();
        return dataProvider.load(keys)
                .doFinally(signalType -> activeBatches.decrementAndGet())
                .defaultIfEmpty(Collections.emptyMap())
                .flatMap(this::putL2Many)
                .doOnNext(resultMap -> {
                    for (var key : keys) {
                        // сразу кладем ответ в L1 кеш.
                        var val = resultMap.get(key);
                        nearCache.put(key, new CacheValue<>(val));

                        // теперь пытаемся отправить результат подписчикам.
                        var sink = inFlight.get(key);
                        if (sink == null) continue;
                        Sinks.EmitResult emitResult;

                        if (val != null) {
                            emitResult = sink.tryEmitValue(Tuples.of(key, val));
                        } else {
                            emitResult = sink.tryEmitEmpty();
                        }

                        if (emitResult.isFailure()) {
                            log.debug("Кеш [{}]: Подписчик для ключа {} уже отключился, пропускаем отправку", cacheName, key);
                        }
                    }
                })
                .doOnError(error -> {
                    for (K key : keys) {
                        var sink = inFlight.get(key);
                        if (sink != null) {
                            var emitResult = sink.tryEmitError(error);
                            if (emitResult.isFailure()) {
                                log.error("Кеш [{}]: ошибка при уведомлении подписчика (tryEmitError) для ключа {}", cacheName, key);
                            }
                        }
                    }
                })
                .then(Mono.just(keys));
    }


    @Override
    public void start() {
        running = true;
    }


    public void stop() {
        batchSink.tryEmitComplete();

        if (!inFlight.isEmpty()) {
            Mono.delay(SHUTDOWN_GRACE).block();

            RuntimeException shutdown = new IllegalStateException("Service is shutting down");
            var sinks = new ArrayList<>(inFlight.values());
            inFlight.clear();
            log.warn("Кеш [{}]: принудительное завершение {} ожидающих запросов!", cacheName, sinks.size());

            for (var sink : sinks) {
                sink.tryEmitError(shutdown);
            }
        }

        loadBatchProcessorDisposable.dispose();
        nearCache.invalidateAll();

        if (invalidationNotificationDisposable != null && !invalidationNotificationDisposable.isDisposed()) {
            invalidationNotificationDisposable.dispose();
        }

        log.info("Кеш [{}]: остановлен", cacheName);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
