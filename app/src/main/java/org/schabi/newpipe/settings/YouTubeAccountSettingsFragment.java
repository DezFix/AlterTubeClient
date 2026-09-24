package org.schabi.newpipe.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import org.schabi.newpipe.App;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.subscription.SubscriptionItem;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.views.YouTubeLoginWebViewActivity;
import org.schabi.newpipe.youtube.LocalDomPoTokenProvider;
import org.schabi.newpipe.youtube.YouTubeCredentialStore;
import org.schabi.newpipe.youtube.YouTubeSubscriptionImportHelper;
import org.schabi.newpipe.youtube.YouTubeSubscriptionImportHelper.Channel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class YouTubeAccountSettingsFragment extends BaseAccountSettingsFragment {
    private final CompositeDisposable disposables = new CompositeDisposable();
    private Preference syncPreference;
    private SubscriptionManager subscriptionManager;
    private boolean syncInProgress;
    private boolean logoutInProgress;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        subscriptionManager = new SubscriptionManager(requireContext().getApplicationContext());
        syncPreference = findPreference(getString(R.string.youtube_sync_subscriptions_key));
        if (syncPreference != null) {
            syncPreference.setOnPreferenceClickListener(preference -> {
                startManualSync();
                return true;
            });
        }
    }

    @Override
    protected int getPreferenceResource() {
        return R.xml.account_settings_youtube;
    }

    @Override
    protected Class<?> getLoginActivityClass() {
        return YouTubeLoginWebViewActivity.class;
    }

    @Override
    protected void onLoginClicked() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.youtube_login_warning_title)
                .setMessage(R.string.youtube_login_warning_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> startLoginActivity())
                .show();
    }

    @Override
    protected boolean hasCredentials() {
        return YouTubeCredentialStore.hasCredentials(requireContext());
    }

    @Override
    protected String getCookiesKey() {
        return getString(R.string.youtube_cookies_key);
    }

    @Override
    protected String getOverrideSwitchKey() {
        return getString(R.string.override_cookies_youtube_key);
    }

    @Override
    protected String getOverrideValueKey() {
        return getString(R.string.override_cookies_youtube_value_key);
    }

    @Override
    protected boolean shouldCheckOverrideKeys() {
        return false;
    }

    @Override
    protected void handleLoginResult(Intent data) {
        if (data == null) {
            syncInProgress = false;
            updateSyncPreference();
            return;
        }
        String path = data.getStringExtra(
                YouTubeLoginWebViewActivity.EXTRA_SUBSCRIPTIONS_CACHE_PATH);
        boolean subscriptionsUnavailable = data.getBooleanExtra(
                YouTubeLoginWebViewActivity.EXTRA_SUBSCRIPTIONS_UNAVAILABLE, false);
        if (data.getStringExtra(YouTubeLoginWebViewActivity.EXTRA_ERROR) != null
                || !YouTubeCredentialStore.hasCredentials(requireContext())) {
            showSyncFailed();
            return;
        }
        LocalDomPoTokenProvider.INSTANCE.invalidate();
        if (subscriptionsUnavailable || path == null || path.isEmpty()) {
            refreshAccountDependentState();
            showSyncFailed();
            return;
        }
        onLoginSuccess();
        importSubscriptions(path);
    }

    @Override
    protected void performLogout() {
        if (logoutInProgress || syncInProgress) {
            return;
        }
        logoutInProgress = true;
        updateSyncPreference();
        Context context = requireContext().getApplicationContext();
        try {
            YouTubeSubscriptionImportHelper.clearDefaultYoutubeBrowsingData(
                    context, () -> runOnUiThread(() -> completeLogout(context)));
        } catch (RuntimeException ignored) {
            completeLogout(context);
        }
    }

    private void completeLogout(Context context) {
        try {
            YouTubeSubscriptionImportHelper.deleteYoutubeLoginProfile();
            YouTubeCredentialStore.clearCredentials(context);
            YouTubeSubscriptionImportHelper.clearCache(context);
            LocalDomPoTokenProvider.INSTANCE.invalidate();
        } finally {
            logoutInProgress = false;
            if (isAdded() && getView() != null) {
                onLogoutSuccess();
            }
        }
    }

    @Override
    protected void refreshAccountDependentState() {
        super.refreshAccountDependentState();
        App.reconcileYoutubePlayerClient(requireContext());
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        updateSyncPreference();
    }

    @Override
    public void onPause() {
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        syncInProgress = false;
        super.onDestroyView();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_LOGIN) {
            if (resultCode == android.app.Activity.RESULT_OK) {
                super.onActivityResult(requestCode, resultCode, data);
            } else {
                syncInProgress = false;
                updateSyncPreference();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        super.onSharedPreferenceChanged(sharedPreferences, key);
        updateSyncPreference();
    }

    private void startManualSync() {
        if (syncInProgress || logoutInProgress) {
            return;
        }
        if (!YouTubeCredentialStore.hasCredentials(requireContext())) {
            Toast.makeText(requireContext(), R.string.youtube_sync_login_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        syncInProgress = true;
        updateSyncPreference();
        startActivityForResult(new Intent(requireContext(), YouTubeLoginWebViewActivity.class)
                .putExtra(YouTubeLoginWebViewActivity.EXTRA_SYNC_ONLY, true), REQUEST_LOGIN);
    }

    private void importSubscriptions(String path) {
        syncInProgress = true;
        updateSyncPreference();
        final Context context = requireContext().getApplicationContext();
        disposables.add(io.reactivex.rxjava3.core.Single.fromCallable(() -> {
                    int found;
                    int added;
                    try {
                        List<Channel> channels = YouTubeSubscriptionImportHelper.resolveStableChannels(
                                YouTubeSubscriptionImportHelper.readCache(context, path));
                        if (channels.isEmpty()) {
                            throw new IOException("No authenticated subscriptions were found");
                        }
                        found = channels.size();
                        List<SubscriptionItem> items = new ArrayList<>(channels.size());
                        for (Channel channel : channels) {
                            items.add(new SubscriptionItem(
                                    ServiceList.YouTube.getServiceId(),
                                    channel.getUrl(),
                                    channel.getName()));
                        }
                        added = subscriptionManager.mergeImportedSubscriptions(items).size();
                        return new int[]{found, added};
                    } finally {
                        YouTubeSubscriptionImportHelper.clearCache(context);
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    syncInProgress = false;
                    updateSyncPreference();
                    showSyncResult(result[0], result[1]);
                }, error -> {
                    syncInProgress = false;
                    updateSyncPreference();
                    showSyncFailed();
                }));
    }

    private void showSyncResult(int found, int added) {
        if (getContext() == null) {
            return;
        }
        Toast.makeText(requireContext(),
                getString(R.string.youtube_subscription_sync_complete, added, found),
                Toast.LENGTH_LONG).show();
    }

    private void showSyncFailed() {
        if (getContext() != null) {
            Toast.makeText(requireContext(), R.string.youtube_subscription_sync_failed,
                    Toast.LENGTH_LONG).show();
        }
        syncInProgress = false;
        updateSyncPreference();
    }

    private void updateSyncPreference() {
        if (syncPreference == null) {
            return;
        }
        boolean loggedIn = YouTubeCredentialStore.hasCredentials(requireContext());
        boolean accountActionInProgress = syncInProgress || logoutInProgress;
        syncPreference.setEnabled(loggedIn && !accountActionInProgress);
        login.setEnabled(!loggedIn && !accountActionInProgress);
        logout.setEnabled(loggedIn && !accountActionInProgress);
        if (accountActionInProgress) {
            syncPreference.setSummary(R.string.youtube_subscription_sync_in_progress);
        } else {
            syncPreference.setSummary(loggedIn
                    ? R.string.youtube_subscription_sync_summary
                    : R.string.youtube_subscription_sync_login_required_summary);
        }
    }
}
