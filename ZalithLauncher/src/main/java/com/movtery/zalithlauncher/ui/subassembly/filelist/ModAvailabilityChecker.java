package com.movtery.zalithlauncher.ui.subassembly.filelist;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks in the background, 5 mods at a time (in list order), on which platforms
 * each mod file exists. Results are cached for the app session.
 */
public final class ModAvailabilityChecker {
    private ModAvailabilityChecker() {
    }

    public interface Listener {
        /** Called on the main thread after each batch with the files that got a result. */
        void onBatch(List<File> updated);
    }

    private static final int BATCH_SIZE = 5;
    private static final Map<String, ModIconStore.PlatformInfo> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger GENERATION = new AtomicInteger();

    /** Cached result, or null if the file was not checked yet. */
    public static ModIconStore.PlatformInfo get(File file) {
        if (file == null) return null;
        return CACHE.get(ModIconStore.keyFor(file));
    }

    /**
     * Starts checking the files that have no cached result.
     * A newer call cancels the one still running.
     *
     * @param force forget the cached results of these files and check them again
     */
    public static void check(List<File> files, boolean force, Listener listener) {
        final int gen = GENERATION.incrementAndGet();
        final List<File> todo = new ArrayList<>();

        for (File f : files) {
            if (f == null || !f.isFile() || !ModIconStore.isModJar(f)) continue;
            String key = ModIconStore.keyFor(f);
            if (force) CACHE.remove(key);
            if (!CACHE.containsKey(key)) todo.add(f);
        }
        if (todo.isEmpty()) return;

        EXECUTOR.execute(() -> {
            for (int i = 0; i < todo.size(); i += BATCH_SIZE) {
                if (gen != GENERATION.get()) return;

                List<File> chunk = new ArrayList<>(
                        todo.subList(i, Math.min(i + BATCH_SIZE, todo.size()))
                );

                Map<File, ModIconStore.PlatformInfo> result;
                try {
                    result = ModIconStore.lookupPlatforms(chunk);
                } catch (Throwable t) {
                    return;
                }
                if (gen != GENERATION.get()) return;

                final List<File> updated = new ArrayList<>();
                for (File f : chunk) {
                    ModIconStore.PlatformInfo info = result.get(f);
                    if (info == null || !info.isComplete()) continue;
                    CACHE.put(ModIconStore.keyFor(f), info);
                    updated.add(f);
                }

                // nothing usable came back: network is probably down, stop here
                if (updated.isEmpty()) return;

                MAIN.post(() -> {
                    if (listener != null) listener.onBatch(updated);
                });
            }
        });
    }
}
