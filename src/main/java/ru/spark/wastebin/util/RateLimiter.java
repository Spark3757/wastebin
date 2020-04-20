package ru.spark.wastebin.util;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public class RateLimiter {
    /**
     * Rate limiter cache - allow x requests every x minutes
     */
    private final LoadingCache<String, AtomicInteger> rateLimiter;
    /**
     * The number of requests allowed in each period
     */
    private final int actionsPerCycle;

    public RateLimiter(int periodMins, int actionsPerCycle) {
        this.rateLimiter = Caffeine.newBuilder()
                .expireAfterWrite(periodMins, TimeUnit.MINUTES)
                .build(key -> new AtomicInteger(0));
        this.actionsPerCycle = actionsPerCycle;
    }

    public boolean check(String ipAddress) {
        //noinspection ConstantConditions
        return this.rateLimiter.get(ipAddress).incrementAndGet() > this.actionsPerCycle;
    }
}
