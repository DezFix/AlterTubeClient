package org.schabi.newpipe.fragments.list.recommended;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

    private final List<StreamInfoItem> items = new ArrayList<>();
    private ClickListener clickListener;

    public void setClickListener(final ClickListener listener) {
        this.clickListener = listener;
    }

    public void setItems(final List<StreamInfoItem> fresh) {
        items.clear();
        items.addAll(fresh);
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
    public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recommended, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder holder, final int position) {
        final StreamInfoItem item = items.get(position);
        holder.title.setText(item.getName());
        holder.channel.setText(item.getUploaderName());
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

        Holder(@NonNull final View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.recommended_thumbnail);
            title = itemView.findViewById(R.id.recommended_title);
            channel = itemView.findViewById(R.id.recommended_channel);
        }
    }
}
