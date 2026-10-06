package com.movtery.zalithlauncher.ui.subassembly.filelist;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.DrawableImageViewTarget;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.databinding.ItemFileListViewBinding;
import com.movtery.zalithlauncher.utils.file.FileTools;
import com.movtery.zalithlauncher.utils.image.ImageUtils;
import com.movtery.zalithlauncher.utils.stringutils.StringUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    private static final LruCache<String, byte[]> ICON_CACHE = new LruCache<String, byte[]>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return Math.max(1, value.length);
        }
    };

    private static boolean isModJar(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".jar.disabled");
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

            binding.check.setOnClickListener(v -> {
                if (isMultiSelectMode) {
                    toggleSelection(mFileItemBean, binding.check);
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
            } else if (file != null && file.isFile() && isModJar(file)) {
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
        }

        private void bindModIcon(File file, FileItemBean bean) {
            // show the default icon first, replace it if the jar has its own logo
            restoreImageStyle();
            Glide.with(context).clear(binding.image);
            binding.image.setImageDrawable(bean.image);

            final String key = file.getAbsolutePath() + "|" + file.lastModified() + "|" + file.length();
            boundKey = key;

            byte[] cached = ICON_CACHE.get(key);
            if (cached != null) {
                showModIcon(cached);
                return;
            }

            ICON_EXECUTOR.execute(() -> {
                byte[] data = ModIconReader.read(file);
                ICON_CACHE.put(key, data == null ? NO_ICON : data);
                MAIN_HANDLER.post(() -> {
                    if (key.equals(boundKey)) showModIcon(data);
                });
            });
        }

        private void showModIcon(byte[] data) {
            if (data == null || data.length == 0) return; // keep the default icon

            binding.image.setPadding(0, 0, 0, 0);
            binding.image.setScaleType(ImageView.ScaleType.CENTER_CROP);

            int px = binding.image.getWidth();
            if (px <= 0) {
                px = (int) (48 * context.getResources().getDisplayMetrics().density);
            }
            Glide.with(context).load(data)
                    .override(px, px)
                    .centerCrop()
                    .into(new DrawableImageViewTarget(binding.image));
        }
    }

    /**
     * Reads the logo of a mod from inside its jar file.
     * Supports Forge / NeoForge (mods.toml), Fabric, Quilt and old Forge (mcmod.info),
     * and falls back to common file names like pack.png.
     */
    private static final class ModIconReader {
        private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;
        private static final Pattern MCMOD_LOGO = Pattern.compile("\"logoFile\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern TOML_LOGO = Pattern.compile("^logoFile\\s*=\\s*[\"']([^\"']+)[\"']");

        static byte[] read(File file) {
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
                        bestSize = size;
                        best = obj.optString(k, null);
                    }
                }
                return best;
            }
            return null;
        }

        private static String readText(ZipFile zip, String name) {
            byte[] bytes = readEntry(zip, name);
            return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        private static byte[] readEntry(ZipFile zip, String name) {
            try {
                String clean = name.replace("\\", "/");
                while (clean.startsWith("/") || clean.startsWith("./")) {
                    clean = clean.startsWith("/") ? clean.substring(1) : clean.substring(2);
                }
                ZipEntry entry = zip.getEntry(clean);
                if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ICON_BYTES) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        if (out.size() > MAX_ICON_BYTES) return null;
                    }
                    return out.toByteArray();
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
    }
}
