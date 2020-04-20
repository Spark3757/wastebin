package ru.spark.wastebin.http;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rapidoid.http.Req;
import org.rapidoid.http.ReqHandler;
import org.rapidoid.http.Resp;
import org.rapidoid.u.U;
import ru.spark.wastebin.content.Content;
import ru.spark.wastebin.content.ContentCache;
import ru.spark.wastebin.content.ContentStorageHandler;
import ru.spark.wastebin.util.RateLimiter;
import ru.spark.wastebin.util.TokenGenerator;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static ru.spark.wastebin.http.WastebinServer.cors;

public final class PostHandler implements ReqHandler {

    private static final Logger LOGGER = LogManager.getLogger(PostHandler.class);

    private final WastebinServer server;
    private final RateLimiter rateLimiter;

    private final ContentStorageHandler contentStorageHandler;
    private final ContentCache contentCache;
    private final TokenGenerator contentTokenGenerator;
    private final TokenGenerator authKeyTokenGenerator;
    private final long maxContentLength;
    private final long lifetimeMillis;
    private final Map<String, Long> lifetimeMillisByUserAgent;

    public PostHandler(WastebinServer server, RateLimiter rateLimiter, ContentStorageHandler contentStorageHandler, ContentCache contentCache, TokenGenerator contentTokenGenerator, long maxContentLength, long lifetimeMillis, Map<String, Long> lifetimeMillisByUserAgent) {
        this.server = server;
        this.rateLimiter = rateLimiter;
        this.contentStorageHandler = contentStorageHandler;
        this.contentCache = contentCache;
        this.contentTokenGenerator = contentTokenGenerator;
        this.authKeyTokenGenerator = new TokenGenerator(32);
        this.maxContentLength = maxContentLength;
        this.lifetimeMillis = lifetimeMillis;
        this.lifetimeMillisByUserAgent = lifetimeMillisByUserAgent;
    }

    @Override
    public Object execute(Req req) {
        byte[] content = req.body();

        String ipAddress = WastebinServer.getIpAddress(req);

        if (content.length == 0) return cors(req.response()).code(400).plain("Missing content");
        if (this.rateLimiter.check(ipAddress)) return cors(req.response()).code(429).plain("Rate limit exceeded");

        String contentType = req.header("Content-Type", "text/plain");

        String key = this.contentTokenGenerator.generate();

        boolean compressed = req.header("Content-Encoding", "").equals("gzip");

        String userAgent = req.header("User-Agent", "null");
        String origin = req.header("Origin", "null");

        long expiry = System.currentTimeMillis() + this.lifetimeMillisByUserAgent.getOrDefault(userAgent, this.lifetimeMillisByUserAgent.getOrDefault(origin, this.lifetimeMillis));

        if (content.length > this.maxContentLength) return cors(req.response()).code(413).plain("Content too large");

        boolean allowModifications = Boolean.parseBoolean(req.header("Allow-Modification", "false"));
        String authKey;
        if (allowModifications) {
            authKey = this.authKeyTokenGenerator.generate();
        } else {
            authKey = null;
        }
        LOGGER.info("[POST]\n" +
                "    key = " + key + "\n" +
                "    type = " + contentType + "\n" +
                "    user agent = " + userAgent + "\n" +
                //"    origin = " + ipAddress + (hostname != null ? " (" + hostname + ")" : "") + "\n" +
                "    ip = " + ipAddress + "\n" +
                (origin.equals("null") ? "" : "    origin = " + origin + "\n") +
                "    content size = " + String.format("%,d", content.length / 1024) + " KB" + (compressed ? " (compressed)" : "") + "\n");

        CompletableFuture<Content> future = new CompletableFuture<>();
        this.contentCache.put(key, future);

        this.contentStorageHandler.getExecutor().execute(() -> this.contentStorageHandler.save(key, contentType, content, expiry, authKey, !compressed, future));

        Resp resp = cors(req.response()).code(201).header("Location", key);

        if (allowModifications) {
            resp.header("Modification-Key", authKey);
        }

        return resp.json(U.map("key", key));
    }

}
