package org.schabi.newpipe.views.shorts;

import android.content.Context;

import androidx.annotation.Nullable;

import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelTabInfo;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.fragments.list.recommended.GenreDictionary;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.settings.NewPipeSettings;
import org.schabi.newpipe.util.ContentFilter;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Builds the vertical feed: the Shorts tab of watched/subscribed channels plus genre
 * searches, so the feed contains new short videos and not just channel uploads.
 */
public final class ShortsFeedProvider {

    private static final long MAX_DURATION_SECONDS = 180L;
    private static final int MAX_CHANNEL_SOURCES = 10;
    private static final int MAX_SEARCH_SOURCES = 3;
    private static final int MAX_ITEMS_PER_SOURCE = 20;
    private static final int MAX_EMPTY_PAGES = 3;
    private static final int MAX_BATCH_SIZE = 20;
    private static final String FILTER_VIDEOS = "videos";
    private static final String FILTER_SHORT_VIDEO = "short_video";
    private static final String WINDOW_WEEK = "past_week";

    private static final List<String> FALLBACK_QUERIES = Arrays.asList(
            "#shorts", "shorts", "шортс прохождение");

    private ShortsFeedProvider() {
    }

    private static final class Source {
        private final String query;
        private final ListLinkHandler channelHandler;
        private final String channelName;
        private final String channelUrl;
        private Page nextPage;
        private int emptyPages;
        private boolean exhausted;

        Source(@Nullable final String query, @Nullable final ListLinkHandler channelHandler,
               @Nullable final String channelName, @Nullable final String channelUrl) {
            this.query = query;
            this.channelHandler = channelHandler;
            this.channelName = channelName;
            this.channelUrl = channelUrl;
        }

        boolean isChannel() {
            return channelHandler != null;
        }
    }

    private static final Set<String> SEEN_URLS = new HashSet<>();
    private static final List<Source> SOURCES = new ArrayList<>();
    private static int sourceIndex;
    private static boolean busy;

    /** First page of the feed. */
    public static Single<List<StreamInfoItem>> load(final Context context) {
        return Single.fromCallable(() -> {
            SEEN_URLS.clear();
            SOURCES.clear();
            SOURCES.addAll(buildSources(context));
            sourceIndex = 0;
            return pump();
        }).subscribeOn(Schedulers.io());
    }

    /** Next page of the current feed. */
    public static Single<List<StreamInfoItem>> loadMore() {
        return Single.fromCallable(() -> {
            if (busy) {
                return new ArrayList<StreamInfoItem>();
            }
            busy = true;
            try {
                return pump();
            } finally {
                busy = false;
            }
        }).subscribeOn(Schedulers.io());
    }

    public static boolean isExhausted() {
        return sourceIndex >= SOURCES.size();
    }

    private static List<StreamInfoItem> pump() {
        final List<StreamInfoItem> result = new ArrayList<>();
        int guard = 0;
        while (sourceIndex < SOURCES.size() && result.size() < MAX_BATCH_SIZE && guard++ < 10) {
            final Source source = SOURCES.get(sourceIndex);
            try {
                final List<InfoItem> items = source.isChannel()
                        ? fetchChannel(source) : fetchSearch(source);
                collect(result, items, source);
            } catch (final Exception ignored) {
                source.exhausted = true;
            }
            if (result.isEmpty() || source.exhausted) {
                sourceIndex++;
            }
        }
        return result;
    }

    private static List<InfoItem> fetchChannel(final Source source) {
        if (source.nextPage == null) {
            final ChannelTabInfo info = ExtractorHelper.getChannelTab(
                    ServiceList.YouTube.getServiceId(), source.channelHandler, false).blockingGet();
            source.nextPage = info.hasNextPage() ? info.getNextPage() : null;
            return info.getRelatedItems();
        }
        final InfoItemsPage<InfoItem> info = ExtractorHelper.getMoreChannelTabItems(
                ServiceList.YouTube.getServiceId(), source.channelHandler,
                source.nextPage).blockingGet();
        source.nextPage = info.hasNextPage() ? info.getNextPage() : null;
        return info.getItems();
    }

