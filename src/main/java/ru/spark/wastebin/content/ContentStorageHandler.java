package ru.spark.wastebin.content;

import com.github.benmanes.caffeine.cache.CacheLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.spark.wastebin.util.Compression;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

public class ContentStorageHandler implements CacheLoader<String, Content> {

    /**
     * Logger instance
     */
    private static final Logger LOGGER = LogManager.getLogger(ContentStorageHandler.class);

    /**
     * Executor service for performing file based i/o
     */
    private final ScheduledExecutorService executor;

    // the path to store the content in
    private final Path contentPath;

    public ContentStorageHandler(ScheduledExecutorService executor, Path contentPath) throws IOException {
        this.executor = executor;
        this.contentPath = contentPath;

        // make directories
        Files.createDirectories(this.contentPath);
    }

    public ScheduledExecutorService getExecutor() {
        return this.executor;
    }

    @Override
    public Content load(String path) throws Exception {
        LOGGER.info("[I/O] Loading " + path + " from disk");

        try {
            Path resolved = this.contentPath.resolve(path);
            return load(resolved);
        } catch (Exception e) {
            LOGGER.error("Exception occurred loading '" + path + "'", e);
            throw e; // rethrow
        }
    }

    private Content load(Path resolved) throws IOException {
        if (!Files.exists(resolved)) {
            return Content.EMPTY_CONTENT;
        }

        try (DataInputStream in = new DataInputStream(Files.newInputStream(resolved))) {
            // read version
            int version = in.readInt();

            // read key
            String key = in.readUTF();

            // read content type
            byte[] contentTypeBytes = new byte[in.readInt()];
            in.readFully(contentTypeBytes);
            String contentType = new String(contentTypeBytes);

            // read expiry
            long expiry = in.readLong();

            // read last modified time
            long lastModified = in.readLong();

            // read modifiable state data
            boolean modifiable = in.readBoolean();
            String authKey = null;
            if (modifiable) {
                authKey = in.readUTF();
            }

            // read content
            byte[] content = new byte[in.readInt()];
            in.readFully(content);

            return new Content(key, contentType, expiry, lastModified, modifiable, authKey, content);
        }
    }

    public Content loadMeta(Path resolved) throws IOException {
        if (!Files.exists(resolved)) {
            return Content.EMPTY_CONTENT;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(resolved)))) {
            // read version
            int version = in.readInt();

            // read key
            String key = in.readUTF();

            // read content type
            byte[] contentTypeBytes = new byte[in.readInt()];
            in.readFully(contentTypeBytes);
            String contentType = new String(contentTypeBytes);

            // read expiry
            long expiry = in.readLong();

            // read last modified time
            long lastModified = in.readLong();

            // read modifiable state data
            boolean modifiable = in.readBoolean();
            String authKey = null;
            if (modifiable) {
                authKey = in.readUTF();
            }

            return new Content(key, contentType, expiry, lastModified, modifiable, authKey, Content.EMPTY_BYTES);
        }
    }

    public void save(String key, String contentType, byte[] content, long expiry, String authKey, boolean requiresCompression, CompletableFuture<Content> future) {
        if (requiresCompression) {
            content = Compression.compress(content);
        }

        // add directly to the cache
        // it's quite likely that the file will be requested only a few seconds after it is uploaded
        Content c = new Content(key, contentType, expiry, System.currentTimeMillis(), authKey != null, authKey, content);
        future.complete(c);

        save(c);
    }

    public void save(Content c) {
        // resolve the path to save at
        Path path = this.contentPath.resolve(c.getKey());

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            // write version
            out.writeInt(1);

            // write name
            out.writeUTF(c.getKey());

            // write content type
            byte[] contextType = c.getContentType().getBytes();
            out.writeInt(contextType.length);
            out.write(contextType);

            // write expiry time
            out.writeLong(c.getExpiry());

            // write last modified
            out.writeLong(c.getLastModified());

            // write modifiable state data
            out.writeBoolean(c.isModifiable());
            if (c.isModifiable()) {
                out.writeUTF(c.getAuthKey());
            }

            // write content
            out.writeInt(c.getContent().length);
            out.write(c.getContent());
        } catch (IOException e) {
            LOGGER.error("Exception occurred saving '" + path + "'", e);
        }
    }

    public void runInvalidation() {
        try (Stream<Path> stream = Files.list(this.contentPath)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Content content = loadMeta(path);
                            if (content.shouldExpire()) {
                                LOGGER.info("Expired: " + path.getFileName().toString());
                                Files.delete(path);
                            }
                        } catch (EOFException e) {
                            LOGGER.info("Corrupted: " + path.getFileName().toString());
                            try {
                                Files.delete(path);
                            } catch (IOException e2) {
                                // ignore
                            }
                        } catch (Exception e) {
                            LOGGER.error("Exception occurred loading meta for '" + path.getFileName().toString() + "'", e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Exception thrown whilst invalidating", e);
        }
    }
}
