package ru.spark.wastebin;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.io.IoBuilder;
import ru.spark.wastebin.content.Content;
import ru.spark.wastebin.content.ContentCache;
import ru.spark.wastebin.content.ContentStorageHandler;
import ru.spark.wastebin.http.WastebinServer;
import ru.spark.wastebin.util.Configuration;
import ru.spark.wastebin.util.RateLimiter;
import ru.spark.wastebin.util.TokenGenerator;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simple "pastebin" service.
 */
public final class Wastebin implements AutoCloseable {

    /**
     * Logger instance
     */
    private static final Logger LOGGER = LogManager.getLogger(Wastebin.class);
    /**
     * Executor service for performing file based i/o
     */
    private final ScheduledExecutorService executor;
    /**
     * The web server instance
     */
    private final WastebinServer server;

    public Wastebin(Configuration config) throws Exception {
        // setup simple logger
        LOGGER.info("loading wastebin...");

        // setup executor
        this.executor = Executors.newScheduledThreadPool(
                config.getInt("corePoolSize", 16),
                new ThreadFactoryBuilder().setNameFormat("wastebin-io-%d").build()
        );

        // setup loader
        ContentStorageHandler contentStorageHandler = new ContentStorageHandler(
                this.executor,
                Paths.get("content")
        );

        // build content cache
        ContentCache contentCache = new ContentCache(
                contentStorageHandler,
                config.getInt("cacheExpiryMinutes", 10),
                config.getInt("cacheMaxSizeMb", 200)
        );

        // load index page
        byte[] indexPage;
        try (InputStreamReader in = new InputStreamReader(Wastebin.class.getResourceAsStream("/index.html"), StandardCharsets.UTF_8)) {
            indexPage = CharStreams.toString(in).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // setup the web server
        this.server = new WastebinServer(
                contentStorageHandler,
                contentCache,
                System.getProperty("server.host", config.getString("host", "127.0.0.1")),
                Integer.getInteger("server.port", config.getInt("port", 8080)),
                new RateLimiter(
                        // by default, allow posts at rate of 3 times per min (every 20s)
                        config.getInt("postRateLimitPeriodMins", 10),
                        config.getInt("postRateLimit", 30)
                ),
                new RateLimiter(
                        // by default, allow updates at rate of 15 times per min (every 4s)
                        config.getInt("updateRateLimitPeriodMins", 2),
                        config.getInt("updateRateLimit", 26)
                ),
                new RateLimiter(
                        // by default, allow reads at rate of 15 times per min (every 4s)
                        config.getInt("readRateLimitPeriodMins", 2),
                        config.getInt("readRateLimit", 30)
                ),
                indexPage,
                new TokenGenerator(config.getInt("keyLength", 7)),
                (Content.MEGABYTE_LENGTH * config.getInt("maxContentLengthMb", 10)),
                TimeUnit.MINUTES.toMillis(config.getLong("lifetimeMinutes", TimeUnit.DAYS.toMinutes(1))),
                config.getLongMap("lifetimeMinutesByUserAgent").entrySet().stream().collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, e -> TimeUnit.MINUTES.toMillis(e.getValue())))
        );
        this.server.start();

        // schedule invalidation task
        this.executor.scheduleWithFixedDelay(contentStorageHandler::runInvalidation, 1, contentCache.getCacheTimeMins(), TimeUnit.MINUTES);
    }

    // Bootstrap
    public static void main(String[] args) throws Exception {
        // setup logging
        System.setOut(IoBuilder.forLogger(LOGGER).setLevel(Level.INFO).buildPrintStream());
        System.setErr(IoBuilder.forLogger(LOGGER).setLevel(Level.ERROR).buildPrintStream());

        // setup a new wastebin instance
        Configuration config = Configuration.load(Paths.get("config.json"));
        Wastebin wastebin = new Wastebin(config);
        Runtime.getRuntime().addShutdownHook(new Thread(wastebin::close, "Wastebin Shutdown Thread"));
    }

    @Override
    public void close() {
        this.server.halt();
        this.executor.shutdown();
        try {
            this.executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Exception whilst shutting down executor", e);
        }
    }

}
