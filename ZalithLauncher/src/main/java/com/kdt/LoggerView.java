package com.kdt;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.movtery.anim.animations.Animations;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.databinding.ViewLoggerBinding;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.utils.anim.ViewAnimUtils;

import net.kdt.pojavlaunch.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A class able to display logs to the user.
 * It has support for the Logger class
 */
public class LoggerView extends ConstraintLayout {
    private Logger.eventLogListener mLogListener;
    private ViewLoggerBinding binding;
    private boolean isShowing = false;

    private static final int TYPE_NORMAL = 0;
    private static final int TYPE_ERROR = 1;
    private static final int TYPE_WARN = 2;
    private static final int TYPE_CHAT = 3;

    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_ERROR = 0xFFFF5555;
    private static final int COLOR_WARN = 0xFFFFA500;
    private static final int COLOR_CHAT = 0xFFFFFF55;

    private static final int MAX_LINES = 5000;
    private static final int TRIM_LINES = 1000;
    private static final int MAX_COPY_CHARS = 500_000;

    private static class Entry {
        final String text;
        final int type;
        final boolean head; // true = start of a new error (not a stack-trace continuation)

        Entry(String text, int type, boolean head) {
            this.text = text;
            this.type = type;
            this.head = head;
        }
    }

    private final List<Entry> mEntries = new ArrayList<>();
    private boolean mProblemsOnly = false;
    private int mLastType = TYPE_NORMAL;
    private boolean mLastContinuation = false;

    public LoggerView(@NonNull Context context) {
        this(context, null);
    }

