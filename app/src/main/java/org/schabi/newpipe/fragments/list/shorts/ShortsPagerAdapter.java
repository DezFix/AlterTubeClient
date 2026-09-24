package org.schabi.newpipe.fragments.list.shorts;

import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.PicassoHelper;

import java.util.ArrayList;
import java.util.List;

public class ShortsPagerAdapter extends RecyclerView.Adapter<ShortsPagerAdapter.ShortsPageHolder> {

    public interface PageTapListener {
        void onPageTap(int position);
        void onPageSeek(int position, float value, boolean relative);
        void onShareClick(int position);
        void onCommentsClick(int position);
        void onSubscribeClick(int position);
        void onRetryClick(int position);
    }

    private static final long DOUBLE_TAP_DELAY_MS = 260L;
    private static final long FEEDBACK_DURATION_MS = 550L;

    private final List<StreamInfoItem> items = new ArrayList<>();
    private PageTapListener tapListener;

    public void setTapListener(final PageTapListener listener) {
        tapListener = listener;
    }

    public void setItems(final List<StreamInfoItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void addItems(final List<StreamInfoItem> moreItems) {
        if (moreItems.isEmpty()) {
            return;
        }
        final int start = items.size();
        items.addAll(moreItems);
        notifyItemRangeInserted(start, moreItems.size());
    }

    public StreamInfoItem getItem(final int position) {
        return items.get(position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ShortsPageHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_shorts_page, parent, false);
        return new ShortsPageHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ShortsPageHolder holder, final int position) {
        final StreamInfoItem item = items.get(position);
        holder.prepareForBind();
        holder.title.setText(item.getName());
        holder.channel.setText(item.getUploaderName());
        final String thumbnail = item.getThumbnailUrl();
        if (thumbnail != null && !thumbnail.isEmpty()) {
            PicassoHelper.loadThumbnail(thumbnail).into(holder.thumbnail);
        } else {
            holder.thumbnail.setImageDrawable(null);
        }
        holder.avatar.setImageDrawable(null);
        holder.subscribeButton.setText(R.string.shorts_subscribe);
        holder.thumbnail.setVisibility(View.VISIBLE);
        holder.loading.setVisibility(View.GONE);
        holder.progress.setProgress(0);
        holder.shareButton.setOnClickListener(v -> clickAt(holder,
                listener -> listener.onShareClick(pos(holder))));
        holder.commentsButton.setOnClickListener(v -> clickAt(holder,
                listener -> listener.onCommentsClick(pos(holder))));
        holder.subscribeButton.setOnClickListener(v -> clickAt(holder,
                listener -> listener.onSubscribeClick(pos(holder))));
        holder.retryButton.setOnClickListener(v -> clickAt(holder,
                listener -> listener.onRetryClick(pos(holder))));
        holder.pendingSingleTap = () -> {
            if (pos(holder) != RecyclerView.NO_POSITION) {
                holder.itemView.performClick();
            }
        };
        holder.gestureDetector = new GestureDetector(holder.itemView.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(@NonNull final MotionEvent event) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(@NonNull final MotionEvent event) {
                        holder.itemView.postDelayed(holder.pendingSingleTap,
                                DOUBLE_TAP_DELAY_MS);
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull final MotionEvent event) {
                        holder.itemView.removeCallbacks(holder.pendingSingleTap);
                        final int direction = event.getX()
                                < holder.itemView.getWidth() / 2f ? -1 : 1;
                        clickAt(holder, listener ->
                                listener.onPageSeek(pos(holder), direction, true));
                        holder.showSeekFeedback(direction);
                        return true;
                    }

                    @Override
                    public void onLongPress(@NonNull final MotionEvent event) {
                        holder.itemView.removeCallbacks(holder.pendingSingleTap);
                    }
                });
        holder.itemView.setOnTouchListener((view, event) ->
                holder.gestureDetector.onTouchEvent(event));
        holder.itemView.setOnClickListener(v -> clickAt(holder,
                listener -> listener.onPageTap(pos(holder))));
        holder.progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(final SeekBar seekBar, final int progress,
                                          final boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(final SeekBar seekBar) {
                holder.seeking = true;
            }

            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) {
                holder.seeking = false;
                final int itemPosition = pos(holder);
                if (itemPosition != RecyclerView.NO_POSITION) {
                    clickAt(holder, listener -> listener.onPageSeek(
                            itemPosition, seekBar.getProgress() / 1000f, false));
                }
            }
        });
    }

    @Override
    public void onViewRecycled(@NonNull final ShortsPageHolder holder) {
        holder.itemView.setKeepScreenOn(false);
        holder.clearCallbacks();
        super.onViewRecycled(holder);
    }

    private int pos(final ShortsPageHolder holder) {
        return holder.getBindingAdapterPosition();
    }

    private void clickAt(final ShortsPageHolder holder, final Click click) {
        if (tapListener != null && pos(holder) != RecyclerView.NO_POSITION) {
            click.run(tapListener);
        }
    }

    private interface Click {
        void run(PageTapListener listener);
    }

    static class ShortsPageHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final ImageView playIndicator;
        final TextView seekFeedback;
        final TextView title;
        final TextView channel;
        final TextView sound;
        final ProgressBar loading;
        final SeekBar progress;
        final ShapeableImageView avatar;
        final Button subscribeButton;
        final View shareButton;
        final View commentsButton;
        final View errorBox;
        final TextView errorText;
        final Button retryButton;
        GestureDetector gestureDetector;
        Runnable pendingSingleTap;
        Runnable hidePlayIndicator;
        Runnable hideSeekFeedback;
        boolean seeking;

        ShortsPageHolder(@NonNull final View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.shorts_thumbnail);
            playIndicator = itemView.findViewById(R.id.shorts_play_indicator);
            seekFeedback = itemView.findViewById(R.id.shorts_seek_feedback);
            title = itemView.findViewById(R.id.shorts_title);
            channel = itemView.findViewById(R.id.shorts_channel);
            sound = itemView.findViewById(R.id.shorts_sound);
            loading = itemView.findViewById(R.id.shorts_page_loading);
            progress = itemView.findViewById(R.id.shorts_progress);
            avatar = itemView.findViewById(R.id.shorts_avatar);
            subscribeButton = itemView.findViewById(R.id.shorts_subscribe_button);
            shareButton = itemView.findViewById(R.id.shorts_share_button);
            commentsButton = itemView.findViewById(R.id.shorts_comments_button);
            errorBox = itemView.findViewById(R.id.shorts_page_error);
            errorText = itemView.findViewById(R.id.shorts_page_error_text);
            retryButton = itemView.findViewById(R.id.shorts_page_retry);
            progress.setMax(1000);
        }

        void prepareForBind() {
            clearCallbacks();
            errorBox.setVisibility(View.GONE);
            sound.setText("");
            PicassoHelper.cancelTag(avatar);
            playIndicator.setVisibility(View.GONE);
            seekFeedback.setVisibility(View.GONE);
            seeking = false;
        }

        void setKeepScreenOn(final boolean keepScreenOn) {
            itemView.setKeepScreenOn(keepScreenOn);
        }

        void showError(@StringRes final int message) {
            loading.setVisibility(View.GONE);
            errorText.setText(message);
            errorBox.setVisibility(View.VISIBLE);
        }

        void hideError() {
            errorBox.setVisibility(View.GONE);
        }

        void showPlayIndicator(final boolean playing) {
            clearPlayIndicatorCallback();
            playIndicator.setImageResource(playing
                    ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            playIndicator.setAlpha(1f);
            playIndicator.setVisibility(View.VISIBLE);
            hidePlayIndicator = () -> playIndicator.animate()
                    .alpha(0f).setDuration(180L)
                    .withEndAction(() -> playIndicator.setVisibility(View.GONE))
                    .start();
            itemView.postDelayed(hidePlayIndicator, FEEDBACK_DURATION_MS);
        }

        void showSeekFeedback(final int direction) {
            clearSeekFeedbackCallback();
            seekFeedback.setText(itemView.getContext().getString(
                    R.string.shorts_seek_feedback, direction * 10));
            seekFeedback.setAlpha(1f);
            seekFeedback.setVisibility(View.VISIBLE);
            hideSeekFeedback = () -> seekFeedback.animate()
                    .alpha(0f).setDuration(180L)
                    .withEndAction(() -> seekFeedback.setVisibility(View.GONE))
                    .start();
            itemView.postDelayed(hideSeekFeedback, FEEDBACK_DURATION_MS);
        }

        void clearCallbacks() {
            if (pendingSingleTap != null) {
                itemView.removeCallbacks(pendingSingleTap);
            }
            clearPlayIndicatorCallback();
            clearSeekFeedbackCallback();
        }

        private void clearPlayIndicatorCallback() {
            if (hidePlayIndicator != null) {
                itemView.removeCallbacks(hidePlayIndicator);
                playIndicator.animate().cancel();
                hidePlayIndicator = null;
            }
        }

        private void clearSeekFeedbackCallback() {
            if (hideSeekFeedback != null) {
                itemView.removeCallbacks(hideSeekFeedback);
                seekFeedback.animate().cancel();
                hideSeekFeedback = null;
            }
        }
    }
}
