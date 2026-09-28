package org.schabi.newpipe.fragments.list.recommended;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Genre preferences ("Темы"): the user picks which topics they like and which ones they do
 * not want to see. Unselected genres are neither boosted nor blocked, so the first launch
 * keeps working exactly as before.
 */
public class GenrePreferences {

    private static final String KEY_ENABLED = "genres_enabled";
    private static final String KEY_LIKED = "genres_liked";
    private static final String KEY_BLOCKED = "genres_blocked";

    private static final List<String> GENRES = Collections.unmodifiableList(Arrays.asList(
            GenreDictionary.GAMES,
            GenreDictionary.FOOD,
            GenreDictionary.AUTO,
            GenreDictionary.SPORT,
            GenreDictionary.MUSIC,
            GenreDictionary.TECH,
            GenreDictionary.NEWS,
            GenreDictionary.TRAVEL,
            GenreDictionary.BEAUTY,
            GenreDictionary.DIY,
            GenreDictionary.EDUCATION,
            GenreDictionary.ANIMALS,
            GenreDictionary.FITNESS,
            GenreDictionary.FINANCE,
            GenreDictionary.HUMOR,
            GenreDictionary.ART));

    private final SharedPreferences preferences;
    private final Context context;

    public GenrePreferences(@NonNull final Context context) {
        this.context = context.getApplicationContext();
        this.preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
    }

    public static List<String> genres() {
        return GENRES;
    }

    public boolean isEnabled() {
        return preferences.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(final boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public Set<String> getLiked() {
        return readSet(KEY_LIKED);
    }

    public Set<String> getBlocked() {
        return readSet(KEY_BLOCKED);
    }

    public void toggleLiked(final String genre) {
        toggle(KEY_LIKED, genre);
    }

    public void toggleBlocked(final String genre) {
        toggle(KEY_BLOCKED, genre);
    }

    public void clear() {
        preferences.edit().remove(KEY_LIKED).remove(KEY_BLOCKED).apply();
    }

    public boolean isLiked(final String genre) {
        return getLiked().contains(genre);
    }

    public boolean isBlocked(final String genre) {
        return getBlocked().contains(genre);
    }

    /** Human readable genre name from resources, so it follows the app language. */
    public String displayName(final String genre) {
        final int resId = genreNameRes(genre);
        if (resId == 0) {
            return GenreDictionary.displayName(genre);
        }
        try {
            return context.getString(resId);
        } catch (final Exception ignored) {
            return GenreDictionary.displayName(genre);
        }
    }

    private void toggle(final String key, final String genre) {
        if (genre == null) {
            return;
        }
        final Set<String> current = readSet(key);
        if (current.contains(genre)) {
            current.remove(genre);
        } else {
            current.add(genre);
            // A genre cannot be liked and blocked at the same time.
            readSet(KEY_LIKED.equals(key) ? KEY_BLOCKED : KEY_LIKED).remove(genre);
        }
        preferences.edit().putStringSet(key, new LinkedHashSet<>(current)).apply();
    }

    private Set<String> readSet(final String key) {
        final Set<String> stored = preferences.getStringSet(key, null);
        return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
    }

    private int genreNameRes(final String genre) {
        if (genre == null) {
            return 0;
        }
        switch (genre) {
            case GenreDictionary.GAMES:
                return R.string.genre_games;
            case GenreDictionary.FOOD:
                return R.string.genre_food;
            case GenreDictionary.AUTO:
                return R.string.genre_auto;
            case GenreDictionary.SPORT:
                return R.string.genre_sport;
            case GenreDictionary.MUSIC:
                return R.string.genre_music;
            case GenreDictionary.TECH:
                return R.string.genre_tech;
            case GenreDictionary.NEWS:
                return R.string.genre_news;
            case GenreDictionary.TRAVEL:
                return R.string.genre_travel;
            case GenreDictionary.BEAUTY:
                return R.string.genre_beauty;
            case GenreDictionary.DIY:
                return R.string.genre_diy;
            case GenreDictionary.EDUCATION:
                return R.string.genre_education;
            case GenreDictionary.ANIMALS:
                return R.string.genre_animals;
            case GenreDictionary.FITNESS:
                return R.string.genre_fitness;
            case GenreDictionary.FINANCE:
                return R.string.genre_finance;
            case GenreDictionary.HUMOR:
                return R.string.genre_humor;
            case GenreDictionary.ART:
                return R.string.genre_art;
            default:
                return 0;
        }
    }
}
