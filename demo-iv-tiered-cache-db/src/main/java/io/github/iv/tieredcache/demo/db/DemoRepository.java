package io.github.iv.tieredcache.demo.db;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoRepository extends JpaRepository<Demo, Long>, DemoCustomRepository {
}