package org.schabi.newpipe.fragments.list.recommended;

import android.content.Context;

import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.util.ContentFilter;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Turns the taste profile into a concrete list of videos.
 *
 * <p>Two sources are combined: genre search queries (the main one, so the user gets
 * videos of the same kind instead of the uploads of the same channels) and related
 * items of a few seed videos from the watch history.</p>
 */
public class RecommendationEngine {

    /** Shorter clips are noise, not recommendations. */
    private static final long MIN_DURATION_SECONDS = 45L;
    /** Full lectures are rarely what people want to watch next. */
    private static final long MAX_DURATION_SECONDS = 2 * 60 * 60L;
    private static final int MAX_ITEMS_PER_SOURCE = 40;
    private static final int MAX_SEED_VIDEOS = 3;
    private static final int MAX_ITEMS_PER_CHANNEL = 2;
    private static final int MAX_CHANNELS_IN_BATCH = 8;

    private final GenreProfileBuilder profile;
    private final Set<String> seenUrls = new HashSet<>();
    private final List<Query> queries = new ArrayList<>();
    private final List<String> seedUrls = new ArrayList<>();
    private int queryIndex;
    private int seedIndex;

    public RecommendationEngine(final Context context, final GenreProfileBuilder profile) {
        this.profile = profile;
    }

    /** A single request together with the reason that is shown in the UI. */
    public static final class Query {
        private final String text;
        private final String reason;
        private final int reasonType;

        Query(final String text, final String reason, final int reasonType) {
            this.text = text;
            this.reason = reason;
            this.reasonType = reasonType;
        }

        public String getText() {
            return text;
        }

        public String getReason() {
            return reason;
        }

        public int getReasonType() {
            return reasonType;
        }
    }

    public GenreProfileBuilder getProfile() {
        return profile;
    }

    public List<Query> getQueries() {
        return queries;
    }

    /** Builds the ordered work list: genre queries first, related seeds afterwards. */
    public void prepare() {
        queries.clear();
        seedUrls.clear();
        queryIndex = 0;
        seedIndex = 0;
        seenUrls.clear();
        for (final String url : seedUrlsOfProfile()) {
            if (url != null && !url.isEmpty()) {
                seenUrls.add(url);
            }
        }
        final List<String> genres = profile.getTopGenres();
        for (final String genre : genres) {
            for (final String query : GenreDictionary.queriesFor(genre)) {
                queries.add(new Query(query, GenreDictionary.displayName(genre),
                        RecommendedAdapter.REASON_GENRE));
            }
        }
        if (queries.isEmpty()) {
            // Genre is unknown: ask the subscribed channels for their new videos.
            for (final String name : profile.getSubscriptionNames()) {
                queries.add(new Query(name + " новые видео", name,
                        RecommendedAdapter.REASON_SIMILAR));
                queries.add(new Query(name + " выпуск", name,
                        RecommendedAdapter.REASON_SIMILAR));
                if (queries.size() >= 6) {
                    break;
                }
            }
        }
        seedUrls.addAll(seedUrlsOfProfile());
    }

    public boolean isEmpty() {
        return queries.isEmpty() && seedUrls.isEmpty();
    }

    public boolean isExhausted() {
        return queryIndex >= queries.size() && seedIndex >= seedUrls.size();
    }

    /** Loads the next batch of recommendations, deduplicated against previous batches. */
    public Single<List<RecommendedAdapter.Entry>> loadNext() {
        if (queryIndex < queries.size()) {
            return loadSearch(queries.get(queryIndex++));
        }
        if (seedIndex < seedUrls.size()) {
            return loadRelated(seedUrls.get(seedIndex++));
        }
        return Single.just(new ArrayList<>());
    }

    private Single<List<RecommendedAdapter.Entry>> loadSearch(final Query query) {
        return ExtractorHelper
                .searchFor(ServiceList.YouTube.getServiceId(), query.getText(),
                        allContentFilter(), Collections.emptyList())
                .map(info -> filterAndRank(collectStreams(info.getRelatedItems()),
                        query.getReason(), query.getReasonType()))
                .onErrorReturnItem(new ArrayList<>())
                .subscribeOn(Schedulers.io());
    }

