# IV Tiered Cache

A high-performance tiered caching library for Java applications with support for both reactive and blocking APIs.

## Overview

IV Tiered Cache is a multi-level caching solution designed to improve application performance by reducing database load and latency. It implements a three-tier caching architecture:

- **L1 (Near Cache)**: In-memory cache for ultra-fast access
- **L2 (Distributed Cache)**: Redis-based cache for distributed applications
- **L3 (Data Source)**: Database or other persistent storage

## Features

- ✅ Reactive and Blocking API support
- ✅ Multi-level caching (L1 + L2 + L3)
- ✅ Batch loading with configurable concurrency
- ✅ Cache invalidation with Redis pub/sub
- ✅ Spring Boot integration
- ✅ Micrometer metrics support
- ✅ Configurable TTL with jitter to prevent cache stampede
- ✅ Version-based cache consistency checking

## Project Structure

```
iv-tiered-cache-parent/
├── iv-tiered-cache-core/                      # Core models and configurations
├── iv-tiered-cache-reactive/                  # Reactive cache implementation
├── iv-tiered-cache-blocking/                  # Blocking cache wrapper
├── iv-tiered-cache-redis/                     # Redis L2 cache implementation
├── iv-tiered-cache-spring-boot-starter-blocking/   # Spring Boot starter (blocking)
├── iv-tiered-cache-spring-boot-starter-reactive/   # Spring Boot starter (reactive)
├── demo-iv-tiered-cache-reactive/             # Reactive demo application
└── demo-iv-tiered-cache-db/                   # Database initialization utility
```

## Requirements

- Java 21+
- Maven 3.6+
- Redis (for L2 cache)
- PostgreSQL (for demo)

## Installation

Add the dependency to your `pom.xml`:

### For Reactive Applications

```xml
<dependency>
    <groupId>ru.mvideo.msp</groupId>
    <artifactId>iv-tiered-cache-spring-boot-starter-reactive</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### For Blocking Applications

```xml
<dependency>
    <groupId>ru.mvideo.msp</groupId>
    <artifactId>iv-tiered-cache-spring-boot-starter-blocking</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## Configuration

Configure the cache properties in your `application.yml` or `application.properties`:

```yaml
tiered-cache:
  your-cache-name:
    name: "your-cache-name"
    nearCacheMaximumSize: 10000        # Max items in L1 cache (0 to disable)
    nearCacheTtl: 30s                  # TTL for L1 cache
    cacheTtl: 1d                       # TTL for L2 cache
    jitter: 0.1                        # TTL jitter (0.0-1.0) to prevent cache stampede
    loadBatchSize: 100                 # Batch size for loading from L3
    loadConcurrency: 20                # Max parallel load requests
    loadBufferTimeout: 5ms             # Buffer timeout for batch requests
```

## Usage

### Reactive API

#### 1. Define Data Provider

```java
@Component
public class MyDataProvider implements ReactiveTieredCacheDataProvider<Long, MyObject> {
    
    @Override
    public Mono<MyObject> loadOne(Long key) {
        // Load single object from database
        return repository.findById(key);
    }
    
    @Override
    public Flux<MyObject> loadMany(Collection<Long> keys) {
        // Load multiple objects in batch
        return repository.findAllById(keys);
    }
    
    @Override
    public Mono<Long> checkVersion(Long key) {
        // Return version/timestamp for cache consistency
        return repository.findVersionById(key);
    }
}
```

#### 2. Configure Cache

```java
@Configuration
public class CacheConfig {

    @Bean
    public TieredCacheProperties tieredCacheProperties() {
        TieredCacheProperties props = new TieredCacheProperties();
        props.setName("my-cache");
        props.setNearCacheMaximumSize(10_000);
        props.setNearCacheTtl(Duration.ofSeconds(30));
        props.setCacheTtl(Duration.ofDays(1));
        return props;
    }

    @Bean
    public ReactiveTieredCacheL2<Long, MyObject> cacheL2(
            ReactiveRedisTemplate<String, MyObject> redisTemplate,
            TieredCacheProperties properties,
            ReactiveTieredCacheDataProvider<Long, MyObject> dataProvider,
            MeterRegistry meterRegistry) {
        
        return new ReactiveTieredCacheRedis<>(
            redisTemplate, properties, dataProvider::checkVersion, meterRegistry);
    }

    @Bean
    public ReactiveTieredCacheInvalidationChannel<Long> invalidationChannel(
            ReactiveRedisTemplate<String, Long> redisTemplate,
            TieredCacheProperties properties) {
        
        return new ReactiveTieredCacheInvalidationChannelRedis<>(redisTemplate, properties);
    }

    @Bean
    public ReactiveTieredCache<Long, MyObject> tieredCache(
            ReactiveTieredCacheL2<Long, MyObject> cacheL2,
            ReactiveTieredCacheInvalidationChannel<Long> channel,
            ReactiveTieredCacheDataProvider<Long, MyObject> dataProvider,
            MeterRegistry meterRegistry,
            TieredCacheProperties properties) {
        
        return new ReactiveTieredCacheImpl<>(cacheL2, channel, dataProvider, meterRegistry, properties);
    }
}
```

