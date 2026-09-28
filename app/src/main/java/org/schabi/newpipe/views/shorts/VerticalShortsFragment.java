package org.schabi.newpipe.views.shorts;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaSource;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.list.recommended.GenreDictionary;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.helper.AudioReactor;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.resolver.QualityResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PicassoHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Vertical video feed.
 *
 * <p>A vertical {@link ViewPager2} with a single {@link ExoPlayer} that is moved onto the
 * current page holder, so only one decoder is ever active. Playback uses the same
 * {@link VideoPlaybackResolver} as the normal player, which keeps SABR/HLS/DASH, caching
 * and quality handling intact.</p>
 */
public class VerticalShortsFragment extends Fragment implements ShortsPagerAdapter.Callbacks {

    private static final int MAX_CACHED_STREAMS = 4;
    private static final int PREFETCH_COUNT = 2;
    private static final int SOURCE_COUNT = 3;

    private ViewPager2 pager;
    private ShortsPagerAdapter adapter;
    private ShortsFeedViewModel feedModel;

    private ExoPlayer player;
    private AudioReactor audioReactor;
    private VideoPlaybackResolver videoResolver;

    private final CompositeDisposable disposables = new CompositeDisposable();
    private final LruCache<Integer, StreamInfo> infoCache = new LruCache<>(MAX_CACHED_STREAMS);
    private final Map<Integer, Boolean> resolving = new ConcurrentHashMap<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ShortsPagerAdapter.VH activeHolder;
    private int currentPos = -1;
    private int playingPos = -1;
    private int resolveToken;
    private boolean userPaused;
    private boolean feedBusy;
    private boolean feedStarted;

