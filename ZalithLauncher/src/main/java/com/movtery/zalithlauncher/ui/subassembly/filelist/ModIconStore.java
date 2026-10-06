package com.movtery.zalithlauncher.ui.subassembly.filelist;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import com.movtery.zalithlauncher.InfoDistributor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Finds the icon of a mod jar:
 * 1) inside the jar itself,
 * 2) in the local icon cache (low resolution PNG),
 * 3) online, by looking the file up by hash on Modrinth, then CurseForge.
 */
public final class ModIconStore {
    private ModIconStore() {
    }

    public interface Listener {
        /**
         * Called on the main thread.
         *
         * @param updated files that got a new icon
         * @param total   number of mods that had no icon and were checked
         * @param found   number of icons found
         */
        void onFinished(List<File> updated, int total, int found);
    }

    private static final String USER_AGENT = "MyMCLauncher/1.0 (personal use)";
    private static final int ICON_SIZE = 128;
    private static final long NONE_TTL_MS = 12L * 60 * 60 * 1000;
    private static final int MAX_DOWNLOAD = 3 * 1024 * 1024;
    private static final int MAX_JAR_ICON_BYTES = 2 * 1024 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    // ================= public helpers =================

    public static boolean isModJar(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".jar.disabled");
    }

    /** Same key for enabled/disabled copy of the same file, independent of the folder. */
    public static String keyFor(File file) {
        String name = file.getName();
        if (name.toLowerCase(Locale.ROOT).endsWith(".disabled")) {
            name = name.substring(0, name.length() - ".disabled".length());
        }
        String raw = name + "|" + file.length() + "|" + file.lastModified();
        return hex(sha1(raw.getBytes(StandardCharsets.UTF_8)));
    }

    /** Icon saved earlier in the local cache, or null. */
    public static byte[] readCached(Context context, File modFile) {
        File png = new File(iconDir(context), keyFor(modFile) + ".png");
        if (!png.isFile()) return null;
        try (InputStream in = new FileInputStream(png)) {
            return readAll(in, MAX_JAR_ICON_BYTES);
        } catch (Exception e) {
            return null;
        }
    }

    /** Icon stored inside the jar (Forge, NeoForge, Fabric, Quilt, old Forge, or common file names). */
    public static byte[] readFromJar(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            String path = findIconPath(zip);
            if (path != null) {
                byte[] bytes = readEntry(zip, path);
                if (isDecodable(bytes)) return bytes;
            }
            String[] fallbacks = {"pack.png", "icon.png", "logo.png"};
            for (String name : fallbacks) {
                byte[] bytes = readEntry(zip, name);
                if (isDecodable(bytes)) return bytes;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Looks online for the icons of mods that have none (not inside the jar, not in the cache).
     *
     * @param force also retry mods that failed to be found earlier
     * @return false if a scan is already running
     */
    public static boolean fetchMissing(Context context, List<File> files, boolean force, Listener listener) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        final Context app = context.getApplicationContext();
        final List<File> input = new ArrayList<>(files);

        EXECUTOR.execute(() -> {
            final List<File> updated = new ArrayList<>();
            int total = 0;
            try {
                total = run(app, input, force, updated);
            } catch (Throwable ignored) {
            } finally {
                RUNNING.set(false);
            }
            final int finalTotal = total;
            MAIN.post(() -> {
                if (listener != null) listener.onFinished(updated, finalTotal, updated.size());
            });
        });
        return true;
    }

    // ================= scan =================

    private static int run(Context ctx, List<File> files, boolean force, List<File> updated) {
        File dir = iconDir(ctx);
        List<File> candidates = new ArrayList<>();

        for (File f : files) {
            if (!f.isFile() || !isModJar(f)) continue;
            String key = keyFor(f);
            if (new File(dir, key + ".png").exists()) continue;

            File none = new File(dir, key + ".none");
            if (none.exists()) {
                if (force || System.currentTimeMillis() - none.lastModified() > NONE_TTL_MS) {
                    none.delete();
                } else {
                    continue;
                }
            }
            if (readFromJar(f) != null) continue;
            candidates.add(f);
        }
        if (candidates.isEmpty()) return 0;

        boolean failed = false;
        List<File> remaining = new ArrayList<>(candidates);

        // ---------- Modrinth (lookup by SHA-1) ----------
        Map<String, List<File>> bySha1 = new HashMap<>();
        for (File f : candidates) {
            try {
                String h = hex(fileSha1(f));
                List<File> list = bySha1.get(h);
                if (list == null) {
                    list = new ArrayList<>();
                    bySha1.put(h, list);
                }
                list.add(f);
            } catch (IOException ignored) {
            }
        }

        try {
            Map<String, String> iconByHash = modrinthIcons(bySha1.keySet());
            for (Map.Entry<String, String> entry : iconByHash.entrySet()) {
                List<File> list = bySha1.get(entry.getKey());
                if (list == null) continue;
                byte[] data = download(entry.getValue());
                if (data == null) continue;
                for (File f : list) {
                    if (saveLowRes(data, new File(dir, keyFor(f) + ".png"))) {
                        updated.add(f);
                        remaining.remove(f);
                    }
                }
            }
        } catch (Exception e) {
            failed = true;
        }

        // ---------- CurseForge (lookup by fingerprint) ----------
        String cfKey = InfoDistributor.CURSEFORGE_API_KEY;
        boolean hasCfKey = cfKey != null && !cfKey.trim().isEmpty() && !"DUMMY".equals(cfKey.trim());
        if (!remaining.isEmpty() && hasCfKey) {
            try {
                Map<Long, List<File>> byFingerprint = new HashMap<>();
                for (File f : remaining) {
                    try {
                        long fp = curseFingerprint(f);
                        List<File> list = byFingerprint.get(fp);
                        if (list == null) {
                            list = new ArrayList<>();
                            byFingerprint.put(fp, list);
                        }
                        list.add(f);
                    } catch (IOException ignored) {
                    }
                }

                Map<Long, String> iconByFingerprint = curseForgeIcons(cfKey.trim(), byFingerprint.keySet());
                for (Map.Entry<Long, String> entry : iconByFingerprint.entrySet()) {
                    List<File> list = byFingerprint.get(entry.getKey());
                    if (list == null) continue;
                    byte[] data = download(entry.getValue());
                    if (data == null) continue;
                    for (File f : list) {
                        if (saveLowRes(data, new File(dir, keyFor(f) + ".png"))) {
                            updated.add(f);
                            remaining.remove(f);
                        }
                    }
                }
            } catch (Exception e) {
                failed = true;
            }
        }

        // remember mods that were not found, so we do not ask again every time
        if (!failed) {
            for (File f : remaining) {
                try {
                    new File(dir, keyFor(f) + ".none").createNewFile();
                } catch (IOException ignored) {
                }
            }
        }
        return candidates.size();
    }

    // ================= Modrinth =================

    /** @return map: sha1 -> icon url */
    private static Map<String, String> modrinthIcons(Set<String> hashes) throws Exception {
        Map<String, String> result = new HashMap<>();
        if (hashes.isEmpty()) return result;

        JSONObject body = new JSONObject();
        body.put("hashes", new JSONArray(hashes));
        body.put("algorithm", "sha1");
        JSONObject versions = new JSONObject(post("https://api.modrinth.com/v2/version_files", body.toString(), null));

        Map<String, String> hashToProject = new HashMap<>();
        Set<String> projectIds = new LinkedHashSet<>();
        Iterator<String> keys = versions.keys();
        while (keys.hasNext()) {
            String hash = keys.next();
            JSONObject version = versions.optJSONObject(hash);
            String projectId = version == null ? null : str(version, "project_id");
            if (projectId != null) {
                hashToProject.put(hash, projectId);
                projectIds.add(projectId);
            }
        }

        Map<String, String> projectToIcon = new HashMap<>();
        List<String> ids = new ArrayList<>(projectIds);
        for (int i = 0; i < ids.size(); i += 40) {
            List<String> chunk = ids.subList(i, Math.min(i + 40, ids.size()));
            String url = "https://api.modrinth.com/v2/projects?ids="
                    + URLEncoder.encode(new JSONArray(chunk).toString(), "UTF-8");
            JSONArray projects = new JSONArray(get(url));
            for (int j = 0; j < projects.length(); j++) {
                JSONObject p = projects.optJSONObject(j);
                if (p == null) continue;
                String id = str(p, "id");
                String icon = str(p, "icon_url");
                if (id != null && icon != null) projectToIcon.put(id, icon);
            }
        }

        for (Map.Entry<String, String> e : hashToProject.entrySet()) {
            String icon = projectToIcon.get(e.getValue());
            if (icon != null) result.put(e.getKey(), icon);
        }
        return result;
    }

    // ================= CurseForge =================

    /** @return map: fingerprint -> logo url */
    private static Map<Long, String> curseForgeIcons(String apiKey, Set<Long> fingerprints) throws Exception {
        Map<Long, String> result = new HashMap<>();
        if (fingerprints.isEmpty()) return result;

        JSONObject body = new JSONObject();
        body.put("fingerprints", new JSONArray(fingerprints));
        JSONObject root = new JSONObject(post("https://api.curseforge.com/v1/fingerprints/432", body.toString(), apiKey));
        JSONObject data = root.optJSONObject("data");
        JSONArray matches = data == null ? null : data.optJSONArray("exactMatches");
        if (matches == null) return result;

        Map<Long, Long> fingerprintToMod = new HashMap<>();
        Set<Long> modIds = new LinkedHashSet<>();
        for (int i = 0; i < matches.length(); i++) {
            JSONObject m = matches.optJSONObject(i);
            if (m == null) continue;
            JSONObject file = m.optJSONObject("file");
            if (file == null) continue;
            long fp = file.optLong("fileFingerprint", 0);
            long modId = file.optLong("modId", m.optLong("id", 0));
            if (fp != 0 && modId != 0) {
                fingerprintToMod.put(fp, modId);
                modIds.add(modId);
            }
        }

        Map<Long, String> modToIcon = new HashMap<>();
        List<Long> ids = new ArrayList<>(modIds);
        for (int i = 0; i < ids.size(); i += 50) {
            List<Long> chunk = ids.subList(i, Math.min(i + 50, ids.size()));
            JSONObject req = new JSONObject();
            req.put("modIds", new JSONArray(chunk));
            JSONObject modsRoot = new JSONObject(post("https://api.curseforge.com/v1/mods", req.toString(), apiKey));
            JSONArray mods = modsRoot.optJSONArray("data");
            if (mods == null) continue;
            for (int j = 0; j < mods.length(); j++) {
                JSONObject mod = mods.optJSONObject(j);
                if (mod == null) continue;
                JSONObject logo = mod.optJSONObject("logo");
                if (logo == null) continue;
                String url = str(logo, "thumbnailUrl");
                if (url == null || url.isEmpty()) url = str(logo, "url");
                if (url != null && !url.isEmpty()) modToIcon.put(mod.optLong("id"), url);
            }
        }

        for (Map.Entry<Long, Long> e : fingerprintToMod.entrySet()) {
            String icon = modToIcon.get(e.getValue());
            if (icon != null) result.put(e.getKey(), icon);
        }
        return result;
    }

    /** CurseForge fingerprint: MurmurHash2 (seed 1) of the file without whitespace bytes (9, 10, 13, 32). */
    private static long curseFingerprint(File file) throws IOException {
        byte[] buf = new byte[65536];

        long length = 0;
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    if (!isWhitespace(buf[i])) length++;
                }
            }
        }

        final int m = 0x5bd1e995;
        final int r = 24;
        int h = 1 ^ (int) length;
        int k = 0;
        int shift = 0;

        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (isWhitespace(b)) continue;
                    k |= (b & 0xff) << shift;
                    shift += 8;
                    if (shift == 32) {
                        k *= m;
                        k ^= k >>> r;
                        k *= m;
                        h *= m;
                        h ^= k;
                        k = 0;
                        shift = 0;
                    }
                }
            }
        }

        if (shift > 0) {
            h ^= k;
            h *= m;
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h & 0xffffffffL;
    }

    private static boolean isWhitespace(byte b) {
        return b == 9 || b == 10 || b == 13 || b == 32;
    }

    // ================= network =================

    private static String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private static String post(String url, String json, String apiKey) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .post(RequestBody.create(json, JSON_TYPE));
        if (apiKey != null) builder.header("x-api-key", apiKey);
        try (Response response = CLIENT.newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    private static byte[] download(String url) {
        try {
            Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
            try (Response response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                if (response.body().contentLength() > MAX_DOWNLOAD) return null;
                byte[] bytes = response.body().bytes();
                return bytes.length > MAX_DOWNLOAD ? null : bytes;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ================= image saving =================

    /** Shrinks the image to a small PNG and saves it. */
    private static boolean saveLowRes(byte[] data, File out) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= ICON_SIZE * 2 && bounds.outHeight / (sample * 2) >= ICON_SIZE * 2) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null) return false;

            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int max = Math.max(w, h);
            if (max > ICON_SIZE) {
                float scale = ICON_SIZE / (float) max;
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)), true);
            }

            File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            return tmp.renameTo(out);
        } catch (Exception e) {
            return false;
        }
    }

    private static File iconDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "mod_icons");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ================= reading a jar =================

    private static final Pattern MCMOD_LOGO = Pattern.compile("\"logoFile\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOML_LOGO = Pattern.compile("^logoFile\\s*=\\s*[\"']([^\"']+)[\"']");

    private static String findIconPath(ZipFile zip) {
        try {
            // Fabric
            String fabric = readText(zip, "fabric.mod.json");
            if (fabric != null) {
                String p = iconFromJson(new JSONObject(fabric).opt("icon"));
                if (p != null) return p;
            }
            // Quilt
            String quilt = readText(zip, "quilt.mod.json");
            if (quilt != null) {
                JSONObject meta = new JSONObject(quilt).getJSONObject("quilt_loader").getJSONObject("metadata");
                String p = iconFromJson(meta.opt("icon"));
                if (p != null) return p;
            }
        } catch (Exception ignored) {
        }
        // NeoForge / Forge
        String[] tomls = {"META-INF/neoforge.mods.toml", "META-INF/mods.toml"};
        for (String toml : tomls) {
            String text = readText(zip, toml);
            if (text == null) continue;
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) continue;
                Matcher m = TOML_LOGO.matcher(trimmed);
                if (m.find()) return m.group(1);
            }
        }
        // Old Forge
        String info = readText(zip, "mcmod.info");
        if (info != null) {
            Matcher m = MCMOD_LOGO.matcher(info);
            if (m.find() && !m.group(1).trim().isEmpty()) return m.group(1);
        }
        return null;
    }

    private static String iconFromJson(Object icon) {
        if (icon instanceof String) return (String) icon;
        if (icon instanceof JSONObject) {
            JSONObject obj = (JSONObject) icon;
            String best = null;
            int bestSize = -1;
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                int size;
                try {
                    size = Integer.parseInt(k);
                } catch (NumberFormatException e) {
                    size = 0;
                }
                if (size > bestSize) {
                    String value = str(obj, k);
                    if (value != null) {
                        bestSize = size;
                        best = value;
                    }
                }
            }
            return best;
        }
        return null;
    }

    private static String readText(ZipFile zip, String name) {
        byte[] bytes = readEntry(zip, name);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readEntry(ZipFile zip, String name) {
        try {
            String clean = name.replace("\\", "/");
            while (clean.startsWith("/") || clean.startsWith("./")) {
                clean = clean.startsWith("/") ? clean.substring(1) : clean.substring(2);
            }
            ZipEntry entry = zip.getEntry(clean);
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_JAR_ICON_BYTES) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                return readAll(in, MAX_JAR_ICON_BYTES);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isDecodable(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return options.outWidth > 0 && options.outHeight > 0;
    }

    // ================= small utils =================

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > limit) return null;
        }
        return out.toByteArray();
    }

    private static String str(JSONObject obj, String key) {
        if (obj.isNull(key)) return null;
        return obj.optString(key, null);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] fileSha1(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
