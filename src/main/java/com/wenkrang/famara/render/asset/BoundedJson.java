package com.wenkrang.famara.render.asset;

import com.google.gson.*;
import com.google.gson.stream.*;

import java.io.*;
import java.nio.charset.*;

/**
 * 流式检查深度、节点数和重复字段，不使用会放宽语法的 JsonParser 入口。
 */
public final class BoundedJson {
    public static JsonObject read(ResourcePack.Content content, String path, AssetLimits limits) {
        return read(content,path,limits,new AssetMemoryBudget(limits.maxTotalBytes()));
    }

    public static JsonObject read(ResourcePack.Content content,String path,AssetLimits limits,AssetMemoryBudget memory) {
        try (Reader chars = new InputStreamReader(content.open(), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
             JsonReader reader = new JsonReader(chars)) {
            reader.setLenient(false);
            JsonElement result = value(reader, 0, new int[]{0}, limits, path,memory);
            if (reader.peek() != JsonToken.END_DOCUMENT || !result.isJsonObject())
                throw new AssetException(AssetException.Code.INVALID, path, "需要唯一JSON对象");
            return result.getAsJsonObject();
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            throw new AssetException(AssetException.Code.INVALID, path, "JSON无法解析", e);
        }
    }

    private static JsonElement value(JsonReader r, int depth, int[] nodes, AssetLimits limits, String path,AssetMemoryBudget memory) throws IOException {
        if (depth > limits.maxJsonDepth() || ++nodes[0] > limits.maxJsonNodes())
            throw new AssetException(AssetException.Code.LIMIT, path, "JSON结构超过限额");
        memory.reserve(128,path);
        switch (r.peek()) {
            case BEGIN_OBJECT -> {
                r.beginObject();
                JsonObject o = new JsonObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    memory.reserve(64L+key.length()*2L,path);
                    if (o.has(key)) throw new AssetException(AssetException.Code.INVALID, path, "重复JSON字段 " + key);
                    o.add(key, value(r, depth + 1, nodes, limits, path,memory));
                }
                r.endObject();
                return o;
            }
            case BEGIN_ARRAY -> {
                r.beginArray();
                JsonArray a = new JsonArray();
                while (r.hasNext()) a.add(value(r, depth + 1, nodes, limits, path,memory));
                r.endArray();
                return a;
            }
            case STRING -> {
                String value=r.nextString();memory.reserve(32L+value.length()*2L,path);
                return new JsonPrimitive(value);
            }
            case NUMBER -> {
                return new JsonPrimitive(new java.math.BigDecimal(r.nextString()));
            }
            case BOOLEAN -> {
                return new JsonPrimitive(r.nextBoolean());
            }
            case NULL -> {
                r.nextNull();
                return JsonNull.INSTANCE;
            }
            default -> throw new AssetException(AssetException.Code.INVALID, path, "意外JSON节点");
        }
    }
}
