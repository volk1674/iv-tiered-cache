package io.github.iv.tieredcache;

import java.util.Collection;
import java.util.Map;

public record ManyResult<K, V>(Map<K, V> values, Collection<K> missingKeys) {
}
