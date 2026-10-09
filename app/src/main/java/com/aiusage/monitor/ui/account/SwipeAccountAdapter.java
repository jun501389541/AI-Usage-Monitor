package com.aiusage.monitor.ui.account;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.VelocityTracker;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.aiusage.monitor.ui.UiKit;

import java.util.ArrayList;
import java.util.List;

/** Account rows with directional actions and within-group long-press sorting. */
final class SwipeAccountAdapter extends RecyclerView.Adapter<SwipeAccountAdapter.AccountViewHolder> {
    interface Listener {
        void open(String accountId);
        void menu(String accountId);
        void pin(String accountId, boolean pinned);
        void delete(String accountId);
        void startDrag(AccountViewHolder holder);
    }

    static final class Row {
        final String accountId, name, balance, usage, status;
        final boolean enabled, degraded, pinned;
        Row(String accountId, String name, String balance, String usage, String status,
            boolean enabled, boolean degraded, boolean pinned) {
            this.accountId = accountId;
            this.name = name;
            this.balance = balance;
            this.usage = usage;
            this.status = status;
            this.enabled = enabled;
            this.degraded = degraded;
            this.pinned = pinned;
        }
    }

    private final Context context;
    private final Listener listener;
    private final List<Row> rows = new ArrayList<>();
    private final int revealWidth;
    private final int actionWidth;
    private int revealedPosition = RecyclerView.NO_POSITION;
    private float revealedOffset;
    private RecyclerView recyclerView;

