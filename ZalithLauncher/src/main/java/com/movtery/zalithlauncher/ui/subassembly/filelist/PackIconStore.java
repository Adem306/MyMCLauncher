package com.movtery.zalithlauncher.ui.subassembly.filelist;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Icons for resource packs, shader packs and worlds.
 * <p>
 * 1) resource pack (folder or .zip): pack.png inside it
 * 2) world (folder): icon.png inside it
 * 3) resource packs / shaders in .zip form that have no icon inside: looked up on Modrinth by the file hash
 * (only once, the result is saved; worlds and folders never go online)
 */
public final class PackIconStore {
    public static final int KIND_NONE = 0;
    public static final int KIND_RESOURCEPACK = 1;
    public static final int KIND_SHADER = 2;
    public static final int KIND_WORLD = 3;

    private static final int MAX_ICON_BYTES = 3 * 1024 * 1024;
    private static final String USER_AGENT = "MyMCLauncher/1.0 (github.com/Adem306/MyMCLauncher)";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final ExecutorService NET_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        /** called on the main thread: files that got a new icon, how many were checked, how many icons were found */
        void onDone(List<File> updated, int total, int found);
    }

    private PackIconStore() {
    }

    /** what kind of item this is, judged by the folder it is in (resourcepacks / shaderpacks / saves) */
    public static int kindOf(File file) {
        if (file == null) return KIND_NONE;
        File parent = file.getParentFile();
        if (parent == null) return KIND_NONE;

        String parentName = parent.getName().toLowerCase(Locale.ROOT);
        boolean isResourceOrShaderFolder = parentName.equals("resourcepacks") || parentName.equals("shaderpacks");
        if (!isResourceOrShaderFolder && !parentName.equals("saves")) return KIND_NONE;

        boolean isDir = file.isDirectory();
        boolean isZip = file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".zip");

        switch (parentName) {
            case "resourcepacks":
                return (isDir || isZip) ? KIND_RESOURCEPACK : KIND_NONE;
            case "shaderpacks":
                return (isDir || isZip) ? KIND_SHADER : KIND_NONE;
            default:
                return isDir ? KIND_WORLD : KIND_NONE;
        }
    }

    public static String keyFor(File file) {
        String raw = file.getAbsolutePath() + "|" + file.lastModified() + "|" + file.length();
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ===== 1) icon inside the pack / world =====

    public static byte[] readInside(File file, int kind) {
        String entryName = kind == KIND_WORLD ? "icon.png" : "pack.png";
        try {
            if (file.isDirectory()) {
                File image = new File(file, entryName);
                if (image.isFile() && image.length() > 0 && image.length() < MAX_ICON_BYTES) {
                    return readAll(new FileInputStream(image));
                }
                return null;
            }
            if (file.isFile()) {
                try (ZipFile zip = new ZipFile(file)) {
                    ZipEntry entry = zip.getEntry(entryName);
                    if (entry == null || entry.getSize() > MAX_ICON_BYTES) return null;
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] data = readAll(in);
                        return data.length == 0 ? null : data;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ===== 2) icon saved from the internet =====

    private static File cacheDir(Context context) {
        File dir = new File(context.getCacheDir(), "pack_icons");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static byte[] readCached(Context context, File file) {
        File image = new File(cacheDir(context), keyFor(file) + ".img");
        if (!image.isFile() || image.length() == 0) return null;
        try {
            return readAll(new FileInputStream(image));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void saveCached(Context context, File file, byte[] data) {
        try {
            File dir = cacheDir(context);
            java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, keyFor(file) + ".img"));
            try {
                out.write(data);
            } finally {
                out.close();
            }
            //noinspection ResultOfMethodCallIgnored
            new File(dir, keyFor(file) + ".none").delete();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isMarkedNone(Context context, File file) {
        return new File(cacheDir(context), keyFor(file) + ".none").exists();
    }

    private static void markNone(Context context, File file) {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(cacheDir(context), keyFor(file) + ".none").createNewFile();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Looks online (Modrinth, by file hash) for the icons of .zip resource packs / shaders that have no icon.
     *
     * @param force also retry the ones that were not found before
     * @return false if a check is already running
     */
    public static boolean fetchMissing(Context context, List<File> files, boolean force, Callback callback) {
        if (!RUNNING.compareAndSet(false, true)) return false;

        final Context appContext = context.getApplicationContext();
        final List<File> copy = new ArrayList<>(files);

        NET_EXECUTOR.execute(() -> {
            final List<File> updated = new ArrayList<>();
            int total = 0;
            int found = 0;
            int networkErrors = 0;
            try {
                for (File file : copy) {
                    if (file == null || !file.isFile()) continue; // only .zip files have a hash to look up
                    int kind = kindOf(file);
                    if (kind != KIND_RESOURCEPACK && kind != KIND_SHADER) continue;
                    if (readInside(file, kind) != null) continue;
                    if (readCached(appContext, file) != null) continue;
                    if (!force && isMarkedNone(appContext, file)) continue;

                    total++;
                    try {
                        byte[] data = downloadIcon(file);
                        if (data != null) {
                            saveCached(appContext, file, data);
                            updated.add(file);
                            found++;
                        } else {
                            markNone(appContext, file);
                        }
                    } catch (IOException e) {
                        // no internet or server problem: do NOT mark it, so it is tried again next time
                        networkErrors++;
                        if (networkErrors >= 3) break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                RUNNING.set(false);
            }

            final int finalTotal = total;
            final int finalFound = found;
            MAIN.post(() -> {
                if (callback != null) callback.onDone(updated, finalTotal, finalFound);
            });
        });
        return true;
    }

    /** returns the icon, or null if the file is not on Modrinth / has no icon. Throws IOException on network problems. */
    private static byte[] downloadIcon(File file) throws IOException {
        String sha1 = sha1Of(file);

        String versionJson = httpGetString("https://api.modrinth.com/v2/version_file/" + sha1 + "?algorithm=sha1");
        if (versionJson == null) return null;

        String projectId;
        try {
            projectId = new JSONObject(versionJson).optString("project_id", "");
        } catch (Exception e) {
            return null;
        }
        if (projectId.isEmpty()) return null;

        String projectJson = httpGetString("https://api.modrinth.com/v2/project/" + projectId);
        if (projectJson == null) return null;

        String iconUrl;
        try {
            JSONObject project = new JSONObject(projectJson);
            iconUrl = project.isNull("icon_url") ? "" : project.optString("icon_url", "");
        } catch (Exception e) {
            return null;
        }
        if (iconUrl.isEmpty()) return null;

        return httpGetBytes(iconUrl);
    }

    private static String sha1Of(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** 404 -> null, other errors -> IOException */
    private static String httpGetString(String url) throws IOException {
        byte[] data = httpGetBytes(url);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int code = connection.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);

            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_ICON_BYTES) return null; // too big to be an icon
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
