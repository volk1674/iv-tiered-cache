package io.github.iv.tieredcache;

public class CacheOverloadException extends RuntimeException {
    public CacheOverloadException(String message) {
        super(message);
    }
}
