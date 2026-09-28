package org.schabi.newpipe.views.shorts;

import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps the vertical feed across configuration changes. */
public class ShortsFeedViewModel extends ViewModel {

    private final List<ShortsVideoItem> items = new ArrayList<>();
    private final Map<Integer, Long> playbackPositions = new LinkedHashMap<>();
    private int position;
    private boolean autoAdvance;

    public List<ShortsVideoItem> getItems() {
        return items;
    }

    public void setItems(final List<ShortsVideoItem> newItems) {
        items.clear();
        items.addAll(newItems);
        playbackPositions.clear();
        position = 0;
    }

    public void addItems(final List<ShortsVideoItem> more) {
        if (more == null || more.isEmpty()) {
            return;
        }
        items.addAll(more);
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(final int newPosition) {
        position = Math.max(0, newPosition);
    }

    public long getPlaybackPosition(final int itemPosition) {
        return playbackPositions.getOrDefault(itemPosition, 0L);
    }

    public void setPlaybackPosition(final int itemPosition, final long playbackPosition) {
        if (itemPosition < 0) {
            return;
        }
        playbackPositions.remove(itemPosition);
        playbackPositions.put(itemPosition, Math.max(0L, playbackPosition));
        while (playbackPositions.size() > 50) {
            final Integer oldest = playbackPositions.keySet().iterator().next();
            playbackPositions.remove(oldest);
        }
    }

    public boolean isAutoAdvance() {
        return autoAdvance;
    }

    public void setAutoAdvance(final boolean autoAdvance) {
        this.autoAdvance = autoAdvance;
    }

    public void reset() {
        items.clear();
        playbackPositions.clear();
        position = 0;
    }
}
