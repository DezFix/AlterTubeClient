package org.schabi.newpipe.views.shorts;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ui.StyledPlayerView;

import org.schabi.newpipe.R;

import java.util.ArrayList;
import java.util.List;

/** Vertical feed adapter: one {@link StyledPlayerView} per page, bound by the fragment. */
public class ShortsPagerAdapter extends RecyclerView.Adapter<ShortsPagerAdapter.VH> {

    public interface Callbacks {
        void onTap();

        void onHolderAttached(@NonNull VH holder);

        void onNearEnd();

        void onAuthorClick(@NonNull ShortsVideoItem item);
    }

    private final List<ShortsVideoItem> items = new ArrayList<>();
    private Callbacks callbacks;

    public ShortsPagerAdapter(@NonNull final Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void setItems(@NonNull final List<ShortsVideoItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void addItems(@NonNull final List<ShortsVideoItem> more) {
        if (more == null || more.isEmpty()) {
            return;
        }
        final int start = items.size();
        items.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    public int size() {
        return items.size();
    }

    @Nullable
    public ShortsVideoItem getItem(final int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_short, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final VH holder, final int position) {
        final ShortsVideoItem item = items.get(position);
        holder.reset();
        holder.author.setText(item.getAuthor());
        holder.author.setVisibility(TextUtils.isEmpty(item.getAuthor()) ? View.GONE : View.VISIBLE);
        holder.title.setText(item.getTitle());
        holder.title.setVisibility(TextUtils.isEmpty(item.getTitle()) ? View.GONE : View.VISIBLE);
        holder.thumbnail.setVisibility(View.VISIBLE);
        holder.error.setVisibility(View.GONE);
        holder.retry.setVisibility(View.GONE);
        holder.itemView.setOnClickListener(v -> {
            if (callbacks != null) {
                callbacks.onTap();
            }
        });
        holder.author.setOnClickListener(v -> {
            final int pos = holder.getBindingAdapterPosition();
            final ShortsVideoItem current = getItem(pos);
            if (callbacks != null && current != null) {
                callbacks.onAuthorClick(current);
            }
        });
        if (position >= getItemCount() - 3 && callbacks != null) {
            callbacks.onNearEnd();
        }
    }

    @Override
    public void onViewAttachedToWindow(@NonNull final VH holder) {
        if (callbacks != null) {
            callbacks.onHolderAttached(holder);
        }
    }

    @Override
    public void onViewRecycled(@NonNull final VH holder) {
        holder.playerView.setPlayer(null);
        holder.reset();
        super.onViewRecycled(holder);
    }

    public static class VH extends RecyclerView.ViewHolder {
        public final StyledPlayerView playerView;
        public final View spinner;
        public final ImageView playIcon;
        public final ImageView thumbnail;
        public final ProgressBar progress;
        public final TextView title;
        public final TextView author;
        public final View error;
        public final View retry;

        VH(@NonNull final View v) {
            super(v);
            playerView = v.findViewById(R.id.player_view);
            spinner = v.findViewById(R.id.spinner);
            playIcon = v.findViewById(R.id.play_icon);
            thumbnail = v.findViewById(R.id.thumbnail);
            progress = v.findViewById(R.id.progress);
            title = v.findViewById(R.id.title);
            author = v.findViewById(R.id.author);
            error = v.findViewById(R.id.error_box);
            retry = v.findViewById(R.id.retry_button);
        }

        void reset() {
            spinner.setVisibility(View.GONE);
            playIcon.setVisibility(View.GONE);
            progress.setProgress(0);
        }
    }
}