    public LoggerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // Triggers the log view shown state by default when viewing it
        binding.toggleLog.setChecked(visibility == VISIBLE);
    }

    public void toggleViewWithAnim() {
        setVisibilityWithAnim(!isShowing);
    }

    public void setVisibilityWithAnim(boolean visibility) {
        if (isShowing == visibility) return;
        isShowing = visibility;

        ViewAnimUtils.setViewAnim(this,
                visibility ? Animations.BounceInUp : Animations.SlideOutDown,
                (long) (AllSettings.getAnimationSpeed().getValue() * 0.7),
                () -> setVisibility(VISIBLE),
                () -> setVisibility(visibility ? VISIBLE : GONE));
    }

    /**
     * 强制展示日志，如果点击关闭按钮，那么将进行回调
     */
    public void forceShow(OnCloseClickListener listener) {
        setVisibilityWithAnim(true);
        binding.cancel.setOnClickListener(v -> listener.onClick());
    }

    /**
     * Inflate the layout, and add component behaviors
     */
    private void init() {
        binding = ViewLoggerBinding.inflate(LayoutInflater.from(getContext()), this, true);

        binding.logView.setTypeface(Typeface.MONOSPACE);
        //TODO clamp the max text so it doesn't go oob
        binding.logView.setMaxLines(Integer.MAX_VALUE);
        binding.logView.setEllipsize(null);
        binding.logView.setVisibility(GONE);

        // Toggle log visibility
        binding.toggleLog.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    binding.logView.setVisibility(isChecked ? VISIBLE : GONE);
                    if (isChecked) {
                        Logger.setLogListener(mLogListener);
                    } else {
                        binding.logView.setText("");
                        mEntries.clear();
                        mLastType = TYPE_NORMAL;
                        mLastContinuation = false;
                        Logger.setLogListener(null); // Makes the JNI code be able to skip expensive logger callbacks
                        // NOTE: was tested by rapidly smashing the log on/off button, no sync issues found :)
                    }
                });
        binding.toggleLog.setChecked(false);

        // Remove the loggerView from the user View
        binding.cancel.setOnClickListener(view -> setVisibilityWithAnim(false));

        // Set the scroll view
        binding.scroll.setKeepFocusing(true);

        //Set up the autoscroll switch
        binding.toggleAutoscroll.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    if (isChecked) binding.scroll.fullScroll(View.FOCUS_DOWN);
                    binding.scroll.setKeepFocusing(isChecked);
                }
        );
        binding.toggleAutoscroll.setChecked(true);

        // Filter buttons
        binding.filterAll.setOnClickListener(v -> setProblemsOnly(false));
        binding.filterProblems.setOnClickListener(v -> setProblemsOnly(true));
        binding.filterAll.setChecked(true);
        binding.filterProblems.setChecked(false);
        updateFilterUi();
        binding.copyLogView.setOnClickListener(v -> copyCurrentView());

        // Listen to logs
        mLogListener = text -> {
            if (binding.logView.getVisibility() != VISIBLE) return;
            post(() -> {
                boolean needRebuild = false;
                for (String line : text.split("\\r?\\n")) {
                    int type = classify(line);
                    Entry entry = new Entry(line, type, type == TYPE_ERROR && !mLastContinuation);
                    mEntries.add(entry);

                    if (mEntries.size() > MAX_LINES) {
                        mEntries.subList(0, TRIM_LINES).clear();
                        needRebuild = true;
                    } else if (!needRebuild && (!mProblemsOnly || isProblem(entry))) {
                        binding.logView.append(buildLine(entry));
                    }
                }
                if (needRebuild) rebuildText();

                if (binding.scroll.isKeepFocusing())
                    binding.scroll.fullScroll(View.FOCUS_DOWN);
            });
        };
    }

    private void setProblemsOnly(boolean problemsOnly) {
        binding.filterAll.setChecked(!problemsOnly);
        binding.filterProblems.setChecked(problemsOnly);
        if (mProblemsOnly == problemsOnly) return;
        mProblemsOnly = problemsOnly;
        updateFilterUi();
        rebuildText();
        if (binding.scroll.isKeepFocusing())
            binding.scroll.post(() -> binding.scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateFilterUi() {
        binding.filterAllDot.setBackgroundResource(
                mProblemsOnly ? R.drawable.log_filter_dot_off : R.drawable.log_filter_dot_on);
        binding.filterProblemsDot.setBackgroundResource(
                mProblemsOnly ? R.drawable.log_filter_dot_on : R.drawable.log_filter_dot_off);
        binding.copyLogView.setText(mProblemsOnly ? "نسخ الأخطاء" : "نسخ الكل");
    }

    /**
     * Copies exactly what the current filter shows (all lines, or errors/warnings only).
     */
    private void copyCurrentView() {
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        int lines = 0;
        int errors = 0;
        int warnings = 0;

        for (int i = mEntries.size() - 1; i >= 0; i--) {
            Entry entry = mEntries.get(i);
            if (mProblemsOnly && !isProblem(entry)) continue;
            if (sb.length() + entry.text.length() + 1 > MAX_COPY_CHARS) {
                truncated = true;
                break;
            }
            sb.insert(0, entry.text + '\n');
            if (!entry.text.trim().isEmpty()) lines++;
            if (entry.type == TYPE_ERROR && entry.head) errors++;
            if (entry.type == TYPE_WARN) warnings++;
        }

        Context context = getContext();
        if (sb.length() == 0 || (mProblemsOnly && errors == 0 && warnings == 0)) {
            Toast.makeText(context, "لا يوجد ما يُنسخ", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Log", sb.toString()));

            String message;
            if (mProblemsOnly) {
                message = "تم نسخ ";
                if (errors > 0) message += errors + " خطأ";
                if (errors > 0 && warnings > 0) message += " و";
                if (warnings > 0) message += warnings + " تحذير";
            } else {
                message = "تم نسخ " + lines + " سطر";
            }
            if (truncated) message += " (آخر الأسطر فقط، السجل كبير)";
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(context, "فشل النسخ", Toast.LENGTH_SHORT).show();
        }
    }

    private void rebuildText() {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (Entry entry : mEntries) {
            if (!mProblemsOnly || isProblem(entry)) builder.append(buildLine(entry));
        }
        binding.logView.setText(builder);
    }

    private boolean isProblem(Entry entry) {
        return entry.type == TYPE_ERROR || entry.type == TYPE_WARN;
    }

    private CharSequence buildLine(Entry entry) {
        SpannableStringBuilder line = new SpannableStringBuilder(entry.text).append('\n');
        int color;
        switch (entry.type) {
            case TYPE_ERROR: color = COLOR_ERROR; break;
            case TYPE_WARN: color = COLOR_WARN; break;
            case TYPE_CHAT: color = COLOR_CHAT; break;
            default: color = COLOR_NORMAL; break;
        }
        line.setSpan(new ForegroundColorSpan(color), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return line;
    }

    /**
     * Decides the line type. Stack-trace lines that follow an error stay as errors.
     */
    private int classify(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        String trimmed = text.trim();
        int type;
        boolean continuation = false;

        if (mLastType == TYPE_ERROR
                && (trimmed.isEmpty() || trimmed.startsWith("at ") || trimmed.startsWith("...")
                || trimmed.startsWith("Suppressed:") || trimmed.startsWith("Caused by"))) {
            // Part of the same error (stack trace); a blank line inside it keeps the error alive
            type = TYPE_ERROR;
            continuation = true;
        } else if (upper.contains("/ERROR]") || upper.contains("/FATAL]")
                || upper.contains("EXCEPTION") || upper.contains("MALFORMEDJSON")
                || upper.contains("FATAL")) {
            type = TYPE_ERROR;
        } else if (upper.contains("/WARN]") || upper.contains("[WARN]")) {
            type = TYPE_WARN;
        } else if (text.contains("[CHAT]") || text.contains("issued server command")
                || text.contains("[Not Secure]")
                || text.contains("Server thread/INFO]: <")) {
            type = TYPE_CHAT;
        } else {
            type = TYPE_NORMAL;
        }

        mLastType = type;
        mLastContinuation = continuation;
        return type;
    }

    public ViewLoggerBinding getBinding() {
        return binding;
    }

    public interface OnCloseClickListener {
        void onClick();
    }
}
