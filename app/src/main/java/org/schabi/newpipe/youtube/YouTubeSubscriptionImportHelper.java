package org.schabi.newpipe.youtube;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebStorage;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor.InvalidSourceException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class YouTubeSubscriptionImportHelper {
    public static final String CACHE_FILE_NAME = "youtube_subscription_import.json";
    public static final String LOGIN_PROFILE_NAME = "youtube_account_login";
    private static final String YOUTUBE_ORIGIN = "https://www.youtube.com";
    private static final String MEDIA_HOST_SUFFIX = ".googlevideo.com";
    private static final int MAX_PASTED_CHANNEL_LIST_LENGTH = 500_000;
    private static final int MAX_PASTED_CHANNELS = 500;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final String COLLECTION_SCRIPT =
            "(function(){var roots=document.querySelectorAll('ytd-rich-item-renderer,"
                    + "ytd-grid-video-renderer,ytd-video-renderer');"
                    + "var avatar=document.querySelector('#avatar-btn,"
                    + "ytd-topbar-menu-button-renderer #avatar-btn');"
                    + "var authenticated=!!(avatar&&avatar.getClientRects().length>0)&&"
                    + "!document.querySelector('a[href*=\"ServiceLogin\"]');"
                    + "var anchors=[];var i;for(i=0;i<roots.length;i++){"
                    + "var nested=roots[i].querySelectorAll('a[href]');for(var j=0;j<nested.length;j++){"
                    + "anchors.push(nested[j]);}}"
                    + "var seen={};var channels=[];for(i=0;i<anchors.length;i++){var a=anchors[i];"
                    + "var raw=a.getAttribute('href')||'';if(!raw){continue;}var u;try{u=new URL(raw,location.href);}"
                    + "catch(e){continue;}if(u.protocol!=='https:'){continue;}var host=u.hostname.toLowerCase();"
                    + "if(host!=='youtube.com'&&host!=='www.youtube.com'&&host!=='m.youtube.com'&&"
                    + "host!=='music.youtube.com'){continue;}var path=u.pathname.replace(/\\/+$/,'');"
                    + "var valid=path.indexOf('/channel/')===0||path.indexOf('/@')===0||"
                    + "path.indexOf('/c/')===0||path.indexOf('/user/')===0;if(!valid||path.length<3){continue;}"
                    + "var key=path;if(seen[key]){continue;}seen[key]=true;var name=a.getAttribute('aria-label')||"
                    + "a.getAttribute('title')||'';var parent=a;while(parent&&parent!==document){if("
                    + "parent.querySelector){var n=parent.querySelector('ytd-channel-name a,#channel-name a,"
                    + ".ytd-channel-name');if(n&&n.textContent){name=n.textContent;break;}}parent=parent.parentElement;}"
                    + "if(!name){name=(a.textContent||'').trim();}channels.push({url:u.origin+u.pathname,name:name});}"
                    + "return JSON.stringify({channels:channels,authenticated:authenticated,scrollHeight:Math.max("
                    + "document.body?document.body.scrollHeight:0,document.documentElement?document.documentElement.scrollHeight:0),"
                    + "scrollY:window.pageYOffset||document.documentElement.scrollTop||0,viewport:window.innerHeight||0});})()";

    private static final String SCROLL_SCRIPT =
            "(function(){var e=document.scrollingElement||document.documentElement;"
                    + "var d=Math.max(window.innerHeight*0.8,600);e.scrollTop=Math.min(e.scrollHeight,e.scrollTop+d);"
                    + "window.scrollBy(0,d);})()";

    private static final String MEDIA_RESOURCES_SCRIPT =
            "(function(){var a=[];var entries=performance.getEntriesByType('resource');"
                    + "for(var i=0;i<entries.length;i++){try{var u=new URL(entries[i].name);"
                    + "var h=u.hostname.toLowerCase();if(u.protocol==='https:'&&(h==='googlevideo.com'||"
                    + "h.slice(-14)==='.googlevideo.com')){a.push(entries[i].name);}}catch(e){}}"
                    + "return JSON.stringify(a);})()";

    private YouTubeSubscriptionImportHelper() {
    }

    public static File getCacheFile(Context context) {
        return new File(context.getApplicationContext().getCacheDir(), CACHE_FILE_NAME);
    }

    public static String writeCache(Context context, List<Channel> channels) throws IOException {
        File cacheDirectory = context.getApplicationContext().getCacheDir();
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw new IOException("Cache directory unavailable");
        }
        File target = new File(cacheDirectory, CACHE_FILE_NAME);
        File temporary = File.createTempFile("youtube_subscription_import", ".tmp", cacheDirectory);
        JSONArray array = new JSONArray();
        try {
            for (Channel channel : channels) {
                JSONObject object = new JSONObject();
                object.put("url", channel.url);
                object.put("name", channel.name);
                array.put(object);
            }
        } catch (final JSONException e) {
            temporary.delete();
            throw new IOException("Subscription cache could not be encoded", e);
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8))) {
            writer.write(array.toString());
            writer.flush();
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            throw new IOException("Cache file could not be replaced");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Cache file could not be stored");
        }
        return target.getAbsolutePath();
    }

    public static List<Channel> readCache(Context context, String path) throws IOException {
        File root = context.getApplicationContext().getCacheDir().getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        String rootPath = root.getAbsolutePath();
        if (!file.getAbsolutePath().startsWith(rootPath + File.separator)) {
            throw new IOException("Invalid cache path");
        }
        if (!file.isFile()) {
            throw new IOException("Cache file unavailable");
        }
        String contents;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            contents = builder.toString();
        }
        try {
            return parseChannels(new JSONTokener(contents).nextValue());
        } catch (Exception ignored) {
            List<Channel> channels = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String url = normalizeChannelUrl(line);
                    if (url != null) {
                        channels.add(new Channel(url, url));
                    }
                }
            }
            return deduplicate(channels);
        }
    }

    public static boolean isPastedChannelListSizeSupported(final String contents) {
        return contents != null && contents.length() <= MAX_PASTED_CHANNEL_LIST_LENGTH;
    }

    public static ResolvedChannelList resolvePastedChannelList(String contents)
            throws IOException, InvalidSourceException {
        if (contents == null || contents.isBlank()) {
            throw new InvalidSourceException("Clipboard is empty");
        }
        if (!isPastedChannelListSizeSupported(contents)) {
            throw new InvalidSourceException("Channel list is too large");
        }

        final Map<String, Channel> candidateChannels = new LinkedHashMap<>();
        int skippedCount = 0;
        int entryCount = 0;
        for (final String rawLine : contents.split("\\R", -1)) {
            final String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            entryCount++;
            if (entryCount > MAX_PASTED_CHANNELS) {
                throw new InvalidSourceException("Too many channels");
            }

            final Channel candidate = normalizePastedChannelEntry(line);
            if (candidate == null) {
                skippedCount++;
            } else if (candidateChannels.putIfAbsent(candidate.getUrl(), candidate) != null) {
                skippedCount++;
            }
        }

        final Map<String, Channel> resolvedChannels = new LinkedHashMap<>();
        IOException lastResolutionError = null;
        for (final Channel candidate : candidateChannels.values()) {
            try {
                final Channel resolved = resolveStableChannel(candidate);
                if (resolvedChannels.putIfAbsent(resolved.getUrl(), resolved) != null) {
                    skippedCount++;
                }
            } catch (final IOException e) {
                skippedCount++;
                lastResolutionError = e;
            }
        }

        if (resolvedChannels.isEmpty()) {
            if (lastResolutionError != null) {
                throw lastResolutionError;
            }
            throw new InvalidSourceException("No valid YouTube channels found");
        }
        return new ResolvedChannelList(new ArrayList<>(resolvedChannels.values()), skippedCount);
    }

    private static Channel normalizePastedChannelEntry(String value) {
        if (value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        if (isCanonicalChannelId(value)) {
            return new Channel(YOUTUBE_ORIGIN + "/channel/" + value, value);
        }
        if (value.startsWith("@")) {
            if (!value.matches("^@[\\p{L}\\p{N}._-]{1,100}$")) {
                return null;
            }
            return new Channel(YOUTUBE_ORIGIN + "/" + value, value.substring(1));
        }

        String candidate = value;
        final String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("youtube.com/") || lower.startsWith("www.youtube.com/")
                || lower.startsWith("m.youtube.com/") || lower.startsWith("music.youtube.com/")) {
            candidate = YOUTUBE_ORIGIN.substring(0, YOUTUBE_ORIGIN.indexOf("://") + 3) + value;
        } else if (lower.startsWith("http://")) {
            candidate = "https://" + value.substring("http://".length());
        }
        final String url = normalizeChannelUrl(candidate);
        return url == null ? null : new Channel(url, url);
    }

    public static void clearCache(Context context) {
        getCacheFile(context).delete();
    }

    public static List<Channel> resolveStableChannels(List<Channel> channels) throws IOException {
        Map<String, Channel> resolvedChannels = new LinkedHashMap<>();
        for (Channel channel : channels) {
            final Channel resolved = resolveStableChannel(channel);
            resolvedChannels.putIfAbsent(resolved.getUrl(), resolved);
        }
        return new ArrayList<>(resolvedChannels.values());
    }

    private static Channel resolveStableChannel(Channel channel) throws IOException {
        Uri uri = Uri.parse(channel.getUrl());
        String path = uri.getPath();
        if (path == null || path.startsWith("/")) {
            path = path == null ? "" : path.substring(1);
        }
        final String channelId;
        try {
            channelId = YoutubeParsingHelper.resolveChannelId(path);
        } catch (Exception e) {
            throw new IOException("Stable channel ID resolution failed", e);
        }
        if (!isCanonicalChannelId(channelId)) {
            throw new IOException("Stable channel ID resolution returned an invalid ID");
        }
        final String stableUrl = YOUTUBE_ORIGIN + "/channel/" + channelId;
        return new Channel(stableUrl, sanitizeName(channel.getName(), stableUrl));
    }

    public static void clearDefaultYoutubeBrowsingData(Context context,
                                                      @Nullable Runnable completion) {
        WebView webView = new WebView(context.getApplicationContext());
        clearYoutubeBrowsingData(webView, CookieManager.getInstance(),
                WebStorage.getInstance(), () -> MAIN_HANDLER.post(() -> {
                    webView.clearHistory();
                    webView.removeAllViews();
                    webView.destroy();
                    if (completion != null) {
                        completion.run();
                    }
                }));
    }

    public static void deleteYoutubeLoginProfile() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().deleteProfile(LOGIN_PROFILE_NAME);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static void clearYoutubeBrowsingData(WebView webView,
                                                CookieManager cookieManager,
                                                WebStorage webStorage,
                                                @Nullable Runnable completion) {
        webView.clearCache(true);
        webView.clearFormData();
        webStorage.deleteAllData();
        clearCookies(cookieManager, completion);
    }

    private static void clearCookies(CookieManager cookieManager,
                                     @Nullable Runnable completion) {
        cookieManager.removeAllCookies(value -> {
            cookieManager.flush();
            if (completion != null) {
                completion.run();
            }
        });
    }

    public static String normalizeChannelUrl(String value) {
        if (value == null) {
            return null;
        }
        try {
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if (scheme == null || host == null || path == null
                    || !"https".equalsIgnoreCase(scheme)
                    || !isYouTubeHost(host)
                    || path.indexOf("..") >= 0) {
                return null;
            }
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (!isChannelPath(path)) {
                return null;
            }
            return YOUTUBE_ORIGIN + path;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void collectCurrentPage(WebView webView, ValueCallback<Snapshot> callback) {
        webView.evaluateJavascript(COLLECTION_SCRIPT, value -> {
            Snapshot snapshot = parseSnapshot(value);
            callback.onReceiveValue(snapshot);
        });
    }

    public static void scroll(WebView webView) {
        webView.evaluateJavascript(SCROLL_SCRIPT, null);
    }

    public static void collectMediaResourceUrls(WebView webView,
                                                 ValueCallback<List<String>> callback) {
        webView.evaluateJavascript(MEDIA_RESOURCES_SCRIPT, value -> {
            List<String> urls = new ArrayList<>();
            try {
                Object decoded = new JSONTokener(value).nextValue();
                if (decoded instanceof JSONArray) {
                    JSONArray array = (JSONArray) decoded;
                    for (int i = 0; i < array.length(); i++) {
                        String url = array.optString(i, "");
                        if (isAllowedMediaUrl(url)) {
                            urls.add(url);
                        }
                    }
                } else if (decoded instanceof String) {
                    JSONArray array = new JSONArray((String) decoded);
                    for (int i = 0; i < array.length(); i++) {
                        String url = array.optString(i, "");
                        if (isAllowedMediaUrl(url)) {
                            urls.add(url);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            callback.onReceiveValue(urls);
        });
    }

    public static boolean isAllowedMediaUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("googlevideo.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(MEDIA_HOST_SUFFIX));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String extractPoToken(String value) {
        if (!isAllowedMediaUrl(value)) {
            return null;
        }
        try {
            String token = Uri.parse(value).getQueryParameter("pot");
            if (token == null || token.isEmpty() || token.length() > 4096) {
                return null;
            }
            return token;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static final class ResolvedChannelList {
        private final List<Channel> channels;
        private final int skippedCount;

        private ResolvedChannelList(final List<Channel> channels, final int skippedCount) {
            this.channels = channels;
            this.skippedCount = skippedCount;
        }

        public List<Channel> getChannels() {
            return channels;
        }

        public int getSkippedCount() {
            return skippedCount;
        }
    }

    public static final class Channel {
        private final String url;
        private final String name;

        public Channel(String url, String name) {
            this.url = url;
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public String getName() {
            return name;
        }
    }

    public static final class Snapshot {
        private final boolean authenticated;
        private final List<Channel> channels;
        private final int scrollHeight;
        private final int scrollY;
        private final int viewport;

        private Snapshot(boolean authenticated, List<Channel> channels,
                         int scrollHeight, int scrollY, int viewport) {
            this.authenticated = authenticated;
            this.channels = channels;
            this.scrollHeight = scrollHeight;
            this.scrollY = scrollY;
            this.viewport = viewport;
        }

        public boolean isAuthenticated() {
            return authenticated;
        }

        public List<Channel> getChannels() {
            return channels;
        }

        public int getScrollHeight() {
            return scrollHeight;
        }

        public int getScrollY() {
            return scrollY;
        }

        public int getViewport() {
            return viewport;
        }
    }

    private static Snapshot parseSnapshot(String value) {
        try {
            Object decoded = new JSONTokener(value).nextValue();
            JSONObject object;
            if (decoded instanceof String) {
                object = new JSONObject((String) decoded);
            } else if (decoded instanceof JSONObject) {
                object = (JSONObject) decoded;
            } else {
                return new Snapshot(false, Collections.emptyList(), 0, 0, 0);
            }
            JSONArray array = object.optJSONArray("channels");
            List<Channel> channels = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String url = normalizeChannelUrl(item.optString("url", ""));
                    if (url != null) {
                        String name = sanitizeName(item.optString("name", ""), url);
                        channels.add(new Channel(url, name));
                    }
                }
            }
            return new Snapshot(object.optBoolean("authenticated", false), deduplicate(channels),
                    object.optInt("scrollHeight", 0),
                    object.optInt("scrollY", 0),
                    object.optInt("viewport", 0));
        } catch (Exception ignored) {
            return new Snapshot(false, Collections.emptyList(), 0, 0, 0);
        }
    }

    private static List<Channel> parseChannels(Object value)
            throws IOException, JSONException {
        JSONArray array;
        if (value instanceof JSONArray) {
            array = (JSONArray) value;
        } else if (value instanceof String) {
            array = new JSONArray((String) value);
        } else {
            throw new IOException("Invalid cache format");
        }
        List<Channel> channels = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String url = normalizeChannelUrl(item.optString("url", ""));
            if (url != null) {
                channels.add(new Channel(url,
                        sanitizeName(item.optString("name", ""), url)));
            }
        }
        return deduplicate(channels);
    }

    private static List<Channel> deduplicate(List<Channel> channels) {
        Map<String, Channel> unique = new LinkedHashMap<>();
        for (Channel channel : channels) {
            if (!unique.containsKey(channel.url)) {
                unique.put(channel.url, channel);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String sanitizeName(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < 256; i++) {
            char character = value.charAt(i);
            if (!Character.isISOControl(character)) {
                result.append(character);
            }
        }
        String name = result.toString().trim();
        return name.isEmpty() ? fallback : name;
    }

    private static boolean isYouTubeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("youtube.com")
                || normalized.equals("www.youtube.com")
                || normalized.equals("m.youtube.com")
                || normalized.equals("music.youtube.com");
    }

    private static boolean isCanonicalChannelId(String channelId) {
        if (channelId == null || channelId.length() != 24 || !channelId.startsWith("UC")) {
            return false;
        }
        for (int i = 2; i < channelId.length(); i++) {
            char character = channelId.charAt(i);
            if (!((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isChannelPath(String path) {
        if (path.startsWith("/channel/")) {
            return path.length() > "/channel/".length();
        }
        if (path.startsWith("/@")) {
            return path.length() > 2;
        }
        if (path.startsWith("/c/")) {
            return path.length() > 3;
        }
        if (path.startsWith("/user/")) {
            return path.length() > 6;
        }
        return false;
    }
}
