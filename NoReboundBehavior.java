
import android.content.Context;
import android.graphics.Rect;
import android.support.annotation.NonNull;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CoordinatorLayout;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.OverScroller;
import java.lang.ref.WeakReference;
import static android.support.v4.view.ViewCompat.TYPE_TOUCH;
import static android.support.v7.widget.RecyclerView.SCROLL_STATE_SETTLING;

public class NoReboundBehavior extends AppBarLayout.Behavior {

    RecyclerView mNestedRecyclerView;
    ViewFlinger mFlinger;
    final int[] mScrollConsumed = new int[2];

    private static final int INVALID_POINTER = -1;
    private VelocityTracker mVelocityTracker;
    private boolean mIsBeingDragged;
    private int mLastMotionY;
    private int mTouchSlop = -1;
    OverScroller mScroller;
    private int mActivePointerId = INVALID_POINTER;
    private WeakReference<View> mLastNestedScrollingChildRef;

    public NoReboundBehavior() {
    }

    public NoReboundBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onInterceptTouchEvent(CoordinatorLayout parent, AppBarLayout child, MotionEvent ev) {
        if (mTouchSlop < 0) {
            mTouchSlop = ViewConfiguration.get(parent.getContext()).getScaledTouchSlop();
        }

        int action = ev.getAction();

        // Shortcut since we're being dragged
        if (action == MotionEvent.ACTION_MOVE && mIsBeingDragged) {
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mIsBeingDragged = false;
                int x = (int) (ev.getX() + 0.5f);
                int y = (int) (ev.getY() + 0.5f);
                if (canDragView(child) && parent.isPointInChildBounds(child, x, y)) {
                    mLastMotionY = y;
                    mActivePointerId = ev.getPointerId(0);
                    ensureVelocityTracker();

                    if (mNestedRecyclerView != null
                            && mNestedRecyclerView.getScrollState() == SCROLL_STATE_SETTLING) {
                        mNestedRecyclerView.stopScroll();
                    }
                }
                if (mFlinger != null) {
                    child.removeCallbacks(mFlinger);
                    mFlinger = null;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                final int activePointerId = mActivePointerId;
                if (activePointerId == INVALID_POINTER) {
                    // If we don't have a valid id, the touch down wasn't on content.
                    break;
                }
                final int pointerIndex = ev.findPointerIndex(activePointerId);
                if (pointerIndex == -1) {
                    break;
                }

                final int y = (int) ev.getY(pointerIndex);
                final int yDiff = Math.abs(y - mLastMotionY);
                if (yDiff > mTouchSlop) {
                    mIsBeingDragged = true;
                    mLastMotionY = y;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
            }
        }

        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(ev);
        }
        return mIsBeingDragged;
    }

    @Override
    public boolean onTouchEvent(CoordinatorLayout parent, AppBarLayout child, MotionEvent ev) {
        if (mTouchSlop < 0) {
            mTouchSlop = ViewConfiguration.get(parent.getContext()).getScaledTouchSlop();
        }
        int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                final int x = (int) ev.getX();
                final int y = (int) ev.getY();

                if (parent.isPointInChildBounds(child, x, y) && canDragView(child)) {
                    mLastMotionY = y;
                    mActivePointerId = ev.getPointerId(0);
                    ensureVelocityTracker();
                } else {
                    return false;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                if (activePointerIndex == -1) {
                    return false;
                }

                final int y = (int) ev.getY(activePointerIndex);
                int dy = mLastMotionY - y;

                if (!mIsBeingDragged && Math.abs(dy) > mTouchSlop) {
                    mIsBeingDragged = true;
                    if (dy > 0) {
                        dy -= mTouchSlop;
                    } else {
                        dy += mTouchSlop;
                    }
                }

                if (mIsBeingDragged) {
                    mLastMotionY = y;
                    // We're being dragged so scroll the ABL
                    if (dy > 0) {
                        onNestedPreScroll(parent, child, null, 0, dy, mScrollConsumed, TYPE_TOUCH);
                    } else {
                        onNestedScroll(parent, child, null, 0, 0, 0, dy, TYPE_TOUCH);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                if (mVelocityTracker != null) {
                    mVelocityTracker.addMovement(ev);
                    mVelocityTracker.computeCurrentVelocity(1000);
                    float yvel = mVelocityTracker.getYVelocity();
                    fling(parent, child, -child.getTotalScrollRange(), 0, yvel);
                }
                // $FALLTHROUGH
            case MotionEvent.ACTION_CANCEL: {
                mIsBeingDragged = false;
                mActivePointerId = INVALID_POINTER;
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
            }
        }

        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(ev);
        }
        return true;
    }

    void ensureVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }

