package org.schabi.newpipe.fragments.list.shorts;

import androidx.lifecycle.ViewModel;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShortsFeedViewModel extends ViewModel {
    private final List<StreamInfoItem> items = new ArrayList<>();
    private final Map<Integer, Long> playbackPositions = new HashMap<>();
    private List<ShortsFeedSource> sources = new ArrayList<>();
    private int sourceIndex;
    private int position;
    private boolean muted;
    private boolean zoom;
    private boolean autoAdvance;
    private Boolean personalizedFeed;

    public List<StreamInfoItem> getItems() {
        return items;
    }

    public List<ShortsFeedSource> getSources() {
        return sources;
    }

    public void setSources(final List<ShortsFeedSource> newSources) {
        sources = new ArrayList<>(newSources);
        sourceIndex = 0;
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public void setSourceIndex(final int newSourceIndex) {
        sourceIndex = Math.max(0, newSourceIndex);
    }

    public void replaceItems(final List<StreamInfoItem> fresh) {
        items.clear();
        items.addAll(fresh);
        playbackPositions.clear();
        position = 0;
    }

    public void appendItems(final List<StreamInfoItem> more) {
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
        if (itemPosition >= 0) {
            playbackPositions.put(itemPosition, Math.max(0L, playbackPosition));
        }
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(final boolean muted) {
        this.muted = muted;
    }

    public boolean isZoom() {
        return zoom;
    }

    public void setZoom(final boolean zoom) {
        this.zoom = zoom;
    }

    public boolean isAutoAdvance() {
        return autoAdvance;
    }

    public void setAutoAdvance(final boolean autoAdvance) {
        this.autoAdvance = autoAdvance;
    }

    public boolean hasPersonalizedFeedMode() {
        return personalizedFeed != null;
    }

    public boolean isPersonalizedFeed() {
        return Boolean.TRUE.equals(personalizedFeed);
    }

    public void setPersonalizedFeed(final boolean personalizedFeed) {
        this.personalizedFeed = personalizedFeed;
    }

    public void reset() {
        items.clear();
        playbackPositions.clear();
        sources.clear();
        sourceIndex = 0;
        position = 0;
        personalizedFeed = null;
    }
}
