package ru.spark.wastebin.content;

public final class Content {

    /**
     * Empty byte array
     */
    public static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * Empty content instance
     */
    public static final Content EMPTY_CONTENT = new Content(null, "text/plain", Long.MAX_VALUE, Long.MIN_VALUE, false, null, EMPTY_BYTES);

    /**
     * Number of bytes in a MB
     */
    public static final long MEGABYTE_LENGTH = 1024L * 1024L;

    private final String key;
    private final boolean modifiable;
    private final String authKey;
    private String contentType;
    private long expiry;
    private long lastModified;
    private byte[] content;

    public Content(String key, String contentType, long expiry, long lastModified, boolean modifiable, String authKey, byte[] content) {
        this.key = key;
        this.contentType = contentType;
        this.expiry = expiry;
        this.lastModified = lastModified;
        this.modifiable = modifiable;
        this.authKey = authKey;
        this.content = content;
    }

    public String getKey() {
        return this.key;
    }

    public String getContentType() {
        return this.contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getExpiry() {
        return this.expiry;
    }

    public void setExpiry(long expiry) {
        this.expiry = expiry;
    }

    public long getLastModified() {
        return this.lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public boolean isModifiable() {
        return this.modifiable;
    }

    public String getAuthKey() {
        return this.authKey;
    }

    public byte[] getContent() {
        return this.content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public boolean shouldExpire() {
        return this.getExpiry() < System.currentTimeMillis();
    }
}