    private Single<List<RecommendedAdapter.Entry>> loadRelated(final String seedUrl) {
        return ExtractorHelper
                .getStreamInfo(ServiceList.YouTube.getServiceId(), seedUrl, false)
                .map(info -> filterAndRank(collectStreams(info.getRelatedItems()),
                        titleOfSeed(seedUrl), RecommendedAdapter.REASON_SIMILAR))
                .onErrorReturnItem(new ArrayList<>())
                .subscribeOn(Schedulers.io());
    }

    private String titleOfSeed(final String seedUrl) {
        for (final StreamHistoryEntry entry : profile.getSeedStreams()) {
            if (seedUrl.equals(entry.getStreamEntity().getUrl())) {
                return entry.getStreamEntity().getTitle();
            }
        }
        return "";
    }


    private List<String> seedUrlsOfProfile() {
        final List<String> urls = new ArrayList<>();
        for (final StreamHistoryEntry entry : profile.getSeedStreams()) {
            if (urls.size() >= MAX_SEED_VIDEOS) {
                break;
            }
            urls.add(entry.getStreamEntity().getUrl());
        }
        return urls;
    }

    private List<FilterItem> allContentFilter() {
        try {
            final StreamingService service =
                    NewPipe.getService(ServiceList.YouTube.getServiceId());
            return Collections.singletonList(service.getSearchQHFactory().getFilterItem(0));
        } catch (final Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<StreamInfoItem> collectStreams(final List<? extends InfoItem> items) {
        final List<StreamInfoItem> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (final InfoItem item : items) {
            if (item instanceof StreamInfoItem) {
                result.add((StreamInfoItem) item);
            }
            if (result.size() >= MAX_ITEMS_PER_SOURCE) {
                break;
            }
        }
        return result;
    }

    private List<RecommendedAdapter.Entry> filterAndRank(final List<StreamInfoItem> raw,
                                                       final String reason,
                                                       final int reasonType) {
        final List<StreamInfoItem> kept = new ArrayList<>();
        final Map<String, Integer> perChannel = new HashMap<>();
        for (final StreamInfoItem item : raw) {
            final String url = item.getUrl() == null ? "" : item.getUrl();
            if (url.isEmpty() || seenUrls.contains(url) || !passesFilters(item)) {
                continue;
            }
            final String channel = item.getUploaderName() == null ? "" : item.getUploaderName();
            final int channelCount = perChannel.getOrDefault(channel, 0);
            if (channelCount >= MAX_ITEMS_PER_CHANNEL
                    || perChannel.size() >= MAX_CHANNELS_IN_BATCH) {
                continue;
            }
            seenUrls.add(url);
            perChannel.put(channel, channelCount + 1);
            kept.add(item);
            if (kept.size() >= MAX_ITEMS_PER_SOURCE) {
                break;
            }
        }
        Collections.sort(kept, (a, b) -> Long.compare(uploadTime(b), uploadTime(a)));
        final List<RecommendedAdapter.Entry> entries = new ArrayList<>(kept.size());
        for (final StreamInfoItem item : kept) {
            entries.add(new RecommendedAdapter.Entry(item, reason, reasonType));
        }
        return entries;
    }

    private boolean passesFilters(final StreamInfoItem item) {
        if (item.getStreamType() != StreamType.VIDEO_STREAM) {
            return false;
        }
        final long duration = item.getDuration();
        if (duration > 0 && (duration < MIN_DURATION_SECONDS || duration > MAX_DURATION_SECONDS)) {
            return false;
        }
        if (item.getUploaderName() != null && item.getUploaderName().isEmpty()) {
            return false;
        }
        return !ContentFilter.isPoliticsBlocked(item.getName(), item.getUploaderName());
    }

    private static long uploadTime(final StreamInfoItem item) {
        try {
            return item.getUploadDate() == null
                    ? 0L : item.getUploadDate().offsetDateTime().toInstant().toEpochMilli();
        } catch (final Exception ignored) {
            return 0L;
        }
    }
}