    private static List<InfoItem> fetchSearch(final Source source) {
        final List<FilterItem> content = videosContentFilter();
        final List<FilterItem> sort = shortSortFilters();
        if (source.nextPage == null) {
            final SearchInfo info = ExtractorHelper.searchFor(ServiceList.YouTube.getServiceId(),
                    source.query, content, sort).blockingGet();
            source.nextPage = info.hasNextPage() ? info.getNextPage() : null;
            return info.getRelatedItems();
        }
        final InfoItemsPage<InfoItem> info = ExtractorHelper.getMoreSearchItems(
                ServiceList.YouTube.getServiceId(), source.query, content, sort,
                source.nextPage).blockingGet();
        source.nextPage = info.hasNextPage() ? info.getNextPage() : null;
        return info.getItems();
    }

    private static void collect(final List<StreamInfoItem> result,
                                final List<InfoItem> items,
                                final Source source) {
        int added = 0;
        for (final InfoItem infoItem : items) {
            if (!(infoItem instanceof StreamInfoItem) || added >= MAX_ITEMS_PER_SOURCE) {
                continue;
            }
            final StreamInfoItem item = (StreamInfoItem) infoItem;
            final String url = item.getUrl() == null ? "" : item.getUrl();
            if (url.isEmpty() || SEEN_URLS.contains(url) || !isShort(item)) {
                continue;
            }
            if (source.isChannel()) {
                if (item.getUploaderName() == null || item.getUploaderName().isEmpty()) {
                    item.setUploaderName(source.channelName);
                }
                if (item.getUploaderUrl() == null || item.getUploaderUrl().isEmpty()) {
                    item.setUploaderUrl(source.channelUrl);
                }
            }
            SEEN_URLS.add(url);
            result.add(item);
            added++;
        }
        if (added == 0) {
            source.emptyPages++;
            if (source.emptyPages >= MAX_EMPTY_PAGES) {
                source.exhausted = true;
            }
        } else {
            source.emptyPages = 0;
        }
    }

    private static boolean isShort(final StreamInfoItem item) {
        if (item.getStreamType() != StreamType.VIDEO_STREAM) {
            return false;
        }
        final String url = item.getUrl() == null ? "" : item.getUrl();
        final boolean explicitShort = item.isShortFormContent() || url.contains("/shorts/");
        if (!explicitShort || item.getDuration() > MAX_DURATION_SECONDS) {
            return false;
        }
        return !ContentFilter.isPoliticsBlocked(item.getName(), item.getUploaderName());
    }

    private static List<Source> buildSources(final Context context) {
        final List<Source> result = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        final List<StreamHistoryEntry> history = readHistory(context);

        int channelCount = 0;
        for (final StreamHistoryEntry entry : history) {
            if (channelCount >= MAX_CHANNEL_SOURCES) {
                break;
            }
            final StreamEntity stream = entry.getStreamEntity();
            if (stream == null || stream.getServiceId() != ServiceList.YouTube.getServiceId()) {
                continue;
            }
            if (addChannel(result, keys, stream.getUploaderUrl(), stream.getUploader())) {
                channelCount++;
            }
        }
        for (final SubscriptionEntity subscription : readSubscriptions(context)) {
            if (channelCount >= MAX_CHANNEL_SOURCES) {
                break;
            }
            if (subscription.getServiceId() == ServiceList.YouTube.getServiceId()
                    && addChannel(result, keys, subscription.getUrl(), subscription.getName())) {
                channelCount++;
            }
        }

        int searchCount = 0;
        for (final String genre : topGenres(history)) {
            final String label = GenreDictionary.displayName(genre);
            if (addSearch(result, keys, label + " шортс", searchCount)) {
                searchCount++;
            }
            if (searchCount >= MAX_SEARCH_SOURCES) {
                break;
            }
        }
        if (searchCount == 0) {
            for (final String query : FALLBACK_QUERIES) {
                if (addSearch(result, keys, query, searchCount)) {
                    searchCount++;
                }
            }
        }
        return result;
    }

