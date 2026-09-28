package org.schabi.newpipe.fragments.list.recommended;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentRecommendedBinding;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.settings.NewPipeSettings;
import org.schabi.newpipe.util.NavigationHelper;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Main page with the recommendations built by {@link RecommendationEngine}: videos of the
 * same genres the user actually watches, not the generic YouTube feed. Pulling the list
 * down rebuilds the profile and reloads the feed.
 */
public class RecommendedFragment extends Fragment {

    private static final String STATE_RESUMED_REFRESH = "recommended_refresh_on_resume";
    private static final String KEY_LAST_UPDATED = "recommended_last_updated";
    private static final int RECOMMENDED_COLUMNS = 2;

    private FragmentRecommendedBinding binding;
    private RecommendedAdapter adapter;
    private final CompositeDisposable disposables = new CompositeDisposable();

    private RecommendationEngine engine;
    private boolean loading;
    private boolean refreshOnResume;
    private boolean lastPersonalized = true;
    private long lastUpdatedMillis;
    private SharedPreferences genrePreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener genreChangeListener =
            (preferences, key) -> {
                if (GenrePreferences.settingKeys().contains(key)) {
                    refreshOnResume = true;
                }
            };

    public static RecommendedFragment newInstance() {
        return new RecommendedFragment();
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        genrePreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        genrePreferences.registerOnSharedPreferenceChangeListener(genreChangeListener);
    }

