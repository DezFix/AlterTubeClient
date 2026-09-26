package org.schabi.newpipe.fragments.list.shorts;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;

import java.io.Serializable;

public final class ShortsFeedSource implements Serializable {
    public enum Type {
        CHANNEL,
        SEARCH,
        FRESH
    }

    private final Type type;
    private final ListLinkHandler channelHandler;
    private final String query;
    private final String channelName;
    private final String channelUrl;
    private final String freshnessWindow;
    private Page nextPage;
    private boolean loaded;
    private int emptyPageCount;

    private ShortsFeedSource(final Type type,
                             final ListLinkHandler channelHandler,
                             final String query,
                             final String channelName,
                             final String channelUrl,
                             final String freshnessWindow) {
        this.type = type;
        this.channelHandler = channelHandler;
        this.query = query;
        this.channelName = channelName;
        this.channelUrl = channelUrl;
        this.freshnessWindow = freshnessWindow;
    }

    public static ShortsFeedSource channel(final ListLinkHandler channelHandler,
                                           final String channelName,
                                           final String channelUrl) {
        return new ShortsFeedSource(Type.CHANNEL, channelHandler, null,
                channelName, channelUrl, null);
    }

    public static ShortsFeedSource search(final String query) {
        return new ShortsFeedSource(Type.SEARCH, null, query, null, null, null);
    }

    public static ShortsFeedSource fresh(final String query, final String freshnessWindow) {
        return new ShortsFeedSource(Type.FRESH, null, query, null, null, freshnessWindow);
    }

    public Type getType() {
        return type;
    }

    public String getFreshnessWindow() {
        return freshnessWindow;
    }

    public ListLinkHandler getChannelHandler() {
        return channelHandler;
    }

    public String getQuery() {
        return query;
    }

    public String getChannelName() {
        return channelName;
    }

    public String getChannelUrl() {
        return channelUrl;
    }

    public Page getNextPage() {
        return nextPage;
    }

    public void setNextPage(final Page nextPage) {
        this.nextPage = nextPage;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(final boolean loaded) {
        this.loaded = loaded;
    }

    public int getEmptyPageCount() {
        return emptyPageCount;
    }

    public void incrementEmptyPageCount() {
        emptyPageCount++;
    }

    public void resetEmptyPageCount() {
        emptyPageCount = 0;
    }
}
