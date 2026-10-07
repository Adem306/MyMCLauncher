package com.movtery.zalithlauncher.ui.subassembly.filelist;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.DrawableImageViewTarget;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.databinding.ItemFileListViewBinding;
import com.movtery.zalithlauncher.utils.file.FileTools;
import com.movtery.zalithlauncher.utils.image.ImageUtils;
import com.movtery.zalithlauncher.utils.stringutils.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public class FileRecyclerAdapter extends RecyclerView.Adapter<FileRecyclerAdapter.InnerHolder> {
    private final List<FileItemBean> mData = new ArrayList<>();
    private final List<FileItemBean> selectedFiles = new ArrayList<>();
    private boolean isMultiSelectMode = false;
    private OnItemClickListener mOnItemClickListener;
    private OnItemLongClickListener mOnItemLongClickListener;
    private OnMultiSelectListener mOnMultiSelectListener;

    // ===== Mod icon support =====
    private static final ExecutorService ICON_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final byte[] NO_ICON = new byte[0];

    // ===== Copy file content support =====
    private static final ExecutorService COPY_EXECUTOR = Executors.newSingleThreadExecutor();
    // the maximum amount of text that gets copied (the END of the file is kept, because errors are usually at the end)
    private static final int MAX_COPY_BYTES = 500 * 1024;

    // Size of the mod icon in dp. Change this number to make the icons bigger or smaller
    // (36 = smaller, 44 = default, 52 = bigger, 60 = very big).
    private static final int MOD_ICON_SIZE_DP = 44;
    private static final LruCache<String, byte[]> ICON_CACHE = new LruCache<String, byte[]>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return Math.max(1, value.length);
        }
    };

    private Context appContext;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        appContext = recyclerView.getContext().getApplicationContext();
    }

    /**
     * Looks online for the icons of mods that have none.
     *
     * @param force also retry mods that were not found before (used by the long press on refresh)
     */
    public void refreshModIcons(Context context, boolean force) {
        appContext = context.getApplicationContext();
        fetchIcons(appContext, force);
    }

    private void fetchIcons(Context context, boolean force) {
        List<File> modFiles = new ArrayList<>();
        List<File> packFiles = new ArrayList<>();
        for (FileItemBean bean : mData) {
            File f = bean.file;
            if (f == null || !f.isFile()) continue;
            if (ModIconStore.isModJar(f)) {
                modFiles.add(f);
            } else {
                int kind = PackIconStore.kindOf(f);
                if (kind == PackIconStore.KIND_RESOURCEPACK || kind == PackIconStore.KIND_SHADER) packFiles.add(f);
            }
        }
        if (!modFiles.isEmpty()) fetchModIcons(context, modFiles, force);
        if (!packFiles.isEmpty()) fetchPackIcons(context, packFiles, force);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void fetchModIcons(Context context, List<File> files, boolean force) {
        if (force) {
            Toast.makeText(context, "جاري فحص صور المودات...", Toast.LENGTH_SHORT).show();
        }

        boolean started = ModIconStore.fetchMissing(context, files, force, (updated, total, found) -> {
            for (File f : updated) {
                ICON_CACHE.remove(ModIconStore.keyFor(f));
            }
            if (!updated.isEmpty()) notifyDataSetChanged();
            if (force) {
                String message = total == 0
                        ? "كل المودات عندها صور"
                        : "تم تحميل " + found + " صورة من أصل " + total + " مود بدون صورة";
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
        if (!started && force) {
            Toast.makeText(context, "الفحص شغال حالياً، انتظر شوي", Toast.LENGTH_SHORT).show();
        }
    }

    /** resource packs / shaders in .zip form that have no icon inside: looked up online (once, the result is saved) */
    @SuppressLint("NotifyDataSetChanged")
    private void fetchPackIcons(Context context, List<File> files, boolean force) {
        if (force) {
            Toast.makeText(context, "جاري فحص صور الريسورس باك والشادرات...", Toast.LENGTH_SHORT).show();
        }

        boolean started = PackIconStore.fetchMissing(context, files, force, (updated, total, found) -> {
            for (File f : updated) {
                ICON_CACHE.remove("pack:" + PackIconStore.keyFor(f));
            }
            if (!updated.isEmpty()) notifyDataSetChanged();
            if (force) {
                String message = total == 0
                        ? "كل الملفات عندها صور"
                        : "تم تحميل " + found + " صورة من أصل " + total + " ملف بدون صورة";
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
        if (!started && force) {
            Toast.makeText(context, "الفحص شغال حالياً، انتظر شوي", Toast.LENGTH_SHORT).show();
        }
    }

    // ===== Copy file content =====

    /** log / text files that get a copy button (.txt, .log, .log.gz) */
    private static boolean isCopyableTextFile(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".log.gz");
    }

    private static final class ReadResult {
        final String text;
        final boolean truncated;

        ReadResult(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    /** reads the file (decompressing .gz). If it is very big, only the END of it is kept. Returns null on failure. */
    private static ReadResult readTextTail(File file) {
        boolean truncated = false;
        try (InputStream raw = new FileInputStream(file);
             InputStream in = file.getName().toLowerCase(Locale.ROOT).endsWith(".gz")
                     ? new GZIPInputStream(raw) : raw) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                // keep only the end of the file in memory if it is very big
                if (out.size() > MAX_COPY_BYTES * 2) {
                    byte[] all = out.toByteArray();
                    out.reset();
                    out.write(all, all.length - MAX_COPY_BYTES, MAX_COPY_BYTES);
                    truncated = true;
                }
            }
            byte[] all = out.toByteArray();
            if (all.length > MAX_COPY_BYTES) {
                all = Arrays.copyOfRange(all, all.length - MAX_COPY_BYTES, all.length);
                truncated = true;
            }
            return new ReadResult(new String(all, StandardCharsets.UTF_8), truncated);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void showToast(Context context, String message) {
        MAIN_HANDLER.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    /** "640 سطر (85 كيلوبايت)" */
    private static String describe(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        int kb = text.getBytes(StandardCharsets.UTF_8).length / 1024;
        return lines + " سطر (" + kb + " كيلوبايت)";
    }

    private static void copyToClipboard(Context context, String text, String successMessage) {
        MAIN_HANDLER.post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("log", text));
                Toast.makeText(context, successMessage, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Toast.makeText(context, "فشل نسخ الملف", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** copies the whole content of the file */
    private static void copyFileContent(Context context, File file) {
        final Context appCtx = context.getApplicationContext();
        COPY_EXECUTOR.execute(() -> {
            ReadResult result = readTextTail(file);
            if (result == null) {
                showToast(appCtx, "فشل نسخ الملف");
                return;
            }
            if (result.text.isEmpty()) {
                showToast(appCtx, "الملف فارغ");
                return;
            }
            String info = describe(result.text);
            copyToClipboard(appCtx, result.text,
                    result.truncated ? "تم نسخ آخر " + info + " فقط (الملف كبير)" : "تم نسخ " + info);
        });
    }

    /** copies only the errors of the file */
    private static void copyErrorsOnly(Context context, File file) {
        final Context appCtx = context.getApplicationContext();
        COPY_EXECUTOR.execute(() -> {
            ReadResult result = readTextTail(file);
            if (result == null) {
                showToast(appCtx, "فشل قراءة الملف");
                return;
            }
            String errors = extractErrors(result.text);
            if (errors.isEmpty()) {
                showToast(appCtx, "ما لقيت أخطاء في هذا الملف");
                return;
            }
            String message = "تم نسخ الأخطاء فقط: " + describe(errors)
                    + (result.truncated ? " (من آخر 500 كيلوبايت)" : "");
            copyToClipboard(appCtx, errors, message);
        });
    }

    /** a new entry in a Minecraft log starts like "[13:33:52] [main/INFO]: ..." */
    private static boolean isLogEntryStart(String line) {
        if (line.length() < 3 || line.charAt(0) != '[') return false;
        int index = line.indexOf("] [");
        return index > 0 && index < 60;
    }

    private static String entryHead(String line) {
        return line.substring(0, Math.min(line.length(), 80));
    }

    private static boolean isErrorEntry(String line) {
        String head = entryHead(line);
        return head.contains("/ERROR]") || head.contains("/FATAL]");
    }

    private static boolean isWarnEntry(String line) {
        return entryHead(line).contains("/WARN]");
    }

    // a WARN entry is kept only if it contains one of these words...
    private static final String[] WARN_INCLUDE = {"Exception", "Caused by", "MalformedJson", "rejected"};
    // ...and none of these (very common harmless warnings of mods that are not installed)
    private static final String[] WARN_EXCLUDE = {"Error loading class", "@Mixin target", "Reference map", "ClassNotFoundException"};

    private static boolean containsAny(String text, String[] words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /** kind: 1 = ERROR / FATAL entry (always kept), 2 = WARN entry (kept only if it looks like a real problem) */
    private static void flushEntry(StringBuilder out, StringBuilder entry, int kind) {
        if (kind == 0 || entry.length() == 0) return;
        if (kind == 1) {
            out.append(entry);
            return;
        }
        String text = entry.toString();
        if (containsAny(text, WARN_INCLUDE) && !containsAny(text, WARN_EXCLUDE)) {
            out.append(text);
        }
    }

    /**
     * Log: every ERROR / FATAL entry, plus the WARN entries that contain an exception or a similar real problem,
     * with everything that belongs to them (stack trace, "Caused by"...).
     * Crash report: the top part, which has the reason of the crash and the stack trace.
     */
    private static String extractErrors(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();

        if (text.contains("---- Minecraft Crash Report ----")) {
            for (String line : lines) {
                if (line.startsWith("A detailed walkthrough of the error")) break;
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }

        StringBuilder entry = new StringBuilder();
        int kind = 0;
        for (String line : lines) {
            if (isLogEntryStart(line)) {
                flushEntry(sb, entry, kind);
                entry.setLength(0);
                kind = isErrorEntry(line) ? 1 : (isWarnEntry(line) ? 2 : 0);
            }
            if (kind != 0) entry.append(line).append('\n');
        }
        flushEntry(sb, entry, kind);
        return sb.toString().trim();
    }

    @NonNull
    @Override
    public InnerHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new InnerHolder(ItemFileListViewBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull InnerHolder holder, int position) {
        holder.setData(mData.get(position), position);
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateItems(List<FileItemBean> items) {
        this.mData.clear();
        this.mData.addAll(items);
        notifyDataSetChanged();
        // mods that have no icon get looked up online automatically (only once, the result is saved)
        if (appContext != null) fetchIcons(appContext, false);
    }

    public List<FileItemBean> getData() {
        return mData;
    }

    public boolean isNoFile() {
        return (mData.size() == 1 && !mData.get(0).isCanCheck) || mData.isEmpty();
    }

    private void toggleSelection(FileItemBean itemBean, CheckBox checkBox) {
        if (itemBean.isCanCheck) {
            if (selectedFiles.contains(itemBean)) {
                selectedFiles.remove(itemBean);
                checkBox.setChecked(false);
            } else {
                selectedFiles.add(itemBean);
                checkBox.setChecked(true);
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setMultiSelectMode(boolean multiSelectMode) {
        isMultiSelectMode = multiSelectMode;
        if (!multiSelectMode) {
            selectedFiles.clear();
        }
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void selectAllFiles(boolean selectAll) {
        selectedFiles.clear();
        if (selectAll) {
            for (FileItemBean item : mData) {
                if (item.isCanCheck) {
                    selectedFiles.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    public List<FileItemBean> getSelectedFiles() {
        return selectedFiles;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.mOnItemClickListener = listener;
    }

    public void setOnMultiSelectListener(OnMultiSelectListener listener) {
        this.mOnMultiSelectListener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.mOnItemLongClickListener = listener;
    }

    public interface OnItemClickListener {
        void onItemClick(int position, FileItemBean itemBean);
    }

    public interface OnMultiSelectListener {
        void onMultiSelect(List<FileItemBean> itemBeans);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(int position, FileItemBean itemBean);
    }

    public class InnerHolder extends RecyclerView.ViewHolder {
        private final Context context;
        private final ItemFileListViewBinding binding;
        private int mPosition;
        private FileItemBean mFileItemBean;

        // original look of the image view, so we can restore it for non-mod rows
        private final ImageView.ScaleType origScaleType;
        private final int origPadLeft, origPadTop, origPadRight, origPadBottom;
        private final int origWidth, origHeight;
        // identifies the mod icon request that is allowed to update this holder
        private String boundKey = null;

        public InnerHolder(@NonNull ItemFileListViewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            context = itemView.getContext();

            origScaleType = binding.image.getScaleType();
            origPadLeft = binding.image.getPaddingLeft();
            origPadTop = binding.image.getPaddingTop();
            origPadRight = binding.image.getPaddingRight();
            origPadBottom = binding.image.getPaddingBottom();
            ViewGroup.LayoutParams imageParams = binding.image.getLayoutParams();
            origWidth = imageParams != null ? imageParams.width : ViewGroup.LayoutParams.WRAP_CONTENT;
            origHeight = imageParams != null ? imageParams.height : ViewGroup.LayoutParams.WRAP_CONTENT;

            binding.check.setOnClickListener(v -> {
                if (isMultiSelectMode) {
                    toggleSelection(mFileItemBean, binding.check);
                }
            });

            binding.copyButton.setOnClickListener(v -> {
                if (mFileItemBean != null && mFileItemBean.file != null) {
                    copyFileContent(context, mFileItemBean.file);
                }
            });

            binding.copyErrorsButton.setOnClickListener(v -> {
                if (mFileItemBean != null && mFileItemBean.file != null) {
                    copyErrorsOnly(context, mFileItemBean.file);
                }
            });

            binding.copyModNameButton.setOnClickListener(v -> {
                if (mFileItemBean != null && mFileItemBean.file != null) {
                    copyModName(context, mFileItemBean.file);
                }
            });

            if (mOnItemClickListener != null) {
                itemView.setOnClickListener(v -> {
                    if (isMultiSelectMode) {
                        toggleSelection(mFileItemBean, binding.check);
                    } else {
                        mOnItemClickListener.onItemClick(mPosition, mFileItemBean);
                    }
                });
            }

            itemView.setOnLongClickListener(v -> {
                if (isMultiSelectMode) {
                    if (mOnMultiSelectListener != null)
                        mOnMultiSelectListener.onMultiSelect(getSelectedFiles());
                } else {
                    if (mOnItemLongClickListener != null)
                        mOnItemLongClickListener.onItemLongClick(mPosition, mFileItemBean);
                }
                return true;
            });
        }

        public void setData(FileItemBean fileItemBean, int position) {
            mPosition = position;
            mFileItemBean = fileItemBean;
            File file = fileItemBean.file;

            binding.name.setText(fileItemBean.name);

            // copy buttons: only for log / text files
            int copyVisibility = isCopyableTextFile(file) ? View.VISIBLE : View.GONE;
            binding.copyButton.setVisibility(copyVisibility);
            binding.copyErrorsButton.setVisibility(copyVisibility);

            // mod name copy button: only for mod jar files
            boolean isModFile = file != null && file.isFile() && ModIconStore.isModJar(file);
            binding.copyModNameButton.setVisibility(isModFile ? View.VISIBLE : View.GONE);

            updateModStatus(file);

            int infoLayoutVisible = View.GONE;
            if (fileItemBean.date != null) {
                String date = StringUtils.formatDate(fileItemBean.date, Locale.getDefault(), TimeZone.getDefault());
                binding.time.setText(date);
                binding.time.setVisibility(View.VISIBLE);
                infoLayoutVisible = View.VISIBLE;
            } else {
                binding.time.setVisibility(View.GONE);
            }

            if (fileItemBean.size != null) {
                String size = FileTools.formatFileSize(fileItemBean.size);
                binding.size.setText(size);
                binding.size.setVisibility(View.VISIBLE);
                infoLayoutVisible = View.VISIBLE;
            } else {
                binding.size.setVisibility(View.GONE);
            }

            binding.infoLayout.setVisibility(infoLayoutVisible);

            if (fileItemBean.isHighlighted) {
                binding.name.setTextColor(Color.rgb(69, 179, 162));
            } else {
                binding.name.setTextColor(
                        binding.name.getResources().getColor(
                                R.color.black_or_white,
                                binding.name.getContext().getTheme()
                        )
                );
            }

            if (fileItemBean.isCanCheck) {
                binding.check.setVisibility(isMultiSelectMode ? View.VISIBLE : View.GONE);
                binding.check.setChecked(selectedFiles.contains(fileItemBean));
            } else {
                binding.check.setVisibility(View.GONE);
            }

            // any icon request still running for a previous row must not touch this one
            boundKey = null;

            if (file != null && file.isFile() && ImageUtils.isImage(file)) {
                restoreImageStyle();
                Glide.with(context).load(file)
                        .override(binding.image.getWidth(), binding.image.getHeight())
                        .centerCrop()
                        .into(new DrawableImageViewTarget(binding.image));
            } else if (file != null && file.isFile() && ModIconStore.isModJar(file)) {
                bindModIcon(file, fileItemBean);
            } else if (PackIconStore.kindOf(file) != PackIconStore.KIND_NONE) {
                bindPackIcon(file, fileItemBean, PackIconStore.kindOf(file));
            } else {
                restoreImageStyle();
                Glide.with(context).clear(binding.image);
                binding.image.setImageDrawable(fileItemBean.image);
            }
        }

        private void updateModStatus(File file) {
            binding.modStatus.setVisibility(View.GONE);
            if (file == null) return;

            int kind = PackIconStore.kindOf(file);
            if (kind == PackIconStore.KIND_RESOURCEPACK || kind == PackIconStore.KIND_SHADER) {
                binding.modStatus.setImageResource(
                        file.isDirectory() ? R.drawable.ic_pack_folder : R.drawable.ic_pack_zip
                );
                binding.modStatus.setColorFilter(
                        context.getResources().getColor(R.color.black_or_white, context.getTheme())
                );
                binding.modStatus.setVisibility(View.VISIBLE);
                return;
            }

            if (!file.isFile()) return;

            String name = file.getName().toLowerCase(Locale.ROOT);
            boolean disabled = name.endsWith(".jar.disabled");
            boolean enabled = name.endsWith(".jar") && ModIconStore.isModJar(file);
            if (!enabled && !disabled) return;

            binding.modStatus.setImageResource(
                    enabled ? R.drawable.ic_mod_enabled : R.drawable.ic_mod_disabled
            );
            binding.modStatus.setColorFilter(
                    enabled
                            ? context.getResources().getColor(R.color.black_or_white, context.getTheme())
                            : Color.rgb(255, 82, 82)
            );
            binding.modStatus.setVisibility(View.VISIBLE);
        }

        private void restoreImageStyle() {
            binding.image.setScaleType(origScaleType);
            binding.image.setPadding(
                    origPadLeft,
                    origPadTop,
                    origPadRight,
                    origPadBottom
            );
            setImageSize(origWidth, origHeight);
        }

        private void setImageSize(int width, int height) {
            ViewGroup.LayoutParams params = binding.image.getLayoutParams();
            if (params != null && (params.width != width || params.height != height)) {
                params.width = width;
                params.height = height;
                binding.image.setLayoutParams(params);
            }
        }

        private int modIconPx() {
            return (int) (MOD_ICON_SIZE_DP
                    * context.getResources().getDisplayMetrics().density + 0.5f);
        }

        private void bindModIcon(File file, FileItemBean bean) {
            restoreImageStyle();
            setImageSize(modIconPx(), modIconPx());
            Glide.with(context).clear(binding.image);
            binding.image.setImageDrawable(bean.image);

            final String key = ModIconStore.keyFor(file);
            boundKey = key;

            byte[] cached = ICON_CACHE.get(key);
            if (cached != null) {
                showModIcon(cached);
                return;
            }

            final Context appCtx = context.getApplicationContext();
            ICON_EXECUTOR.execute(() -> {
                byte[] data = ModIconStore.readFromJar(file);
                if (data == null) {
                    data = ModIconStore.readCached(appCtx, file);
                }
                final byte[] result = data;
                ICON_CACHE.put(key, result == null ? NO_ICON : result);
                MAIN_HANDLER.post(() -> {
                    if (key.equals(boundKey)) showModIcon(result);
                });
            });
        }

        private void bindPackIcon(File file, FileItemBean bean, int kind) {
            restoreImageStyle();
            setImageSize(modIconPx(), modIconPx());
            Glide.with(context).clear(binding.image);
            binding.image.setImageDrawable(bean.image);

            final String key = "pack:" + PackIconStore.keyFor(file);
            boundKey = key;

            byte[] cached = ICON_CACHE.get(key);
            if (cached != null) {
                showModIcon(cached);
                return;
            }

            final Context appCtx = context.getApplicationContext();
            ICON_EXECUTOR.execute(() -> {
                byte[] data = PackIconStore.readInside(file, kind);
                if (data == null) {
                    data = PackIconStore.readCached(appCtx, file);
                }
                final byte[] result = data;
                ICON_CACHE.put(key, result == null ? NO_ICON : result);
                MAIN_HANDLER.post(() -> {
                    if (key.equals(boundKey)) showModIcon(result);
                });
            });
        }

        private void showModIcon(byte[] data) {
            if (data == null || data.length == 0) return;

            binding.image.setPadding(0, 0, 0, 0);
            binding.image.setScaleType(ImageView.ScaleType.CENTER_CROP);

            setImageSize(modIconPx(), modIconPx());
            int px = modIconPx();
            Glide.with(context).load(data)
                    .override(px, px)
                    .centerCrop()
                    .into(new DrawableImageViewTarget(binding.image));
        }
    }

    /**
     * Copies the full file name of a mod, including its extension.
     */
    private static void copyModName(Context context, File file) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

            clipboard.setPrimaryClip(
                    ClipData.newPlainText("Mod name", file.getName())
            );

            Toast.makeText(
                    context,
                    "تم نسخ اسم المود",
                    Toast.LENGTH_SHORT
            ).show();
        } catch (Throwable t) {
            Toast.makeText(
                    context,
                    "فشل نسخ اسم المود",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }
        }
