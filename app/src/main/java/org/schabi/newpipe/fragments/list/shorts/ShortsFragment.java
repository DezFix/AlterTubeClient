package org.schabi.newpipe.fragments.list.shorts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentShortsBinding;
import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.list.shorts.ShortsPagerAdapter.ShortsPageHolder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.resolver.PlaybackResolver;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * TikTok-style vertical Shorts feed (YouTube only).
 * A single shared ExoPlayer is attached to the currently visible page;
 * streams are resolved through the regular extractor + resolver stack,
 * so cookies, PO tokens and headers keep working.
 */
public class ShortsFragment extends Fragment {

    private static final String[] GENERIC_QUERIES = {"#shorts", "#shorts video", "shorts"};
    /** Max personalized queries (subs + history) prepended before generic ones. */
    private static final int MAX_SUB_QUERIES = 4;
    private static final int MAX_HISTORY_QUERIES = 3;
    /** YouTube allows shorts up to 3 minutes; unknown duration (-1) is also accepted. */
    private static final long MAX_SHORT_DURATION_SECONDS = 180;
    private static final String PREF_AUTO_ADVANCE = "shorts_auto_advance";
    /** When this close to the tail, fetch the next page (TikTok-style endless feed). */
    private static final int PREFETCH_TAIL = 3;

    private FragmentShortsBinding binding;
    private ShortsPagerAdapter adapter;
    private final CompositeDisposable disposables = new CompositeDisposable();

    private ExoPlayer player;
    private PlayerDataSource dataSource;
    private final Map<Integer, StreamInfo> infoCache = new HashMap<>();

    private int currentPosition = 0;
    private int resolveToken = 0;
    private boolean muted = false;
    private boolean autoAdvance = false;
    private ShortsFeedViewModel feedModel;

