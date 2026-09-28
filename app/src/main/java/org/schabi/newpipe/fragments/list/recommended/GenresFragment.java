package org.schabi.newpipe.fragments.list.recommended;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentGenresBinding;

/**
 * "Темы": pick the genres you like and the genres you do not want to see. Liked genres are
 * boosted in the recommendations, blocked genres are dropped.
 */
public class GenresFragment extends BaseFragment {

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
        binding.genresEnabledSwitch.setChecked(preferences.isEnabled());
        binding.genresEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.setEnabled(isChecked);
            adapter.notifyDataSetChanged();
        });
        binding.genresResetButton.setOnClickListener(v -> {
            preferences.clear();
            adapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        setTitle(getString(R.string.recommended_genres_title));
        if (binding != null) {
            binding.genresEnabledSwitch.setChecked(preferences.isEnabled());
        }
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
            final boolean topicsEnabled = preferences.isEnabled();
            final boolean liked = topicsEnabled && preferences.isLiked(genre);
            final boolean hidden = topicsEnabled && preferences.isBlocked(genre);

            holder.name.setText(preferences.displayName(genre));
            holder.status.setText(statusRes(topicsEnabled, liked, hidden));
            setSwitch(holder.likeSwitch, liked, topicsEnabled,
                    (buttonView, isChecked) -> {
                        preferences.setLiked(genre, isChecked);
                        notifyItemChanged(position);
                    });
            setSwitch(holder.hideSwitch, hidden, topicsEnabled,
                    (buttonView, isChecked) -> {
                        preferences.setBlocked(genre, isChecked);
                        notifyItemChanged(position);
                    });
        }

        private int statusRes(final boolean topicsEnabled,
                              final boolean liked,
                              final boolean hidden) {
            if (!topicsEnabled) {
                return R.string.recommended_genre_disabled;
            }
            if (liked) {
                return R.string.recommended_genre_boosted;
            }
            if (hidden) {
                return R.string.recommended_genre_hidden;
            }
            return R.string.recommended_genre_standard;
        }

        private void setSwitch(final SwitchMaterial switchView,
                               final boolean checked,
                               final boolean enabled,
                               final android.widget.CompoundButton.OnCheckedChangeListener listener) {
            switchView.setOnCheckedChangeListener(null);
            switchView.setChecked(checked);
            switchView.setEnabled(enabled);
            switchView.setAlpha(enabled ? 1.0f : 0.5f);
            switchView.setOnCheckedChangeListener(listener);
        }

        @Override
        public int getItemCount() {
            return GenrePreferences.genres().size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView status;
            final SwitchMaterial likeSwitch;
            final SwitchMaterial hideSwitch;

            Holder(@NonNull final View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.genre_name);
                status = itemView.findViewById(R.id.genre_status);
                likeSwitch = itemView.findViewById(R.id.genre_like_switch);
                hideSwitch = itemView.findViewById(R.id.genre_hide_switch);
            }
        }
    }
}