    void ensureRecyclerView(View parent) {
        if (mNestedRecyclerView == null || !mNestedRecyclerView.getLocalVisibleRect(new Rect())) {
            mNestedRecyclerView = findShownNestedRecyclerView(parent);
        }
    }

    boolean canDragView(AppBarLayout view) {
        // Else we'll use the default behaviour of seeing if it can scroll down
        if (mLastNestedScrollingChildRef != null) {
            // If we have a reference to a scrolling view, check it
            final View scrollingView = mLastNestedScrollingChildRef.get();
            return scrollingView != null && scrollingView.isShown()
                    && !scrollingView.canScrollVertically(-1);
        } else {
            // Otherwise we assume that the scrolling view hasn't been scrolled and can drag.
            return true;
        }
    }

    @Override
    public boolean onStartNestedScroll(CoordinatorLayout parent, AppBarLayout child, View directTargetChild, View target, int nestedScrollAxes, int type) {
        mLastNestedScrollingChildRef = null;
        return super.onStartNestedScroll(parent, child, directTargetChild, target, nestedScrollAxes, type);
    }

    @Override
    public boolean onNestedFling(@NonNull CoordinatorLayout coordinatorLayout, @NonNull AppBarLayout child, @NonNull View target, float velocityX, float velocityY, boolean consumed) {
        ensureRecyclerView(target);
        return super.onNestedFling(coordinatorLayout, child, target, velocityX, velocityY, consumed);
    }

    @Override
    public void onStopNestedScroll(CoordinatorLayout coordinatorLayout, AppBarLayout abl, View target, int type) {
        if (target != null) {
            mLastNestedScrollingChildRef = new WeakReference<>(target);
        }
        super.onStopNestedScroll(coordinatorLayout, abl, target, type);
    }

    void fling(CoordinatorLayout parent, AppBarLayout child, int minOffset,
               int maxOffset, float velocityY) {
        if (mFlinger != null) {
            child.removeCallbacks(mFlinger);
            mFlinger = null;
        }

        if (mScroller == null) {
            mScroller = new OverScroller(child.getContext(), sQuinticInterpolator);
        }

        mScroller.fling(
                0, getTopAndBottomOffset(), // curr
                0, Math.round(velocityY), // velocity.
                0, 0, // x
                minOffset, maxOffset); // y

        if (mScroller.computeScrollOffset()) {
            mFlinger = new ViewFlinger(parent, child);
            ViewCompat.postOnAnimation(child, mFlinger);
        } else {
            onStopNestedScroll(parent, child, null, TYPE_TOUCH);
        }
    }

    private RecyclerView findShownNestedRecyclerView(View view) {
        RecyclerView target = null;
        View parent = view;
        if (parent != null) {
            if (instanceOfRecyclerView(parent)) {
                target = (RecyclerView) parent;
            } else {
                if (parent instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) parent;
                    for (int i = group.getChildCount() - 1; i >= 0; i--) {
                        target = findShownNestedRecyclerView(group.getChildAt(i));
                        if (target != null) {
                            break;
                        }
                    }
                }
            }
        }
        return target;
    }

    private boolean instanceOfRecyclerView(View target) {
        if (target instanceof RecyclerView) {
            if (((RecyclerView)target).isNestedScrollingEnabled()
                    && target.getLocalVisibleRect(new Rect())) {
                return true;
            }
        }
        return false;
    }

    class ViewFlinger implements Runnable {

        CoordinatorLayout mParent;
        AppBarLayout mChild;

        int mLastY;

        public ViewFlinger(CoordinatorLayout parent, AppBarLayout child) {
            mParent = parent;
            mChild = child;
        }

        @Override
        public void run() {
            if (mChild != null && mScroller != null) {
                if (mScroller.computeScrollOffset()) {
                    int y = mScroller.getCurrY();
                    mLastY = getTopAndBottomOffset();
                    int dy = mLastY - y;
                    if (dy > 0) {
                        onNestedPreScroll(mParent, mChild, null, 0, dy, mScrollConsumed, TYPE_TOUCH);
                    } else {
                        onNestedScroll(mParent, mChild, null, 0, 0, 0, dy, TYPE_TOUCH);
                    }
                    ViewCompat.postOnAnimation(mChild, this);
                } else {
                    //must be TYPE_TOUCH
                    onStopNestedScroll(mParent, mChild, null, TYPE_TOUCH);
                }
            }
        }
    }

    static final Interpolator sQuinticInterpolator = new Interpolator() {
        @Override
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };
}
