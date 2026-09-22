package org.schabi.newpipe.fragments.list.shorts;

import androidx.lifecycle.ViewModel;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity-scoped Shorts feed cache. Survives fragment view recreation
 * (tab rebuilds, rotation), so the feed never resets to the first video
 * and is not reloaded from network on every recreation.
 */
public class ShortsFeedViewModel extends ViewModel {
    private final List<StreamInfoItem> items = new ArrayList<>();
    private int position = 0;

    public List<StreamInfoItem> getItems() {
        return items;
    }

    public void replaceItems(final List<StreamInfoItem> fresh) {
        items.clear();
        items.addAll(fresh);
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
}
