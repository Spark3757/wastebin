package ru.spark.wastebin.util;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import org.rapidoid.http.Req;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class Compression {

    private static final Splitter COMMA_SPLITTER = Splitter.on(", ");

    private Compression() {
    }

    public static boolean acceptsCompressed(Req req) {
        String header = req.header("Accept-Encoding", null);
        return header != null && Iterables.contains(COMMA_SPLITTER.split(header), "gzip");
    }

    public static byte[] compress(byte[] buf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length);
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
            gzipOut.write(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    public static byte[] decompress(byte[] buf) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(buf);
        try (GZIPInputStream gzipIn = new GZIPInputStream(in)) {
            return ByteStreams.toByteArray(gzipIn);
        }
    }

}
