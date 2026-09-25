package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowInsetsCompat;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.view.ViewUtils;


/**
 * The {@link TermuxActivity} relies on {@link android.view.WindowManager.LayoutParams#SOFT_INPUT_ADJUST_RESIZE)}
 * set by {@link TermuxTerminalViewClient#setSoftKeyboardState(boolean, boolean)} to automatically
 * resize the view and push the terminal up when soft keyboard is opened. However, this does not
 * always work properly. When `enforce-char-based-input=true` is set in `termux.properties`
 * and {@link com.termux.view.TerminalView#onCreateInputConnection(EditorInfo)} sets the inputType
 * to `InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS`
 * instead of the default `InputType.TYPE_NULL` for termux, some keyboards may still show suggestions.
 * Gboard does too, but only when text is copied and clipboard suggestions **and** number keys row
 * toggles are enabled in its settings. When number keys row toggle is not enabled, Gboard will still
 * show the row but will switch it with suggestions if needed. If its enabled, then number keys row
 * is always shown and suggestions are shown in an additional row on top of it. This additional row is likely
 * part of the candidates view returned by the keyboard app in {@link InputMethodService#onCreateCandidatesView()}.
 *
 * With the above configuration, the additional clipboard suggestions row partially covers the
 * extra keys/terminal. Reopening the keyboard/activity does not fix the issue. This is either a bug
 * in the Android OS where it does not consider the candidate's view height in its calculation to push
 * up the view or because Gboard does not include the candidate's view height in the height reported
 * to android that should be used, hence causing an overlap.
 *
 * Gboard logs the following entry to `logcat` when its opened with or without the suggestions bar showing:
 * I/KeyboardViewUtil: KeyboardViewUtil.calculateMaxKeyboardBodyHeight():62 leave 500 height for app when screen height:2392, header height:176 and isFullscreenMode:false, so the max keyboard body height is:1716
 * where `keyboard_height = screen_height - height_for_app - header_height` (62 is a hardcoded value in Gboard source code and may be a version number)
 * So this may in fact be due to Gboard but https://stackoverflow.com/questions/57567272 suggests
 * otherwise. Another similar report https://stackoverflow.com/questions/66761661.
 * Also check https://github.com/termux/termux-app/issues/1539.
 *
 * This overlap may happen even without `enforce-char-based-input=true` for keyboards with extended layouts
 * like number row, etc.
 *
 * To fix these issues, `activity_termux.xml` has the constant 1sp transparent
 * `activity_termux_bottom_space_view` View at the bottom. This will appear as a line matching the
 * activity theme. When {@link TermuxActivity} {@link ViewTreeObserver.OnGlobalLayoutListener} is
 * called when any of the sub view layouts change,  like keyboard opening/closing keyboard,
 * extra keys/input view switched, etc, we check how much of the bottom space view extends below
 * the window's visible bottom and set the root view's bottom margin to exactly that amount, so
 * that the keyboard does not overlap the extra keys/terminal.
 *
 * The margin is recomputed with {@link RootViewBottomMarginPolicy} on every relevant trigger:
 * any global layout change, and whenever the window insets change (via
 * {@link WindowInsetsListener}), so the first keyboard show after a cold start converges to the
 * correct margin even when no further layout pass occurs. The policy is a stateless pure
 * function; re-evaluation is idempotent at the fixed point, so no memoization, prefetch or
 * wall-clock throttling is needed to prevent oscillation.
 */
public class TermuxActivityRootView extends LinearLayout implements ViewTreeObserver.OnGlobalLayoutListener {

    public TermuxActivity mActivity;

    /** Log root view events. */
    private boolean ROOT_VIEW_LOGGING_ENABLED = false;

    private static final String LOG_TAG = "TermuxActivityRootView";

    private static int mStatusBarHeight;

    /** Dedup flag for the inset-triggered re-evaluation runnable. */
    private boolean mRecomputePosted;

    public TermuxActivityRootView(Context context) {
        super(context);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setActivity(TermuxActivity activity) {
        mActivity = activity;
    }

    /**
     * Sets whether root view logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    public void setIsRootViewLoggingEnabled(boolean value) {
        ROOT_VIEW_LOGGING_ENABLED = value;
    }

    @Override
    public void onGlobalLayout() {
        updateBottomMargin();
    }

    /**
     * Recompute the bottom margin that keeps the terminal above the keyboard
     * and apply it if it changed. Safe to call on any trigger (layout, inset
     * change); converges in one evaluation and is idempotent at the fixed
     * point, so repeated triggers cannot induce oscillation.
     */
    public void updateBottomMargin() {
        if (mActivity == null || !mActivity.isVisible()) return;

        View bottomSpaceView = mActivity.getTermuxActivityBottomSpaceView();
        if (bottomSpaceView == null) return;

        boolean root_view_logging_enabled = ROOT_VIEW_LOGGING_ENABLED;

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();

        // Get the position Rects of the bottom space view and the main window holding it
        Rect[] windowAndViewRects = ViewUtils.getWindowAndViewRects(bottomSpaceView, mStatusBarHeight);
        if (windowAndViewRects == null)
            return;

        Rect windowAvailableRect = windowAndViewRects[0];
        Rect bottomSpaceViewRect = windowAndViewRects[1];

        int targetMargin = RootViewBottomMarginPolicy.targetMarginBottom(
            bottomSpaceViewRect.bottom, windowAvailableRect.bottom, params.bottomMargin);

        if (root_view_logging_enabled) {
            Logger.logVerbose(LOG_TAG, "updateBottomMargin: windowAvailableRect " +
                ViewUtils.toRectString(windowAvailableRect) + ", bottomSpaceViewRect " +
                ViewUtils.toRectString(bottomSpaceViewRect) + ", currentMargin " + params.bottomMargin +
                ", targetMargin " + targetMargin);
        }

        if (params.bottomMargin != targetMargin) {
            if (root_view_logging_enabled)
                Logger.logVerbose(LOG_TAG, "Setting bottom margin to " + targetMargin);
            params.setMargins(0, 0, 0, targetMargin);
            setLayoutParams(params);
        } else {
            if (root_view_logging_enabled)
                Logger.logVerbose(LOG_TAG, "Bottom margin already equals " + targetMargin);
        }
    }

    /**
     * Captures the status bar height and schedules a bottom-margin
     * re-evaluation whenever the window insets change. The re-evaluation is
     * posted rather than run inline because this callback fires mid-traversal.
     * This trigger is what guarantees convergence on the first keyboard show
     * after a cold start: even if the IME inset change causes no app relayout
     * (e.g. the framework does not resize this window), the margin is still
     * recomputed against the updated visible frame.
     */
    public static class WindowInsetsListener implements View.OnApplyWindowInsetsListener {

        private final TermuxActivityRootView mRootView;

        public WindowInsetsListener(@NonNull TermuxActivityRootView rootView) {
            mRootView = rootView;
        }

        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            mStatusBarHeight =  WindowInsetsCompat.toWindowInsetsCompat(insets).getInsets(WindowInsetsCompat.Type.statusBars()).top;
            mRootView.scheduleBottomMarginRecompute();
            // Let view window handle insets however it wants
            return v.onApplyWindowInsets(insets);
        }
    }

    private void scheduleBottomMarginRecompute() {
        if (mRecomputePosted) return;
        mRecomputePosted = true;
        post(() -> {
            mRecomputePosted = false;
            updateBottomMargin();
        });
    }
}
