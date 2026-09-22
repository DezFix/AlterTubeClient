package org.schabi.newpipe.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Built-in politics block for AlterTube feeds (Shorts, Recommended, Music).
 * Two tiers so innocent words survive:
 * <ul>
 *   <li>CONTAINS — long stems, safe as substrings
 *       ("путина" matches, "трамплин" would too, hence trump is below);</li>
 *   <li>WORDS — short tokens matched on word boundaries only, so "дума"
 *       matches the institution but not "думаю", and "trump" matches Trump
 *       but not "trumpet".</li>
 * </ul>
 * Deliberately avoids bare "war/война" so games keep passing.
 */
public final class ContentFilter {
    private ContentFilter() {
    }

    private static final Set<String> CONTAINS = new HashSet<>(Arrays.asList(
            // RU stems and phrases
            "путин", "песков", "лавров", "медведев", "навальн", "лукашенко",
            "зеленск", "байден", "кремл", "госдума", "верховна рада",
            "единая россия", "слуга народу", "кпрф", "лдпр",
            "выбор", "референдум", "инаугурац", "пропаганд", "митинг",
            "протест", "санкци", "мобилизац", "президент", "депутат",
            "сенатор", "министр", "губернатор", "политик",
            // UA stems and phrases
            "путін", "зеленськ", "лукашенк", "байден",
            "вибор", "референдум", "пропаганд", "мітинг", "протест",
            "санкці", "мобілізац", "президент", "депутат", "політик",
            // EN stems and phrases
            "putin", "zelensky", "lukashenko", "biden", "navalny",
            "kremlin", "election", "referendum", "inaugurat",
            "propaganda", "protest", "sanction", "mobiliz",
            "white house", "congress", "parliament", "senator", "minister",
            "president", "politic"
    ));

    private static final Pattern WORDS;

    static {
        final String[] words = {
                "сво", "дума", "всу", "мэр", "trump", "duma",
        };
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append("\\b").append(Pattern.quote(words[i])).append("\\b");
        }
        WORDS = Pattern.compile(sb.toString(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * @return true when the title or channel looks political and must be hidden.
     */
    public static boolean isPoliticsBlocked(final String title, final String channel) {
        final String haystack = ((title == null ? "" : title) + " "
                + (channel == null ? "" : channel)).toLowerCase(Locale.ROOT);
        if (haystack.trim().isEmpty()) {
            return false;
        }
        for (final String keyword : CONTAINS) {
            if (haystack.contains(keyword)) {
                return true;
            }
        }
        return WORDS.matcher(haystack).find();
    }
}
