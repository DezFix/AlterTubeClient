package org.schabi.newpipe.fragments.list.recommended;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentGenresBinding;

/**
 * "Темы": pick the genres you like and the genres you do not want to see. Liked genres are
 * boosted in the recommendations, blocked genres are dropped.
 */
public class GenresFragment extends Fragment {

    private FragmentGenresBinding binding;
    private GenrePreferences preferences;
    private GenreAdapter adapter;

    public static GenresFragment newInstance() {
        return new GenresFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentGenresBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        preferences = new GenrePreferences(requireContext());
        adapter = new GenreAdapter();
        binding.genresList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.genresList.setAdapter(adapter);
        binding.genresResetButton.setOnClickListener(v -> {
            preferences.clear();
            adapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        setTitle(getString(R.string.recommended_genres_title));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private final class GenreAdapter extends RecyclerView.Adapter<GenreAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            final View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_genre, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, final int position) {
            final String genre = GenrePreferences.genres().get(position);
            holder.name.setText(preferences.displayName(genre));
            holder.likeButton.setText(preferences.isLiked(genre)
                    ? R.string.recommended_genre_like_on : R.string.recommended_genre_like);
            holder.likeButton.setSelected(preferences.isLiked(genre));
            holder.blockButton.setText(preferences.isBlocked(genre)
                    ? R.string.recommended_genre_blocked_on : R.string.recommended_genre_blocked);
            holder.blockButton.setSelected(preferences.isBlocked(genre));
            holder.likeButton.setOnClickListener(v -> {
                preferences.toggleLiked(genre);
                notifyDataSetChanged();
            });
            holder.blockButton.setOnClickListener(v -> {
                preferences.toggleBlocked(genre);
                notifyDataSetChanged();
            });
        }

        @Override
        public int getItemCount() {
            return GenrePreferences.genres().size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView likeButton;
            final TextView blockButton;

            Holder(@NonNull final View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.genre_name);
                likeButton = itemView.findViewById(R.id.genre_like);
                blockButton = itemView.findViewById(R.id.genre_block);
            }
        }
    }
}
