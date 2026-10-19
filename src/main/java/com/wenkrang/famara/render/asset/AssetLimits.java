package com.wenkrang.famara.render.asset;

/**
 * 所有限额显式注入；此类不提供未经批准的生产默认值。
 */
public record AssetLimits(int maxEntries, int maxEntryBytes, long maxTotalBytes,
                          int maxTexturePixels, int maxAnimationFrames, int maxModelFaces,
                          int maxReferenceDepth, int maxJsonDepth, int maxJsonNodes, double maxModelSpan) {
    public AssetLimits {
        if (maxEntries <= 0 || maxEntryBytes <= 0 || maxEntryBytes == Integer.MAX_VALUE
                || maxTotalBytes <= 0 || maxEntryBytes > maxTotalBytes || maxTexturePixels <= 0 || maxAnimationFrames <= 0
                || maxModelFaces <= 0 || maxReferenceDepth <= 0 || maxReferenceDepth > 256
                || maxJsonDepth <= 0 || maxJsonDepth > 256 || maxJsonNodes <= 0
                || !Double.isFinite(maxModelSpan) || maxModelSpan <= 0)
            throw new IllegalArgumentException("资源限额必须为有界正数");
    }
}
