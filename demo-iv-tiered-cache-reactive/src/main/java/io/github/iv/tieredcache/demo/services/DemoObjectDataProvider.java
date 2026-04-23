package io.github.iv.tieredcache.demo.services;

import io.github.iv.tieredcache.ReactiveTieredCacheDataProvider;
import io.github.iv.tieredcache.demo.models.DemoObject;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Component
public class DemoObjectDataProvider implements ReactiveTieredCacheDataProvider<Long, DemoObject> {

    private final DatabaseClient databaseClient;

    @Override
    public Mono<Map<Long, DemoObject>> load(Collection<Long> keys) {
        final String SQL = "select * from demo where id = any(:ids::bigint[])";

        return databaseClient.sql(SQL)
                .bind("ids", keys.toArray(new Long[0]))
                .map(this::mapToDemoObject)
                .all()
                .collectMap(DemoObject::getId);
    }


    private DemoObject mapToDemoObject(Row row, RowMetadata rowMetadata) {
        var id = row.get(0, Long.class);
        var data = row.get(1, String.class);
        return new DemoObject(id, data);
    }

}
