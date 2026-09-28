package org.schabi.newpipe.fragments.list.recommended;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.PicassoHelper;

import java.util.ArrayList;
import java.util.List;

public class RecommendedAdapter extends RecyclerView.Adapter<RecommendedAdapter.Holder> {

    public interface ClickListener {
        void onItemClick(int position);
    }

    public static final int REASON_NONE = 0;
    public static final int REASON_GENRE = 1;
    public static final int REASON_SIMILAR = 2;

    /** A recommended video together with the reason why it was picked. */
    public static final class Entry {
        private final StreamInfoItem item;
        private final String reason;
        private final int reasonType;

        public Entry(final StreamInfoItem item, final String reason, final int reasonType) {
            this.item = item;
            this.reason = reason == null ? "" : reason;
            this.reasonType = reasonType;
        }

        public StreamInfoItem getItem() {
            return item;
        }

        public String getReason() {
            return reason;
        }

        public int getReasonType() {
            return reasonType;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private ClickListener clickListener;

    public void setClickListener(final ClickListener listener) {
        this.clickListener = listener;
    }

    public void clear() {
        entries.clear();
        notifyDataSetChanged();
    }

    public void append(final List<Entry> fresh) {
        if (fresh == null || fresh.isEmpty()) {
            return;
        }
        final int start = entries.size();
        entries.addAll(fresh);
        notifyItemRangeInserted(start, fresh.size());
    }

    @Nullable
    public Entry getEntry(final int position) {
        return position >= 0 && position < entries.size() ? entries.get(position) : null;
    }

    public int size() {
        return entries.size();
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recommended, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder holder, final int position) {
        final Entry entry = entries.get(position);
        final StreamInfoItem item = entry.getItem();
        final Context context = holder.itemView.getContext();
        holder.title.setText(item.getName());
        holder.channel.setText(item.getUploaderName());
        if (entry.getReasonType() == REASON_NONE || entry.getReason().isEmpty()) {
            holder.reason.setVisibility(View.GONE);
        } else {
            holder.reason.setVisibility(View.VISIBLE);
            final int label = entry.getReasonType() == REASON_GENRE
                    ? R.string.recommended_reason_genre : R.string.recommended_reason_similar;
            holder.reason.setText(context.getString(label, entry.getReason()));
        }
        final String thumb = item.getThumbnailUrl();
        if (thumb != null && !thumb.isEmpty()) {
            PicassoHelper.loadThumbnail(thumb).into(holder.thumbnail);
        } else {
            holder.thumbnail.setImageDrawable(null);
        }
        holder.itemView.setOnClickListener(v -> {
            final int pos = holder.getBindingAdapterPosition();
            if (clickListener != null && pos != RecyclerView.NO_POSITION) {
                clickListener.onItemClick(pos);
            }
        });
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView title;
        final TextView channel;
        final TextView reason;

        Holder(@NonNull final View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.recommended_thumbnail);
            title = itemView.findViewById(R.id.recommended_title);
            channel = itemView.findViewById(R.id.recommended_channel);
            reason = itemView.findViewById(R.id.recommended_reason);
        }
    }
}
