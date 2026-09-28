package org.schabi.newpipe.fragments.list.recommended;

import android.content.Context;

import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.settings.NewPipeSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Builds a taste profile out of the watch history: which genres the user actually
 * watches and which concrete videos can act as seeds.
 */
public class GenreProfileBuilder {

    /** Number of recent history entries scanned for genre detection. */
    private static final int HISTORY_SCAN = 60;
    /** Number of history entries kept as seeds for the "related" source. */
    private static final int MAX_SEEDS = 8;
    /** How recent a history entry must be to count (days). */
    private static final long HISTORY_WINDOW_DAYS = 90L;

    private final Context context;
    private final List<StreamHistoryEntry> history = new ArrayList<>();
    private final List<String> subscriptionNames = new ArrayList<>();
    private final Map<String, Integer> genreScores = new LinkedHashMap<>();

    public GenreProfileBuilder(final Context context) {
        this.context = context.getApplicationContext();
    }

    public List<StreamHistoryEntry> getHistory() {
        return history;
    }

    public List<String> getSubscriptionNames() {
        return subscriptionNames;
    }

    public List<String> getTopGenres() {
        final List<Map.Entry<String, Integer>> sorted = new ArrayList<>(genreScores.entrySet());
        Collections.sort(sorted, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        final List<String> result = new ArrayList<>();
        for (final Map.Entry<String, Integer> entry : sorted) {
            if (result.size() >= 3) {
                break;
            }
            // Skip weak signals: a single hit should not define the profile.
            if (entry.getValue() >= 3) {
                result.add(entry.getKey());
            }
        }
        if (result.isEmpty() && !sorted.isEmpty()) {
            result.add(sorted.get(0).getKey());
        }
        return result;
    }

    public boolean isEmpty() {
        return genreScores.isEmpty() && subscriptionNames.isEmpty();
    }

    public List<StreamHistoryEntry> getSeedStreams() {
        final List<StreamHistoryEntry> seeds = new ArrayList<>();
        final long threshold = System.currentTimeMillis()
                - TimeUnit.DAYS.toMillis(HISTORY_WINDOW_DAYS);
        for (final StreamHistoryEntry entry : history) {
            final StreamEntity stream = entry.getStreamEntity();
            if (stream == null
                    || stream.getServiceId() != ServiceList.YouTube.getServiceId()
                    || stream.getUrl() == null
                    || stream.getUrl().isEmpty()) {
                continue;
            }
            if (accessTimeMillis(entry) < threshold) {
                continue;
            }
            seeds.add(entry);
            if (seeds.size() >= MAX_SEEDS) {
                break;
            }
        }
        return seeds;
    }

    public GenreProfileBuilder load() {
        loadHistory();
        loadSubscriptions();
        detectGenres();
        return this;
    }

    private void loadHistory() {
        history.clear();
        if (!NewPipeSettings.isPersonalizedFeedEnabled(context)) {
            return;
        }
        try {
            final HistoryRecordManager manager = new HistoryRecordManager(context);
            if (!manager.isStreamHistoryEnabled()) {
                return;
            }
            final List<StreamHistoryEntry> entries =
                    manager.getRecentStreamHistory().blockingFirst(new ArrayList<>());
            final int limit = Math.min(entries.size(), HISTORY_SCAN);
            for (int i = 0; i < limit; i++) {
                final StreamHistoryEntry entry = entries.get(i);
                if (entry != null && entry.getStreamEntity() != null) {
                    history.add(entry);
                }
            }
        } catch (final Exception ignored) {
            // History unavailable: the profile falls back to subscriptions only.
        }
    }

    private void loadSubscriptions() {
        subscriptionNames.clear();
        try {
            final List<SubscriptionEntity> subscriptions =
                    new SubscriptionManager(context).subscriptionTable()
                            .getAll().blockingFirst(new ArrayList<>());
            for (final SubscriptionEntity subscription : subscriptions) {
                if (subscription.getServiceId() == ServiceList.YouTube.getServiceId()
                        && subscription.getName() != null
                        && !subscription.getName().trim().isEmpty()) {
                    subscriptionNames.add(subscription.getName().trim());
                }
            }
        } catch (final Exception ignored) {
        }
    }

    private void detectGenres() {
        genreScores.clear();
        final Map<String, Integer> totals = new LinkedHashMap<>();
        for (final StreamHistoryEntry entry : history) {
            final StreamEntity stream = entry.getStreamEntity();
            if (stream == null) {
                continue;
            }
            // Recency matters: an old view should not define today's taste.
            final double weight = recencyWeight(accessTimeMillis(entry));
            addScores(totals, GenreDictionary.score(stream.getTitle(), stream.getUploader()),
                    weight);
        }
        // Subscription names are a weaker signal than the actual watch history.
        for (final String name : subscriptionNames) {
            addScores(totals, GenreDictionary.score(name, name), 0.6d);
        }
        totals.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(entry -> genreScores.put(entry.getKey(), entry.getValue()));
    }

    private void addScores(final Map<String, Integer> totals,
                           final Map<String, Integer> scores,
                           final double weight) {
        for (final Map.Entry<String, Integer> entry : scores.entrySet()) {
            final int weighted = (int) Math.round(entry.getValue() * weight);
            if (weighted <= 0) {
                continue;
            }
            totals.put(entry.getKey(), totals.getOrDefault(entry.getKey(), 0) + weighted);
        }
    }

    private double recencyWeight(final long timeMillis) {
        if (timeMillis <= 0) {
            return 0.7d;
        }
        final long days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - timeMillis);
        if (days <= 7) {
            return 2.0d;
        }
        if (days <= 30) {
            return 1.4d;
        }
        if (days <= 60) {
            return 1.0d;
        }
        return 0.6d;
    }

    private static long accessTimeMillis(final StreamHistoryEntry entry) {
        try {
            return entry.getAccessDate() == null
                    ? 0L : entry.getAccessDate().toInstant().toEpochMilli();
        } catch (final Exception ignored) {
            return 0L;
        }
    }
}
