package com.kawai.mochi;

import android.graphics.Bitmap;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class StickerBitmapLruCache {
    private static final StickerBitmapLruCache INSTANCE = new StickerBitmapLruCache();

    private final LruCache<String, Bitmap> cache;

    private StickerBitmapLruCache() {
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int targetSizeKb = Math.min(maxMemoryKb / 8, 24 * 1024);
        cache = new LruCache<String, Bitmap>(Math.max(targetSizeKb, 4 * 1024)) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    @NonNull
    static StickerBitmapLruCache getInstance() {
        return INSTANCE;
    }

    @Nullable
    Bitmap get(@NonNull String key) {
        return cache.get(key);
    }

    void put(@NonNull String key, @NonNull Bitmap bitmap) {
        cache.put(key, bitmap);
    }
}