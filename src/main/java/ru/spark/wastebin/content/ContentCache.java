package ru.spark.wastebin.content;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ContentCache {

    private final int cacheTimeMins;

    /**
     * Content cache - caches the raw byte data for the last x requested files
     */
    private final AsyncLoadingCache<String, Content> contentCache;

    public ContentCache(ContentStorageHandler loader, int cacheTimeMins, int cacheMaxSizeMb) {
        this.cacheTimeMins = cacheTimeMins;
        this.contentCache = Caffeine.newBuilder()
                .executor(loader.getExecutor())
                .expireAfterAccess(cacheTimeMins, TimeUnit.MINUTES)
                .maximumWeight(cacheMaxSizeMb * Content.MEGABYTE_LENGTH)
                .weigher((Weigher<String, Content>) (path, content) -> content.getContent().length)
                .buildAsync(loader);
    }

    public int getCacheTimeMins() {
        return this.cacheTimeMins;
    }

    public void put(String key, CompletableFuture<Content> future) {
        this.contentCache.put(key, future);
    }

    public CompletableFuture<Content> get(String key) {
        return this.contentCache.get(key);
    }

}
