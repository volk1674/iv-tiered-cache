package io.github.iv.tieredcache.demo.db;

import java.util.Collection;
import java.util.List;

public interface DemoCustomRepository {

    List<Demo> findByIds(Collection<Long> ids);

}
