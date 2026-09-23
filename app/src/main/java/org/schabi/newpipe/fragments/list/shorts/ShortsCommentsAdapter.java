package org.schabi.newpipe.fragments.list.shorts;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;

import java.util.ArrayList;
import java.util.List;

public class ShortsCommentsAdapter
        extends RecyclerView.Adapter<ShortsCommentsAdapter.Holder> {

    private final List<CommentsInfoItem> items = new ArrayList<>();

    public void setItems(final List<CommentsInfoItem> fresh) {
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_shorts_comment, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder holder, final int position) {
        final CommentsInfoItem item = items.get(position);
        holder.author.setText(item.getUploaderName());
        holder.text.setText(item.getCommentText() == null
                ? "" : item.getCommentText().getContent());
        final int likes = item.getLikeCount();
        holder.likes.setVisibility(likes > 0 ? View.VISIBLE : View.GONE);
        holder.likes.setText("♥ " + likes);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView author;
        final TextView text;
        final TextView likes;

        Holder(@NonNull final View itemView) {
            super(itemView);
            author = itemView.findViewById(R.id.shorts_comment_author);
            text = itemView.findViewById(R.id.shorts_comment_text);
            likes = itemView.findViewById(R.id.shorts_comment_likes);
        }
    }
}
