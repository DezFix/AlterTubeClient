package org.schabi.newpipe.views.shorts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

/**
 * One video of the vertical feed.
 *
 * <p>The playback is not a raw media URL: YouTube streams are resolved with the regular
 * {@link org.schabi.newpipe.player.resolver.VideoPlaybackResolver}, so SABR, HLS and DASH
 * all work exactly like in the normal player.</p>
 */
public class ShortsVideoItem {
    private final StreamInfoItem info;

    public ShortsVideoItem(@NonNull final StreamInfoItem info) {
        this.info = info;
    }

    @NonNull
    public StreamInfoItem getInfo() {
        return info;
    }

    @Nullable
    public String getTitle() {
        final String name = info.getName();
        return name == null ? "" : name;
    }

    @Nullable
    public String getAuthor() {
        return info.getUploaderName();
    }

    @Nullable
    public String getUrl() {
        return info.getUrl();
    }
}
