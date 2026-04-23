package io.github.iv.tieredcache.demo.configs;

import io.github.iv.tieredcache.ReactiveTieredCache;
import io.github.iv.tieredcache.ReactiveTieredCacheDataProvider;
import io.github.iv.tieredcache.ReactiveTieredCacheImpl;
import io.github.iv.tieredcache.ReactiveTieredCacheInvalidationChannel;
import io.github.iv.tieredcache.ReactiveTieredCacheL2;
import io.github.iv.tieredcache.TieredCacheProperties;
import io.github.iv.tieredcache.demo.models.DemoObject;
import io.github.iv.tieredcache.redis.reactive.ReactiveTieredCacheInvalidationChannelRedis;
import io.github.iv.tieredcache.redis.reactive.ReactiveTieredCacheRedis;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.validation.annotation.Validated;

@Configuration
public class CacheConfig {

    @Bean
    public ReactiveRedisTemplate<String, DemoObject> redisTemplateDemoObject(ReactiveRedisConnectionFactory factory) {
        var keySerializer = new StringRedisSerializer();
        var valueSerializer = new Jackson2JsonRedisSerializer<>(DemoObject.class);
        RedisSerializationContext<String, DemoObject> context = RedisSerializationContext
                .<String, DemoObject>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .build();
        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public ReactiveRedisTemplate<String, Long> redisTemplateLong(ReactiveRedisConnectionFactory factory) {
        var keySerializer = new StringRedisSerializer();
        var valueSerializer = new Jackson2JsonRedisSerializer<>(Long.class);
        RedisSerializationContext<String, Long> context = RedisSerializationContext
                .<String, Long>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .build();
        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Validated
    @ConfigurationProperties("tiered-cache.demo-object")
    @Bean
    public TieredCacheProperties tieredCacheProperties() {
        return new TieredCacheProperties();
    }

    @Bean
    public ReactiveTieredCacheL2<Long, DemoObject> demoObjectL2(
            ReactiveRedisTemplate<String, DemoObject> redisTemplate,
            @Qualifier("tieredCacheProperties")
            TieredCacheProperties cacheProperties,
            ReactiveTieredCacheDataProvider<Long, DemoObject> dataProvider,
            MeterRegistry meterRegistry
    ) {

        return new ReactiveTieredCacheRedis<>(
                redisTemplate,
                cacheProperties,
                dataProvider::checkVersion,
                meterRegistry);
    }

    @Bean
    public ReactiveTieredCacheInvalidationChannel<Long> invalidationChannelDemoObject(
            ReactiveRedisTemplate<String, Long> redisTemplate,
            @Qualifier("tieredCacheProperties")
            TieredCacheProperties cacheProperties
    ) {
        return new ReactiveTieredCacheInvalidationChannelRedis<>(redisTemplate, cacheProperties);
    }

    @Bean
    public ReactiveTieredCache<Long, DemoObject> reactiveTieredCacheDemoObject(
            ReactiveTieredCacheL2<Long, DemoObject> cacheL2,
            ReactiveTieredCacheInvalidationChannel<Long> invalidationChannelDemoObject,
            ReactiveTieredCacheDataProvider<Long, DemoObject> dataProvider,
            MeterRegistry meterRegistry,
            @Qualifier("tieredCacheProperties")
            TieredCacheProperties cacheProperties) {
        return new ReactiveTieredCacheImpl<>(cacheL2, invalidationChannelDemoObject, dataProvider, meterRegistry,
                cacheProperties);
    }

}
