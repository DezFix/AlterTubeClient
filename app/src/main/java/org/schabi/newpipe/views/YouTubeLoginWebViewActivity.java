package org.schabi.newpipe.views;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.youtube.YouTubeCredentialStore;
import org.schabi.newpipe.youtube.YouTubeSubscriptionImportHelper;
import org.schabi.newpipe.youtube.YouTubeSubscriptionImportHelper.Channel;
import org.schabi.newpipe.youtube.YouTubeSubscriptionImportHelper.Snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class YouTubeLoginWebViewActivity extends BaseLoginWebViewActivity {
    public static final String EXTRA_SYNC_ONLY = "youtube_sync_only";
    public static final String EXTRA_SUBSCRIPTIONS_CACHE_PATH =
            "youtube_subscriptions_cache_path";
    public static final String EXTRA_ERROR = "youtube_sync_error";

    private static final String YOUTUBE_URL = "https://www.youtube.com/";
    private static final String SIGN_IN_URL = "https://www.youtube.com/signin";
    private static final String SUBSCRIPTIONS_URL =
            "https://www.youtube.com/feed/subscriptions";
    private static final long COLLECTION_DELAY_MS = 800L;
    private static final long LOGIN_TIMEOUT_MS = 300_000L;
    private static final int MAX_COLLECTION_ROUNDS = 60;
    private static final int MIN_COLLECTION_ROUNDS = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private volatile String capturedPoToken;
    private volatile boolean sessionDetected;
    private boolean collectionStarted;
    private boolean resultStarted;
    private boolean webViewDestroyed;
    private int stableCollectionRounds;
    private int collectionRounds;

    @Override
    protected String getLoginUrl() {
        return isSyncOnly() ? SUBSCRIPTIONS_URL : SIGN_IN_URL;
    }

    @Override
    protected String getSuccessCookieIndicator() {
        return "SID=";
    }

    @Override
    protected void configureWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webSettings.setMediaPlaybackRequiresUserGesture(true);
        webSettings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36");
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);
        handler.postDelayed(this::finishWithError, LOGIN_TIMEOUT_MS);
    }

    @Override
    protected WebViewClient createWebViewClient() {
        return new YouTubeWebViewClient();
    }

    @Override
    protected void handleSuccessfulLogin(String cookies) {
    }

    @Override
    protected void loadLoginUrl() {
        if (isSyncOnly()) {
            YouTubeSubscriptionImportHelper.clearYoutubeSessionCookies(
                    () -> seedStoredCookies(CookieManager.getInstance()));
        } else {
            super.loadLoginUrl();
        }
    }

    @Override
    public void onBackPressed() {
        clearWebViewCookies();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        ioExecutor.shutdownNow();
        if (!webViewDestroyed && webView != null) {
            webView.stopLoading();
            webView.onPause();
            webView.removeAllViews();
            webView.destroy();
            webViewDestroyed = true;
        }
        super.onDestroy();
    }

    private boolean isSyncOnly() {
        return getIntent().getBooleanExtra(EXTRA_SYNC_ONLY, false);
    }

    private void seedStoredCookies(CookieManager cookieManager) {
        String cookies = YouTubeCredentialStore.getCookies(this);
        Map<String, String> values = new LinkedHashMap<>();
        addCookies(values, cookies);
        if (values.isEmpty()) {
            finishWithError();
            return;
        }
        AtomicInteger remaining = new AtomicInteger(values.size());
        AtomicInteger rejectedRequiredCookies = new AtomicInteger();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            String value = name + "=" + entry.getValue()
                    + "; Domain=.youtube.com; Path=/; Secure; SameSite=None";
            cookieManager.setCookie(YOUTUBE_URL, value, saved -> {
                if (!Boolean.TRUE.equals(saved) && isRequiredSessionCookie(name)) {
                    rejectedRequiredCookies.incrementAndGet();
                }
                if (remaining.decrementAndGet() == 0) {
                    runOnUiThread(() -> {
                        if (!isUnavailable()) {
                            cookieManager.flush();
                            if (rejectedRequiredCookies.get() == 0
                                    && YouTubeCredentialStore.hasSessionCookie(
                                    cookieManager.getCookie(YOUTUBE_URL))) {
                                super.loadLoginUrl();
                            } else {
                                finishWithError();
                            }
                        }
                    });
                }
            });
        }
    }

    private static boolean isRequiredSessionCookie(final String name) {
        return "SAPISID".equals(name)
                || "__Secure-1PAPISID".equals(name)
                || "__Secure-3PAPISID".equals(name)
                || "__Secure-1PSID".equals(name)
                || "__Secure-3PSID".equals(name);
    }

    private void startCollection() {
        if (collectionStarted || resultStarted) {
            return;
        }
        collectionStarted = true;
        stableCollectionRounds = 0;
        collectionRounds = 0;
        collectCurrentPage();
    }

    private void collectCurrentPage() {
        if (resultStarted || isUnavailable()) {
            return;
        }
        YouTubeSubscriptionImportHelper.collectCurrentPage(webView, snapshot -> {
            if (resultStarted || isUnavailable()) {
                return;
            }
            int previousSize = channels.size();
            for (Channel channel : snapshot.getChannels()) {
                channels.putIfAbsent(channel.getUrl(), channel);
            }
            boolean noGrowth = channels.size() == previousSize;
            stableCollectionRounds = noGrowth ? stableCollectionRounds + 1 : 0;
            collectionRounds++;
            boolean atBottom = snapshot.getScrollHeight() <= 0
                    || snapshot.getScrollY() + snapshot.getViewport()
                    >= snapshot.getScrollHeight() - 32;
            if (collectionRounds >= MAX_COLLECTION_ROUNDS
                    || collectionRounds >= MIN_COLLECTION_ROUNDS
                    && atBottom && stableCollectionRounds >= 2) {
                collectMediaResourcesAndFinish();
                return;
            }
            YouTubeSubscriptionImportHelper.scroll(webView);
            handler.postDelayed(this::collectCurrentPage, COLLECTION_DELAY_MS);
        });
    }

    private void collectMediaResourcesAndFinish() {
        YouTubeSubscriptionImportHelper.collectMediaResourceUrls(webView, urls -> {
            for (String url : urls) {
                String token = YouTubeSubscriptionImportHelper.extractPoToken(url);
                if (token != null) {
                    capturedPoToken = token;
                    break;
                }
            }
            finishCollection();
        });
        handler.postDelayed(this::finishCollection, 2_000L);
    }

    private void finishCollection() {
        if (resultStarted || isUnavailable()) {
            return;
        }
        resultStarted = true;
        handler.removeCallbacksAndMessages(null);
        String cookies = collectWebViewCookies();
        if (!YouTubeCredentialStore.hasSessionCookie(cookies)) {
            finishWithError();
            return;
        }
        List<Channel> snapshot = new ArrayList<>(channels.values());
        String poToken = capturedPoToken;
        ioExecutor.execute(() -> {
            try {
                YoutubeParsingHelper.getAuthorizationHeader(cookies);
                YouTubeCredentialStore.saveCredentials(this, cookies, poToken);
                String path = YouTubeSubscriptionImportHelper.writeCache(this, snapshot);
                runOnUiThread(() -> {
                    if (!isUnavailable()) {
                        finishWithSuccess(path);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!isUnavailable()) {
                        finishWithError();
                    }
                });
            }
        });
    }

    private String collectWebViewCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        Map<String, String> values = new LinkedHashMap<>();
        addCookies(values, cookieManager.getCookie(YOUTUBE_URL));
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (result.length() > 0) {
                result.append("; ");
            }
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private void addCookies(Map<String, String> values, String header) {
        if (header == null || header.isEmpty()) {
            return;
        }
        String[] cookies = header.split(";");
        for (String item : cookies) {
            String cookie = item.trim();
            int separator = cookie.indexOf('=');
            if (separator <= 0 || separator == cookie.length() - 1) {
                continue;
            }
            values.put(cookie.substring(0, separator), cookie.substring(separator + 1));
        }
    }

    private void finishWithSuccess(String path) {
        if (isUnavailable()) {
            return;
        }
        clearWebViewCookies(() -> {
            if (isUnavailable()) {
                return;
            }
            Intent result = new Intent();
            result.putExtra(EXTRA_SUBSCRIPTIONS_CACHE_PATH, path);
            result.putExtra(EXTRA_SYNC_ONLY, isSyncOnly());
            setResult(RESULT_OK, result);
            closeWebView();
        });
    }

    private void finishWithError() {
        if (isUnavailable()) {
            return;
        }
        resultStarted = true;
        handler.removeCallbacksAndMessages(null);
        clearWebViewCookies(() -> {
            if (isUnavailable()) {
                return;
            }
            Intent result = new Intent();
            result.putExtra(EXTRA_ERROR, "sync_failed");
            setResult(RESULT_CANCELED, result);
            closeWebView();
        });
    }

    private void closeWebView() {
        if (webViewDestroyed || webView == null) {
            finish();
            return;
        }
        webView.stopLoading();
        webView.onPause();
        webView.removeAllViews();
        webView.destroy();
        webViewDestroyed = true;
        finish();
    }

    private void clearWebViewCookies() {
        clearWebViewCookies(null);
    }

    private void clearWebViewCookies(@Nullable Runnable completion) {
        YouTubeSubscriptionImportHelper.clearYoutubeSessionCookies(() -> runOnUiThread(() -> {
            if (completion != null) {
                completion.run();
            }
        }));
    }

    private boolean isUnavailable() {
        return isFinishing() || isDestroyed() || webViewDestroyed;
    }

    private static boolean isAllowedNavigationUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.toLowerCase(Locale.ROOT).equals("youtube.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".youtube.com")
                    || host.toLowerCase(Locale.ROOT).equals("accounts.google.com"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isSubscriptionsUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            String path = uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("youtube.com")
                    || host.equalsIgnoreCase("www.youtube.com")
                    || host.equalsIgnoreCase("m.youtube.com"))
                    && "/feed/subscriptions".equals(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    private class YouTubeWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (isUnavailable() || !isAllowedNavigationUrl(url)) {
                return;
            }
            if (!sessionDetected && YouTubeCredentialStore.hasSessionCookie(
                    collectWebViewCookies())) {
                sessionDetected = true;
                if (!isSubscriptionsUrl(url)) {
                    view.loadUrl(SUBSCRIPTIONS_URL);
                    return;
                }
            }
            if (sessionDetected && isSubscriptionsUrl(url)) {
                handler.postDelayed(YouTubeLoginWebViewActivity.this::startCollection, 1_200L);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !isAllowedNavigationUrl(request.getUrl().toString());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !isAllowedNavigationUrl(url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (sessionDetected) {
                String token = YouTubeSubscriptionImportHelper.extractPoToken(
                        request.getUrl().toString());
                if (token != null) {
                    capturedPoToken = token;
                }
            }
            return null;
        }
    }
}