    @Override
    public void onDestroy() {
        if (genrePreferences != null) {
            genrePreferences.unregisterOnSharedPreferenceChangeListener(genreChangeListener);
            genrePreferences = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentRecommendedBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (savedInstanceState != null) {
            refreshOnResume = savedInstanceState.getBoolean(STATE_RESUMED_REFRESH, false);
        }
        adapter = new RecommendedAdapter();
        adapter.setClickListener(this::openItem);
        binding.recommendedList.setLayoutManager(
                new GridLayoutManager(requireContext(), RECOMMENDED_COLUMNS));
        binding.recommendedList.setAdapter(adapter);
        binding.recommendedList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull final RecyclerView recyclerView,
                                   final int dx, final int dy) {
                if (dy > 0 && isNearEnd(recyclerView)) {
                    loadMore();
                }
            }
        });
        binding.recommendedSwipeRefresh.setOnRefreshListener(() -> {
            // Pull down: rebuild the taste profile and start a clean feed.
            disposables.clear();
            disposables.add(Single.fromCallable(() -> buildEngine())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::startFeed, throwable -> finishWithError()));
        });
        binding.recommendedRetryButton.setOnClickListener(v -> reload());
        binding.recommendedGenresButton.setOnClickListener(v -> openGenres());
        binding.recommendedPopupButton.setOnClickListener(v -> playFeed(true));
        binding.recommendedBackgroundButton.setOnClickListener(v -> playFeed(false));
        lastUpdatedMillis = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getLong(KEY_LAST_UPDATED, 0L);
        updateStatus();
    }

    private void openGenres() {
        // fragment_holder lives in the activity layout, so the transaction has to be done
        // on the activity fragment manager. Using the parent manager crashed with
        // "No view found for id fragment_holder".
        final FragmentActivity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.isStateSaved() || manager.isDestroyed()) {
            return;
        }
        manager.beginTransaction()
                .replace(R.id.fragment_holder, GenresFragment.newInstance())
                .addToBackStack(null)
                .commit();
    }

    /**
     * Starts the visible recommendations in the floating window (popup) or in the
     * background player, exactly like the "Что нового" page does.
     */
    private void playFeed(final boolean popup) {
        final List<StreamInfoItem> items = new ArrayList<>();
        for (int i = 0; i < adapter.size(); i++) {
            final RecommendedAdapter.Entry entry = adapter.getEntry(i);
            if (entry != null) {
                items.add(entry.getItem());
            }
        }
        if (items.isEmpty()) {
            Toast.makeText(requireContext(), R.string.recommended_empty, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        final PlayQueue queue = new SinglePlayQueue(items, 0);
        if (popup) {
            NavigationHelper.playOnPopupPlayer(requireContext(), queue, false);
        } else {
            NavigationHelper.playOnBackgroundPlayer(requireContext(), queue, false);
        }
    }

    private void updateStatus() {
        if (binding == null) {
            return;
        }
        if (lastUpdatedMillis <= 0L) {
            binding.recommendedUpdated.setText(R.string.recommended_never_updated);
            return;
        }
        final long minutes = Math.max(0L,
                (System.currentTimeMillis() - lastUpdatedMillis) / 60000L);
        binding.recommendedUpdated.setText(minutes < 1L
                ? getString(R.string.recommended_updated_now)
                : getString(R.string.recommended_updated_minutes, minutes));
    }

    private void storeUpdatedTimestamp() {
        lastUpdatedMillis = System.currentTimeMillis();
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putLong(KEY_LAST_UPDATED, lastUpdatedMillis).apply();
        updateStatus();
    }

    private boolean isNearEnd(final RecyclerView recyclerView) {
        final GridLayoutManager manager = (GridLayoutManager) recyclerView.getLayoutManager();
        if (manager == null) {
            return false;
        }
        final int lastVisible = manager.findLastVisibleItemPosition();
        return lastVisible >= adapter.size() - RECOMMENDED_COLUMNS * 2;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (SubscriptionsImportService.consumePendingImportCompletion(
                requireContext().getApplicationContext())) {
            reload();
            return;
        }
        final boolean personalized = NewPipeSettings.isPersonalizedFeedEnabled(requireContext());
        if (personalized != lastPersonalized) {
            reload();
            return;
        }
        if (refreshOnResume) {
            refreshOnResume = false;
            reload();
            return;
        }
        if (binding != null && adapter.size() == 0) {
            reload();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_RESUMED_REFRESH, refreshOnResume);
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        binding = null;
        super.onDestroyView();
    }

    private void reload() {
        if (binding == null) {
            return;
        }
        disposables.clear();
        binding.recommendedErrorBox.setVisibility(View.GONE);
        binding.recommendedSwipeRefresh.setRefreshing(true);
        disposables.add(Single.fromCallable(() -> buildEngine())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::startFeed, throwable -> finishWithError()));
    }

    private RecommendationEngine buildEngine() {
        lastPersonalized = NewPipeSettings.isPersonalizedFeedEnabled(requireContext());
        final GenreProfileBuilder profile = new GenreProfileBuilder(requireContext()).load();
        return new RecommendationEngine(requireContext(), profile);
    }

    private void startFeed(final RecommendationEngine freshEngine) {
        if (binding == null) {
            return;
        }
        engine = freshEngine;
        engine.prepare();
        adapter.clear();
        loading = false;
        binding.recommendedErrorBox.setVisibility(View.GONE);
        binding.recommendedSwipeRefresh.setRefreshing(false);
        if (engine.isEmpty()) {
            showEmptyState();
            return;
        }
        binding.recommendedLoading.setVisibility(View.VISIBLE);
        loadMore();
    }

    private void loadMore() {
        if (binding == null || engine == null || loading) {
            return;
        }
        if (engine.isExhausted()) {
            if (adapter.size() == 0) {
                finishWithError();
            }
            return;
        }
        loading = true;
        binding.recommendedLoading.setVisibility(View.VISIBLE);
        disposables.add(engine.loadNext()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    if (binding == null) {
                        return;
                    }
                    loading = false;
                    binding.recommendedLoading.setVisibility(View.GONE);
                    binding.recommendedSwipeRefresh.setRefreshing(false);
                    adapter.append(items);
                    if (!items.isEmpty()) {
                        storeUpdatedTimestamp();
                    }
                    if (adapter.size() == 0 && engine.isExhausted()) {
                        showEmptyState();
                    }
                }, throwable -> {
                    if (binding != null) {
                        loading = false;
                        binding.recommendedLoading.setVisibility(View.GONE);
                        binding.recommendedSwipeRefresh.setRefreshing(false);
                    }
                    finishWithError();
                }));
    }

    private void showEmptyState() {
        if (binding == null) {
            return;
        }
        binding.recommendedLoading.setVisibility(View.GONE);
        binding.recommendedErrorBox.setVisibility(View.VISIBLE);
        binding.recommendedErrorText.setText(R.string.recommended_empty);
    }

    private void finishWithError() {
        if (binding == null) {
            return;
        }
        binding.recommendedLoading.setVisibility(View.GONE);
        binding.recommendedSwipeRefresh.setRefreshing(false);
        loading = false;
        if (adapter.size() == 0) {
            showEmptyState();
        } else {
            Toast.makeText(requireContext(), R.string.recommended_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openItem(final int position) {
        final RecommendedAdapter.Entry entry = adapter.getEntry(position);
        if (entry == null) {
            return;
        }
        final StreamInfoItem item = entry.getItem();
        // The player is opened as a normal video detail page: this used to be a crash
        // source when the fragment transactions were done on a child manager.
        NavigationHelper.openVideoDetail(requireContext(), item.getServiceId(),
                item.getUrl(), item.getName(), null, false);
    }
}
