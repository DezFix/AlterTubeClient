package org.schabi.newpipe.fragments.list.music;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentRecommendedBinding;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.recommended.RecommendedAdapter;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.ContentFilter;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.NavigationHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Music tab v1: music video feed, tap plays AUDIO in the background player
 * (no video surface). A dedicated now-playing disc screen comes next.
 */
public class MusicFragment extends Fragment {

    private static final String[] MUSIC_QUERIES =
            {"new music videos", "top music videos this week", "latest music releases"};

    private FragmentRecommendedBinding binding;
    private RecommendedAdapter adapter;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final List<StreamInfoItem> items = new ArrayList<>();

    public static MusicFragment newInstance() {
        return new MusicFragment();
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
            if (position < 0 || position >= items.size()) {
                return;
            }
            NavigationHelper.playOnBackgroundPlayer(requireContext(),
                    new SinglePlayQueue(new ArrayList<>(items), position), true);
        });
        binding.recommendedList.setLayoutManager(new LinearLayoutManager(requireContext()));
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

        disposables.add(loadQuery(0)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(first -> {
                    if (binding == null) {
                        return;
                    }
                    final List<StreamInfoItem> merged = new ArrayList<>(first);
                    disposables.add(loadQuery(1)
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(second -> {
                                if (binding == null) {
                                    return;
                                }
                                binding.recommendedLoading.setVisibility(View.GONE);
                                final Set<String> seen = new HashSet<>();
                                for (final StreamInfoItem item : merged) {
                                    if (item.getUrl() != null) {
                                        seen.add(item.getUrl());
                                    }
                                }
                                for (final StreamInfoItem item : second) {
                                    if (item.getUrl() != null && seen.add(item.getUrl())) {
                                        merged.add(item);
                                    }
                                }
                                showItems(merged);
                            }, throwable -> {
                                if (binding == null) {
                                    return;
                                }
                                binding.recommendedLoading.setVisibility(View.GONE);
                                showItems(merged);
                            }));
                }, throwable -> {
                    if (binding == null) {
                        return;
                    }
                    binding.recommendedLoading.setVisibility(View.GONE);
                    binding.recommendedErrorBox.setVisibility(View.VISIBLE);
                }));
    }

    private void showItems(final List<StreamInfoItem> fresh) {
        items.clear();
        items.addAll(fresh);
        if (items.isEmpty()) {
            binding.recommendedErrorBox.setVisibility(View.VISIBLE);
        } else {
            adapter.setItems(items);
        }
    }

    private Single<List<StreamInfoItem>> loadQuery(final int queryIndex) {
        return Single.fromCallable(() -> {
            final StreamingService service =
                    NewPipe.getService(ServiceList.YouTube.getServiceId());
            final List<FilterItem> contentFilter = Collections.singletonList(
                    service.getSearchQHFactory().getFilterItem(0)); // "all"
            final SearchInfo info = SearchInfo.getInfo(service,
                    service.getSearchQHFactory().fromQuery(
                            MUSIC_QUERIES[queryIndex], contentFilter,
                            Collections.emptyList()));
            final List<StreamInfoItem> out = new ArrayList<>();
            for (final InfoItem item : info.getRelatedItems()) {
                if (!(item instanceof StreamInfoItem)) {
                    continue;
                }
                final StreamInfoItem streamItem = (StreamInfoItem) item;
                if (ContentFilter.isPoliticsBlocked(
                        streamItem.getName(), streamItem.getUploaderName())) {
                    continue;
                }
                out.add(streamItem);
            }
            return out;
        });
    }
}