#### 3. Use the Cache

```java
@RestController
@RequiredArgsConstructor
public class MyController {

    private final ReactiveTieredCache<Long, MyObject> cache;

    @GetMapping("/items/{id}")
    public Mono<MyObject> getItem(@PathVariable Long id) {
        return cache.get(id);
    }

    @PostMapping("/items/batch")
    public Flux<MyObject> getItems(@RequestBody List<Long> ids) {
        return cache.getMany(ids).flatMapIterable(Map::values);
    }

    @DeleteMapping("/items/{id}")
    public Mono<Boolean> evictItem(@PathVariable Long id) {
        return cache.evict(id);
    }
}
```

### Blocking API

For blocking applications, use the `BlockingTieredCache` wrapper:

```java
@Service
@RequiredArgsConstructor
public class MyService {

    private final BlockingTieredCache<Long, MyObject> cache;

    public MyObject getItem(Long id) {
        return cache.get(id);
    }

    public Map<Long, MyObject> getItems(Collection<Long> ids) {
        return cache.getMany(ids);
    }
}
```

## Cache Operations

| Operation | Description | Method |
|-----------|-------------|--------|
| Get Single | Retrieve a single item by key | `get(K key)` |
| Get Multiple | Retrieve multiple items by keys | `getMany(Collection<K> keys)` |
| Refresh | Force refresh an item from source | `refresh(K key)` |
| Evict | Remove an item from all cache levels | `evict(K key)` |

## Architecture

```
┌─────────────────┐
│   Application   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   L1 Cache      │  ← In-memory (Caffeine)
│   (Near Cache)  │     Fast, local, limited size
└────────┬────────┘
         │ Miss
         ▼
┌─────────────────┐
│   L2 Cache      │  ← Redis
│  (Distributed)  │     Shared across instances
└────────┬────────┘
         │ Miss
         ▼
┌─────────────────┐
│   L3 Storage    │  ← Database / External API
│  (Data Source)  │     Persistent storage
└─────────────────┘
```

### Cache Flow

1. **Get Request**: Check L1 → Check L2 → Load from L3
2. **Load from L3**: Batch requests with configurable buffer timeout and concurrency
3. **Cache Population**: L3 → L2 → L1
4. **Invalidation**: Redis pub/sub notifies all instances to invalidate L1 entries

## Metrics

The library integrates with Micrometer and provides metrics for:
- Cache hits/misses at each level
- Load times and batch sizes
- Invalidation events
- Error rates

## Demo Applications

### Reactive Demo

Run the reactive demo application:

```bash
cd demo-iv-tiered-cache-reactive
mvn spring-boot:run
```

The demo provides endpoints:
- `POST /demo/get-many` - Batch retrieve cached items
- `POST /demo/evict` - Invalidate cache entries

### Database Initializer

Initialize demo data in PostgreSQL:

```bash
cd demo-iv-tiered-cache-db
mvn spring-boot:run
```

This creates 1,000,000 sample records for testing.

## Advanced Features

### Batch Loading

The cache automatically batches concurrent requests to reduce database load:

- **loadBatchSize**: Maximum items per batch (default: 100)
- **loadConcurrency**: Maximum parallel batch requests (default: 20)
- **loadBufferTimeout**: Time to wait for batch accumulation (default: 5ms)

### Cache Invalidation

Distributed cache invalidation using Redis pub/sub ensures consistency across application instances:

```java
// Invalidate specific keys
cache.evict(key).subscribe();

// Or use the invalidation channel directly
invalidationChannel.publish(Collections.singletonList(key)).subscribe();
```

### Jitter for TTL

To prevent cache stampede (many keys expiring simultaneously), TTL is randomized:

```
actualTtl = baseTtl * (1 + random(-jitter, +jitter))
```

## License

This project is licensed under the MIT License.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
