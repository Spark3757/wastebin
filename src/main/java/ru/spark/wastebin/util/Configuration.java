package ru.spark.wastebin.util;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class Configuration {

    private final JsonObject jsonObject;

    public Configuration(JsonObject jsonObject) {
        this.jsonObject = jsonObject;
    }

    public static Configuration load(Path configPath) throws IOException {
        Configuration config;
        if (Files.exists(configPath)) {
            try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                config = new Configuration(new Gson().fromJson(reader, JsonObject.class));
            }
        } else {
            config = new Configuration(new JsonObject());
        }
        return config;
    }

    public String getString(String path, String def) {
        JsonElement e = this.jsonObject.get(path);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return def;
        }
        return e.getAsString();
    }

    public int getInt(String path, int def) {
        JsonElement e = this.jsonObject.get(path);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return def;
        }
        return e.getAsInt();
    }

    public long getLong(String path, long def) {
        JsonElement e = this.jsonObject.get(path);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return def;
        }
        return e.getAsLong();
    }

    public Map<String, Long> getLongMap(String path) {
        JsonElement e = this.jsonObject.get(path);
        if (e == null || !e.isJsonObject()) {
            return ImmutableMap.of();
        }

        JsonObject map = e.getAsJsonObject();
        return map.entrySet().stream().collect(ImmutableMap.toImmutableMap(
                Map.Entry::getKey,
                ent -> ent.getValue().getAsLong()
        ));
    }
}
