package org.schabi.newpipe.fragments.list.shorts;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.material.imageview.ShapeableImageView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.PicassoHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical pager pages for the Shorts feed (YouTube-Shorts style:
 * right action rail, bottom channel info, progress bar).
 * Playback itself is owned by {@link ShortsFragment} (single shared
 * ExoPlayer); the adapter only exposes each page's views.
 */
public class ShortsPagerAdapter extends RecyclerView.Adapter<ShortsPagerAdapter.ShortsPageHolder> {

    public interface PageTapListener {
        void onPageTap(int position);
        void onShareClick(int position);
        void onCommentsClick(int position);
        void onSubscribeClick(int position);
    }

    private final List<StreamInfoItem> items = new ArrayList<>();
    private PageTapListener tapListener;

    public void setTapListener(final PageTapListener listener) {
        this.tapListener = listener;
    }

    public void setItems(final List<StreamInfoItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void addItems(final List<StreamInfoItem> moreItems) {
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
    public ShortsPageHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                               final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_shorts_page, parent, false);
        return new ShortsPageHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ShortsPageHolder holder, final int position) {
        final StreamInfoItem item = items.get(position);
        holder.title.setText(item.getName());
        holder.channel.setText(item.getUploaderName());
        final String thumb = item.getThumbnailUrl();
        if (thumb != null && !thumb.isEmpty()) {
            PicassoHelper.loadThumbnail(thumb).into(holder.thumbnail);
        } else {
            holder.thumbnail.setImageDrawable(null);
        }
        // Per-page dynamic state (avatar, subscribe) is filled by the fragment
        // once the stream is resolved; reset to neutral placeholders here.
        holder.avatar.setImageDrawable(null);
        holder.subscribeButton.setText(R.string.shorts_subscribe);
        holder.thumbnail.setVisibility(View.VISIBLE);
        holder.playerView.setPlayer(null);
        holder.loading.setVisibility(View.GONE);
        holder.progress.setProgress(0);
        holder.itemView.setOnClickListener(v -> clickAt(holder, l -> l.onPageTap(pos(holder))));
        holder.shareButton.setOnClickListener(v -> clickAt(holder, l -> l.onShareClick(pos(holder))));
        holder.commentsButton.setOnClickListener(
                v -> clickAt(holder, l -> l.onCommentsClick(pos(holder))));
        holder.subscribeButton.setOnClickListener(
                v -> clickAt(holder, l -> l.onSubscribeClick(pos(holder))));
    }

    private int pos(final ShortsPageHolder holder) {
        return holder.getBindingAdapterPosition();
    }

    private interface Click {
        void run(PageTapListener listener);
    }

    private void clickAt(final ShortsPageHolder holder, final Click click) {
        if (tapListener != null && pos(holder) != RecyclerView.NO_POSITION) {
            click.run(tapListener);
        }
    }

    static class ShortsPageHolder extends RecyclerView.ViewHolder {
        final PlayerView playerView;
        final ImageView thumbnail;
        final TextView title;
        final TextView channel;
        final TextView sound;
        final ProgressBar loading;
        final ProgressBar progress;
        final ShapeableImageView avatar;
        final Button subscribeButton;
        final View shareButton;
        final View commentsButton;

        ShortsPageHolder(@NonNull final View itemView) {
            super(itemView);
            playerView = itemView.findViewById(R.id.shorts_player_view);
            thumbnail = itemView.findViewById(R.id.shorts_thumbnail);
            title = itemView.findViewById(R.id.shorts_title);
            channel = itemView.findViewById(R.id.shorts_channel);
            sound = itemView.findViewById(R.id.shorts_sound);
            loading = itemView.findViewById(R.id.shorts_page_loading);
            progress = itemView.findViewById(R.id.shorts_progress);
            avatar = itemView.findViewById(R.id.shorts_avatar);
            subscribeButton = itemView.findViewById(R.id.shorts_subscribe_button);
            shareButton = itemView.findViewById(R.id.shorts_share_button);
            commentsButton = itemView.findViewById(R.id.shorts_comments_button);
        }

        void attachPlayer(final ExoPlayer player) {
            playerView.setPlayer(player);
        }

        void detachPlayer() {
            playerView.setPlayer(null);
        }
    }
}
