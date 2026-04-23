package io.github.iv.tieredcache.demo.controllers;

import io.github.iv.tieredcache.ReactiveTieredCache;
import io.github.iv.tieredcache.demo.models.DemoObject;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class DemoController {

    private final ReactiveTieredCache<Long, DemoObject> reactiveTieredCache;

    @PostMapping("/demo/get-many")
    public Flux<DemoObject> getProductsMvideo(@RequestBody @Valid List<Long> request) {
        return reactiveTieredCache.getMany(request).flatMapIterable(Map::values);
    }
    
    @PostMapping("/demo/evict")
    public Mono<Long> invalidate(@RequestBody @Valid List<Long> request) {
        return Flux.fromIterable(request).flatMap(reactiveTieredCache::evict)
                .filter(Boolean.TRUE::equals).count();
    }


}