    private static boolean addChannel(final List<Source> result, final Set<String> keys,
                                      @Nullable final String channelUrl,
                                      @Nullable final String channelName) {
        if (channelUrl == null || channelUrl.isEmpty()) {
            return false;
        }
        try {
            final StreamingService service =
                    NewPipe.getService(ServiceList.YouTube.getServiceId());
            final ListLinkHandler channelHandler =
                    service.getChannelLHFactory().fromUrl(channelUrl);
            if (!keys.add("channel:" + channelHandler.getId())) {
                return false;
            }
            final ListLinkHandler shortsHandler = service.getChannelTabLHFactory()
                    .fromQuery(channelHandler.getId(), Collections.singletonList(
                                    new FilterItem(Filter.ITEM_IDENTIFIER_UNKNOWN,
                                            ChannelTabs.SHORTS)), null,
                            channelHandler.getOriginalUrl());
            result.add(new Source(null, shortsHandler,
                    channelName == null ? "" : channelName, channelHandler.getOriginalUrl()));
            return true;
        } catch (final Exception ignored) {
            return false;
        }
    }

    private static boolean addSearch(final List<Source> result, final Set<String> keys,
                                     final String query, final int searchCount) {
        if (searchCount >= MAX_SEARCH_SOURCES || query == null || query.trim().isEmpty()) {
            return false;
        }
        final String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (!keys.add("search:" + normalized)) {
            return false;
        }
        result.add(new Source(query.trim(), null, null, null));
        return true;
    }

    private static List<StreamHistoryEntry> readHistory(final Context context) {
        if (!NewPipeSettings.isPersonalizedFeedEnabled(context)) {
            return new ArrayList<>();
        }
        try {
            final HistoryRecordManager manager = new HistoryRecordManager(context);
            if (!manager.isStreamHistoryEnabled()) {
                return new ArrayList<>();
            }
            final List<StreamHistoryEntry> entries =
                    manager.getRecentStreamHistory(50).blockingFirst(new ArrayList<>());
            return entries == null ? new ArrayList<>() : entries;
        } catch (final Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static List<SubscriptionEntity> readSubscriptions(final Context context) {
        try {
            final List<SubscriptionEntity> subscriptions =
                    new SubscriptionManager(context).subscriptionTable()
                            .getAll().blockingFirst(new ArrayList<>());
            return subscriptions == null ? new ArrayList<>() : subscriptions;
        } catch (final Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static List<String> topGenres(final List<StreamHistoryEntry> history) {
        final Map<String, Integer> totals = new LinkedHashMap<>();
        for (final StreamHistoryEntry entry : history) {
            final StreamEntity stream = entry.getStreamEntity();
            if (stream == null || stream.getServiceId() != ServiceList.YouTube.getServiceId()) {
                continue;
            }
            for (final Map.Entry<String, Integer> scored
                    : GenreDictionary.score(stream.getTitle(), stream.getUploader()).entrySet()) {
                totals.put(scored.getKey(),
                        totals.getOrDefault(scored.getKey(), 0) + scored.getValue());
            }
        }
        final List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
        Collections.sort(sorted, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        final List<String> genres = new ArrayList<>();
        for (final Map.Entry<String, Integer> entry : sorted) {
            if (genres.size() >= 2 || entry.getValue() < 3) {
                break;
            }
            genres.add(entry.getKey());
        }
        return genres;
    }

    private static List<FilterItem> videosContentFilter() {
        try {
            final StreamingService service =
                    NewPipe.getService(ServiceList.YouTube.getServiceId());
            for (final FilterGroup group : service.getSearchQHFactory()
                    .getAvailableContentFilter().getFilterGroups()) {
                for (final FilterItem item : group.filterItems) {
                    if (FILTER_VIDEOS.equals(item.getName())) {
                        return Collections.singletonList(item);
                    }
                }
            }
        } catch (final Exception ignored) {
        }
        return Collections.emptyList();
    }

    private static List<FilterItem> shortSortFilters() {
        final List<FilterItem> filters = new ArrayList<>();
        try {
            final StreamingService service =
                    NewPipe.getService(ServiceList.YouTube.getServiceId());
            final FilterGroup[] groups = service.getSearchQHFactory()
                    .getAvailableSortFilter().getFilterGroups();
            for (final String name : new String[]{WINDOW_WEEK, FILTER_SHORT_VIDEO}) {
                for (final FilterGroup group : groups) {
                    boolean found = false;
                    for (final FilterItem item : group.filterItems) {
                        if (name.equals(item.getName())) {
                            if (!found) {
                                filters.add(item);
                                found = true;
                            }
                            break;
                        }
                    }
                }
            }
        } catch (final Exception ignored) {
            return Collections.emptyList();
        }
        return filters;
    }
}
