package org.schabi.newpipe.fragments.list.shorts;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.analytics.PlayerId;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.databinding.FragmentShortsBinding;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.list.shorts.ShortsPagerAdapter.ShortsPageHolder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.player.helper.AudioReactor;
import org.schabi.newpipe.player.helper.CustomRenderersFactory;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.resolver.QualityResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.util.ContentFilter;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PicassoHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class ShortsFragment extends Fragment {

    private static final String[] GENERIC_QUERIES = {
            "#shorts", "shorts gaming", "shorts music", "shorts technology"
    };
    private static final float[] PLAYBACK_SPEEDS = {
            0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f
    };
    private static final int MAX_CHANNEL_SOURCES = 24;
    private static final int MAX_SEARCH_SOURCES = 6;
    private static final long MAX_SHORT_DURATION_SECONDS = 180L;
    private static final int PREFETCH_TAIL = 3;
    private static final int MAX_CACHED_STREAM_INFOS = 12;
    private static final int MENU_OPEN_AS_VIDEO = 1;
    private static final int MENU_COPY_LINK = 2;
    private static final int MENU_AUTO_ADVANCE = 3;
    private static final int MENU_QUALITY_AUTO = 10;
    private static final int MENU_QUALITY_BASE = 100;
    private static final int MENU_SPEED_BASE = 1000;
    private static final int MENU_RESIZE_FIT = 2000;
    private static final int MENU_RESIZE_ZOOM = 2001;
    private static final String PREF_AUTO_ADVANCE = "shorts_auto_advance";
    private static final String PREF_MUTED = "shorts_muted";
    private static final String PREF_ZOOM = "shorts_zoom";
    private static final String PREF_SPEED = "shorts_playback_speed";

    private FragmentShortsBinding binding;
    private ShortsPagerAdapter adapter;
    private ShortsFeedViewModel feedModel;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final LruCache<Integer, StreamInfo> infoCache =
            new LruCache<>(MAX_CACHED_STREAM_INFOS);
    private final Map<Integer, ResolveRequest> resolvingPositions = new ConcurrentHashMap<>();
    private final Set<Integer> viewedPositions = Collections.synchronizedSet(new HashSet<>());
    private final Map<Integer, Integer> errorAttempts = new HashMap<>();
    private final Set<String> seenUrls = new HashSet<>();
    private final SparseArray<VideoStream> qualitySelections = new SparseArray<>();

    private ExoPlayer player;
    private AudioReactor audioReactor;
    private PlayerDataSource dataSource;
    private VideoPlaybackResolver videoResolver;
    private HistoryRecordManager historyManager;
    private int currentPosition = -1;
    private int playerPosition = -1;
    private int openingToken = -1;
    private int playbackToken = -1;
    private int sourceIndex;
    private int resolveToken;
    private int feedGeneration;
    private boolean loadingMore;
    private boolean userPaused;
    private boolean playRequested;
    private boolean resumeWhenVisible;
    private boolean playerReloadRequired;
    private boolean firstFrameRendered;
    private boolean resumed;
    private boolean muted;
    private boolean zoom;
    private boolean autoAdvance;
    private boolean feedPageRetry;
    private float playbackSpeed = 1f;
    private String selectedResolution;
    private String selectedCodec;
    private Runnable advanceRunnable;

    private static final class ResolveRequest {
        private final int generation;
        private final int token;
        private final boolean force;
        private boolean openWhenCurrent;

        private ResolveRequest(final int generation, final int token, final boolean force) {
            this.generation = generation;
            this.token = token;
            this.force = force;
        }
    }

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
        feedModel = new ViewModelProvider(requireActivity()).get(ShortsFeedViewModel.class);

        adapter = new ShortsPagerAdapter();
        adapter.setTapListener(new ShortsPagerAdapter.PageTapListener() {
            @Override
            public void onPageTap(final int position) {
                togglePlayPause();
            }

            @Override
            public void onPageSeek(final int position, final float value,
                                   final boolean relative) {
                seekCurrent(position, value, relative);
            }

            @Override
            public void onShareClick(final int position) {
                shareCurrent();
            }

            @Override
            public void onCommentsClick(final int position) {
                openComments(position);
            }

            @Override
            public void onSubscribeClick(final int position) {
                toggleSubscribe(position);
            }

            @Override
            public void onRetryClick(final int position) {
                if (feedPageRetry) {
                    feedPageRetry = false;
                    loadMore();
                } else {
                    userPaused = false;
                    playRequested = true;
                    retryCurrent(true);
                }
            }
        });
        binding.shortsPager.setAdapter(adapter);
        binding.shortsPager.setUserInputEnabled(true);
        binding.shortsPager.setOffscreenPageLimit(1);
        binding.shortsPager.registerOnPageChangeCallback(pageCallback);
        binding.shortsRetryButton.setOnClickListener(v -> loadFeed());
        binding.shortsCloseButton.setOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        binding.shortsPlayButton.setOnClickListener(v -> togglePlayPause());
        binding.shortsMuteButton.setOnClickListener(v -> toggleMute());
        binding.shortsMenuButton.setOnClickListener(v -> showMenu());

        final Context context = requireContext().getApplicationContext();
        final android.content.SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        muted = feedModel.getItems().isEmpty()
                ? preferences.getBoolean(PREF_MUTED, false) : feedModel.isMuted();
        zoom = feedModel.getItems().isEmpty()
                ? preferences.getBoolean(PREF_ZOOM, false) : feedModel.isZoom();
        autoAdvance = feedModel.getItems().isEmpty()
                ? preferences.getBoolean(PREF_AUTO_ADVANCE, false) : feedModel.isAutoAdvance();
        playbackSpeed = preferences.getFloat(PREF_SPEED, 1f);
        feedModel.setMuted(muted);
        feedModel.setZoom(zoom);
        feedModel.setAutoAdvance(autoAdvance);

        dataSource = new PlayerDataSource(context, DownloaderImpl.USER_AGENT,
                new DefaultBandwidthMeter.Builder(context).build());
        videoResolver = new VideoPlaybackResolver(context, dataSource, getQualityResolver());
        historyManager = new HistoryRecordManager(context);
        final DefaultRenderersFactory renderFactory = preferences.getBoolean(
                context.getString(R.string.always_use_exoplayer_set_output_surface_workaround_key),
                false)
                ? new CustomRenderersFactory(context) : new DefaultRenderersFactory(context);
        if (preferences.getBoolean(
                context.getString(R.string.disable_exoplayer_media_codec_async_queueing_key), false)) {
            renderFactory.forceDisableMediaCodecAsynchronousQueueing();
        }
        renderFactory.setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(context, renderFactory)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), false)
                .build();
        binding.shortsPlayerView.setPlayer(player);
        applyZoomMode();
        audioReactor = new AudioReactor(context, player);
        player.setSeekParameters(PlayerHelper.getSeekParameters(context));
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.setPlaybackSpeed(playbackSpeed);
        player.setVolume(muted ? 0f : 1f);
        applyRepeatMode();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(final int state) {
                ShortsFragment.this.onPlayerStateChanged(state);
            }

            @Override
            public void onIsPlayingChanged(final boolean isPlaying) {
                updateTopBar();
                updateKeepScreenOn();
                if (isPlaying) {
                    if (firstFrameRendered) {
                        hideCurrentThumbnail();
                    }
                    if (playbackToken == resolveToken) {
                        recordCurrentView();
                    }
                    startProgressUpdates();
                } else {
                    stopProgressUpdates();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (playerPosition == currentPosition && playbackToken == resolveToken) {
                    firstFrameRendered = true;
                    hideCurrentThumbnail();
                }
            }

            @Override
            public void onPlayWhenReadyChanged(final boolean playWhenReady, final int reason) {
                if (playWhenReady && (!resumed || !playRequested
                        || playerPosition != currentPosition || playbackToken != resolveToken)) {
                    pausePlayback();
                } else if (!playWhenReady
                        && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                        && audioReactor != null) {
                    audioReactor.abandonAudioFocus();
                }
                updateTopBar();
                updateKeepScreenOn();
            }

            @Override
            public void onVolumeChanged(final float volume) {
                if (muted && volume != 0f && player != null) {
                    player.setVolume(0f);
                }
            }

            @Override
            public void onPlayerError(@NonNull final PlaybackException error) {
                handlePlayerError();
            }
        });

        if (!feedModel.getItems().isEmpty() && !feedModel.getSources().isEmpty()) {
            restoreFeed();
        } else {
            loadFeed();
        }
    }

    private QualityResolver getQualityResolver() {
        final Context context = requireContext().getApplicationContext();
        return new QualityResolver() {
            @Override
            public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
                return ListHelper.getDefaultResolutionIndex(context, sortedVideos);
            }

            @Override
            public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                                  final String selectedResolution,
                                                  @Nullable final String selectedCodec) {
                return ListHelper.getResolutionAndCodecIndex(
                        selectedResolution, selectedCodec, sortedVideos);
            }

            @Override
            public int getCurrentAudioQualityIndex(final List<AudioStream> audioStreams) {
                return ListHelper.getDefaultAudioFormat(context, audioStreams);
            }
        };
    }

    private void restoreFeed() {
        for (final StreamInfoItem item : feedModel.getItems()) {
            if (item.getUrl() != null) {
                seenUrls.add(item.getUrl());
            }
        }
        sourceIndex = Math.min(feedModel.getSourceIndex(),
                Math.max(0, feedModel.getSources().size() - 1));
        adapter.setItems(feedModel.getItems());
        final int restore = Math.min(feedModel.getPosition(), adapter.getItemCount() - 1);
        applyZoomMode();
        binding.shortsPager.setCurrentItem(restore, false);
        playPosition(restore);
    }

    private void togglePlayPause() {
        if (player == null) {
            return;
        }
        if (playerReloadRequired) {
            userPaused = false;
            playRequested = true;
            reloadPlayerIfNeeded();
            return;
        }
        final ShortsPageHolder holder = currentHolder();
        final boolean currentSource = playerPosition == currentPosition
                && playbackToken == resolveToken;
        if (!currentSource && playerPosition == currentPosition
                && player.getPlayerError() != null) {
            userPaused = false;
            playRequested = true;
            retryCurrent(true);
            return;
        }
        final boolean shouldPause = currentSource && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED;
        if (shouldPause) {
            userPaused = true;
            playRequested = false;
            pausePlayback();
            if (holder != null) {
                holder.showPlayIndicator(false);
            }
        } else {
            userPaused = false;
            playRequested = true;
            if (!currentSource) {
                pausePlayback();
            }
            if (resumed && currentSource) {
                if (player.getPlaybackState() == Player.STATE_ENDED) {
                    player.seekTo(0L);
                }
                if (audioReactor != null) {
                    audioReactor.requestAudioFocus();
                }
                player.play();
            }
            if (holder != null) {
                holder.showPlayIndicator(true);
            }
        }
        updateTopBar();
        updateKeepScreenOn();
    }

    private void pausePlayback() {
        if (player != null) {
            player.pause();
        }
        if (audioReactor != null) {
            audioReactor.abandonAudioFocus();
        }
    }

    private boolean shouldKeepScreenOn() {
        return resumed && playRequested && !userPaused && player != null
                && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED;
    }

    private void updateKeepScreenOn() {
        final ShortsPageHolder holder = currentHolder();
        if (holder != null) {
            holder.setKeepScreenOn(shouldKeepScreenOn());
        }
    }

    private void toggleMute() {
        muted = !muted;
        feedModel.setMuted(muted);
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putBoolean(PREF_MUTED, muted).apply();
        if (player != null) {
            player.setVolume(muted ? 0f : 1f);
        }
        updateTopBar();
    }

    private void toggleAutoAdvance() {
        autoAdvance = !autoAdvance;
        feedModel.setAutoAdvance(autoAdvance);
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putBoolean(PREF_AUTO_ADVANCE, autoAdvance).apply();
        applyRepeatMode();
    }

    private void applyRepeatMode() {
        if (player != null) {
            player.setRepeatMode(autoAdvance
                    ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
        }
    }

    private void updateTopBar() {
        if (binding == null) {
            return;
        }
        binding.shortsPlayButton.setImageResource(
                player != null && player.getPlayWhenReady()
                        && player.getPlaybackState() != Player.STATE_ENDED
                        ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        binding.shortsMuteButton.setImageResource(
                muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
    }

    private void shareCurrent() {
        if (!isValidPosition(currentPosition)) {
            return;
        }
        final StreamInfoItem item = adapter.getItem(currentPosition);
        ShareUtils.shareText(requireContext(), item.getName(), item.getUrl());
    }

    private void openComments(final int position) {
        if (!isValidPosition(position)) {
            return;
        }
        final StreamInfoItem item = adapter.getItem(position);
        ShortsCommentsSheet.newInstance(item.getUrl(), item.getName())
                .show(getChildFragmentManager(), "shorts_comments");
    }

    private void toggleSubscribe(final int position) {
        if (!isValidPosition(position)) {
            return;
        }
        final StreamInfoItem item = adapter.getItem(position);
        final String channelUrl = item.getUploaderUrl();
        if (channelUrl == null || channelUrl.isEmpty()) {
            return;
        }
        final Context context = requireContext().getApplicationContext();
        final int serviceId = ServiceList.YouTube.getServiceId();
        final String name = item.getUploaderName() == null ? "" : item.getUploaderName();
        final StreamInfo cached = infoCache.get(position);
        final String avatar = cached == null || cached.getUploaderAvatarUrl() == null
                ? null : cached.getUploaderAvatarUrl();
        disposables.add(Single.fromCallable(() -> {
                    final SubscriptionManager manager = new SubscriptionManager(context);
                    final boolean subscribed = manager.subscriptionTable()
                            .getSubscription(serviceId, channelUrl)
                            .blockingGet() != null;
                    if (subscribed) {
                        manager.deleteSubscription(serviceId, channelUrl).blockingAwait();
                        return false;
                    }
                    final SubscriptionEntity entity = new SubscriptionEntity();
                    entity.setServiceId(serviceId);
                    entity.setUrl(channelUrl);
                    entity.setName(name);
                    if (avatar != null) {
                        entity.setAvatarUrl(avatar);
                    }
                    manager.subscriptionTable().insert(entity);
                    return true;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(subscribed -> {
                    final ShortsPageHolder holder = holderAt(position);
                    if (holder != null
                            && holder.getBindingAdapterPosition() == position) {
                        holder.subscribeButton.setText(subscribed
                                ? R.string.shorts_subscribed : R.string.shorts_subscribe);
                    }
                    Toast.makeText(requireContext(), subscribed
                                    ? R.string.shorts_subscribed_toast
                                    : R.string.shorts_unsubscribed_toast,
                            Toast.LENGTH_SHORT).show();
                }, throwable -> Toast.makeText(requireContext(),
                        R.string.error_snackbar_message, Toast.LENGTH_SHORT).show()));
    }

    private void refreshSubscribeState(final ShortsPageHolder holder,
                                       final int position,
                                       final String channelUrl) {
        holder.subscribeButton.setText(R.string.shorts_subscribe);
        if (channelUrl == null || channelUrl.isEmpty()) {
            return;
        }
        final Context context = requireContext().getApplicationContext();
        final int serviceId = ServiceList.YouTube.getServiceId();
        disposables.add(Single.fromCallable(() ->
                        new SubscriptionManager(context)
                                .subscriptionTable()
                                .getSubscription(serviceId, channelUrl)
                                .blockingGet() != null)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(subscribed -> {
                    if (holder.getBindingAdapterPosition() == position) {
                        holder.subscribeButton.setText(subscribed
                                ? R.string.shorts_subscribed : R.string.shorts_subscribe);
                    }
                }, throwable -> {
                    if (holder.getBindingAdapterPosition() == position) {
                        holder.subscribeButton.setText(R.string.shorts_subscribe);
                    }
                }));
    }

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if (player != null && binding != null
                    && playerPosition == currentPosition && playbackToken == resolveToken
                    && player.isPlaying()) {
                final ShortsPageHolder holder = currentHolder();
                if (holder != null && !holder.seeking) {
                    final long duration = player.getDuration();
                    if (duration > 0) {
                        holder.progress.setProgress(
                                (int) (1000L * player.getCurrentPosition() / duration));
                    }
                }
                startProgressUpdates();
            }
        }
    };

    private void startProgressUpdates() {
        stopProgressUpdates();
        if (binding != null) {
            binding.getRoot().postDelayed(progressUpdater, 500L);
        }
    }

    private void stopProgressUpdates() {
        if (binding != null) {
            binding.getRoot().removeCallbacks(progressUpdater);
        }
    }

    private void seekCurrent(final int position, final float value,
                             final boolean relative) {
        if (player == null || position != currentPosition || playerPosition != position) {
            return;
        }
        final long duration = player.getDuration();
        if (duration <= 0) {
            return;
        }
        final long target = relative
                ? player.getCurrentPosition() + Math.round(value * 10_000L)
                : Math.round(duration * Math.max(0f, Math.min(1f, value)));
        player.seekTo(Math.max(0L, Math.min(duration, target)));
    }

    private void showMenu() {
        if (getContext() == null || !isValidPosition(currentPosition)) {
            return;
        }
        final StreamInfoItem item = adapter.getItem(currentPosition);
        final androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(requireContext(),
                        binding.shortsMenuButton);
        menu.getMenu().add(0, MENU_OPEN_AS_VIDEO, 0, R.string.shorts_open_as_video);
        menu.getMenu().add(0, MENU_COPY_LINK, 1, R.string.shorts_copy_link);
        menu.getMenu().add(0, MENU_AUTO_ADVANCE, 2, R.string.shorts_autoadvance)
                .setCheckable(true).setChecked(autoAdvance);

        final SubMenu speedMenu = menu.getMenu().addSubMenu(
                0, MENU_SPEED_BASE, 3, R.string.shorts_playback_speed);
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            final float speed = PLAYBACK_SPEEDS[i];
            speedMenu.add(0, MENU_SPEED_BASE + i + 1, i, formatSpeed(speed))
                    .setCheckable(true)
                    .setChecked(Math.abs(playbackSpeed - speed) < 0.001f);
        }

        final SubMenu resizeMenu = menu.getMenu().addSubMenu(
                0, MENU_RESIZE_FIT, 4, R.string.shorts_resize_mode);
        resizeMenu.add(0, MENU_RESIZE_FIT, 0, R.string.resize_fit)
                .setCheckable(true).setChecked(!zoom);
        resizeMenu.add(0, MENU_RESIZE_ZOOM, 1, R.string.resize_zoom)
                .setCheckable(true).setChecked(zoom);

        final SubMenu qualityMenu = menu.getMenu().addSubMenu(
                0, MENU_QUALITY_AUTO, 5, R.string.shorts_quality);
        populateQualityMenu(qualityMenu);

        menu.setOnMenuItemClickListener(menuItem -> {
            final int id = menuItem.getItemId();
            if (id == MENU_OPEN_AS_VIDEO) {
                openAsVideo(item);
                return true;
            }
            if (id == MENU_COPY_LINK) {
                ShareUtils.copyToClipboard(requireContext(), item.getUrl());
                return true;
            }
            if (id == MENU_AUTO_ADVANCE) {
                toggleAutoAdvance();
                return true;
            }
            if (id == MENU_QUALITY_AUTO) {
                videoResolver.clearSelectedStream();
                selectedResolution = null;
                selectedCodec = null;
                retryCurrent(true);
                return true;
            }
            if (id > MENU_QUALITY_BASE && id < MENU_SPEED_BASE) {
                final VideoStream stream = qualitySelections.get(id);
                if (stream != null) {
                    videoResolver.setSelectedStream(stream);
                    selectedResolution = stream.getResolution();
                    selectedCodec = stream.getCodec();
                    retryCurrent(true);
                }
                return true;
            }
            if (id > MENU_SPEED_BASE && id <= MENU_SPEED_BASE + PLAYBACK_SPEEDS.length) {
                playbackSpeed = PLAYBACK_SPEEDS[id - MENU_SPEED_BASE - 1];
                player.setPlaybackSpeed(playbackSpeed);
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .putFloat(PREF_SPEED, playbackSpeed).apply();
                return true;
            }
            if (id == MENU_RESIZE_FIT || id == MENU_RESIZE_ZOOM) {
                zoom = id == MENU_RESIZE_ZOOM;
                feedModel.setZoom(zoom);
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .putBoolean(PREF_ZOOM, zoom).apply();
                applyZoomMode();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void populateQualityMenu(final SubMenu qualityMenu) {
        qualitySelections.clear();
        final StreamInfo info = currentStreamInfo();
        if (info == null) {
            qualityMenu.add(0, MENU_QUALITY_AUTO, 0, R.string.quality_auto)
                    .setEnabled(false);
            return;
        }
        final List<VideoStream> streams = new ArrayList<>();
        streams.addAll(info.getVideoStreams());
        streams.addAll(info.getVideoOnlyStreams());
        final Set<String> labels = new LinkedHashSet<>();
        int menuId = MENU_QUALITY_BASE + 1;
        for (final VideoStream stream : streams) {
            final String resolution = stream.getResolution();
            if (resolution == null || resolution.isEmpty()) {
                continue;
            }
            final String label = resolution + (stream.getCodec() == null
                    ? "" : " • " + stream.getCodec().toUpperCase(Locale.ROOT));
            if (!labels.add(label)) {
                continue;
            }
            qualitySelections.put(menuId, stream);
            qualityMenu.add(0, menuId, menuId - MENU_QUALITY_BASE, label)
                    .setCheckable(true)
                    .setChecked(resolution.equals(selectedResolution)
                            && java.util.Objects.equals(stream.getCodec(), selectedCodec));
            menuId++;
        }
        qualityMenu.add(0, MENU_QUALITY_AUTO, 0, R.string.quality_auto)
                .setCheckable(true).setChecked(selectedResolution == null);
    }

    private String formatSpeed(final float speed) {
        final float value = speed == Math.rint(speed) ? (float) Math.rint(speed) : speed;
        return value + "×";
    }

    private void applyZoomMode() {
        if (binding != null) {
            binding.shortsPlayerView.setResizeMode(zoom
                    ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
    }

    private void openAsVideo(final StreamInfoItem item) {
        savePlaybackState();
        userPaused = true;
        playRequested = false;
        pausePlayback();
        playerReloadRequired = true;
        firstFrameRendered = false;
        playerPosition = -1;
        playbackToken = -1;
        openingToken = -1;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        showCurrentThumbnail();
        NavigationHelper.openVideoDetail(requireContext(), item.getServiceId(), item.getUrl(),
                item.getName(), null, false);
    }

    private void reloadPlayerIfNeeded() {
        if (!playerReloadRequired) {
            return;
        }
        playerReloadRequired = false;
        if (!isValidPosition(currentPosition)) {
            return;
        }
        firstFrameRendered = false;
        showCurrentThumbnail();
        final ShortsPageHolder holder = currentHolder();
        if (holder != null) {
            holder.hideError();
            holder.loading.setVisibility(View.VISIBLE);
        }
        playerPosition = -1;
        playbackToken = -1;
        openingToken = -1;
        resolveToken++;
        resolvePosition(currentPosition, false, true);
    }

    private void showCurrentThumbnail() {
        final ShortsPageHolder holder = currentHolder();
        if (holder != null) {
            holder.thumbnail.setVisibility(View.VISIBLE);
        }
    }

    private void hideCurrentThumbnail() {
        final ShortsPageHolder holder = currentHolder();
        if (holder != null) {
            holder.thumbnail.setVisibility(View.GONE);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
        stopProgressUpdates();
        stopAdvanceRunnable();
        savePlaybackState();
        if (player != null && !resumeWhenVisible) {
            resumeWhenVisible = player.getPlayWhenReady();
        }
        pausePlayback();
        updateKeepScreenOn();
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        reloadPlayerIfNeeded();
        if (player != null && player.getCurrentMediaItem() != null
                && playerPosition == currentPosition && playbackToken == resolveToken
                && !userPaused) {
            if (player.getPlaybackState() == Player.STATE_ENDED && autoAdvance) {
                advanceAfterEnd();
            } else if (resumeWhenVisible
                    || (playRequested && player.getPlaybackState() != Player.STATE_ENDED)) {
                playRequested = true;
                if (audioReactor != null) {
                    audioReactor.requestAudioFocus();
                }
                if (player.getPlaybackState() == Player.STATE_ENDED) {
                    player.seekTo(0L);
                }
                player.play();
            }
        }
        resumeWhenVisible = false;
        updateKeepScreenOn();
    }

    @Override
    public void onDestroyView() {
        feedGeneration++;
        resolveToken++;
        playbackToken = -1;
        stopProgressUpdates();
        stopAdvanceRunnable();
        disposables.clear();
        savePlaybackState();
        binding.shortsPlayerView.setPlayer(null);
        if (audioReactor != null) {
            audioReactor.dispose();
            audioReactor = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        infoCache.evictAll();
        resolvingPositions.clear();
        currentPosition = -1;
        playerPosition = -1;
        openingToken = -1;
        loadingMore = false;
        userPaused = false;
        playRequested = false;
        resumeWhenVisible = false;
        playerReloadRequired = false;
        firstFrameRendered = false;
        resumed = false;
        feedPageRetry = false;
        errorAttempts.clear();
        binding.shortsPager.unregisterOnPageChangeCallback(pageCallback);
        binding.shortsPager.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }

    private void loadFeed() {
        if (binding == null) {
            return;
        }
        disposables.clear();
        savePlaybackState();
        final int generation = ++feedGeneration;
        final Context context = requireContext().getApplicationContext();
        binding.shortsLoading.setVisibility(View.VISIBLE);
        binding.shortsErrorBox.setVisibility(View.GONE);
        loadingMore = false;
        seenUrls.clear();
        infoCache.evictAll();
        resolvingPositions.clear();
        viewedPositions.clear();
        errorAttempts.clear();
        currentPosition = -1;
        playerPosition = -1;
        openingToken = -1;
        playbackToken = -1;
        resolveToken++;
        sourceIndex = 0;
        userPaused = false;
        playRequested = false;
        playerReloadRequired = false;
        firstFrameRendered = false;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        adapter.setItems(Collections.emptyList());
        feedModel.reset();

        disposables.add(buildFeedSources(context)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(sources -> {
                    if (binding == null || generation != feedGeneration) {
                        return;
                    }
                    if (sources.isEmpty()) {
                        showFeedError();
                        return;
                    }
                    feedModel.setSources(sources);
                    sourceIndex = 0;
                    loadSourceAt(0, generation, true);
                }, throwable -> {
                    if (binding != null && generation == feedGeneration) {
                        showFeedError();
                    }
                }));
    }

    private Single<List<ShortsFeedSource>> buildFeedSources(final Context context) {
        return Single.fromCallable(() -> {
            final LinkedHashMap<String, ShortsFeedSource> sources = new LinkedHashMap<>();
            final Set<String> searchKeys = new HashSet<>();
            final List<StreamHistoryEntry> history;
            try {
                final HistoryRecordManager manager = new HistoryRecordManager(context);
                history = manager.isStreamHistoryEnabled()
                        ? manager.getRecentStreamHistory().blockingFirst(new ArrayList<>())
                        : new ArrayList<>();
            } catch (final Exception ignored) {
                return fallbackSources(sources, searchKeys, new ArrayList<>());
            }

            int channelCount = 0;
            int searchCount = 0;
            for (int i = 0; i < history.size()
                    && channelCount < MAX_CHANNEL_SOURCES; i++) {
                final StreamEntity stream = history.get(i).getStreamEntity();
                if (stream.getServiceId() != ServiceList.YouTube.getServiceId()) {
                    continue;
                }
                if (addChannelSource(sources, stream.getUploaderUrl(),
                        stream.getUploader(), channelCount)) {
                    channelCount++;
                } else if (i < 8 && addSearchSource(sources, searchKeys,
                        (stream.getUploader() == null ? "" : stream.getUploader())
                                + " shorts", searchCount)) {
                    searchCount++;
                }
            }

            try {
                final List<SubscriptionEntity> subscriptions =
                        new SubscriptionManager(context).subscriptionTable()
                                .getAll().blockingFirst(new ArrayList<>());
                for (final SubscriptionEntity subscription : subscriptions) {
                    if (channelCount >= MAX_CHANNEL_SOURCES) {
                        break;
                    }
                    if (subscription.getServiceId() == ServiceList.YouTube.getServiceId()
                            && addChannelSource(sources, subscription.getUrl(),
                            subscription.getName(), channelCount)) {
                        channelCount++;
                    }
                }
            } catch (final Exception ignored) {
            }

            if (sources.size() < 3) {
                for (final String keyword : topTitleKeywords(history, 3)) {
                    if (addSearchSource(sources, searchKeys,
                            keyword + " shorts", searchCount)) {
                        searchCount++;
                    }
                }
            }
            int genericSearchCount = 0;
            for (final String query : GENERIC_QUERIES) {
                if (addSearchSource(sources, searchKeys, query, searchCount)) {
                    searchCount++;
                    genericSearchCount++;
                }
                if (genericSearchCount >= 2 || sources.size() >= 12
                        || searchCount >= MAX_SEARCH_SOURCES) {
                    break;
                }
            }
            if (sources.isEmpty()) {
                for (final String query : GENERIC_QUERIES) {
                    addSearchSource(sources, searchKeys, query, searchCount++);
                }
            }
            return new ArrayList<>(sources.values());
        });
    }

    private List<ShortsFeedSource> fallbackSources(
            final Map<String, ShortsFeedSource> sources,
            final Set<String> searchKeys,
            final List<StreamHistoryEntry> history) {
        int searchCount = 0;
        if (sources.size() < 3) {
            for (final String keyword : topTitleKeywords(history, 3)) {
                if (addSearchSource(sources, searchKeys,
                        keyword + " shorts", searchCount)) {
                    searchCount++;
                }
            }
        }
        for (final String query : GENERIC_QUERIES) {
            addSearchSource(sources, searchKeys, query, searchCount++);
        }
        return new ArrayList<>(sources.values());
    }

    private boolean addChannelSource(final Map<String, ShortsFeedSource> sources,
                                     final String channelUrl,
                                     final String channelName,
                                     final int channelCount) {
        if (channelCount >= MAX_CHANNEL_SOURCES || channelUrl == null
                || channelUrl.isEmpty()) {
            return false;
        }
        try {
            final StreamingService service = NewPipe.getService(ServiceList.YouTube.getServiceId());
            final ListLinkHandler channelHandler = service.getChannelLHFactory()
                    .fromUrl(channelUrl);
            final String key = "channel:" + channelHandler.getId();
            if (sources.containsKey(key)) {
                return false;
            }
            final ListLinkHandler shortsHandler = service.getChannelTabLHFactory()
                    .fromQuery(channelHandler.getId(), Collections.singletonList(
                                    new FilterItem(Filter.ITEM_IDENTIFIER_UNKNOWN,
                                            ChannelTabs.SHORTS)), null,
                            channelHandler.getOriginalUrl());
            sources.put(key, ShortsFeedSource.channel(shortsHandler,
                    channelName == null ? "" : channelName,
                    channelHandler.getOriginalUrl()));
            return true;
        } catch (final Exception ignored) {
            return false;
        }
    }

    private boolean addSearchSource(final Map<String, ShortsFeedSource> sources,
                                    final Set<String> searchKeys,
                                    final String rawQuery,
                                    final int searchCount) {
        if (searchCount >= MAX_SEARCH_SOURCES || rawQuery == null) {
            return false;
        }
        final String query = rawQuery.trim();
        if (query.isEmpty() || !searchKeys.add(query.toLowerCase(Locale.ROOT))) {
            return false;
        }
        sources.put("search:" + query.toLowerCase(Locale.ROOT),
                ShortsFeedSource.search(query));
        return true;
    }

    private void loadSourceAt(final int index, final int generation,
                              final boolean replace) {
        if (binding == null || generation != feedGeneration) {
            return;
        }
        if (index < 0 || index >= feedModel.getSources().size()) {
            if (replace) {
                showFeedError();
            }
            loadingMore = false;
            return;
        }
        sourceIndex = index;
        feedModel.setSourceIndex(index);
        final ShortsFeedSource source = feedModel.getSources().get(index);
        if (replace) {
            binding.shortsLoading.setVisibility(View.VISIBLE);
        }
        disposables.add(loadInitialSource(source)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    if (binding == null || generation != feedGeneration) {
                        return;
                    }
                    loadingMore = false;
                    binding.shortsLoading.setVisibility(View.GONE);
                    feedPageRetry = false;
                    hideCurrentPageError();
                    handleSourceItems(index, generation, replace, source, items);
                }, throwable -> {
                    if (binding == null || generation != feedGeneration) {
                        return;
                    }
                    moveToNextSource(index, generation, replace);
                }));
    }

    private Single<List<InfoItem>> loadInitialSource(final ShortsFeedSource source) {
        final Single<List<InfoItem>> single;
        if (source.getType() == ShortsFeedSource.Type.CHANNEL) {
            single = ExtractorHelper.getChannelTab(ServiceList.YouTube.getServiceId(),
                            source.getChannelHandler(), false)
                    .map(info -> {
                        source.setNextPage(info.hasNextPage() ? info.getNextPage() : null);
                        source.setLoaded(true);
                        return info.getRelatedItems();
                    });
        } else {
            single = ExtractorHelper.searchFor(ServiceList.YouTube.getServiceId(),
                            source.getQuery(), allFilter(), Collections.emptyList())
                    .map(info -> {
                        source.setNextPage(info.hasNextPage() ? info.getNextPage() : null);
                        source.setLoaded(true);
                        return info.getRelatedItems();
                    });
        }
        return single;
    }

    private void handleSourceItems(final int index, final int generation,
                                   final boolean replace, final ShortsFeedSource source,
                                   final List<? extends InfoItem> items) {
        if (binding == null || generation != feedGeneration) {
            return;
        }
        applySourceMetadata(source, items);
        final List<StreamInfoItem> shorts = filterShorts(items);
        if (!shorts.isEmpty()) {
            loadingMore = false;
            binding.shortsLoading.setVisibility(View.GONE);
            appendOrReplaceItems(shorts, replace);
            return;
        }
        final Page nextPage = source.getNextPage();
        if (nextPage != null) {
            loadingMore = true;
            if (replace) {
                binding.shortsLoading.setVisibility(View.VISIBLE);
            }
            loadSourceContinuation(index, generation, replace, source, nextPage);
        } else {
            moveToNextSource(index, generation, replace);
        }
    }

    private void loadSourceContinuation(final int index, final int generation,
                                        final boolean replace, final ShortsFeedSource source,
                                        final Page page) {
        if (binding == null || generation != feedGeneration) {
            return;
        }
        disposables.add(loadMoreSource(source, page)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    if (binding == null || generation != feedGeneration) {
                        return;
                    }
                    handleSourceItems(index, generation, replace, source, items);
                }, throwable -> {
                    if (binding == null || generation != feedGeneration) {
                        return;
                    }
                    moveToNextSource(index, generation, replace);
                }));
    }

    private void appendOrReplaceItems(final List<StreamInfoItem> shorts,
                                      final boolean replace) {
        if (binding == null || shorts.isEmpty()) {
            return;
        }
        if (replace || adapter.getItemCount() == 0) {
            adapter.setItems(shorts);
            feedModel.replaceItems(shorts);
            currentPosition = -1;
            binding.shortsPager.setCurrentItem(0, false);
            playPosition(0);
        } else {
            adapter.addItems(shorts);
            feedModel.appendItems(shorts);
        }
    }

    private void moveToNextSource(final int index, final int generation,
                                  final boolean replace) {
        if (binding == null || generation != feedGeneration) {
            return;
        }
        if (index + 1 < feedModel.getSources().size()) {
            loadingMore = true;
            loadSourceAt(index + 1, generation,
                    replace || adapter.getItemCount() == 0);
        } else {
            loadingMore = false;
            if (adapter.getItemCount() == 0) {
                showFeedError();
            }
        }
    }

    private void loadMore() {
        if (loadingMore || binding == null || adapter == null
                || feedModel.getSources().isEmpty()) {
            return;
        }
        if (sourceIndex < 0 || sourceIndex >= feedModel.getSources().size()) {
            return;
        }
        final ShortsFeedSource source = feedModel.getSources().get(sourceIndex);
        if (!source.isLoaded()) {
            loadingMore = true;
            loadSourceAt(sourceIndex, feedGeneration, false);
            return;
        }
        if (source.getNextPage() == null) {
            advanceSource();
            return;
        }
        loadingMore = true;
        final int generation = feedGeneration;
        final Page page = source.getNextPage();
        disposables.add(loadMoreSource(source, page)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    if (binding == null || generation != feedGeneration) {
                        return;
                    }
                    loadingMore = false;
                    feedPageRetry = false;
                    hideCurrentPageError();
                    applySourceMetadata(source, items);
                    final List<StreamInfoItem> shorts = filterShorts(items);
                    if (!shorts.isEmpty()) {
                        adapter.addItems(shorts);
                        feedModel.appendItems(shorts);
                    } else if (source.getNextPage() == null
                            && sourceIndex + 1 < feedModel.getSources().size()) {
                        advanceSource();
                    } else if (source.getNextPage() != null) {
                        loadMore();
                    }
                }, throwable -> {
                    if (binding == null || generation != feedGeneration) {
                        return;
                    }
                    loadingMore = false;
                    if (isValidPosition(currentPosition)) {
                        feedPageRetry = true;
                        showCurrentPageError(R.string.shorts_feed_page_error);
                    }
                }));
    }

    private Single<List<InfoItem>> loadMoreSource(final ShortsFeedSource source,
                                                  final Page page) {
        if (source.getType() == ShortsFeedSource.Type.CHANNEL) {
            return ExtractorHelper.getMoreChannelTabItems(
                            ServiceList.YouTube.getServiceId(), source.getChannelHandler(), page)
                    .map(info -> {
                        source.setNextPage(info.hasNextPage() ? info.getNextPage() : null);
                        return info.getItems();
                    });
        }
        return ExtractorHelper.getMoreSearchItems(
                        ServiceList.YouTube.getServiceId(), source.getQuery(), allFilter(),
                        Collections.emptyList(), page)
                .map(info -> {
                    source.setNextPage(info.hasNextPage() ? info.getNextPage() : null);
                    return info.getItems();
                });
    }

    private void advanceSource() {
        if (loadingMore) {
            return;
        }
        moveToNextSource(sourceIndex, feedGeneration, false);
    }

    private void applySourceMetadata(final ShortsFeedSource source,
                                     final List<? extends InfoItem> items) {
        if (source.getType() != ShortsFeedSource.Type.CHANNEL || items == null) {
            return;
        }
        for (final InfoItem infoItem : items) {
            if (infoItem instanceof StreamInfoItem) {
                final StreamInfoItem item = (StreamInfoItem) infoItem;
                if (item.getUploaderName() == null || item.getUploaderName().isEmpty()) {
                    item.setUploaderName(source.getChannelName());
                }
                if (item.getUploaderUrl() == null || item.getUploaderUrl().isEmpty()) {
                    item.setUploaderUrl(source.getChannelUrl());
                }
            }
        }
    }

    private List<FilterItem> allFilter() {
        try {
            final StreamingService service =
                    NewPipe.getService(ServiceList.YouTube.getServiceId());
            return Collections.singletonList(
                    service.getSearchQHFactory().getFilterItem(0));
        } catch (final Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static final Set<String> TITLE_STOP_WORDS =
            new HashSet<>(java.util.Arrays.asList(
                    "видео", "смотреть", "онлайн", "новый", "новая", "новое", "новые",
                    "часть", "выпуск", "обзор", "прохождение", "стрим", "клип",
                    "песня", "трек", "хит", "топ", "film", "серия", "сезон",
                    "все", "это", "как", "для", "при", "или", "уже", "еще",
                    "меня", "тебя", "себя", "нас", "вас", "них", "него", "нее",
                    "который", "которая", "которые", "такой", "такая", "самый",
                    "мой", "моя", "мое", "твой", "твоя", "наш", "ваш",
                    "video", "videos", "official", "music", "shorts", "short",
                    "lyric", "lyrics", "cover", "remix", "live", "full",
                    "episode", "part", "with", "from", "this", "that",
                    "what", "when", "your", "about", "there", "their", "have",
                    "movie", "best", "2024", "2025", "2026", "song",
                    "songs", "hits", "top", "audio", "sound", "free", "watch"
            ));

    private List<String> topTitleKeywords(final List<StreamHistoryEntry> history,
                                          final int limit) {
        final Map<String, Integer> counts = new HashMap<>();
        int scanned = 0;
        for (int i = 0; i < history.size() && scanned < 30; i++, scanned++) {
            final StreamEntity stream = history.get(i).getStreamEntity();
            if (stream.getServiceId() != ServiceList.YouTube.getServiceId()
                    || stream.getTitle() == null) {
                continue;
            }
            final String[] tokens = stream.getTitle()
                    .toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
            for (final String token : tokens) {
                if (token.length() < 4 || TITLE_STOP_WORDS.contains(token)) {
                    continue;
                }
                boolean digitsOnly = true;
                for (int c = 0; c < token.length(); c++) {
                    if (!Character.isDigit(token.charAt(c))) {
                        digitsOnly = false;
                        break;
                    }
                }
                if (!digitsOnly) {
                    counts.put(token, counts.getOrDefault(token, 0) + 1);
                }
            }
        }
        final List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        Collections.sort(sorted,
                (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        final List<String> result = new ArrayList<>();
        for (final Map.Entry<String, Integer> entry : sorted) {
            if (result.size() >= limit) {
                break;
            }
            result.add(entry.getKey());
        }
        return result;
    }

    private List<StreamInfoItem> filterShorts(final List<? extends InfoItem> rawItems) {
        final List<StreamInfoItem> shorts = new ArrayList<>();
        if (rawItems == null) {
            return shorts;
        }
        for (final InfoItem infoItem : rawItems) {
            if (!(infoItem instanceof StreamInfoItem)) {
                continue;
            }
            final StreamInfoItem item = (StreamInfoItem) infoItem;
            final String url = item.getUrl() == null ? "" : item.getUrl();
            if (url.isEmpty() || item.getStreamType() != StreamType.VIDEO_STREAM
                    || seenUrls.contains(url)) {
                continue;
            }
            final boolean explicitShort = item.isShortFormContent()
                    || url.contains("/shorts/");
            if (!explicitShort || item.getDuration() > MAX_SHORT_DURATION_SECONDS
                    || ContentFilter.isPoliticsBlocked(
                    item.getName(), item.getUploaderName())) {
                continue;
            }
            seenUrls.add(url);
            shorts.add(item);
            if (shorts.size() >= 60) {
                break;
            }
        }
        return shorts;
    }

    private void playPosition(final int position) {
        if (binding == null || player == null || position == currentPosition) {
            return;
        }
        stopAdvanceRunnable();
        playbackToken = -1;
        firstFrameRendered = false;
        if (currentPosition >= 0) {
            savePlaybackState();
            pausePlayback();
            final ShortsPageHolder previous = holderAt(currentPosition);
            if (previous != null) {
                previous.setKeepScreenOn(false);
                previous.thumbnail.setVisibility(View.VISIBLE);
            }
        }
        playerPosition = -1;
        playerReloadRequired = false;
        feedPageRetry = false;
        userPaused = false;
        final Context pageContext = getContext();
        playRequested = pageContext != null
                && PlayerHelper.isAutoplayAllowedByUser(pageContext);
        resumeWhenVisible = false;
        currentPosition = position;
        feedModel.setPosition(position);
        resolveToken++;
        if (!isValidPosition(position)) {
            return;
        }
        if (position >= adapter.getItemCount() - PREFETCH_TAIL) {
            loadMore();
        }
        final ShortsPageHolder holder = holderAt(position);
        if (holder != null) {
            holder.errorBox.setVisibility(View.GONE);
            holder.loading.setVisibility(View.VISIBLE);
            holder.thumbnail.setVisibility(View.VISIBLE);
        }
        applyZoomMode();
        resolvePosition(position, false, true);
        resolveAhead(position + 1);
        resolveAhead(position + 2);
        scheduleOpenResolvedPosition(position, 0, resolveToken, feedGeneration);
    }

    private void scheduleOpenResolvedPosition(final int position, final int attempt,
                                               final int token, final int generation) {
        if (binding == null || attempt > 4 || token != resolveToken
                || generation != feedGeneration) {
            return;
        }
        binding.shortsPager.postDelayed(() -> {
            if (binding == null || token != resolveToken || generation != feedGeneration
                    || position != currentPosition || !isValidPosition(position)) {
                return;
            }
            final StreamInfo info = infoCache.get(position);
            if (info != null) {
                applyZoomMode();
                openStream(position, token, info, attempt + 1);
            }
        }, Math.max(0L, attempt * 200L));
    }

    private static boolean hasVideoStreams(@NonNull final StreamInfo info) {
        return !info.getVideoStreams().isEmpty() || !info.getVideoOnlyStreams().isEmpty();
    }

    private void resolvePosition(final int position, final boolean force,
                                 final boolean openIfCurrent) {
        if (!isValidPosition(position)) {
            return;
        }
        if (!force) {
            final StreamInfo cached = infoCache.get(position);
            if (cached != null) {
                if (!hasVideoStreams(cached)) {
                    infoCache.remove(position);
                    if (openIfCurrent && position == currentPosition) {
                        showCurrentPageError(R.string.shorts_playback_error);
                    }
                    return;
                }
                if (openIfCurrent && position == currentPosition) {
                    openStream(position, resolveToken, cached);
                }
                return;
            }
        } else {
            infoCache.remove(position);
        }
        final ResolveRequest request = new ResolveRequest(feedGeneration, resolveToken, force);
        final ResolveRequest existing = resolvingPositions.putIfAbsent(position, request);
        if (existing != null) {
            if (!force) {
                if (openIfCurrent || position == currentPosition) {
                    existing.openWhenCurrent = true;
                }
                return;
            }
            if (!resolvingPositions.remove(position, existing)
                    || resolvingPositions.putIfAbsent(position, request) != null) {
                return;
            }
        }
        final String url = adapter.getItem(position).getUrl();
        if (url == null || url.isEmpty()) {
            resolvingPositions.remove(position, request);
            return;
        }
        disposables.add(ExtractorHelper
                .getStreamInfo(ServiceList.YouTube.getServiceId(), url, force)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> resolvingPositions.remove(position, request))
                .subscribe(info -> {
                    final boolean active = resolvingPositions.get(position) == request;
                    final boolean currentToken = !request.force || request.token == resolveToken;
                    if (active && currentToken && request.generation == feedGeneration) {
                        if (!hasVideoStreams(info)) {
                            infoCache.remove(position);
                            if (position == currentPosition
                                    && (request.openWhenCurrent || openIfCurrent)) {
                                showCurrentPageError(R.string.shorts_playback_error);
                            }
                            return;
                        }
                        infoCache.put(position, info);
                    }
                    if (active && currentToken && request.generation == feedGeneration
                            && position == currentPosition && player != null
                            && (request.openWhenCurrent || openIfCurrent)) {
                        openStream(position, resolveToken, info);
                    }
                }, throwable -> {
                    if (resolvingPositions.get(position) == request
                            && request.generation == feedGeneration
                            && position == currentPosition
                            && (!request.force || request.token == resolveToken)) {
                        showCurrentPageError(R.string.shorts_playback_error);
                    }
                }));
    }

    private void resolveAhead(final int position) {
        if (isValidPosition(position)) {
            resolvePosition(position, false, false);
        }
    }

    private void openStream(final int position, final int token, final StreamInfo info) {
        openStream(position, token, info, 0);
    }

    private void openStream(final int position, final int token, final StreamInfo info,
                            final int attempt) {
        if (player == null || binding == null || info == null
                || token != resolveToken || position != currentPosition) {
            return;
        }
        if (!hasVideoStreams(info)) {
            infoCache.remove(position);
            showCurrentPageError(R.string.shorts_playback_error);
            return;
        }
        final ShortsPageHolder holder = holderAt(position);
        if (holder == null) {
            scheduleOpenResolvedPosition(position, attempt + 1, token, feedGeneration);
            return;
        }
        if (openingToken == token) {
            return;
        }
        openingToken = token;
        firstFrameRendered = false;
        holder.loading.setVisibility(View.VISIBLE);
        holder.hideError();
        holder.thumbnail.setVisibility(View.VISIBLE);
        final long initialPosition = initialPositionFor(position, info);
        final Looper playbackLooper = player.getPlaybackLooper();
        final AtomicReference<MediaSource> pendingSource = new AtomicReference<>();
        final AtomicBoolean sourceAttached = new AtomicBoolean();
        disposables.add(Single.fromCallable(() -> {
                    final MediaSource source;
                    synchronized (videoResolver) {
                        source = videoResolver.resolve(info, initialPosition);
                    }
                    pendingSource.set(source);
                    return source;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> {
                    if (!sourceAttached.get()) {
                        releaseUnattachedMediaSource(playbackLooper,
                                pendingSource.getAndSet(null));
                    }
                })
                .subscribe(mediaSource -> {
                    if (mediaSource == null) {
                        if (token == resolveToken && position == currentPosition
                                && player != null && binding != null) {
                            showCurrentPageError(R.string.shorts_playback_error);
                        }
                        return;
                    }
                    final ShortsPageHolder activeHolder = holderAt(position);
                    if (player == null || binding == null || activeHolder == null
                            || token != resolveToken || position != currentPosition) {
                        if (token == resolveToken) {
                            openingToken = -1;
                            if (player != null && binding != null && activeHolder == null) {
                                scheduleOpenResolvedPosition(position, 0, token, feedGeneration);
                            }
                        }
                        if (pendingSource.compareAndSet(mediaSource, null)) {
                            releaseUnattachedMediaSource(playbackLooper, mediaSource);
                        }
                        return;
                    }
                    playerPosition = position;
                    playbackToken = token;
                    firstFrameRendered = false;
                    fillChannelRow(activeHolder, position, info);
                    activeHolder.thumbnail.setVisibility(View.VISIBLE);
                    try {
                        player.setMediaSource(mediaSource, initialPosition);
                        sourceAttached.set(true);
                        pendingSource.set(null);
                        player.prepare();
                    } catch (final RuntimeException error) {
                        if (sourceAttached.get() && player != null) {
                            playbackToken = -1;
                            player.stop();
                            player.clearMediaItems();
                        }
                        showCurrentPageError(R.string.shorts_playback_error);
                        return;
                    }
                    player.setVolume(muted ? 0f : 1f);
                    if (resumed && !userPaused && playRequested) {
                        if (audioReactor != null) {
                            audioReactor.requestAudioFocus();
                        }
                        player.play();
                    } else {
                        pausePlayback();
                    }
                    updateTopBar();
                    updateKeepScreenOn();
                    resolveAhead(position + 1);
                    resolveAhead(position + 2);
                }, throwable -> {
                    if (token == resolveToken && position == currentPosition) {
                        showCurrentPageError(R.string.shorts_playback_error);
                    }
                }));
    }

    private void releaseUnattachedMediaSource(@Nullable final Looper playbackLooper,
                                               @Nullable final MediaSource mediaSource) {
        if (mediaSource == null) {
            return;
        }
        final Looper looper = playbackLooper == null
                ? Looper.getMainLooper() : playbackLooper;
        new Handler(looper).post(() -> {
            final MediaSource.MediaSourceCaller caller = (source, timeline) -> { };
            try {
                mediaSource.prepareSource(caller, null, PlayerId.UNSET);
            } catch (final RuntimeException ignored) {
            }
            try {
                mediaSource.releaseSource(caller);
            } catch (final RuntimeException ignored) {
            }
        });
    }

    private long initialPositionFor(final int position, final StreamInfo info) {
        if (historyManager != null && !historyManager.isStreamHistoryEnabled()) {
            return 0L;
        }
        long positionMillis = feedModel.getPlaybackPosition(position);
        final long durationSeconds = Math.max(0L, info.getDuration());
        final long durationMillis = durationSeconds > Long.MAX_VALUE / 1_000L
                ? Long.MAX_VALUE : durationSeconds * 1_000L;
        if (durationMillis > 0L && positionMillis >= Math.max(0L, durationMillis - 2_000L)) {
            positionMillis = 0L;
        }
        return Math.max(0L, positionMillis);
    }

    private void retryCurrent(final boolean force) {
        if (!isValidPosition(currentPosition)) {
            if (adapter == null || adapter.getItemCount() == 0) {
                loadFeed();
            }
            return;
        }
        savePlaybackState();
        playbackToken = -1;
        pausePlayback();
        updateKeepScreenOn();
        resolveToken++;
        feedPageRetry = false;
        final ShortsPageHolder holder = currentHolder();
        firstFrameRendered = false;
        if (holder != null) {
            holder.hideError();
            holder.loading.setVisibility(View.VISIBLE);
            holder.thumbnail.setVisibility(View.VISIBLE);
        }
        errorAttempts.merge(currentPosition, 1, Integer::sum);
        resolvePosition(currentPosition, force, true);
    }

    private void handlePlayerError() {
        if (!isValidPosition(currentPosition) || player == null
                || playerPosition != currentPosition || playbackToken != resolveToken) {
            return;
        }
        final int attempts = errorAttempts.getOrDefault(currentPosition, 0);
        if (attempts == 0) {
            errorAttempts.put(currentPosition, 1);
            retryCurrent(true);
        } else {
            userPaused = true;
            playRequested = false;
            playbackToken = -1;
            pausePlayback();
            showCurrentPageError(R.string.shorts_playback_error);
        }
    }

    private void hideCurrentPageError() {
        final ShortsPageHolder holder = currentHolder();
        if (holder != null) {
            holder.hideError();
        }
    }

    private void showCurrentPageError(final int message) {
        final ShortsPageHolder holder = currentHolder();
        if (holder != null) {
            holder.showError(message);
        }
    }

    private void showFeedError() {
        if (binding == null) {
            return;
        }
        loadingMore = false;
        feedPageRetry = false;
        binding.shortsLoading.setVisibility(View.GONE);
        binding.shortsErrorBox.setVisibility(View.VISIBLE);
    }

    private void onPlayerStateChanged(final int state) {
        updateKeepScreenOn();
        if (playerPosition != currentPosition || playbackToken != resolveToken) {
            return;
        }
        final ShortsPageHolder holder = currentHolder();
        if (state == Player.STATE_READY) {
            if (holder != null) {
                holder.loading.setVisibility(View.GONE);
                holder.hideError();
            }
            if (firstFrameRendered) {
                hideCurrentThumbnail();
            }
            errorAttempts.remove(currentPosition);
            if (resumed && playRequested && !userPaused && player.isPlaying()) {
                recordCurrentView();
            }
        } else if (state == Player.STATE_BUFFERING && holder != null) {
            holder.loading.setVisibility(View.VISIBLE);
            holder.thumbnail.setVisibility(View.VISIBLE);
        } else if (state == Player.STATE_ENDED) {
            if (audioReactor != null) {
                audioReactor.abandonAudioFocus();
            }
            if (autoAdvance) {
                advanceAfterEnd();
            }
        }
    }

    private void advanceAfterEnd() {
        stopAdvanceRunnable();
        if (binding == null || adapter == null) {
            return;
        }
        final int endedPosition = currentPosition;
        final int generation = feedGeneration;
        final int token = resolveToken;
        if (endedPosition + 1 < adapter.getItemCount()) {
            binding.shortsPager.setCurrentItem(endedPosition + 1, true);
            return;
        }
        advanceRunnable = new Runnable() {
            @Override
            public void run() {
                if (binding == null || endedPosition != currentPosition
                        || generation != feedGeneration || token != resolveToken) {
                    return;
                }
                if (endedPosition + 1 < adapter.getItemCount()) {
                    binding.shortsPager.setCurrentItem(endedPosition + 1, true);
                    return;
                }
                if (loadingMore) {
                    binding.getRoot().postDelayed(this, 1_000L);
                } else if (!feedPageRetry
                        && sourceIndex + 1 < feedModel.getSources().size()) {
                    loadMore();
                    binding.getRoot().postDelayed(this, 1_000L);
                }
            }
        };
        loadMore();
        binding.getRoot().postDelayed(advanceRunnable, 1_000L);
    }

    private void stopAdvanceRunnable() {
        if (binding != null && advanceRunnable != null) {
            binding.getRoot().removeCallbacks(advanceRunnable);
        }
        advanceRunnable = null;
    }

    private void recordCurrentView() {
        if (!resumed || !playRequested || userPaused
                || playerPosition != currentPosition || !isValidPosition(playerPosition)) {
            return;
        }
        final StreamInfo info = streamInfoAt(playerPosition);
        if (info == null || historyManager == null || !viewedPositions.add(playerPosition)) {
            return;
        }
        disposables.add(historyManager.onViewed(info)
                .onErrorComplete()
                .subscribe(ignored -> { }, ignored -> { }));
    }

    private void savePlaybackState() {
        if (player == null || !isValidPosition(playerPosition)) {
            return;
        }
        final StreamInfo info = streamInfoAt(playerPosition);
        if (info == null) {
            return;
        }
        final long positionMillis = Math.max(0L, player.getCurrentPosition());
        feedModel.setPlaybackPosition(playerPosition, positionMillis);
        if (historyManager != null) {
            disposables.add(historyManager.saveStreamState(info, positionMillis)
                    .onErrorComplete()
                    .subscribe(() -> { }, ignored -> { }));
        }
    }

    private void fillChannelRow(final ShortsPageHolder holder, final int position,
                                final StreamInfo info) {
        final String avatarUrl = info.getUploaderAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            PicassoHelper.loadAvatar(avatarUrl).into(holder.avatar);
        }
        final String uploader = info.getUploaderName() == null ? "" : info.getUploaderName();
        final String description = adapter.getItem(position).getShortDescription();
        holder.sound.setText(description == null || description.isEmpty()
                ? getString(R.string.shorts_original_sound, uploader) : description);
        refreshSubscribeState(holder, position, info.getUploaderUrl());
    }

    private StreamInfo currentStreamInfo() {
        return streamInfoAt(playerPosition >= 0 ? playerPosition : currentPosition);
    }

    private StreamInfo streamInfoAt(final int position) {
        return isValidPosition(position) ? infoCache.get(position) : null;
    }

    private boolean isValidPosition(final int position) {
        return adapter != null && position >= 0 && position < adapter.getItemCount();
    }

    @Nullable
    private ShortsPageHolder holderAt(final int position) {
        if (binding == null || binding.shortsPager.getChildCount() == 0) {
            return null;
        }
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
