package org.schabi.newpipe.fragments.list.shorts;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ui.PlayerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.PicassoHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical pager pages for the Shorts feed. Playback itself is owned by
 * {@link ShortsFragment} (single shared ExoPlayer); the adapter only exposes
 * each page's {@link PlayerView} so the player can be attached on selection.
 */
public class ShortsPagerAdapter extends RecyclerView.Adapter<ShortsPagerAdapter.ShortsPageHolder> {

    public interface PageTapListener {
        void onPageTap(int position);
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
        final String thumbnailUrl = item.getThumbnailUrl();
        if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
            PicassoHelper.loadThumbnail(thumbnailUrl).into(holder.thumbnail);
        } else {
            holder.thumbnail.setImageDrawable(null);
        }
        holder.playerView.setPlayer(null);
        holder.thumbnail.setVisibility(View.VISIBLE);
        holder.loading.setVisibility(View.GONE);
        holder.itemView.setOnClickListener(v -> {
            final int pos = holder.getBindingAdapterPosition();
            if (tapListener != null && pos != RecyclerView.NO_POSITION) {
                tapListener.onPageTap(pos);
            }
        });
    }

    static class ShortsPageHolder extends RecyclerView.ViewHolder {
        final PlayerView playerView;
        final ImageView thumbnail;
        final TextView title;
        final TextView channel;
        final ProgressBar loading;

        ShortsPageHolder(@NonNull final View itemView) {
            super(itemView);
            playerView = itemView.findViewById(R.id.shorts_player_view);
            thumbnail = itemView.findViewById(R.id.shorts_thumbnail);
            title = itemView.findViewById(R.id.shorts_title);
            channel = itemView.findViewById(R.id.shorts_channel);
            loading = itemView.findViewById(R.id.shorts_page_loading);
        }

        void attachPlayer(final ExoPlayer player) {
            playerView.setPlayer(player);
        }

        void detachPlayer() {
            playerView.setPlayer(null);
        }
    }
}