    // Endless-feed state: walk queries in order, paging inside each one.
    private int queryIndex = 0;
    private Page nextPage;
    private boolean loadingMore = false;
    private final java.util.Set<String> seenUrls = new java.util.HashSet<>();
    private List<String> feedQueries = new ArrayList<>();
    private final ViewPager2.OnPageChangeCallback pageCallback =
            new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(final int position) {
                    playPosition(position);
                }
            };

    public static ShortsFragment newInstance() {
        return new ShortsFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentShortsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new ShortsPagerAdapter();
        adapter.setTapListener(new ShortsPagerAdapter.PageTapListener() {
            @Override
            public void onPageTap(final int position) {
                if (player != null && position == currentPosition) {
                    if (player.isPlaying()) {
                        player.pause();
                    } else {
                        player.play();
                    }
                }
            }

            @Override
            public void onShareClick(final int position) {
                if (adapter == null || position < 0 || position >= adapter.getItemCount()) {
                    return;
                }
                final StreamInfoItem item = adapter.getItem(position);
                ShareUtils.shareText(requireContext(), item.getName(), item.getUrl());
            }
        });
        binding.shortsPager.setAdapter(adapter);
        binding.shortsPager.setUserInputEnabled(true);
        binding.shortsPager.setOffscreenPageLimit(1);
        binding.shortsPager.registerOnPageChangeCallback(pageCallback);
        binding.shortsRetryButton.setOnClickListener(v -> loadFeed());

        feedModel = new ViewModelProvider(requireActivity()).get(ShortsFeedViewModel.class);
        autoAdvance = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(requireContext())
                .getBoolean(PREF_AUTO_ADVANCE, false);
        binding.shortsMuteButton.setOnClickListener(v -> toggleMute());
        binding.shortsAutoadvanceButton.setOnClickListener(v -> toggleAutoAdvance());
        updateButtons();

        dataSource = new PlayerDataSource(requireContext(), DownloaderImpl.USER_AGENT,
                new DefaultBandwidthMeter.Builder(requireContext()).build());
        player = new ExoPlayer.Builder(requireContext()).build();
        applyRepeatMode();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(final int state) {
                final ShortsPageHolder holder = currentHolder();
                if (holder == null) {
                    return;
                }
                if (state == Player.STATE_READY) {
                    holder.loading.setVisibility(View.GONE);
                    holder.thumbnail.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    holder.loading.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_ENDED && autoAdvance
                        && binding != null && adapter != null
                        && currentPosition + 1 < adapter.getItemCount()) {
                    binding.shortsPager.setCurrentItem(currentPosition + 1, true);
                }
            }
        });

        if (!feedModel.getItems().isEmpty()) {
            // View recreated (rotation, tab rebuild): resume cached feed, no reload.
            // Rebuild dedupe set so pagination continues without duplicates.
            for (final StreamInfoItem cachedItem : feedModel.getItems()) {
                if (cachedItem.getUrl() != null) {
                    seenUrls.add(cachedItem.getUrl());
                }
            }
            adapter.setItems(feedModel.getItems());
            final int restore = Math.min(feedModel.getPosition(), adapter.getItemCount() - 1);
            binding.shortsPager.setCurrentItem(restore, false);
            playPosition(restore);
        } else {
            loadFeed();
        }
    }

    private void toggleMute() {
        muted = !muted;
        if (player != null) {
            player.setVolume(muted ? 0f : 1f);
        }
        updateButtons();
    }

    private void toggleAutoAdvance() {
        autoAdvance = !autoAdvance;
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putBoolean(PREF_AUTO_ADVANCE, autoAdvance).apply();
        applyRepeatMode();
        updateButtons();
    }

    private void applyRepeatMode() {
        if (player != null) {
            player.setRepeatMode(autoAdvance ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
        }
    }

    private void updateButtons() {
        if (binding == null) {
            return;
        }
        binding.shortsMuteButton.setImageResource(
                muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
        binding.shortsAutoadvanceButton.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        autoAdvance ? 0xFFFFFFFF : 0x80FFFFFF));
    }

    @Override
    public void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (player != null && adapter != null && adapter.getItemCount() > 0 && !player.isPlaying()) {
            player.play();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        resolveToken++;
        disposables.clear();
        if (player != null) {
            player.release();
            player = null;
        }
        binding.shortsPager.unregisterOnPageChangeCallback(pageCallback);
        binding.shortsPager.setAdapter(null);
        binding = null;
    }

    private void loadFeed() {
        binding.shortsLoading.setVisibility(View.VISIBLE);
        binding.shortsErrorBox.setVisibility(View.GONE);
        queryIndex = 0;
        nextPage = null;
        loadingMore = false;
        seenUrls.clear();
        infoCache.clear();
        currentPosition = 0;

        // Personalized queries first (subs + history), generic fallback after.
        disposables.add(buildTargetedQueries()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(queries -> {
                    if (binding == null) {
                        return;
                    }
                    feedQueries = queries;
                    final Single<SearchInfo> search = buildFirstPageSingle();
                    if (search == null) {
                        binding.shortsLoading.setVisibility(View.GONE);
                        binding.shortsErrorBox.setVisibility(View.VISIBLE);
                        return;
                    }
                    disposables.add(search
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(this::onFeedLoaded, throwable -> {
                                if (binding == null) {
                                    return;
                                }
                                binding.shortsLoading.setVisibility(View.GONE);
                                binding.shortsErrorBox.setVisibility(View.VISIBLE);
                            }));
                }, throwable -> {
                    if (binding == null) {
                        return;
                    }
                    binding.shortsLoading.setVisibility(View.GONE);
                    binding.shortsErrorBox.setVisibility(View.VISIBLE);
                }));
    }

    /**
     * Targeted feed queries: subscribed channels and recently watched uploaders
     * first (e.g. a gamer gets gaming shorts), generic queries as fallback.
     */
    private Single<List<String>> buildTargetedQueries() {
        return Single.fromCallable(() -> {
            final java.util.LinkedHashSet<String> queries = new java.util.LinkedHashSet<>();
            final android.content.Context ctx =
                    requireContext().getApplicationContext();
            // 1) subscribed YouTube channels
            try {
                final List<SubscriptionEntity> subs = new SubscriptionManager(ctx)
                        .subscriptionTable().getAll()
                        .blockingFirst(new ArrayList<>());
                int taken = 0;
                for (final SubscriptionEntity sub : subs) {
                    if (taken >= MAX_SUB_QUERIES) {
                        break;
                    }
                    if (sub.getServiceId() != ServiceList.YouTube.getServiceId()) {
                        continue;
                    }
                    final String name = sub.getName() == null ? "" : sub.getName().trim();
                    if (!name.isEmpty()) {
                        queries.add(name + " shorts");
                        taken++;
                    }
                }
            } catch (final Exception ignored) {
                // no subs or db unavailable: generic feed below
            }
            // 2) uploaders from recent watch history (newest first)
            try {
                final List<StreamHistoryEntry> history = new HistoryRecordManager(ctx)
                        .getStreamHistorySortedById()
                        .blockingFirst(new ArrayList<>());
                int taken = 0;
                int scanned = 0;
                for (int i = history.size() - 1;
                        i >= 0 && scanned < 15 && taken < MAX_HISTORY_QUERIES; i--, scanned++) {
                    final StreamEntity stream = history.get(i).getStreamEntity();
                    if (stream.getServiceId() != ServiceList.YouTube.getServiceId()) {
                        continue;
                    }
                    final String uploader =
                            stream.getUploader() == null ? "" : stream.getUploader().trim();
                    if (!uploader.isEmpty() && queries.add(uploader + " shorts")) {
                        taken++;
                    }
                }
            } catch (final Exception ignored) {
                // no history: generic feed below
            }
            // 3) generic fallback (also covers fresh installs)
            Collections.addAll(queries, GENERIC_QUERIES);
            return new ArrayList<>(queries);
        });
    }

    @Nullable
    private Single<SearchInfo> buildFirstPageSingle() {
        // NB: the extractor requires a non-empty content filter ("all"),
        // otherwise YoutubeFilters throws. The handler is also built eagerly
        // (outside Rx), so guard the whole call against synchronous throws.
        try {
            return ExtractorHelper.searchFor(ServiceList.YouTube.getServiceId(),
                    feedQueries.get(queryIndex), allFilter(), Collections.emptyList());
        } catch (final Exception e) {
            return null;
        }
    }

    private List<FilterItem> allFilter() {
        try {
            final StreamingService service =
                    NewPipe.getService(ServiceList.YouTube.getServiceId());
            return Collections.singletonList(
                    service.getSearchQHFactory().getFilterItem(0)); // "all"
        } catch (final Exception e) {
            return Collections.emptyList();
        }
    }

    private void onFeedLoaded(final SearchInfo searchInfo) {
        if (binding == null) {
            return;
        }
        binding.shortsLoading.setVisibility(View.GONE);

        final List<StreamInfoItem> shorts = filterShorts(searchInfo.getRelatedItems());
        nextPage = searchInfo.hasNextPage() ? searchInfo.getNextPage() : null;

        if (shorts.isEmpty()) {
            // First query gave nothing usable: try the next one.
            loadNextQueryOrFail();
            return;
        }
        adapter.setItems(shorts);
        feedModel.replaceItems(shorts);
        binding.shortsPager.setCurrentItem(0, false);
        playPosition(0);
    }

    /** Loads the next page of the current query, or moves to the next query. */
    private void loadMore() {
        if (loadingMore || binding == null || adapter == null) {
            return;
        }
        if (nextPage != null) {
            loadingMore = true;
            final Page page = nextPage;
            final int qIndex = queryIndex;
            disposables.add(ExtractorHelper
                    .getMoreSearchItems(ServiceList.YouTube.getServiceId(),
                            feedQueries.get(qIndex), allFilter(),
                            Collections.emptyList(), page)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            infoPage -> {
                                loadingMore = false;
                                if (binding == null || adapter == null) {
                                    return;
                                }
                                nextPage = infoPage.hasNextPage()
                                        ? infoPage.getNextPage() : null;
                                final List<StreamInfoItem> more =
                                        filterShorts(infoPage.getItems());
                                if (!more.isEmpty()) {
                                    adapter.addItems(more);
                                    feedModel.appendItems(more);
                                } else if (nextPage == null) {
                                    advanceQuery();
                                }
                            },
                            throwable -> loadingMore = false));
        } else {
            advanceQuery();
        }
    }

    private void advanceQuery() {
        if (loadingMore) {
            return;
        }
        if (queryIndex + 1 >= feedQueries.size()) {
            return; // out of queries: feed simply ends
        }
        loadingMore = true;
        queryIndex++;
        nextPage = null;
        final Single<SearchInfo> search = buildFirstPageSingle();
        if (search == null) {
            loadingMore = false;
            return;
        }
        disposables.add(search
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        searchInfo -> {
                            loadingMore = false;
                            if (binding == null || adapter == null) {
                                return;
                            }
                            nextPage = searchInfo.hasNextPage()
                                    ? searchInfo.getNextPage() : null;
                            final List<StreamInfoItem> more =
                                    filterShorts(searchInfo.getRelatedItems());
                            if (!more.isEmpty()) {
                                adapter.addItems(more);
                                feedModel.appendItems(more);
                            }
                        },
                        throwable -> loadingMore = false));
    }

    private void loadNextQueryOrFail() {
        if (queryIndex + 1 >= feedQueries.size()) {
            binding.shortsErrorBox.setVisibility(View.VISIBLE);
            return;
        }
        queryIndex++;
        nextPage = null;
        final Single<SearchInfo> search = buildFirstPageSingle();
        if (search == null) {
            binding.shortsErrorBox.setVisibility(View.VISIBLE);
            return;
        }
        disposables.add(search
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onFeedLoaded, throwable -> {
                    if (binding == null) {
                        return;
                    }
                    binding.shortsLoading.setVisibility(View.GONE);
                    binding.shortsErrorBox.setVisibility(View.VISIBLE);
                }));
    }

    private List<StreamInfoItem> filterShorts(final List<? extends InfoItem> rawItems) {
        final List<StreamInfoItem> shorts = new ArrayList<>();
        if (rawItems == null) {
            return shorts;
        }
        for (final InfoItem item : rawItems) {
            if (!(item instanceof StreamInfoItem)) {
                continue;
            }
            final StreamInfoItem streamItem = (StreamInfoItem) item;
            final String url = streamItem.getUrl() == null ? "" : streamItem.getUrl();
            if (url.isEmpty() || !seenUrls.add(url)) {
                continue; // no url or already in feed
            }
            final long duration = streamItem.getDuration();
            // Query results are shorts-biased; accept /shorts/ links,
            // items up to 3 minutes and items with unknown duration.
            final boolean looksLikeShort = url.contains("/shorts/")
                    || duration <= 0
                    || duration <= MAX_SHORT_DURATION_SECONDS;
            if (looksLikeShort) {
                shorts.add(streamItem);
            }
        }
        return shorts;
    }

    private void playPosition(final int position) {
        currentPosition = position;
        if (feedModel != null) {
            feedModel.setPosition(position);
        }
        if (player == null || adapter == null
                || position < 0 || position >= adapter.getItemCount()) {
            return;
        }
        final int token = ++resolveToken;
        // Endless feed: fetch more while the user approaches the tail.
        if (adapter != null && position >= adapter.getItemCount() - PREFETCH_TAIL) {
            loadMore();
        }
        final StreamInfo cached = infoCache.get(position);
        if (cached != null) {
            openStream(position, token, cached);
            return;
        }

        final ShortsPageHolder holder = holderAt(position);
        if (holder != null) {
            holder.loading.setVisibility(View.VISIBLE);
        }
        final String url = adapter.getItem(position).getUrl();
        if (url == null || url.isEmpty()) {
            if (position + 1 < adapter.getItemCount()) {
                binding.shortsPager.setCurrentItem(position + 1, true);
            }
            return;
        }
        disposables.add(ExtractorHelper
                .getStreamInfo(ServiceList.YouTube.getServiceId(), url, false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        info -> {
                            infoCache.put(position, info);
                            if (token == resolveToken && position == currentPosition) {
                                openStream(position, token, info);
                            }
                        },
                        throwable -> {
                            if (binding == null
                                    || token != resolveToken || position != currentPosition) {
                                return;
                            }
                            // Skip unplayable items automatically.
                            if (position + 1 < adapter.getItemCount()) {
                                binding.shortsPager.setCurrentItem(position + 1, true);
                            }
                        }));
    }

    private void openStream(final int position, final int token, final StreamInfo info) {
        if (player == null || binding == null) {
            return;
        }
        final Stream stream = pickStream(info);
        if (stream == null) {
            if (position + 1 < adapter.getItemCount()) {
                binding.shortsPager.setCurrentItem(position + 1, true);
            }
            return;
        }
        try {
            final MediaSource source = PlaybackResolver.buildMediaSource(
                    dataSource, stream, info, info.getUrl(), StreamInfoTag.of(info));
            if (token != resolveToken || position != currentPosition) {
                return;
            }
            final ShortsPageHolder holder = holderAt(position);
            if (holder == null) {
                return;
            }
            holder.thumbnail.setVisibility(View.VISIBLE);
            holder.loading.setVisibility(View.VISIBLE);
            holder.attachPlayer(player);
            player.setMediaSource(source);
            player.prepare();
            player.play();
            // Pre-resolve the next pages (current + next + next, like TikTok),
            // so swiping ahead plays instantly.
            resolveAhead(position + 1);
            resolveAhead(position + 2);
        } catch (final Exception e) {
            if (position + 1 < adapter.getItemCount()
                    && token == resolveToken && position == currentPosition) {
                binding.shortsPager.setCurrentItem(position + 1, true);
            }
        }
    }

    /** Resolves a page ahead in background so swiping plays instantly. */
    private void resolveAhead(final int position) {
        if (adapter == null || position < 0 || position >= adapter.getItemCount()
                || infoCache.containsKey(position)) {
            return;
        }
        final String url = adapter.getItem(position).getUrl();
        if (url == null || url.isEmpty()) {
            return;
        }
        disposables.add(ExtractorHelper
                .getStreamInfo(ServiceList.YouTube.getServiceId(), url, false)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        info -> infoCache.put(position, info),
                        throwable -> { /* will resolve on arrival */ }));
    }

    @Nullable
    private static Stream pickStream(final StreamInfo info) {        for (final VideoStream video : info.getVideoStreams()) {
            if (!video.isVideoOnly()) {
                return video;
            }
        }
        if (!info.getVideoStreams().isEmpty()) {
            return info.getVideoStreams().get(0);
        }
        if (!info.getAudioStreams().isEmpty()) {
            return info.getAudioStreams().get(0);
        }
        return null;
    }

    @Nullable
    private ShortsPageHolder holderAt(final int position) {
        if (binding == null || binding.shortsPager.getChildCount() == 0) {
            return null;
        }
        // ViewPager2 hosts a single internal RecyclerView.
        final View child = binding.shortsPager.getChildAt(0);
        if (!(child instanceof RecyclerView)) {
            return null;
        }
        final RecyclerView.ViewHolder holder =
                ((RecyclerView) child).findViewHolderForAdapterPosition(position);
        return holder instanceof ShortsPageHolder ? (ShortsPageHolder) holder : null;
    }

    @Nullable
    private ShortsPageHolder currentHolder() {
        return holderAt(currentPosition);
    }
}