    public static VerticalShortsFragment newInstance() {
        return new VerticalShortsFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        pager = new ViewPager2(requireContext());
        pager.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setOffscreenPageLimit(1);
        final View child = pager.getChildAt(0);
        if (child instanceof RecyclerView) {
            ((RecyclerView) child).setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        return pager;
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        feedModel = new ViewModelProvider(requireActivity()).get(ShortsFeedViewModel.class);
        final Context context = requireContext().getApplicationContext();

        adapter = new ShortsPagerAdapter(this);
        pager.setAdapter(adapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(final int position) {
                onCurrentPageChanged(position);
            }
        });

        videoResolver = new VideoPlaybackResolver(context,
                new PlayerDataSource(context, DownloaderImpl.USER_AGENT,
                        new DefaultBandwidthMeter.Builder(context).build()),
                qualityResolver(context));
        player = new ExoPlayer.Builder(context)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setSeekParameters(PlayerHelper.getSeekParameters(context));
        audioReactor = new AudioReactor(context, player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(final int state) {
                updateUi(state);
                if (state == Player.STATE_ENDED && ShortsFeedProvider.isExhausted()) {
                    loadMore();
                }
            }

            @Override
            public void onIsPlayingChanged(final boolean isPlaying) {
                if (isPlaying && activeHolder != null) {
                    activeHolder.thumbnail.setVisibility(View.GONE);
                }
                updateUi(player == null ? Player.STATE_IDLE : player.getPlaybackState());
            }

            @Override
            public void onRenderedFirstFrame() {
                if (activeHolder != null) {
                    activeHolder.thumbnail.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(@NonNull final PlaybackException error) {
                showError();
            }
        });

        if (feedModel.getItems().isEmpty()) {
            startFeed();
        } else {
            adapter.setItems(feedModel.getItems());
            final int position = Math.min(feedModel.getPosition(),
                    Math.max(0, adapter.size() - 1));
            pager.setCurrentItem(position, false);
            onCurrentPageChanged(position);
        }
    }

    private QualityResolver qualityResolver(final Context context) {
        return new QualityResolver() {
            @Override
            public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
                return ListHelper.getDefaultResolutionIndex(context, sortedVideos);
            }

            @Override
            public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                                  final String selectedResolution,
                                                  @Nullable final String selectedCodec) {
                return ListHelper.getResolutionAndCodecIndex(selectedResolution, selectedCodec,
                        sortedVideos);
            }

            @Override
            public int getCurrentAudioQualityIndex(final List<AudioStream> audioStreams) {
                return ListHelper.getDefaultAudioFormat(context, audioStreams);
            }
        };
    }

    private void startFeed() {
        if (feedStarted) {
            return;
        }
        feedStarted = true;
        feedBusy = true;
        disposables.add(ShortsFeedProvider.load(requireContext().getApplicationContext())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    feedBusy = false;
                    if (bindingGone() || items.isEmpty()) {
                        onFeedEmpty();
                        return;
                    }
                    final List<ShortsVideoItem> converted = convert(items);
                    feedModel.setItems(converted);
                    adapter.setItems(converted);
                    currentPos = 0;
                    playingPos = -1;
                    feedModel.setPosition(0);
                    pager.setCurrentItem(0, false);
                    onCurrentPageChanged(0);
                }, throwable -> {
                    feedBusy = false;
                    if (!bindingGone()) {
                        onFeedEmpty();
                    }
                }));
    }

    private void onFeedEmpty() {
        if (bindingGone()) {
            return;
        }
        Toast.makeText(requireContext(), R.string.shorts_feed_empty, Toast.LENGTH_LONG).show();
    }

    private List<ShortsVideoItem> convert(final List<StreamInfoItem> items) {
        final List<ShortsVideoItem> result = new ArrayList<>(items.size());
        for (final StreamInfoItem item : items) {
            result.add(new ShortsVideoItem(item));
        }
        return result;
    }

    private void loadMore() {
        if (bindingGone() || feedBusy || ShortsFeedProvider.isExhausted()) {
            return;
        }
        feedBusy = true;
        disposables.add(ShortsFeedProvider.loadMore()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    feedBusy = false;
                    if (bindingGone() || items.isEmpty()) {
                        return;
                    }
                    final List<ShortsVideoItem> converted = convert(items);
                    feedModel.addItems(converted);
                    adapter.addItems(converted);
                }, throwable -> feedBusy = false));
    }

    private void onCurrentPageChanged(final int position) {
        if (position == currentPos || bindingGone()) {
            return;
        }
        currentPos = position;
        feedModel.setPosition(position);
        userPaused = false;
        bindPlayerToCurrent();
        prefetch(position);
        if (adapter.size() - position < SOURCE_COUNT) {
            loadMore();
        }
    }

    private void bindPlayerToCurrent() {
        if (player == null || currentPos < 0) {
            return;
        }
        final ShortsPagerAdapter.VH holder = holderAt(currentPos);
        if (holder == null) {
            return;
        }
        if (activeHolder != null && activeHolder != holder) {
            activeHolder.playerView.setPlayer(null);
            activeHolder.thumbnail.setVisibility(View.VISIBLE);
        }
        activeHolder = holder;
        holder.error.setVisibility(View.GONE);
        holder.retry.setVisibility(View.GONE);
        holder.playerView.setPlayer(player);
        loadThumbnail(holder);
        if (playingPos != currentPos) {
            playingPos = currentPos;
            resolveToken++;
            resolveAndPlay(currentPos);
        }
    }

    private void loadThumbnail(final ShortsPagerAdapter.VH holder) {
        final ShortsVideoItem item = adapter.getItem(currentPos);
        if (item == null) {
            return;
        }
        final String thumb = item.getInfo().getThumbnailUrl();
        if (thumb == null || thumb.isEmpty()) {
            holder.thumbnail.setVisibility(View.GONE);
            return;
        }
        holder.thumbnail.setVisibility(View.VISIBLE);
        PicassoHelper.loadThumbnail(thumb).into(holder.thumbnail);
    }

    private void resolveAndPlay(final int position) {
        final ShortsVideoItem item = adapter.getItem(position);
        if (item == null) {
            return;
        }
        final int token = resolveToken;
        final StreamInfo cached = infoCache.get(position);
        if (cached != null) {
            play(position, token, cached);
            return;
        }
        if (resolving.putIfAbsent(position, Boolean.TRUE) != null) {
            return;
        }
        final ShortsPagerAdapter.VH holder = holderAt(position);
        if (holder != null) {
            holder.spinner.setVisibility(View.VISIBLE);
        }
        disposables.add(ExtractorHelper
                .getStreamInfo(ServiceList.YouTube.getServiceId(), item.getUrl(), false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> resolving.remove(position))
                .subscribe(info -> {
                    if (hasVideoStreams(info)) {
                        infoCache.put(position, info);
                    }
                    if (position == currentPos && token == resolveToken) {
                        play(position, token, info);
                    }
                }, throwable -> {
                    if (position == currentPos && token == resolveToken) {
                        showError();
                    }
                }));
    }

    private boolean hasVideoStreams(final StreamInfo info) {
        return !info.getVideoStreams().isEmpty() || !info.getVideoOnlyStreams().isEmpty();
    }

    private void play(final int position, final int token, final StreamInfo info) {
        if (player == null || position != currentPos || token != resolveToken) {
            return;
        }
        if (!hasVideoStreams(info)) {
            showError();
            return;
        }
        final MediaSource mediaSource;
        try {
            mediaSource = videoResolver.resolve(info);
        } catch (final Exception e) {
            showError();
            return;
        }
        if (mediaSource == null) {
            showError();
            return;
        }
        player.setMediaSource(mediaSource);
        player.prepare();
        player.setPlayWhenReady(!userPaused);
        if (audioReactor != null && !userPaused) {
            audioReactor.requestAudioFocus();
        }
        final ShortsPagerAdapter.VH holder = holderAt(position);
        if (holder != null) {
            holder.spinner.setVisibility(View.GONE);
        }
        recordViewed(info);
    }

    private void recordViewed(final StreamInfo info) {
        try {
            final HistoryRecordManager manager =
                    new HistoryRecordManager(requireContext().getApplicationContext());
            disposables.add(manager.onViewed(info)
                    .subscribeOn(Schedulers.io())
                    .subscribe(ignored -> { }, throwable -> { }));
        } catch (final Exception ignored) {
        }
    }

    private void prefetch(final int position) {
        for (int i = position + 1; i <= position + PREFETCH_COUNT; i++) {
            final ShortsVideoItem item = adapter.getItem(i);
            if (item == null || infoCache.get(i) != null
                    || resolving.putIfAbsent(i, Boolean.TRUE) != null) {
                continue;
            }
            disposables.add(ExtractorHelper
                    .getStreamInfo(ServiceList.YouTube.getServiceId(), item.getUrl(), false)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally(() -> resolving.remove(i))
                    .subscribe(info -> {
                        if (hasVideoStreams(info)) {
                            infoCache.put(i, info);
                        }
                    }, throwable -> { }));
        }
    }

    @Nullable
    private ShortsPagerAdapter.VH holderAt(final int position) {
        if (pager == null) {
            return null;
        }
        final View child = pager.getChildAt(0);
        if (!(child instanceof RecyclerView)) {
            return null;
        }
        final RecyclerView.ViewHolder raw =
                ((RecyclerView) child).findViewHolderForAdapterPosition(position);
        return raw instanceof ShortsPagerAdapter.VH ? (ShortsPagerAdapter.VH) raw : null;
    }

    private void showError() {
        if (activeHolder == null) {
            return;
        }
        activeHolder.spinner.setVisibility(View.GONE);
        activeHolder.error.setVisibility(View.VISIBLE);
        activeHolder.retry.setVisibility(View.VISIBLE);
        activeHolder.retry.setOnClickListener(v -> {
            resolveToken++;
            playingPos = -1;
            resolveAndPlay(currentPos);
        });
    }

    private void updateUi(final int state) {
        if (activeHolder == null || player == null) {
            return;
        }
        activeHolder.spinner.setVisibility(
                state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        activeHolder.playIcon.setVisibility(userPaused ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onTap() {
        if (player == null) {
            return;
        }
        userPaused = !userPaused;
        player.setPlayWhenReady(!userPaused);
        if (!userPaused && audioReactor != null) {
            audioReactor.requestAudioFocus();
        }
        updateUi(player.getPlaybackState());
    }

    @Override
    public void onHolderAttached(@NonNull final ShortsPagerAdapter.VH holder) {
        if (holder.getBindingAdapterPosition() == currentPos) {
            bindPlayerToCurrent();
        }
    }

    @Override
    public void onNearEnd() {
        loadMore();
    }

    @Override
    public void onAuthorClick(@NonNull final ShortsVideoItem item) {
        final String url = item.getInfo().getUploaderUrl();
        if (url == null || url.isEmpty()) {
            return;
        }
        NavigationHelper.openChannelFragment(requireContext(),
                ServiceList.YouTube.getServiceId(), url, item.getAuthor());
    }

    /** Opens the current video in the regular player. */
    public void openAsVideo() {
        final ShortsVideoItem item = adapter.getItem(currentPos);
        if (item == null || getActivity() == null) {
            return;
        }
        final StreamInfoItem info = item.getInfo();
        NavigationHelper.openVideoDetail(requireContext(), info.getServiceId(), info.getUrl(),
                info.getName(), null, false);
        if (getActivity() != null) {
            getActivity().finish();
        }
    }

    /** Exposed so the activity can show a quality/speed menu later. */
    @Nullable
    public VideoPlaybackResolver getVideoResolver() {
        return videoResolver;
    }

    public static String genreOf(@NonNull final ShortsVideoItem item) {
        return GenreDictionary.displayName(
                GenreDictionary.score(item.getTitle(), item.getAuthor())
                        .entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(""));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (player != null && !userPaused) {
            player.setPlayWhenReady(true);
        }
        handler.post(ticker);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (player != null) {
            player.setPlayWhenReady(false);
            if (audioReactor != null) {
                audioReactor.abandonAudioFocus();
            }
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && activeHolder != null) {
                final long duration = player.getDuration();
                if (duration > 0) {
                    activeHolder.progress.setProgress(
                            (int) (player.getCurrentPosition() * 1000L / duration));
                }
            }
            handler.postDelayed(this, 500L);
        }
    };

    private boolean bindingGone() {
        return pager == null || adapter == null;
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        if (activeHolder != null) {
            activeHolder.playerView.setPlayer(null);
        }
        activeHolder = null;
        playingPos = -1;
        currentPos = -1;
        if (audioReactor != null) {
            audioReactor.dispose();
            audioReactor = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (pager != null) {
            pager.setAdapter(null);
            pager = null;
        }
        infoCache.evictAll();
        resolving.clear();
        disposables.clear();
        adapter = null;
        feedStarted = false;
        super.onDestroyView();
    }
}
