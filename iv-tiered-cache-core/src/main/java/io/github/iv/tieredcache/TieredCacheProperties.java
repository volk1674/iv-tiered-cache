package io.github.iv.tieredcache;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Duration;

@Data
public class TieredCacheProperties {

    /**
     * Название для кеша.
     */
    @NotBlank
    private String name;

    /**
     * Максимальное количество объектов в L1 кеше.
     * Если установлено значение 0, то ближний кеш испльзоваться не будет.
     */
    private int nearCacheMaximumSize = 10_000;

    /**
     * Максимальное время жизни объекта в L1 кеше.
     * Если установлено значение 0, то ближний кеш испльзоваться не будет.
     */
    @NotNull
    private Duration nearCacheTtl = Duration.ofSeconds(30);

    /**
     * Максимальное время жизни объекта в L2 кеше.
     */
    @NotNull
    private Duration cacheTtl = Duration.ofDays(1);

    /**
     * Отклонение l2Ttl в процентах, для предотвращения одновременного протухания множества объектов в кеше.
     */
    @Min(0)
    @Max(1)
    private double jitter = 0.1;

    /**
     * Количество объектов загружаемых из L3 (база данных или другое хранилище) в одном запросе.
     */
    @Min(1)
    @Max(1000)
    private int loadBatchSize = 100;

    /**
     * Максимальное количество паралельных запросов на загрузку данных.
     */
    @Min(1)
    @Max(1000)
    private int loadConcurrency = 20;

    /**
     * Задержка для накопления буфера для batch запроса загрузки данных.
     */
    @NotNull
    private Duration loadBufferTimeout = Duration.ofMillis(5);

    
}
