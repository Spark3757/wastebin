package ru.spark.wastebin.http;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rapidoid.http.Req;
import org.rapidoid.http.ReqHandler;
import ru.spark.wastebin.content.Content;
import ru.spark.wastebin.content.ContentCache;
import ru.spark.wastebin.content.ContentStorageHandler;
import ru.spark.wastebin.util.Compression;
import ru.spark.wastebin.util.RateLimiter;
import ru.spark.wastebin.util.TokenGenerator;

import java.util.concurrent.atomic.AtomicReference;

public final class PutHandler implements ReqHandler {

    private static final Logger LOGGER = LogManager.getLogger(PutHandler.class);

    private final WastebinServer server;
    private final RateLimiter rateLimiter;

    private final ContentStorageHandler contentStorageHandler;
    private final ContentCache contentCache;
    private final long maxContentLength;
    private final long lifetimeMillis;

    public PutHandler(WastebinServer server, RateLimiter rateLimiter, ContentStorageHandler contentStorageHandler, ContentCache contentCache, long maxContentLength, long lifetimeMillis) {
        this.server = server;
        this.rateLimiter = rateLimiter;
        this.contentStorageHandler = contentStorageHandler;
        this.contentCache = contentCache;
        this.maxContentLength = maxContentLength;
        this.lifetimeMillis = lifetimeMillis;
    }

    @Override
    public Object execute(Req req) {
        String path = req.path().substring(1);
        if (path.trim().isEmpty() || path.contains(".") || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
            return WastebinServer.cors(req.response()).code(404).plain("Invalid path");
        }

        AtomicReference<byte[]> newContent = new AtomicReference<>(req.body());

        String ipAddress = WastebinServer.getIpAddress(req);

        if (newContent.get().length == 0) return WastebinServer.cors(req.response()).code(400).plain("Missing content");
        if (this.rateLimiter.check(ipAddress))
            return WastebinServer.cors(req.response()).code(429).plain("Rate limit exceeded");

        String authKey = req.header("Modification-Key", null);
        if (authKey == null)
            return WastebinServer.cors(req.response()).code(403).plain("Modification-Key header not present");

        this.contentCache.get(path).whenCompleteAsync((oldContent, throwable) -> {
            if (throwable != null || oldContent == null || oldContent.getKey() == null || oldContent.getContent().length == 0) {
                WastebinServer.cors(req.response()).plain("Incorrect modification key").done();
                return;
            }

            if (!oldContent.isModifiable()) {
                WastebinServer.cors(req.response()).code(403).plain("Incorrect modification key").done();
                return;
            }

            if (!oldContent.getAuthKey().equals(authKey)) {
                WastebinServer.cors(req.response()).code(403).plain("Incorrect modification key").done();
                return;
            }

            String newContentType = req.header("Content-Type", oldContent.getContentType());

            boolean compressed = req.header("Content-Encoding", "").equals("gzip");
            if (!compressed) {
                newContent.set(Compression.compress(newContent.get()));
            }

            if (newContent.get().length > this.maxContentLength) {
                WastebinServer.cors(req.response()).code(413).plain("Content too large").done();
                return;
            }

            long newExpiry = System.currentTimeMillis() + this.lifetimeMillis;

            String origin = req.header("Origin", null);
            LOGGER.info("[PUT]\n" +
                    "    key = " + path + "\n" +
                    "    new type = " + new String(newContentType.getBytes()) + "\n" +
                    "    user agent = " + req.header("User-Agent", "null") + "\n" +
                    "    ip = " + ipAddress + "\n" +
                    (origin == null ? "" : "    origin = " + origin + "\n") +
                    "    old content size = " + String.format("%,d", oldContent.getContent().length / 1024) + " KB" + "\n" +
                    "    new content size = " + String.format("%,d", newContent.get().length / 1024) + " KB" + "\n");

            // update the content instance with the new data
            oldContent.setContentType(newContentType);
            oldContent.setExpiry(newExpiry);
            oldContent.setLastModified(System.currentTimeMillis());
            oldContent.setContent(newContent.get());

            // make the http response
            WastebinServer.cors(req.response()).code(200)
                    .body(Content.EMPTY_BYTES)
                    .done();

            // save to disk
            this.contentStorageHandler.save(oldContent);
        }, this.contentStorageHandler.getExecutor());

        return req.async();
    }

}
