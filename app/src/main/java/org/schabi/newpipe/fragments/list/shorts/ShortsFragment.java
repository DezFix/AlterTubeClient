package org.schabi.newpipe.fragments.list.shorts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentShortsBinding;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.list.shorts.ShortsPagerAdapter.ShortsPageHolder;
import org.schabi.newpipe.player.mediaitem.StreamInfoTag;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.resolver.PlaybackResolver;
import org.schabi.newpipe.util.ExtractorHelper;

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

    private static final String SHORTS_QUERY = "#shorts";
    /** YouTube allows shorts up to 3 minutes; unknown duration (-1) is also accepted. */
    private static final long MAX_SHORT_DURATION_SECONDS = 180;
    private static final String PREF_AUTO_ADVANCE = "shorts_auto_advance";

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
        adapter.setTapListener(position -> {
            if (player != null && position == currentPosition) {
                if (player.isPlaying()) {
                    player.pause();
                } else {
                    player.play();
                }
            }
        });
        binding.shortsPager.setAdapter(adapter);
        binding.shortsPager.setUserInputEnabled(true);
        binding.shortsPager.setOffscreenPageLimit(1);
        binding.shortsPager.registerOnPageChangeCallback(pageCallback);
        binding.shortsRetryButton.setOnClickListener(v -> loadFeed());

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

        loadFeed();
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

        // NB: the extractor requires a non-empty content filter ("all"),
        // otherwise YoutubeFilters throws. The handler is also built eagerly
        // (outside Rx), so guard the whole call against synchronous throws.
        final Single<SearchInfo> search;
        try {
            final StreamingService service =
                    NewPipe.getService(ServiceList.YouTube.getServiceId());
            final List<FilterItem> contentFilter = Collections.singletonList(
                    service.getSearchQHFactory().getFilterItem(0)); // "all"
            search = ExtractorHelper.searchFor(ServiceList.YouTube.getServiceId(),
                    SHORTS_QUERY, contentFilter, Collections.emptyList());
        } catch (final Exception e) {
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
    }

    private void onFeedLoaded(final SearchInfo searchInfo) {
        if (binding == null) {
            return;
        }
        binding.shortsLoading.setVisibility(View.GONE);

        final List<StreamInfoItem> shorts = new ArrayList<>();
        for (final InfoItem item : searchInfo.getRelatedItems()) {
            if (!(item instanceof StreamInfoItem)) {
                continue;
            }
            final StreamInfoItem streamItem = (StreamInfoItem) item;
            final String url = streamItem.getUrl() == null ? "" : streamItem.getUrl();
            final long duration = streamItem.getDuration();
            // "#shorts" results are mostly shorts; accept /shorts/ links,
            // items up to 3 minutes and items with unknown duration.
            final boolean looksLikeShort = url.contains("/shorts/")
                    || duration <= 0
                    || duration <= MAX_SHORT_DURATION_SECONDS;
            if (looksLikeShort) {
                shorts.add(streamItem);
            }
        }

        if (shorts.isEmpty()) {
            binding.shortsErrorBox.setVisibility(View.VISIBLE);
            return;
        }
        currentPosition = 0;
        infoCache.clear();
        adapter.setItems(shorts);
        binding.shortsPager.setCurrentItem(0, false);
        playPosition(0);
    }

    private void playPosition(final int position) {
        currentPosition = position;
        if (player == null || adapter == null
                || position < 0 || position >= adapter.getItemCount()) {
            return;
        }
        final int token = ++resolveToken;
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
        } catch (final Exception e) {
            if (position + 1 < adapter.getItemCount()
                    && token == resolveToken && position == currentPosition) {
                binding.shortsPager.setCurrentItem(position + 1, true);
            }
        }
    }

    @Nullable
    private static Stream pickStream(final StreamInfo info) {
        for (final VideoStream video : info.getVideoStreams()) {
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
