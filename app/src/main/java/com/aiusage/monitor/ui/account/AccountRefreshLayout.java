package com.aiusage.monitor.ui.account;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.RecyclerView;

/** Leaves horizontal account gestures to the rows, including diagonal swipes. */
final class AccountRefreshLayout extends SwipeRefreshLayout {
    private final int touchSlop;
    private RecyclerView accountList;
    private float downX, downY;
    private boolean directionLocked, horizontal, ignoreGesture;

    AccountRefreshLayout(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setContent(ScrollView scroll, RecyclerView list) {
        accountList = list;
        addView(scroll);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                directionLocked = false;
                horizontal = false;
                ignoreGesture = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Before interception, changing fingers must not change the pull's origin.
                ignoreGesture = true;
                return false;
            case MotionEvent.ACTION_POINTER_UP:
                if (ignoreGesture) return false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (ignoreGesture) return false;
                if (!directionLocked) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    if (Math.max(dx, dy) > touchSlop) {
                        directionLocked = true;
                        horizontal = dx > dy;
                    }
                }
                // Decide before the base layout sees MOVE: it tests only vertical slop.
                if (horizontal) {
                    // Both inner scrolling views test vertical slop. Let the row see MOVE.
                    accountList.requestDisallowInterceptTouchEvent(true);
                    return false;
                }
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(event);
    }
}
