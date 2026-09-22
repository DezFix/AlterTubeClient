package org.schabi.newpipe.fragments.list.recommended;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentRecommendedBinding;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.util.ContentFilter;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.NavigationHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * "Recommended" tab: videos related to recently watched ones
 * (history-based, YouTube only). Open a video to tune future picks.
 */
public class RecommendedFragment extends Fragment {

    /** How many recent watches seed the recommendations. */
    private static final int SEED_COUNT = 5;
    /** Max items in the list. */
    private static final int MAX_ITEMS = 30;

    private FragmentRecommendedBinding binding;
    private RecommendedAdapter adapter;
    private final CompositeDisposable disposables = new CompositeDisposable();

    public static RecommendedFragment newInstance() {
        return new RecommendedFragment();
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

        adapter = new RecommendedAdapter();
        adapter.setClickListener(position -> {
            if (position < 0 || position >= adapter.getItemCount()) {
                return;
            }
            final StreamInfoItem item = adapter.getItem(position);
            NavigationHelper.openVideoDetailFragment(requireContext(),
                    getParentFragmentManager(),
                    item.getServiceId(), item.getUrl(), item.getName(), null, false);
        });
        binding.recommendedList.setLayoutManager(
                new androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2));
        binding.recommendedList.setAdapter(adapter);
        binding.recommendedRetryButton.setOnClickListener(v -> load());

        load();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        disposables.clear();
        binding.recommendedList.setAdapter(null);
        binding = null;
    }

    private void load() {
        binding.recommendedLoading.setVisibility(View.VISIBLE);
        binding.recommendedErrorBox.setVisibility(View.GONE);

        disposables.add(buildRecommendations()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    if (binding == null) {
                        return;
                    }
                    binding.recommendedLoading.setVisibility(View.GONE);
                    if (items.isEmpty()) {
                        binding.recommendedErrorBox.setVisibility(View.VISIBLE);
                    } else {
                        adapter.setItems(items);
                    }
                }, throwable -> {
                    if (binding == null) {
                        return;
                    }
                    binding.recommendedLoading.setVisibility(View.GONE);
                    binding.recommendedErrorBox.setVisibility(View.VISIBLE);
                }));
    }

    private Single<List<StreamInfoItem>> buildRecommendations() {
        return Single.fromCallable(() -> {
            final List<StreamInfoItem> out = new ArrayList<>();
            final Set<String> seen = new HashSet<>();
            List<StreamHistoryEntry> history = new ArrayList<>();
            try {
                history = new HistoryRecordManager(
                        requireContext().getApplicationContext())
                        .getStreamHistorySortedById()
                        .blockingFirst(new ArrayList<>());
            } catch (final Exception ignored) {
                // no history: empty recommendations with retry
            }
            int seeds = 0;
            for (int i = history.size() - 1;
                    i >= 0 && seeds < SEED_COUNT && out.size() < MAX_ITEMS; i--) {
                final String url = history.get(i).getStreamEntity().getUrl();
                if (url == null || url.isEmpty()
                        || history.get(i).getStreamEntity().getServiceId()
                        != ServiceList.YouTube.getServiceId()) {
                    continue;
                }
                seeds++;
                try {
                    final StreamInfo info = ExtractorHelper.getNewStreamInfo(
                            ServiceList.YouTube.getServiceId(), url);
                    for (final InfoItem related : info.getRelatedItems()) {
                        if (!(related instanceof StreamInfoItem)) {
                            continue;
                        }
                        final StreamInfoItem item = (StreamInfoItem) related;
                        if (item.getUrl() == null || !seen.add(item.getUrl())) {
                            continue;
                        }
                        if (ContentFilter.isPoliticsBlocked(
                                item.getName(), item.getUploaderName())) {
                            continue;
                        }
                        out.add(item);
                        if (out.size() >= MAX_ITEMS) {
                            break;
                        }
                    }
                } catch (final Exception ignored) {
                    // one bad seed must not kill the list
                }
            }
            return out;
        });
    }
}
