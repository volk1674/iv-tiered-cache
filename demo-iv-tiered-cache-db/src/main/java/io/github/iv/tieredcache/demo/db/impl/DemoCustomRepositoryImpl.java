package io.github.iv.tieredcache.demo.db.impl;

import io.github.iv.tieredcache.demo.db.Demo;
import io.github.iv.tieredcache.demo.db.DemoCustomRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Component
public class DemoCustomRepositoryImpl implements DemoCustomRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Demo> findByIds(Collection<Long> ids) {
        String sql = """
                select * from demo
                where id = any(?::bigint[])
                """;

        var query = entityManager.createNativeQuery(sql, Demo.class);
        query.setParameter(1, ids.toArray(new Long[0]));

        //noinspection unchecked
        return query.getResultList();
    }
}
