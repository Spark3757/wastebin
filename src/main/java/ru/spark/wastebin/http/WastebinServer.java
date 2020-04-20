package ru.spark.wastebin.http;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rapidoid.http.Req;
import org.rapidoid.http.Resp;
import org.rapidoid.setup.Setup;
import ru.spark.wastebin.content.Content;
import ru.spark.wastebin.content.ContentCache;
import ru.spark.wastebin.content.ContentStorageHandler;
import ru.spark.wastebin.util.RateLimiter;
import ru.spark.wastebin.util.TokenGenerator;

import java.util.Map;

public class WastebinServer {

    private static final Logger LOGGER = LogManager.getLogger(WastebinServer.class);

    private final Setup server;

    public WastebinServer(ContentStorageHandler contentStorageHandler, ContentCache contentCache, String host, int port, RateLimiter postRateLimiter, RateLimiter putRateLimiter, RateLimiter readRateLimiter, byte[] indexPage, TokenGenerator contentTokenGenerator, long maxContentLength, long lifetimeMillis, Map<String, Long> lifetimeMillisByUserAgent) {
        this.server = Setup.create("wastebin");
        this.server.address(host).port(port);

        // catch all errors & just return some generic error message
        this.server.custom().errorHandler((req, resp, error) -> {
            LOGGER.error("Error thrown by handler", error);
            return cors(resp).code(404).plain("Invalid path");
        });

        // define route handlers
        defineOptionsRoute(this.server, "/post", "POST");
        defineOptionsRoute(this.server, "/*", "GET");
        this.server.page("/").html(indexPage);
        this.server.post("/post").managed(false).serve(new PostHandler(this, postRateLimiter, contentStorageHandler, contentCache, contentTokenGenerator, maxContentLength, lifetimeMillis, lifetimeMillisByUserAgent));
        this.server.get("/*").managed(false).cacheCapacity(0).serve(new GetHandler(this, readRateLimiter, contentCache));
        this.server.put("/*").managed(false).cacheCapacity(0).serve(new PutHandler(this, putRateLimiter, contentStorageHandler, contentCache, maxContentLength, lifetimeMillis));
    }

    private static void defineOptionsRoute(Setup setup, String path, String allowedMethod) {
        setup.options(path).serve(req -> cors(req.response())
                .header("Access-Control-Allow-Methods", allowedMethod)
                .header("Access-Control-Max-Age", "86400")
                .header("Access-Control-Allow-Headers", "Content-Type")
                .code(200)
                .body(Content.EMPTY_BYTES)
        );
    }

    static Resp cors(Resp resp) {
        return resp.header("Access-Control-Allow-Origin", "*");
    }

    static String getIpAddress(Req req) {
        String ipAddress = req.header("x-real-ip", null);
        if (ipAddress == null) {
            ipAddress = req.clientIpAddress();
        }
        return ipAddress;
    }

    public void start() {
        this.server.activate();
    }

    public void halt() {
        this.server.halt();
    }

}
