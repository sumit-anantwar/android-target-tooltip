package it.sephiroth.android.library.tooltip;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.util.Log.DEBUG;
import static android.util.Log.ERROR;
import static android.util.Log.INFO;
import static android.util.Log.VERBOSE;
import static android.util.Log.WARN;
import static it.sephiroth.android.library.tooltip.TooltipManager.ClosePolicy;
import static it.sephiroth.android.library.tooltip.TooltipManager.DBG;
import static it.sephiroth.android.library.tooltip.TooltipManager.Gravity;
import static it.sephiroth.android.library.tooltip.TooltipManager.Gravity.BOTTOM;
import static it.sephiroth.android.library.tooltip.TooltipManager.Gravity.CENTER;
import static it.sephiroth.android.library.tooltip.TooltipManager.Gravity.LEFT;
import static it.sephiroth.android.library.tooltip.TooltipManager.Gravity.RIGHT;
import static it.sephiroth.android.library.tooltip.TooltipManager.Gravity.TOP;
import static it.sephiroth.android.library.tooltip.TooltipManager.log;

class TooltipView extends ViewGroup implements Tooltip {
    private static final String TAG = "TooltipView";
    private static final List<Gravity> gravities = new ArrayList<>(Arrays.asList(LEFT, RIGHT, TOP, BOTTOM, CENTER));
    private final List<Gravity> viewGravities = new ArrayList<>(gravities);
    private final long mShowDelay;
    private final int mTextAppearance;
    private final int mToolTipId;
    private final Rect mDrawRect;
    private final Rect mTempRect;
    private final long mShowDuration;
    private final ClosePolicy mClosePolicy;
    private final Point mPoint;
    private final int mTextResId;
    private final int mTopRule;
    private final int mMaxWidth;
    private final boolean mHideArrow;
    private final long mActivateDelay;
    private final boolean mRestrict;
    private final long mFadeDuration;
    private final TooltipManager.onTooltipClosingCallback mCloseCallback;
    private final TooltipTextDrawable mDrawable;
    private final int[] mTempLocation = new int[2];
    private final Handler mHandler = new Handler();
    private final Rect mScreenRect = new Rect();
    private final Point mTmpPoint = new Point();
    private Gravity mGravity;
    private Animator mShowAnimation;
    private boolean mShowing;
    private WeakReference<View> mViewAnchor;
    private boolean mAttached;
    private boolean mInitialized;
    private boolean mActivated;
    private int mPadding;
    private CharSequence mText;
    private Rect mViewRect;
    private View mView;
    private TextView mTextView;
    private OnToolTipListener mTooltipListener;
    Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            onClose(false, false, false);
        }
    };
    Runnable activateRunnable = new Runnable() {
        @Override
        public void run() {
            log(TAG, VERBOSE, "[%d] activated..", mToolTipId);
            mActivated = true;
        }
    };
    private final ViewTreeObserver.OnPreDrawListener mPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (!mAttached) {
                log(TAG, WARN, "[%d] onPreDraw. not attached", mToolTipId);
                removePreDrawObserver(null);
                return true;
            }

            if (null != mViewAnchor && mAttached) {
                View view = mViewAnchor.get();
                if (null != view) {
                    if (view.isDirty()) {
                        return true;
                    }

                    if (DBG) {
                        Rect drawRect = new Rect();
                        view.getDrawingRect(drawRect);
                        log(TAG, DEBUG, "[%d] onPreDraw: global: %s, draw: %s", mToolTipId, mViewRect, drawRect);
                    }

                    view.getLocationOnScreen(mTempLocation);
                    int left = mTempLocation[0] - mViewRect.left;
                    int top = mTempLocation[1] - mViewRect.top;

                    mViewRect.offset(left, top);
                    mDrawRect.offset(left, top);

                    mView.setTranslationX(mDrawRect.left);
                    mView.setTranslationY(mDrawRect.top);
                }
            }
            return true;
        }
    };
    private final ViewTreeObserver.OnGlobalLayoutListener mGlobalLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            if (!mAttached) {
                log(TAG, WARN, "[%d] onGlobalLayout. removeListeners", mToolTipId);
                removeGlobalLayoutObserver(null);
                return;
            }

            if (null != mViewAnchor) {
                View view = mViewAnchor.get();

                if (null != view) {
                    Rect globalRect = new Rect();
                    Rect localRect = new Rect();
                    view.getGlobalVisibleRect(globalRect);
                    view.getLocalVisibleRect(localRect);

                    if (DBG) {
                        log(TAG, INFO, "[%d] onGlobalLayout: %s >> %s", mToolTipId, mViewRect, globalRect);
                    }

                    if (!Rect.intersects(mScreenRect, localRect)) {
                        if (DBG) {
                            log(TAG, WARN, "[%d] invalid rect", mToolTipId);
                        }
                        return;
                    }

                    if (!mViewRect.equals(globalRect)) {
                        mViewRect.set(globalRect);
                        calculatePositions(false);
                        requestLayout();
                    }

                    //  removeGlobalLayoutObserver(view);
                } else {
                    if (DBG) {
                        log(TAG, WARN, "[%d] view is null", mToolTipId);
                    }
                }
            }
        }
    };
    private final View.OnAttachStateChangeListener mAttachedStateListener = new OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(final View v) {
            // setVisibility(VISIBLE);
        }

        @Override
        @TargetApi (17)
        public void onViewDetachedFromWindow(final View v) {
            log(TAG, INFO, "[%d] onViewDetachedFromWindow", mToolTipId);
            removeViewListeners(v);

            if (!mAttached) {
                log(TAG, WARN, "[%d] not attached", mToolTipId);
                return;
            }

            Activity activity = (Activity) getContext();
            if (null != activity) {
                if (activity.isFinishing()) {
                    log(TAG, WARN, "[%d] skipped because activity is finishing...", mToolTipId);
                    return;
                }
                if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) {
                    return;
                }
                onClose(false, false, true);
            }
        }
    };

    public TooltipView(Context context, final TooltipManager manager, final TooltipManager.Builder builder) {
        super(context);

        TypedArray theme =
            context.getTheme().obtainStyledAttributes(null, R.styleable.TooltipLayout, builder.defStyleAttr, builder.defStyleRes);
        this.mPadding = theme.getDimensionPixelSize(R.styleable.TooltipLayout_ttlm_padding, 30);
        this.mTextAppearance = theme.getResourceId(R.styleable.TooltipLayout_android_textAppearance, 0);
        theme.recycle();

        this.mToolTipId = builder.id;
        this.mText = builder.text;
        this.mGravity = builder.gravity;
        this.mTextResId = builder.textResId;
        this.mMaxWidth = builder.maxWidth;
        this.mTopRule = builder.actionbarSize;
        this.mClosePolicy = builder.closePolicy;
        this.mShowDuration = builder.showDuration;
        this.mShowDelay = builder.showDelay;
        this.mHideArrow = builder.hideArrow;
        this.mActivateDelay = builder.activateDelay;
        this.mRestrict = builder.restrictToScreenEdges;
        this.mFadeDuration = builder.fadeDuration;
        this.mCloseCallback = builder.closeCallback;

        if (null != builder.point) {
            this.mPoint = new Point(builder.point);
            this.mPoint.y += mTopRule;
        } else {
            this.mPoint = null;
        }

        this.mDrawRect = new Rect();
        this.mTempRect = new Rect();

        if (null != builder.view) {
            mViewRect = new Rect();
            builder.view.getGlobalVisibleRect(mViewRect);
            mViewAnchor = new WeakReference<>(builder.view);

            if (builder.view.getViewTreeObserver().isAlive()) {
                builder.view.getViewTreeObserver().addOnGlobalLayoutListener(mGlobalLayoutListener);
                builder.view.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
                builder.view.addOnAttachStateChangeListener(mAttachedStateListener);
            }
        }

        if (!builder.isCustomView) {
            this.mDrawable = new TooltipTextDrawable(context, builder);
        } else {
            this.mDrawable = null;
        }
        setVisibility(INVISIBLE);
    }

    @Override
    public int getTooltipId() {
        return mToolTipId;
    }

    @SuppressWarnings ("unused")
    public boolean isShowing() {
        return mShowing;
    }

    void removeFromParent() {
        log(TAG, INFO, "[%d] removeFromParent", mToolTipId);
        ViewParent parent = getParent();
        removeCallbacks();

        if (null != parent) {
            ((ViewGroup) parent).removeView(TooltipView.this);

            if (null != mShowAnimation && mShowAnimation.isStarted()) {
                mShowAnimation.cancel();
            }
        }
    }

    private void removeCallbacks() {
        mHandler.removeCallbacks(hideRunnable);
        mHandler.removeCallbacks(activateRunnable);
    }

    @Override
    protected void onAttachedToWindow() {
        log(TAG, INFO, "[%d] onAttachedToWindow", mToolTipId);
        super.onAttachedToWindow();
        mAttached = true;

        //        final Activity act = TooltipManager.getActivity(getContext());
        //        if (act != null) {
        //            Window window = act.getWindow();
        //            window.getDecorView().getWindowVisibleDisplayFrame(mScreenRect);
        //        } else {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        android.view.Display display = wm.getDefaultDisplay();
        display.getRectSize(mScreenRect);
        //        }

        initializeView();
        // show();
    }

    @Override
    protected void onDetachedFromWindow() {
        log(TAG, INFO, "[%d] onDetachedFromWindow", mToolTipId);
        removeListeners();
        mAttached = false;
        mViewAnchor = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(final boolean changed, final int l, final int t, final int r, final int b) {
        log(TAG, INFO, "[%d] onLayout(%b, %d, %d, %d, %d)", mToolTipId, changed, l, t, r, b);

        //  The layout has actually already been performed and the positions
        //  cached.  Apply the cached values to the children.
        if (null != mView) {
            mView.layout(mView.getLeft(), mView.getTop(), mView.getMeasuredWidth(), mView.getMeasuredHeight());
        }

        if (changed) {
            updateViewRectAndPositions();
            // calculatePositions();
        }
    }

    private void removeListeners() {
        mTooltipListener = null;

        if (null != mViewAnchor) {
            View view = mViewAnchor.get();
            removeViewListeners(view);
        }
    }

    private void removeViewListeners(final View view) {
        log(TAG, INFO, "[%d] removeListeners", mToolTipId);
        removeGlobalLayoutObserver(view);
        removePreDrawObserver(view);
        removeOnAttachStateObserver(view);
    }

    private void removeGlobalLayoutObserver(@Nullable View view) {
        if (null == view && null != mViewAnchor) {
            view = mViewAnchor.get();
        }
        if (null != view && view.getViewTreeObserver().isAlive()) {
            if (Build.VERSION.SDK_INT >= 16) {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(mGlobalLayoutListener);
            } else {
                view.getViewTreeObserver().removeGlobalOnLayoutListener(mGlobalLayoutListener);
            }
        } else {
            log(TAG, ERROR, "[%d] removeGlobalLayoutObserver failed", mToolTipId);
        }
    }

    private void removePreDrawObserver(@Nullable View view) {
        if (null == view && null != mViewAnchor) {
            view = mViewAnchor.get();
        }
        if (null != view && view.getViewTreeObserver().isAlive()) {
            view.getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
        } else {
            log(TAG, ERROR, "[%d] removePreDrawObserver failed", mToolTipId);
        }
    }

    private void removeOnAttachStateObserver(@Nullable View view) {
        if (null == view && null != mViewAnchor) {
            view = mViewAnchor.get();
        }
        if (null != view) {
            view.removeOnAttachStateChangeListener(mAttachedStateListener);
        } else {
            log(TAG, ERROR, "[%d] removeOnAttachStateObserver failed", mToolTipId);
        }
    }

    private void initializeView() {
        if (!isAttached() || mInitialized) {
            return;
        }
        mInitialized = true;

        log(TAG, VERBOSE, "[%d] initializeView", mToolTipId);

        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        mView = LayoutInflater.from(getContext()).inflate(mTextResId, this, false);
        mView.setLayoutParams(params);

        if (null != mDrawable) {
            mView.setBackgroundDrawable(mDrawable);
            if (mHideArrow) {
                mView.setPadding(mPadding / 2, mPadding / 2, mPadding / 2, mPadding / 2);
            } else {
                mView.setPadding(mPadding, mPadding, mPadding, mPadding);
            }
        }

        mTextView = (TextView) mView.findViewById(android.R.id.text1);
        mTextView.setText(Html.fromHtml((String) this.mText));
        if (mMaxWidth > -1) {
            mTextView.setMaxWidth(mMaxWidth);
        }

        if (0 != mTextAppearance) {
            mTextView.setTextAppearance(getContext(), mTextAppearance);
        }

        this.addView(mView);
    }

    @Override
    public void show() {
        log(TAG, INFO, "[%d] show", mToolTipId);
        if (!isAttached()) {
            log(TAG, ERROR, "[%d] not attached!", mToolTipId);
            return;
        }
        fadeIn(mFadeDuration);
    }

    @Override
    public void hide(boolean remove) {
        hide(remove, mFadeDuration);
    }

    private void hide(boolean remove, long fadeDuration) {
        log(TAG, INFO, "[%d] hide(%b, %d)", mToolTipId, remove, fadeDuration);

        if (!isAttached()) {
            return;
        }
        fadeOut(remove, fadeDuration);
    }

    protected void fadeOut(final boolean remove, long fadeDuration) {
        if (!isAttached() || !mShowing) {
            return;
        }

        log(TAG, INFO, "[%d] fadeOut(%b, %d)", mToolTipId, remove, fadeDuration);

        if (null != mShowAnimation) {
            mShowAnimation.cancel();
        }

        mShowing = false;

        if (fadeDuration > 0) {
            float alpha = getAlpha();
            mShowAnimation = ObjectAnimator.ofFloat(this, "alpha", alpha, 0);
            mShowAnimation.setDuration(fadeDuration);
            mShowAnimation.addListener(
                new Animator.AnimatorListener() {
                    boolean cancelled;

                    @Override
                    public void onAnimationStart(final Animator animation) {
                        cancelled = false;
                    }

                    @Override
                    public void onAnimationEnd(final Animator animation) {
                        log(TAG, VERBOSE, "[%d] fadeout onAnimationEnd, cancelled: %b", mToolTipId, cancelled);
                        if (cancelled) {
                            return;
                        }

                        if (remove) {
                            fireOnHideCompleted();
                        }
                        mShowAnimation = null;
                    }

                    @Override
                    public void onAnimationCancel(final Animator animation) {
                        log(TAG, VERBOSE, "[%d] fadeout onAnimationCancel", mToolTipId);
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationRepeat(final Animator animation) {

                    }
                });
            mShowAnimation.start();
        } else {
            setVisibility(View.INVISIBLE);
            if (remove) {
                fireOnHideCompleted();
            }
        }
    }

    private void fireOnHideCompleted() {
        if (null != mTooltipListener) {
            mTooltipListener.onHideCompleted(TooltipView.this);
        }
    }

    @Override
    public void setOffsetX(int x) {
        mView.setTranslationX(x - mViewRect.left + mDrawRect.left);
    }

    @Override
    public void setOffsetY(int y) {
        mView.setTranslationY(y - mViewRect.top + mDrawRect.top);
    }

    @Override
    public void offsetTo(final int x, final int y) {
        mView.setTranslationX(x - mViewRect.left + mDrawRect.left);
        mView.setTranslationY(y - mViewRect.top + mDrawRect.top);
    }

    @Override
    public boolean isAttached() {
        return mAttached;
    }

    protected void fadeIn(final long fadeDuration) {
        if (mShowing) {
            return;
        }

        if (null != mShowAnimation) {
            mShowAnimation.cancel();
        }

        log(TAG, INFO, "[%d] fadeIn", mToolTipId);

        mShowing = true;

        if (fadeDuration > 0) {
            mShowAnimation = ObjectAnimator.ofFloat(this, "alpha", 0, 1);
            mShowAnimation.setDuration(fadeDuration);
            if (this.mShowDelay > 0) {
                mShowAnimation.setStartDelay(this.mShowDelay);
            }
            mShowAnimation.addListener(
                new Animator.AnimatorListener() {
                    boolean cancelled;

                    @Override
                    public void onAnimationStart(final Animator animation) {
                        log(TAG, VERBOSE, "[%d] fadein onAnimationStart", mToolTipId);
                        updateViewRectAndPositions();
                        setVisibility(View.VISIBLE);
                        cancelled = false;
                    }

                    @Override
                    public void onAnimationEnd(final Animator animation) {
                        log(TAG, VERBOSE, "[%d] fadein onAnimationEnd, cancelled: %b", mToolTipId, cancelled);

                        if (null != mTooltipListener && !cancelled) {
                            mTooltipListener.onShowCompleted(TooltipView.this);
                            postActivate(mActivateDelay);
                        }
                    }

                    @Override
                    public void onAnimationCancel(final Animator animation) {
                        log(TAG, VERBOSE, "[%d] fadein onAnimationCancel", mToolTipId);
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationRepeat(final Animator animation) {

                    }
                });
            mShowAnimation.start();
        } else {
            setVisibility(View.VISIBLE);
            mTooltipListener.onShowCompleted(TooltipView.this);
            if (!mActivated) {
                postActivate(mActivateDelay);
            }
        }

        if (mShowDuration > 0) {
            mHandler.removeCallbacks(hideRunnable);
            mHandler.postDelayed(hideRunnable, mShowDuration);
        }
    }

    void postActivate(long ms) {
        log(TAG, VERBOSE, "[%d] postActivate: %d", mToolTipId, ms);
        if (ms > 0) {
            if (isAttached()) {
                mHandler.postDelayed(activateRunnable, ms);
            }
        } else {
            mActivated = true;
        }
    }

    private void updateViewRectAndPositions() {
        log(TAG, INFO, "[%d] updateViewRectAndPositions", mToolTipId);
        if (mViewAnchor != null) {
            View view = mViewAnchor.get();
            if (null != view) {
                view.getGlobalVisibleRect(mViewRect);
                calculatePositions();
            }
        }
    }

    private void calculatePositions() {
        calculatePositions(mRestrict);
    }

    private void calculatePositions(boolean restrict) {
        viewGravities.clear();
        viewGravities.addAll(gravities);
        viewGravities.remove(mGravity);
        viewGravities.add(0, mGravity);
        calculatePositions(viewGravities, restrict);
    }

    private void calculatePositions(List<Gravity> gravities, final boolean checkEdges) {
        final long t1 = System.currentTimeMillis();
        if (!isAttached()) {
            return;
        }

        // failed to display the tooltip due to
        // something wrong with its dimensions or
        // the target position..
        if (gravities.size() < 1) {
            if (null != mTooltipListener) {
                mTooltipListener.onShowFailed(this);
            }
            setVisibility(View.GONE);
            return;
        }

        Gravity gravity = gravities.remove(0);

        if (DBG) {
            log(
                TAG, INFO, "[%s] calculatePositions. gravity: %s, gravities: %d, restrict: %b", mToolTipId, gravity,
                gravities.size(), checkEdges
            );
        }

        int statusbarHeight = mScreenRect.top;

        if (mViewRect == null) {
            mViewRect = new Rect();
            mViewRect.set(mPoint.x, mPoint.y + statusbarHeight, mPoint.x, mPoint.y + statusbarHeight);
        }

        final int screenTop = mScreenRect.top + mTopRule;

        int width = mView.getWidth();
        int height = mView.getHeight();

        if (DBG) {
            log(TAG, VERBOSE, "[%d] mView.size: %dx%d", mToolTipId, width, height);
        }

        // get the destination mPoint

        if (gravity == BOTTOM) {
            mDrawRect.set(
                mViewRect.centerX() - width / 2,
                mViewRect.bottom,
                mViewRect.centerX() + width / 2,
                mViewRect.bottom + height
            );

            if (checkEdges && !mScreenRect.contains(mDrawRect)) {
                if (mDrawRect.right > mScreenRect.right) {
                    mDrawRect.offset(mScreenRect.right - mDrawRect.right, 0);
                } else if (mDrawRect.left < mScreenRect.left) {
                    mDrawRect.offset(-mDrawRect.left, 0);
                }
                if (mDrawRect.bottom > mScreenRect.bottom) {
                    // this means there's no enough space!
                    calculatePositions(gravities, checkEdges);
                    return;
                } else if (mDrawRect.top < screenTop) {
                    mDrawRect.offset(0, screenTop - mDrawRect.top);
                }
            }
        } else if (gravity == TOP) {
            mDrawRect.set(
                mViewRect.centerX() - width / 2,
                mViewRect.top - height,
                mViewRect.centerX() + width / 2,
                mViewRect.top
            );

            if (checkEdges && !mScreenRect.contains(mDrawRect)) {
                if (mDrawRect.right > mScreenRect.right) {
                    mDrawRect.offset(mScreenRect.right - mDrawRect.right, 0);
                } else if (mDrawRect.left < mScreenRect.left) {
                    mDrawRect.offset(-mDrawRect.left, 0);
                }
                if (mDrawRect.top < screenTop) {
                    // this means there's no enough space!
                    calculatePositions(gravities, checkEdges);
                    return;
                } else if (mDrawRect.bottom > mScreenRect.bottom) {
                    mDrawRect.offset(0, mScreenRect.bottom - mDrawRect.bottom);
                }
            }
        } else if (gravity == RIGHT) {
            mDrawRect.set(
                mViewRect.right,
                mViewRect.centerY() - height / 2,
                mViewRect.right + width,
                mViewRect.centerY() + height / 2
            );

            if (checkEdges && !mScreenRect.contains(mDrawRect)) {
                if (mDrawRect.bottom > mScreenRect.bottom) {
                    mDrawRect.offset(0, mScreenRect.bottom - mDrawRect.bottom);
                } else if (mDrawRect.top < screenTop) {
                    mDrawRect.offset(0, screenTop - mDrawRect.top);
                }
                if (mDrawRect.right > mScreenRect.right) {
                    // this means there's no enough space!
                    calculatePositions(gravities, checkEdges);
                    return;
                } else if (mDrawRect.left < mScreenRect.left) {
                    mDrawRect.offset(mScreenRect.left - mDrawRect.left, 0);
                }
            }
        } else if (gravity == LEFT) {
            mDrawRect.set(
                mViewRect.left - width,
                mViewRect.centerY() - height / 2,
                mViewRect.left,
                mViewRect.centerY() + height / 2
            );

            if (checkEdges && !mScreenRect.contains(mDrawRect)) {
                if (mDrawRect.bottom > mScreenRect.bottom) {
                    mDrawRect.offset(0, mScreenRect.bottom - mDrawRect.bottom);
                } else if (mDrawRect.top < screenTop) {
                    mDrawRect.offset(0, screenTop - mDrawRect.top);
                }
                if (mDrawRect.left < mScreenRect.left) {
                    // this means there's no enough space!
                    this.mGravity = RIGHT;
                    calculatePositions(gravities, checkEdges);
                    return;
                } else if (mDrawRect.right > mScreenRect.right) {
                    mDrawRect.offset(mScreenRect.right - mDrawRect.right, 0);
                }
            }
        } else if (this.mGravity == CENTER) {
            mDrawRect.set(
                mViewRect.centerX() - width / 2,
                mViewRect.centerY() - height / 2,
                mViewRect.centerX() + width / 2,
                mViewRect.centerY() + height / 2
            );

            if (checkEdges && !mScreenRect.contains(mDrawRect)) {
                if (mDrawRect.bottom > mScreenRect.bottom) {
                    mDrawRect.offset(0, mScreenRect.bottom - mDrawRect.bottom);
                } else if (mDrawRect.top < screenTop) {
                    mDrawRect.offset(0, screenTop - mDrawRect.top);
                }
                if (mDrawRect.right > mScreenRect.right) {
                    mDrawRect.offset(mScreenRect.right - mDrawRect.right, 0);
                } else if (mDrawRect.left < mScreenRect.left) {
                    mDrawRect.offset(mScreenRect.left - mDrawRect.left, 0);
                }
            }
        }

        if (DBG) {
            log(TAG, VERBOSE, "[%d] mScreenRect: %s, mTopRule: %d, statusBar: %d", mToolTipId, mScreenRect, mTopRule,
                statusbarHeight
            );
            log(TAG, VERBOSE, "[%d] mDrawRect: %s", mToolTipId, mDrawRect);
            log(TAG, VERBOSE, "[%d] mViewRect: %s", mToolTipId, mViewRect);
        }

        // translate the textview
        mView.setTranslationX(mDrawRect.left);
        mView.setTranslationY(mDrawRect.top);

        if (null != mDrawable) {
            getAnchorPoint(gravity, mTmpPoint);
            mDrawable.setAnchor(gravity, mHideArrow ? 0 : mPadding / 2, mHideArrow ? null : mTmpPoint);
        }
    }

    void getAnchorPoint(final Gravity gravity, Point outPoint) {

        if (gravity == BOTTOM) {
            outPoint.x = mViewRect.centerX();
            outPoint.y = mViewRect.bottom;
        } else if (gravity == TOP) {
            outPoint.x = mViewRect.centerX();
            outPoint.y = mViewRect.top;
        } else if (gravity == RIGHT) {
            outPoint.x = mViewRect.right;
            outPoint.y = mViewRect.centerY();
        } else if (gravity == LEFT) {
            outPoint.x = mViewRect.left;
            outPoint.y = mViewRect.centerY();
        } else if (this.mGravity == CENTER) {
            outPoint.x = mViewRect.centerX();
            outPoint.y = mViewRect.centerY();
        }

        outPoint.x -= mDrawRect.left;
        outPoint.y -= mDrawRect.top;

        if (!mHideArrow) {
            if (gravity == LEFT || gravity == RIGHT) {
                outPoint.y -= mPadding / 2;
            } else if (gravity == TOP || gravity == BOTTOM) {
                outPoint.x -= mPadding / 2;
            }
        }
    }

    void setText(final CharSequence text) {
        this.mText = text;
        if (null != mTextView) {
            mTextView.setText(Html.fromHtml((String) text));
        }
    }

    @Override
    public boolean onTouchEvent(@NonNull final MotionEvent event) {
        if (!mAttached || !mShowing || !isShown()) {
            return false;
        }

        final int action = event.getActionMasked();

        log(TAG, INFO, "[%d] onTouchEvent: %d, active: %b", mToolTipId, action, mActivated);

        if (mClosePolicy != ClosePolicy.None) {

            if (!mActivated) {
                log(TAG, WARN, "[%d] not yet activated...", mToolTipId);
                return true;
            }

            if (action == MotionEvent.ACTION_DOWN) {

                Rect outRect = new Rect();
                mView.getGlobalVisibleRect(outRect);
                final boolean containsTouch = outRect.contains((int) event.getX(), (int) event.getY());

                if (DBG) {
                    log(TAG, VERBOSE, "[%d] containsTouch: %b", mToolTipId, containsTouch);
                    log(TAG, VERBOSE, "[%d] mDrawRect: %s, point: %g, %g", mToolTipId, mDrawRect, event.getX(), event.getY());
                    log(
                        TAG,
                        VERBOSE, "[%d] real drawing rect: %s, contains: %b", mToolTipId, outRect,
                        outRect.contains((int) event.getX(), (int) event.getY())
                    );
                }

                switch (mClosePolicy) {
                    case TouchInside:
                    case TouchInsideExclusive:
                        if (containsTouch) {
                            onClose(true, true, false);
                            return true;
                        }
                        return mClosePolicy == ClosePolicy.TouchInsideExclusive;
                    case TouchOutside:
                    case TouchOutsideExclusive:
                        onClose(true, containsTouch, false);
                        return mClosePolicy == ClosePolicy.TouchOutsideExclusive || containsTouch;
                    case TouchAnyWhere:
                        onClose(true, containsTouch, false);
                        return false;
                    case None:
                        break;
                }
            }
        }

        return false;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (!mAttached) {
            return;
        }
        super.onDraw(canvas);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int myWidth = 0;
        int myHeight = 0;

        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        // Record our dimensions if they are known;
        if (widthMode != MeasureSpec.UNSPECIFIED) {
            myWidth = widthSize;
        }

        if (heightMode != MeasureSpec.UNSPECIFIED) {
            myHeight = heightSize;
        }

        log(TAG, VERBOSE, "[%d] myWidth: %d, myHeight: %d", mToolTipId, myWidth, myHeight);

        if (null != mView) {
            if (mView.getVisibility() != GONE) {
                int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(myWidth, MeasureSpec.AT_MOST);
                int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(myHeight, MeasureSpec.AT_MOST);
                mView.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            } else {
                myWidth = 0;
                myHeight = 0;
            }
        }

        setMeasuredDimension(myWidth, myHeight);
    }

    private void onClose(boolean fromUser, boolean containsTouch, boolean immediate) {
        log(TAG, INFO, "[%d] onClose. fromUser: %b, containsTouch: %b, immediate: %b",
            mToolTipId,
            fromUser,
            containsTouch,
            immediate
        );

        if (!isAttached()) {
            return;
        }

        if (null != mCloseCallback) {
            mCloseCallback.onClosing(mToolTipId, fromUser, containsTouch);
        }

        hide(true, immediate ? 0 : mFadeDuration);
    }

    void setOnToolTipListener(OnToolTipListener listener) {
        this.mTooltipListener = listener;
    }

    interface OnToolTipListener {
        void onHideCompleted(TooltipView layout);

        void onShowCompleted(TooltipView layout);

        void onShowFailed(TooltipView layout);
    }
}
