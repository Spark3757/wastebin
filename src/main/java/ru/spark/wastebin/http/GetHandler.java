package ru.spark.wastebin.http;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rapidoid.http.MediaType;
import org.rapidoid.http.Req;
import org.rapidoid.http.ReqHandler;
import org.rapidoid.http.Resp;
import ru.spark.wastebin.content.ContentCache;
import ru.spark.wastebin.util.Compression;
import ru.spark.wastebin.util.RateLimiter;
import ru.spark.wastebin.util.TokenGenerator;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static ru.spark.wastebin.http.WastebinServer.cors;

public final class GetHandler implements ReqHandler {

    private static final Logger LOGGER = LogManager.getLogger(GetHandler.class);

    private final WastebinServer server;
    private final RateLimiter rateLimiter;
    private final ContentCache contentCache;

    public GetHandler(WastebinServer server, RateLimiter rateLimiter, ContentCache contentCache) {
        this.server = server;
        this.rateLimiter = rateLimiter;
        this.contentCache = contentCache;
    }

    @Override
    public Object execute(Req req) {
        String path = req.path().substring(1);
        if (path.trim().isEmpty() || path.contains(".") || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
            return cors(req.response()).code(404).plain("Invalid path");
        }

        String ipAddress = WastebinServer.getIpAddress(req);

        if (this.rateLimiter.check(ipAddress)) return cors(req.response()).code(429).plain("Rate limit exceeded");

        boolean supportsCompression = Compression.acceptsCompressed(req);

        String origin = req.header("Origin", null);
        LOGGER.info("[REQUEST]\n" +
                "    key = " + path + "\n" +
                "    user agent = " + req.header("User-Agent", "null") + "\n" +
                //"    origin = " + ipAddress + (hostname != null ? " (" + hostname + ")" : "") + "\n" +
                "    ip = " + ipAddress + "\n" +
                (origin == null ? "" : "    origin = " + origin + "\n"));

        this.contentCache.get(path).whenCompleteAsync((content, throwable) -> {
            if (throwable != null || content == null || content.getKey() == null || content.getContent().length == 0) {
                cors(req.response()).code(404).plain("Invalid path").done();
                return;
            }

            String lastModifiedTime = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(content.getLastModified()).atOffset(ZoneOffset.UTC));

            Resp resp = cors(req.response()).code(200).header("Last-Modified", lastModifiedTime);

            if (content.isModifiable()) {
                resp.header("Cache-Control", "no-cache");
            } else {
                resp.header("Cache-Control", "public, max-age=86400");
            }

            if (supportsCompression) {
                resp.header("Content-Encoding", "gzip")
                        .body(content.getContent())
                        .contentType(MediaType.of(content.getContentType()))
                        .done();
                return;
            }

            byte[] uncompressed;
            try {
                uncompressed = Compression.decompress(content.getContent());
            } catch (IOException e) {
                cors(req.response()).code(404).plain("Unable to uncompress data").done();
                return;
            }

            resp.body(uncompressed)
                    .contentType(MediaType.of(content.getContentType()))
                    .done();
        });

        return req.async();
    }
}