    @Override public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        this.recyclerView = recyclerView;
    }

    @Override public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        this.recyclerView = null;
    }

    SwipeAccountAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        revealWidth = UiKit.dp(context, 88);
        actionWidth = revealWidth + UiKit.dp(context, 16);
        setHasStableIds(false);
    }

    void setRows(List<Row> replacement) {
        rows.clear();
        if (replacement != null) rows.addAll(replacement);
        revealedPosition = RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    Row rowAt(int position) {
        return position < 0 || position >= rows.size() ? null : rows.get(position);
    }

    List<String> orderedIds() {
        List<String> ids = new ArrayList<>();
        for (Row row : rows) ids.add(row.accountId);
        return ids;
    }

    boolean canMove(int from, int to) {
        Row source = rowAt(from);
        Row target = rowAt(to);
        return source != null && target != null && source.pinned == target.pinned;
    }

    boolean move(int from, int to) {
        if (!canMove(from, to) || from == to) return false;
        Row moved = rows.remove(from);
        rows.add(to, moved);
        notifyItemMoved(from, to);
        return true;
    }

    void closeRevealed() {
        if (revealedPosition == RecyclerView.NO_POSITION) return;
        int old = revealedPosition;
        revealedPosition = RecyclerView.NO_POSITION;
        revealedOffset = 0;
        RecyclerView.ViewHolder holder = recyclerView == null ? null
                : recyclerView.findViewHolderForAdapterPosition(old);
        if (holder instanceof AccountViewHolder) ((AccountViewHolder) holder).animateSwipeTo(0f);
        else notifyItemChanged(old);
    }

    @Override public AccountViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        FrameLayout row = new FrameLayout(context);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, UiKit.dp(context, 5), 0, UiKit.dp(context, 5));

        // One clipped surface gives the card and revealed action a shared silhouette.
        FrameLayout surface = new FrameLayout(context);
        surface.setBackground(UiKit.roundRect(context, UiKit.COLOR_CARD, 0, 18, 0));
        surface.setClipToOutline(true);
        row.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout actions = new FrameLayout(context);
        TextView delete = actionButton("删除", 0xFFB42332);
        FrameLayout.LayoutParams deleteParams = new FrameLayout.LayoutParams(
                actionWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL);
        actions.addView(delete, deleteParams);
        TextView pin = actionButton("置顶", UiKit.COLOR_STATUS);
        FrameLayout.LayoutParams pinParams = new FrameLayout.LayoutParams(
                actionWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.CENTER_VERTICAL);
        actions.addView(pin, pinParams);
        delete.setVisibility(View.INVISIBLE);
        pin.setVisibility(View.INVISIBLE);
        surface.addView(actions, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = UiKit.card(context);
        card.setPadding(UiKit.dp(context, 18), UiKit.dp(context, 18),
                0, UiKit.dp(context, 18));
        card.setElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        surface.addView(card, cardParams);

        FrameLayout content = new FrameLayout(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(0, 0, UiKit.dp(context, 30), 0);
        content.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout identity = new LinearLayout(context);
        identity.setOrientation(LinearLayout.VERTICAL);
        body.addView(identity, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = UiKit.text(context, "", 17, UiKit.COLOR_TEXT, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView pinned = UiKit.text(context, "置顶", 10, UiKit.COLOR_BUTTON_TEXT, Typeface.BOLD);
        pinned.setGravity(Gravity.CENTER);
        pinned.setPadding(UiKit.dp(context, 7), UiKit.dp(context, 3),
                UiKit.dp(context, 7), UiKit.dp(context, 3));
        pinned.setBackground(UiKit.roundRect(context, UiKit.COLOR_BUTTON, 0, 10, 0));
        LinearLayout.LayoutParams pinnedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinnedParams.setMargins(UiKit.dp(context, 6), 0, UiKit.dp(context, 8), 0);
        titleRow.addView(pinned, pinnedParams);
        identity.addView(titleRow);
        TextView status = UiKit.text(context, "", 12, UiKit.COLOR_MUTED, Typeface.NORMAL);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        identity.addView(status, UiKit.matchWrap(context, 6));

        LinearLayout metrics = new LinearLayout(context);
        metrics.setOrientation(LinearLayout.VERTICAL);
        metrics.setGravity(Gravity.END);
        TextView balance = UiKit.text(context, "", 17, UiKit.COLOR_TEXT, Typeface.BOLD);
        balance.setGravity(Gravity.END);
        metrics.addView(balance, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView usage = UiKit.text(context, "", 12, UiKit.COLOR_HINT, Typeface.NORMAL);
        usage.setGravity(Gravity.END);
        LinearLayout.LayoutParams usageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        usageParams.topMargin = UiKit.dp(context, 6);
        metrics.addView(usage, usageParams);
        LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metricsParams.leftMargin = UiKit.dp(context, 12);
        body.addView(metrics, metricsParams);
        TextView more = UiKit.text(context, "⋮", 24, UiKit.COLOR_MUTED, Typeface.BOLD);
        more.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        more.setPadding(0, 0, UiKit.dp(context, 6), 0);
        more.setContentDescription("更多账户操作");
        FrameLayout.LayoutParams moreParams = new FrameLayout.LayoutParams(
                UiKit.dp(context, 44), UiKit.dp(context, 48), Gravity.END | Gravity.CENTER_VERTICAL);
        content.addView(more, moreParams);
        card.addView(content);
        TextView disabled = UiKit.text(context, "已停用 · 不计入自动刷新", 11, UiKit.COLOR_HINT, Typeface.NORMAL);
        card.addView(disabled, UiKit.matchWrap(context, 8));
        TextView degraded = UiKit.text(context, "密钥保护降级，建议重新保存", 11, UiKit.COLOR_PEAK, Typeface.NORMAL);
        card.addView(degraded, UiKit.matchWrap(context, 8));

        return new AccountViewHolder(row, card, name, pinned, balance, status, usage,
                disabled, degraded, more, delete, pin);
    }

    @Override public void onBindViewHolder(AccountViewHolder holder, int position) {
        holder.cancelSwipeAnimation();
        Row row = rows.get(position);
        holder.accountId = row.accountId;
        holder.name.setText(row.name);
        holder.name.setContentDescription(row.name);
        holder.balance.setText(row.balance);
        holder.balance.setTextColor(row.enabled ? UiKit.COLOR_TEXT : UiKit.COLOR_MUTED);
        holder.status.setText(row.status);
        holder.usage.setText(row.usage);
        holder.pinned.setVisibility(row.pinned ? View.VISIBLE : View.GONE);
        holder.disabled.setVisibility(row.enabled ? View.GONE : View.VISIBLE);
        holder.degraded.setVisibility(row.degraded ? View.VISIBLE : View.GONE);
        holder.card.setAlpha(row.enabled ? 1f : 0.6f);
        holder.card.setContentDescription(row.name + "，" + row.status);
        holder.applySwipeOffset(position == revealedPosition ? revealedOffset : 0f);
        holder.deleteAction.setOnClickListener(view -> {
            closeRevealed();
            listener.delete(row.accountId);
        });
        holder.pinAction.setText(row.pinned ? "取消\n置顶" : "置顶");
        holder.pinAction.setContentDescription(row.pinned ? "取消账户置顶" : "置顶账户");
        holder.pinAction.setOnClickListener(view -> {
            closeRevealed();
            listener.pin(row.accountId, !row.pinned);
        });
        holder.more.setOnClickListener(view -> listener.menu(row.accountId));
        holder.card.setOnClickListener(view -> listener.open(row.accountId));

        holder.attachTouchHandling();
    }

    @Override public int getItemCount() { return rows.size(); }

    @Override public void onViewRecycled(AccountViewHolder holder) {
        holder.cancelSwipeAnimation();
        holder.recycleVelocityTracker();
        holder.applySwipeOffset(0f);
        super.onViewRecycled(holder);
    }

    private TextView actionButton(String text, int color) {
        TextView button = UiKit.text(context, text, 13, UiKit.COLOR_TEXT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(UiKit.dp(context, 5), UiKit.dp(context, 8),
                UiKit.dp(context, 5), UiKit.dp(context, 8));
        button.setBackgroundColor(color);
        button.setMaxLines(2);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    final class AccountViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        final LinearLayout card;
        final TextView name, pinned, balance, status, usage, disabled, degraded, more;
        final TextView deleteAction, pinAction;
        String accountId;
        float downX, downY, startOffset;
        boolean horizontalSwipe, longPressDrag;
        GestureDetector gestureDetector;
        ValueAnimator swipeAnimator;
        VelocityTracker velocityTracker;

        AccountViewHolder(FrameLayout root, LinearLayout card, TextView name, TextView pinned,
                TextView balance, TextView status, TextView usage, TextView disabled,
                TextView degraded, TextView more, TextView deleteAction, TextView pinAction) {
            super(root);
            this.root = root;
            this.card = card;
            this.name = name;
            this.pinned = pinned;
            this.balance = balance;
            this.status = status;
            this.usage = usage;
            this.disabled = disabled;
            this.degraded = degraded;
            this.more = more;
            this.deleteAction = deleteAction;
            this.pinAction = pinAction;
        }

        void applySwipeOffset(float offset) {
            boolean wasOpen = card.getTranslationX() != 0f;
            boolean isOpen = offset != 0f;
            if (wasOpen != isOpen) {
                card.setBackground(UiKit.roundRect(context, UiKit.COLOR_CARD,
                        isOpen ? 0 : UiKit.COLOR_BORDER, isOpen ? 0 : 18, isOpen ? 0 : 1));
            }
            card.setTranslationX(offset);
            deleteAction.setVisibility(offset < 0f ? View.VISIBLE : View.INVISIBLE);
            pinAction.setVisibility(offset > 0f ? View.VISIBLE : View.INVISIBLE);
            if (isOpen) {
                TextView action = offset < 0f ? deleteAction : pinAction;
                // Keep the label centred in the exposed area using a transform,
                // rather than remeasuring the whole row on every animation frame.
                action.setTranslationX(-Math.signum(offset) * (actionWidth - Math.abs(offset)) / 2f);
                float progress = Math.min(1f, Math.abs(offset) / revealWidth);
                int alpha = Math.round(255f * Math.max(0f, Math.min(1f, (progress - 0.25f) / 0.55f)));
                action.setTextColor((alpha << 24) | (UiKit.COLOR_TEXT & 0x00FFFFFF));
            }
        }

        void cancelSwipeAnimation() {
            if (swipeAnimator != null) {
                swipeAnimator.cancel();
                swipeAnimator = null;
            }
        }

        void animateSwipeTo(float target) {
            cancelSwipeAnimation();
            swipeAnimator = ValueAnimator.ofFloat(card.getTranslationX(), target);
            swipeAnimator.setDuration(420);
            swipeAnimator.setInterpolator(fraction -> {
                if (fraction >= 1f) return 1f;
                double time = fraction * 0.42;
                if (target == 0f) {
                    // Critical damping closes without crossing into the opposite action.
                    double decay = 28.0 * time;
                    return (float) (1.0 - (1.0 + decay) * Math.exp(-decay));
                }
                // A lightly underdamped spring gives one small, settling rebound.
                double damping = 0.78;
                double frequency = 24.0;
                double dampedFrequency = frequency * Math.sqrt(1.0 - damping * damping);
                double decay = Math.exp(-damping * frequency * time);
                return (float) (1.0 - decay * (Math.cos(dampedFrequency * time)
                        + damping * frequency / dampedFrequency * Math.sin(dampedFrequency * time)));
            });
            swipeAnimator.addUpdateListener(animation -> applySwipeOffset((float) animation.getAnimatedValue()));
            swipeAnimator.start();
        }

        void recycleVelocityTracker() {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        void attachTouchHandling() {
            horizontalSwipe = false;
            longPressDrag = false;
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public void onLongPress(MotionEvent event) {
                    if (horizontalSwipe || card.getTranslationX() != 0f
                            || getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                    longPressDrag = true;
                    closeRevealed();
                    listener.startDrag(AccountViewHolder.this);
                }
            });
            card.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    cancelSwipeAnimation();
                    recycleVelocityTracker();
                    velocityTracker = VelocityTracker.obtain();
                }
                if (velocityTracker != null) {
                    // The card moves under the finger; use screen coordinates for velocity.
                    MotionEvent screenEvent = MotionEvent.obtain(event);
                    screenEvent.setLocation(event.getRawX(), event.getRawY());
                    velocityTracker.addMovement(screenEvent);
                    screenEvent.recycle();
                }
                gestureDetector.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startOffset = card.getTranslationX();
                        horizontalSwipe = false;
                        longPressDrag = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        int slop = ViewConfiguration.get(context).getScaledTouchSlop();
                        if (!horizontalSwipe && !longPressDrag && Math.abs(dx) > slop
                                && Math.abs(dx) > Math.abs(dy)) {
                            horizontalSwipe = true;
                            closeOtherRevealed(getBindingAdapterPosition());
                            root.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (horizontalSwipe) {
                            float offset = startOffset + dx;
                            float extra = Math.max(0f, Math.abs(offset) - revealWidth);
                            if (extra > 0f) {
                                float resistance = UiKit.dp(context, 16)
                                        * (1f - (float) Math.exp(-extra / UiKit.dp(context, 64)));
                                offset = Math.signum(offset) * (revealWidth + resistance);
                            }
                            applySwipeOffset(offset);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (longPressDrag) {
                            recycleVelocityTracker();
                            return true;
                        }
                        if (horizontalSwipe) {
                            float velocity = 0f;
                            if (velocityTracker != null) {
                                velocityTracker.computeCurrentVelocity(1000,
                                        ViewConfiguration.get(context).getScaledMaximumFlingVelocity());
                                velocity = velocityTracker.getXVelocity();
                            }
                            settleSwipe(this, velocity);
                        } else if (Math.abs(event.getRawX() - downX) < ViewConfiguration.get(context).getScaledTouchSlop()
                                && Math.abs(event.getRawY() - downY) < ViewConfiguration.get(context).getScaledTouchSlop()) {
                            if (startOffset != 0f) {
                                closeRevealed();
                                animateSwipeTo(0f);
                            }
                            else card.performClick();
                        } else {
                            animateSwipeTo(startOffset);
                        }
                        recycleVelocityTracker();
                        horizontalSwipe = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (!longPressDrag) {
                            animateSwipeTo(getBindingAdapterPosition() == revealedPosition ? revealedOffset : 0f);
                        }
                        recycleVelocityTracker();
                        horizontalSwipe = false;
                        return true;
                    default:
                        return true;
                }
            });
        }
    }

    private void closeOtherRevealed(int current) {
        if (revealedPosition != RecyclerView.NO_POSITION && revealedPosition != current) closeRevealed();
    }

    private void settleSwipe(AccountViewHolder holder, float velocity) {
        int position = holder.getBindingAdapterPosition();
        if (position < 0 || position >= rows.size()) return;
        float offset = holder.card.getTranslationX();
        float target = Math.abs(offset) >= revealWidth / 2f ? Math.signum(offset) * revealWidth : 0f;
        if (Math.abs(velocity) >= UiKit.dp(context, 400)) {
            // A fling towards the exposed side opens; a reverse fling closes it.
            target = offset * velocity > 0f ? Math.signum(velocity) * revealWidth : 0f;
        }
        revealedPosition = target == 0f ? RecyclerView.NO_POSITION : position;
        revealedOffset = target;
        holder.animateSwipeTo(target);
    }
}
