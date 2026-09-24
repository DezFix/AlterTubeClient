package org.schabi.newpipe.fragments.list.recommended;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.feed.model.FeedGroupEntity;
import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.stream.StreamWithState;
import org.schabi.newpipe.databinding.FragmentRecommendedBinding;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.feed.FeedDatabaseManager;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.ContentFilter;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.NavigationHelper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class RecommendedFragment extends Fragment {

    private static final int SEED_COUNT = 6;
    private static final int MAX_RELATED_PER_SEED = 20;
    private static final int MAX_SUBSCRIPTION_ITEMS = 10;
    private static final int MAX_ITEMS = 30;
    private static final int MAX_ITEMS_PER_CHANNEL = 2;
    private static final Set<String> STOP_WORDS = new HashSet<>(java.util.Arrays.asList(
            "видео", "смотреть", "онлайн", "новый", "новая", "новое", "новые",
            "часть", "выпуск", "обзор", "прохождение", "стрим", "клип",
            "video", "videos", "official", "shorts", "short", "live", "full",
            "episode", "part", "with", "from", "this", "that", "what", "when",
            "your", "about", "there", "their", "have", "best", "audio", "watch"
    ));

    private FragmentRecommendedBinding binding;
    private RecommendedAdapter adapter;
    private SerialDisposable loadDisposable = new SerialDisposable();

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
        loadDisposable = new SerialDisposable();
        adapter = new RecommendedAdapter();
        adapter.setClickListener(position -> {
            if (position < 0 || position >= adapter.getItemCount()) {
                return;
            }
            final StreamInfoItem item = adapter.getItem(position);
            NavigationHelper.openVideoDetailFragment(requireContext(),
                    getParentFragmentManager(), item.getServiceId(), item.getUrl(),
                    item.getName(), null, false);
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
        loadDisposable.dispose();
        binding.recommendedList.setAdapter(null);
        binding = null;
    }

    private void load() {
        final Context context = requireContext().getApplicationContext();
        binding.recommendedLoading.setVisibility(View.VISIBLE);
        binding.recommendedErrorBox.setVisibility(View.GONE);
        loadDisposable.set(buildRecommendations(context)
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
                    if (binding != null) {
                        binding.recommendedLoading.setVisibility(View.GONE);
                        binding.recommendedErrorBox.setVisibility(View.VISIBLE);
                    }
                }));
    }

    private Single<List<StreamInfoItem>> buildRecommendations(final Context context) {
        return Single.fromCallable(() -> {
            final List<StreamHistoryEntry> history;
            try {
                history = new HistoryRecordManager(context)
                        .getRecentStreamHistory()
                        .blockingFirst(new ArrayList<>());
            } catch (final Exception ignored) {
                return new ArrayList<>();
            }

            final Set<String> watchedUrls = new HashSet<>();
            final Set<String> watchedChannels = new HashSet<>();
            final Map<String, Integer> interestTokens = new HashMap<>();
            final List<StreamHistoryEntry> seeds = new ArrayList<>();
            for (int i = 0; i < history.size(); i++) {
                final StreamHistoryEntry entry = history.get(i);
                if (entry.getStreamEntity().getServiceId()
                        != ServiceList.YouTube.getServiceId()) {
                    continue;
                }
                if (seeds.size() < SEED_COUNT) {
                    seeds.add(entry);
                }
                final String url = entry.getStreamEntity().getUrl();
                if (url != null) {
                    watchedUrls.add(url);
                }
                final String channel = normalizeChannel(
                        entry.getStreamEntity().getUploaderUrl());
                if (channel != null) {
                    watchedChannels.add(channel);
                }
                final int weight = Math.max(1,
                        SEED_COUNT - Math.min(i, SEED_COUNT - 1));
                for (final String token : tokens(entry.getStreamEntity().getTitle())) {
                    interestTokens.put(token,
                            Math.min(12, interestTokens.getOrDefault(token, 0) + weight));
                }
            }

            final Map<String, ScoredItem> scored = new HashMap<>();
            addSubscriptionCandidates(context, watchedUrls, interestTokens, scored);
            if (scored.size() < MAX_ITEMS && !seeds.isEmpty()) {
                addRelatedCandidates(seeds, watchedUrls, watchedChannels,
                        interestTokens, scored);
            }
            if (scored.isEmpty()) {
                return new ArrayList<>();
            }

            final List<ScoredItem> ranked = new ArrayList<>(scored.values());
            ranked.sort(Comparator
                    .comparingInt((ScoredItem value) -> value.score).reversed()
                    .thenComparing(value -> value.uploadEpoch,
                            Comparator.nullsLast(Comparator.reverseOrder())));

            final List<StreamInfoItem> result = new ArrayList<>();
            final Map<String, Integer> channelCounts = new HashMap<>();
            final List<ScoredItem> deferred = new ArrayList<>();
            for (final ScoredItem value : ranked) {
                final String channel = channelKey(value.item);
                final int count = channelCounts.getOrDefault(channel, 0);
                if (count >= MAX_ITEMS_PER_CHANNEL) {
                    deferred.add(value);
                } else {
                    result.add(value.item);
                    channelCounts.put(channel, count + 1);
                    if (result.size() >= MAX_ITEMS) {
                        return result;
                    }
                }
            }
            for (final ScoredItem value : deferred) {
                if (result.size() >= MAX_ITEMS) {
                    break;
                }
                result.add(value.item);
            }
            return result;
        });
    }

    private void addSubscriptionCandidates(
            final Context context,
            final Set<String> watchedUrls,
            final Map<String, Integer> interestTokens,
            final Map<String, ScoredItem> scored) {
        try {
            final List<StreamWithState> streams = new FeedDatabaseManager(context)
                    .getStreams(FeedGroupEntity.GROUP_ALL_ID, false)
                    .blockingGet(new ArrayList<>());
            int added = 0;
            for (final StreamWithState stream : streams) {
                if (added >= MAX_SUBSCRIPTION_ITEMS) {
                    break;
                }
                if (stream.getStream().getServiceId() != ServiceList.YouTube.getServiceId()
                        || !isAllowedRecommendation(stream.getStream().getStreamType(),
                        stream.getStream().getDuration())) {
                    continue;
                }
                final StreamInfoItem item = stream.getStream().toStreamInfoItem();
                if (!isUsable(item, watchedUrls)) {
                    continue;
                }
                int score = 24;
                for (final String token : tokens(item.getName())) {
                    score += Math.min(4, interestTokens.getOrDefault(token, 0));
                }
                merge(scored, item, score, uploadEpoch(item));
                added++;
            }
        } catch (final Exception ignored) {
        }
    }

    private void addRelatedCandidates(
            final List<StreamHistoryEntry> seeds,
            final Set<String> watchedUrls,
            final Set<String> watchedChannels,
            final Map<String, Integer> interestTokens,
            final Map<String, ScoredItem> scored) {
        for (int seedIndex = 0; seedIndex < seeds.size(); seedIndex++) {
            final String seedUrl = seeds.get(seedIndex).getStreamEntity().getUrl();
            if (seedUrl == null || seedUrl.isEmpty()) {
                continue;
            }
            try {
                final StreamInfo info = ExtractorHelper.getNewStreamInfo(
                        ServiceList.YouTube.getServiceId(), seedUrl);
                int accepted = 0;
                for (final InfoItem relatedItem : info.getRelatedItems()) {
                    if (accepted >= MAX_RELATED_PER_SEED
                            || !(relatedItem instanceof StreamInfoItem)) {
                        continue;
                    }
                    final StreamInfoItem item = (StreamInfoItem) relatedItem;
                    if (!isUsable(item, watchedUrls)) {
                        continue;
                    }
                    int score = Math.max(4, 12 - seedIndex * 2);
                    int overlaps = 0;
                    for (final String token : tokens(item.getName())) {
                        if (interestTokens.containsKey(token)) {
                            overlaps++;
                        }
                    }
                    score += Math.min(12, overlaps * 3);
                    final String channel = normalizeChannel(item.getUploaderUrl());
                    if (channel != null && watchedChannels.contains(channel)) {
                        score += 4;
                    }
                    merge(scored, item, score, uploadEpoch(item));
                    accepted++;
                }
            } catch (final Exception ignored) {
            }
        }
    }

    private void merge(final Map<String, ScoredItem> scored,
                       final StreamInfoItem item,
                       final int score,
                       final Long uploadEpoch) {
        final ScoredItem previous = scored.get(item.getUrl());
        if (previous == null) {
            scored.put(item.getUrl(), new ScoredItem(item, score, uploadEpoch));
        } else {
            previous.score += score;
            if (previous.uploadEpoch == null
                    || uploadEpoch != null && uploadEpoch.isAfter(previous.uploadEpoch)) {
                previous.uploadEpoch = uploadEpoch;
            }
        }
    }

    private boolean isUsable(final StreamInfoItem item, final Set<String> watchedUrls) {
        if (item == null || item.getUrl() == null || item.getUrl().isEmpty()
                || watchedUrls.contains(item.getUrl())
                || item.isShortFormContent()
                || item.getUrl().contains("/shorts/")
                || !isAllowedRecommendation(item.getStreamType(), item.getDuration())
                || ContentFilter.isPoliticsBlocked(
                item.getName(), item.getUploaderName())) {
            return false;
        }
        return true;
    }

    private static boolean isAllowedRecommendation(final StreamType streamType,
                                                   final long duration) {
        return streamType == StreamType.VIDEO_STREAM && duration > 0;
    }

    private Long uploadEpoch(final StreamInfoItem item) {
        if (item.getUploadDate() == null) {
            return null;
        }
        final OffsetDateTime date = item.getUploadDate().offsetDateTime();
        return date.toInstant().toEpochMilli();
    }

    private String channelKey(final StreamInfoItem item) {
        final String channel = normalizeChannel(item.getUploaderUrl());
        return channel == null ? item.getUrl() : channel;
    }

    private String normalizeChannel(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.split("[?#]", 2)[0]
                .replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private Set<String> tokens(final String value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> result = new LinkedHashSet<>();
        for (final String token : value.toLowerCase(Locale.ROOT)
                .split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 4 && !STOP_WORDS.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private static final class ScoredItem {
        private final StreamInfoItem item;
        private int score;
        private Long uploadEpoch;

        private ScoredItem(final StreamInfoItem item, final int score,
                           final Long uploadEpoch) {
            this.item = item;
            this.score = score;
            this.uploadEpoch = uploadEpoch;
        }
    }
}
