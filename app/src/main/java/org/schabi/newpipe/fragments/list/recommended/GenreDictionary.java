package org.schabi.newpipe.fragments.list.recommended;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in genre dictionary used to turn watch history into meaningful search queries
 * instead of feeding YouTube's generic feed.
 */
public final class GenreDictionary {

    public static final String GAMES = "games";
    public static final String FOOD = "food";
    public static final String AUTO = "auto";
    public static final String SPORT = "sport";
    public static final String MUSIC = "music";
    public static final String TECH = "tech";
    public static final String NEWS = "news";
    public static final String TRAVEL = "travel";
    public static final String BEAUTY = "beauty";
    public static final String DIY = "diy";
    public static final String EDUCATION = "education";
    public static final String ANIMALS = "animals";
    public static final String FITNESS = "fitness";
    public static final String FINANCE = "finance";
    public static final String HUMOR = "humor";
    public static final String ART = "art";

    /** Query templates, ordered: the first one is the main request, the rest widen it. */
    private static final Map<String, List<String>> QUERIES = buildQueries();
    /** Extra seeds that only refine the query (used when we do not know the exact genre). */
    private static final Map<String, List<String>> REFINEMENTS = buildRefinements();

    private GenreDictionary() {
    }

    private static Map<String, List<String>> buildQueries() {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(GAMES, Arrays.asList(
                "игровые прохождения",
                "игровые новости",
                "обзор игр",
                "прохождение игр"));
        map.put(FOOD, Arrays.asList(
                "рецепты блюд",
                "готовка рецепты",
                "обзор продуктов еда"));
        map.put(AUTO, Arrays.asList(
                "авто обзоры",
                "авто новости",
                "тест-драйвы"));
        map.put(SPORT, Arrays.asList(
                "спорт новости",
                "спорт обзоры",
                "матчи разбор"));
        map.put(MUSIC, Arrays.asList(
                "музыка новинки",
                "клипы 2026",
                "обзор музыки"));
        map.put(TECH, Arrays.asList(
                "обзоры техники",
                "гаджеты новости",
                "сравнения техники"));
        map.put(NEWS, Arrays.asList(
                "новости сегодня",
                "разбор новостей",
                "главное за день"));
        map.put(TRAVEL, Arrays.asList(
                "путешествия обзоры",
                "куда поехать отдых",
                "-travel влог"));
        map.put(BEAUTY, Arrays.asList(
                "уроки макияжа",
                "уход за кожей",
                "обзоры косметики"));
        map.put(DIY, Arrays.asList(
                "своими руками мастер класс",
                "ремонт дома советы",
                " diy projects"));
        map.put(EDUCATION, Arrays.asList(
                "уроки для начинающих",
                "наука explained",
                "разборы уроков"));
        map.put(ANIMALS, Arrays.asList(
                "смешные животные",
                "уход за питомцами",
                "природа документальный"));
        map.put(FITNESS, Arrays.asList(
                "тренировки дома",
                "здоровье советы",
                "программы тренировок"));
        map.put(FINANCE, Arrays.asList(
                "личные финансы советы",
                "экономика простым языком",
                "накопления как"));
        map.put(HUMOR, Arrays.asList(
                "смешные видео",
                "юмор подборка",
                "приколы"));
        map.put(ART, Arrays.asList(
                "рисование для начинающих",
                "арт видео",
                "цифровое искусство"));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<String>> buildRefinements() {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(GAMES, Arrays.asList("прохождение", "обзор", "гайд", "что нового"));
        map.put(FOOD, Arrays.asList("рецепт", "быстро", "просто"));
        map.put(AUTO, Arrays.asList("тест-драйв", "разбор", "сравнение"));
        map.put(SPORT, Arrays.asList("разбор", "итоги", "лучшее"));
        map.put(MUSIC, Arrays.asList("новый", "лучшее", "подборка"));
        map.put(TECH, Arrays.asList("обзор", "сравнение", "тест"));
        map.put(NEWS, Arrays.asList("разбор", "сегодня", "главное"));
        map.put(TRAVEL, Arrays.asList("обзор", "маршрут", "советы"));
        map.put(BEAUTY, Arrays.asList("урок", "обзор", "для начинающих"));
        map.put(DIY, Arrays.asList("своими руками", "инструкция", "идея"));
        map.put(EDUCATION, Arrays.asList("урок", "разбор", "основы"));
        map.put(ANIMALS, Arrays.asList("смешное", "советы", "документальный"));
        map.put(FITNESS, Arrays.asList("дома", "программа", "разминка"));
        map.put(FINANCE, Arrays.asList("советы", "разбор", "как"));
        map.put(HUMOR, Arrays.asList("подборка", "смешное"));
        map.put(ART, Arrays.asList("урок", "для начинающих", "идея"));
        return Collections.unmodifiableMap(map);
    }

    /** Keywords that hint at a genre. Matched against titles, uploader names and tags. */
    private static final Map<String, List<String>> KEYWORDS = buildKeywords();

    private static Map<String, List<String>> buildKeywords() {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(GAMES, Arrays.asList(
                "игра", "игр", "gameplay", "gaming", "gamer", "прохождение", "прохожу",
                "плей", "спидран", "босс", "лвл", "мобилка", "steam", "ps4", "ps5",
                "xbox", "switch", "онлайн", "пати", "мм", "шутер", "симулятор"));
        map.put(FOOD, Arrays.asList(
                "рецепт", "готов", "готовка", "кухн", "блюд", "суп", "салат", "десерт",
                "выпечк", "торт", "блюд", "куша", "ягода", "овощ", "мясо", "рецепты"));
        map.put(AUTO, Arrays.asList(
                "авто", "автомобил", "машина", "машин", "двигател", "мотор", "драйв",
                "тест-драйв", "тюнинг", "коробк", "подвеск", "шины", "гараж", "bmw",
                "mercedes", "audi", "tesla", " toyota", "honda"));
        map.put(SPORT, Arrays.asList(
                "футбол", "хокке", "баскетбол", "бои", "бокс", "мма", "уфк", "театр",
                "спорт", "матч", "турнир", "лига", "чемпионат", "олимп", "рашбол",
                "крикет", "теннис", "гандбол"));
        map.put(MUSIC, Arrays.asList(
                "музык", "клип", "песн", "трек", "альбом", "концерт", "хит", "ремикс",
                "припев", "слова", "instrumental", "cover", "мелодия", "звук"));
        map.put(TECH, Arrays.asList(
                "техник", "гаджет", "смартфон", "телефон", "ноутбук", "компьютер",
                "обзор", "сравнени", "прошивк", "ремонт телефон", "windows", "android",
                "iphone", "macbook", "процессор", "видеокарт"));
        map.put(NEWS, Arrays.asList(
                "новост", "сюжет", "репортаж", "интервью", "аналитик", "что произошло",
                "сегодня", "неделя", "итоги", "выпуск новостей", "breaking"));
        map.put(TRAVEL, Arrays.asList(
                "путешеств", "поездк", "отдых", "туризм", "маршрут", "город",
                "страна", "море", "горы", "отель", "виза", "аэропорт", "влог"));
        map.put(BEAUTY, Arrays.asList(
                "макияж", "косметик", "уход", "кожа", "волос", "ногти", "стиль",
                "мода", "beauty", "маникюр", "брови", "губы"));
        map.put(DIY, Arrays.asList(
                "своими руками", "ремонт", "мастер-класс", "сделал сам", "инструкц",
                "сборк", "модел", "столяр", "электрик", "поделк", "лайфхак"));
        map.put(EDUCATION, Arrays.asList(
                "урок", "учим", "обучени", "курс", "лекц", "наука", "физик", "математ",
                "истори", "язык", "englis", "уроки", "разбор задач", "доказательств"));
        map.put(ANIMALS, Arrays.asList(
                "кошк", "собак", "животн", "питом", "природа", "хинь", "птиц",
                "рыбк", "насеком", "документальн", "зоо", "лайфхак для кошк"));
        map.put(FITNESS, Arrays.asList(
                "трениров", "спортзал", "мышц", "пресс", "похуден", "диет", "здоров",
                "йога", "растяжк", "зарядка", "массаж", "осанка", "бег"));
        map.put(FINANCE, Arrays.asList(
                "деньг", "финанс", "инвест", "накоп", "заработ", "бизнес", "эконом",
                "кредит", "вклад", "налог", "бухгалтер", "зарплат"));
        map.put(HUMOR, Arrays.asList(
                "смешн", "прикол", "юмор", "смешно", "ржака", "мем", "подборка смешн",
                "жиза", "ахах"));
        map.put(ART, Arrays.asList(
                "рисова", "рисунок", "арт", "живопис", "скетч", "цифровое искусство",
                "дизайн", "иллюстрац", "анимац", "фото искусство"));
        return Collections.unmodifiableMap(map);
    }

    public static List<String> allGenres() {
        return new java.util.ArrayList<>(KEYWORDS.keySet());
    }

    /** Human readable genre name, shown in the "why this video" label. */
    public static String displayName(final String genre) {
        final String name = DISPLAY_NAMES.get(genre);
        return name == null ? genre : name;
    }

    private static Map<String, String> buildDisplayNames() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put(GAMES, "игры");
        map.put(FOOD, "еда и рецепты");
        map.put(AUTO, "авто");
        map.put(SPORT, "спорт");
        map.put(MUSIC, "музыка");
        map.put(TECH, "техника");
        map.put(NEWS, "новости");
        map.put(TRAVEL, "путешествия");
        map.put(BEAUTY, "красота и стиль");
        map.put(DIY, "своими руками");
        map.put(EDUCATION, "обучение и наука");
        map.put(ANIMALS, "животные и природа");
        map.put(FITNESS, "фитнес и здоровье");
        map.put(FINANCE, "деньги");
        map.put(HUMOR, "юмор");
        map.put(ART, "творчество");
        return Collections.unmodifiableMap(map);
    }

    private static final Map<String, String> DISPLAY_NAMES = buildDisplayNames();

    public static List<String> queriesFor(final String genre) {
        final List<String> list = QUERIES.get(genre);
        return list == null ? Collections.emptyList() : list;
    }

    public static List<String> refinementsFor(final String genre) {
        final List<String> list = REFINEMENTS.get(genre);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * Scores the given text against every genre. Returns genre id to score, where a
     * hit in the title counts more than a hit in an uploader name.
     */
    public static Map<String, Integer> score(final String title, final String uploader) {
        final Map<String, Integer> result = new LinkedHashMap<>();
        final String normalizedTitle = normalize(title);
        final String normalizedUploader = normalize(uploader);
        for (final Map.Entry<String, List<String>> entry : KEYWORDS.entrySet()) {
            int score = 0;
            for (final String keyword : entry.getValue()) {
                if (normalizedTitle.contains(keyword)) {
                    score += 3;
                }
                if (normalizedUploader.contains(keyword)) {
                    score += 2;
                }
            }
            if (score > 0) {
                result.put(entry.getKey(), score);
            }
        }
        return result;
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
