package org.schabi.newpipe.settings.tabs;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.List;

public final class TabsManager {
    private final SharedPreferences sharedPreferences;
    private final String savedTabsKey;
    private final Context context;
    private SavedTabsChangeListener savedTabsChangeListener;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;

    private TabsManager(final Context context) {
        this.context = context;
        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.savedTabsKey = context.getString(R.string.saved_tabs_key);
    }

    public static TabsManager getManager(final Context context) {
        return new TabsManager(context);
    }

    /** Bumped when the default tab set changes: migrates stored tabs once. */
    private static final String TABS_SCHEME_KEY = "altertube_tabs_scheme";
    /**
     * v3: stored tabs are replaced with fresh defaults once, so removals
     * (e.g. Trending) also apply to existing installs. Afterwards the user
     * owns the order completely.
     */
    private static final int TABS_SCHEME_V3 = 3;

    public List<Tab> getTabs() {
        final String savedJson = sharedPreferences.getString(savedTabsKey, null);
        try {
            if (savedJson != null && !savedJson.isEmpty() && needsSchemeMigration()) {
                final List<Tab> fresh = getDefaultTabs();
                saveTabs(fresh);
                sharedPreferences.edit().putInt(TABS_SCHEME_KEY, TABS_SCHEME_V3).apply();
                return fresh;
            }
            return TabsJsonHelper.getTabsFromJson(savedJson);
        } catch (final TabsJsonHelper.InvalidJsonException e) {
            Toast.makeText(context, R.string.saved_tabs_invalid_json, Toast.LENGTH_SHORT).show();
            return getDefaultTabs();
        }
    }

    private boolean needsSchemeMigration() {
        return sharedPreferences.getInt(TABS_SCHEME_KEY, 0) < TABS_SCHEME_V3;
    }

    public void saveTabs(final List<Tab> tabList) {
        final String jsonToSave = TabsJsonHelper.getJsonToSave(tabList);
        sharedPreferences.edit().putString(savedTabsKey, jsonToSave).apply();
    }

    public void resetTabs() {
        sharedPreferences.edit().remove(savedTabsKey).apply();
    }

    public List<Tab> getDefaultTabs() {
        return TabsJsonHelper.getDefaultTabs();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Listener
    //////////////////////////////////////////////////////////////////////////*/

    public void setSavedTabsListener(final SavedTabsChangeListener listener) {
        if (preferenceChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        savedTabsChangeListener = listener;
        preferenceChangeListener = getPreferenceChangeListener();
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
    }

    public void unsetSavedTabsListener() {
        if (preferenceChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        preferenceChangeListener = null;
        savedTabsChangeListener = null;
    }

    private SharedPreferences.OnSharedPreferenceChangeListener getPreferenceChangeListener() {
        return (sp, key) -> {
            if (key != null && key.equals(savedTabsKey)) {
                if (savedTabsChangeListener != null) {
                    savedTabsChangeListener.onTabsChanged();
                }
            }
        };
    }

    public interface SavedTabsChangeListener {
        void onTabsChanged();
    }
}
