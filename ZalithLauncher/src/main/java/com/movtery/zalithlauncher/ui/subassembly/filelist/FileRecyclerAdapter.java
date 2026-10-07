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

    @SuppressLint("NotifyDataSetChanged")
    private void fetchIcons(Context context, boolean force) {
        List<File> files = new ArrayList<>();
        for (FileItemBean bean : mData) {
            File f = bean.file;
            if (f != null && f.isFile() && ModIconStore.isModJar(f)) files.add(f);
        }
        if (files.isEmpty()) return;

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

    // ===== Copy file content =====

    /** log / text files that get a copy button (.txt, .log, .log.gz) */
    private static boolean isCopyableTextFile(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".log.gz");
    }

    /** reads the file (decompressing .gz) in the background, then copies the text to the clipboard */
    private static void copyFileContent(Context context, File file) {
        final Context appCtx = context.getApplicationContext();
        COPY_EXECUTOR.execute(() -> {
            String text = null;
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
                text = new String(all, StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }

            final String result = text;
            final boolean cut = truncated;
            MAIN_HANDLER.post(() -> {
                if (result == null) {
                    Toast.makeText(appCtx, "فشل نسخ الملف", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (result.isEmpty()) {
                    Toast.makeText(appCtx, "الملف فارغ", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    ClipboardManager clipboard = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("log", result));
                    Toast.makeText(appCtx,
                            cut ? "تم نسخ آخر 500 كيلوبايت فقط (الملف كبير)" : "تم نسخ محتوى الملف",
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(appCtx, "فشل نسخ الملف", Toast.LENGTH_SHORT).show();
                }
            });
        });
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
            selectedFiles.clear(); // 退出多选模式时重置选择的文件
        }
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void selectAllFiles(boolean selectAll) {
        selectedFiles.clear();
        if (selectAll) { //全选时遍历全部item设置选择状态
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

            // copy button: only for log / text files
            binding.copyButton.setVisibility(isCopyableTextFile(file) ? View.VISIBLE : View.GONE);

            int infoLayoutVisible = View.GONE;
            if (fileItemBean.date != null) {
                String date = StringUtils.formatDate(fileItemBean.date, Locale.getDefault(), TimeZone.getDefault());
                binding.time.setText(date);
                binding.time.setVisibility(View.VISIBLE);
                infoLayoutVisible = View.VISIBLE;
            } else binding.time.setVisibility(View.GONE);

            if (fileItemBean.size != null) {
                String size = FileTools.formatFileSize(fileItemBean.size);
                binding.size.setText(size);
                binding.size.setVisibility(View.VISIBLE);
                infoLayoutVisible = View.VISIBLE;
            } else binding.size.setVisibility(View.GONE);

            binding.infoLayout.setVisibility(infoLayoutVisible);

            if (fileItemBean.isHighlighted) {
                binding.name.setTextColor(Color.rgb(69, 179, 162)); //设置高亮
            } else {
                binding.name.setTextColor(binding.name.getResources().getColor(R.color.black_or_white, binding.name.getContext().getTheme()));
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
            } else {
                restoreImageStyle();
                Glide.with(context).clear(binding.image);
                binding.image.setImageDrawable(fileItemBean.image);
            }
        }

        private void restoreImageStyle() {
            binding.image.setScaleType(origScaleType);
            binding.image.setPadding(origPadLeft, origPadTop, origPadRight, origPadBottom);
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
            return (int) (MOD_ICON_SIZE_DP * context.getResources().getDisplayMetrics().density + 0.5f);
        }

        private void bindModIcon(File file, FileItemBean bean) {
            // show the default icon first, replace it if the mod has its own logo
            restoreImageStyle();
            setImageSize(modIconPx(), modIconPx()); // same size for every mod row, so the list does not jump
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
                byte[] data = ModIconStore.readFromJar(file);          // 1) inside the jar
                if (data == null) data = ModIconStore.readCached(appCtx, file); // 2) saved from the internet
                final byte[] result = data;
                ICON_CACHE.put(key, result == null ? NO_ICON : result);
                MAIN_HANDLER.post(() -> {
                    if (key.equals(boundKey)) showModIcon(result);
                });
            });
        }

        private void showModIcon(byte[] data) {
            if (data == null || data.length == 0) return; // keep the default icon

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
}
