package io.github.freeform.bubble;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text classifier for clipboard suggestions.
 *
 * <p>Shared by the injected SystemUI process (which builds the bubble) and by the system_server
 * clipboard hook (which is the only reliable clipboard source on Android 10+, because clipboard
 * reads are focus-gated). Keeping the classification in one place means both paths agree, and the
 * hook only ever forwards the actionable token instead of the whole clipboard text.</p>
 */
final class ClipboardClassifier {
    static final int KIND_PHONE = 1;
    static final int KIND_EXPRESS = 2;
    static final int KIND_LINK = 3;
    static final int KIND_SHOPPING = 4;
    static final int KIND_APP_LINK = 5;
    static final int KIND_ADDRESS = 6;
    /** At most this many bubbles for one copied text. */
    static final int MAX_RESULTS = 4;
    static final int KIND_COORDINATES = 7;

    static final class Result {
        final int kind;
        /** Normalized payload: dialable number, URL, deep link, tracking code or address. */
        final String value;
        /** Original clipboard text, shortened, used for the bubble body. */
        final String display;
        /** Package guessed from the host/scheme, may be null. */
        final String appPackage;

        Result(int kind, String value, String display, String appPackage) {
            this.kind = kind;
            this.value = value;
            this.display = display;
            this.appPackage = appPackage;
        }
    }

    /** Shopping sites: always offered as "open shopping app". */
    private static final String[] SHOPPING_HOSTS = {
            "taobao.com", "tmall.com", "tb.cn", "tmall.hk", "jd.com", "jd.hk", "3.cn",
            "pinduoduo.com", "yangkeduo.com", "suning.com", "vip.com", "kaola.com",
            "you.163.com", "mogujie.com", "dangdang.com", "smzdm.com"
    };
    /** Hosts that belong to a specific app (opened with it when it is installed). */
    private static final Map<String, String> HOST_APPS = new LinkedHashMap<>();
    /** Deep-link schemes mapped to their app. */
    private static final Map<String, String> SCHEME_APPS = new LinkedHashMap<>();

    static {
        HOST_APPS.put("mp.weixin.qq.com", "com.tencent.mm");
        HOST_APPS.put("weixin.qq.com", "com.tencent.mm");
        HOST_APPS.put("b23.tv", "tv.danmaku.bili");
        HOST_APPS.put("bilibili.com", "tv.danmaku.bili");
        HOST_APPS.put("v.douyin.com", "com.ss.android.ugc.aweme");
        HOST_APPS.put("douyin.com", "com.ss.android.ugc.aweme");
        HOST_APPS.put("xhslink.com", "com.xingin.xhs");
        HOST_APPS.put("xiaohongshu.com", "com.xingin.xhs");
        HOST_APPS.put("zhihu.com", "com.zhihu.android");
        HOST_APPS.put("weibo.com", "com.sina.weibo");
        HOST_APPS.put("weibo.cn", "com.sina.weibo");
        HOST_APPS.put("t.cn", "com.sina.weibo");
        HOST_APPS.put("music.163.com", "com.netease.cloudmusic");
        HOST_APPS.put("i.y.qq.com", "com.tencent.qqmusic");
        HOST_APPS.put("y.qq.com", "com.tencent.qqmusic");

        HOST_APPS.put("surl.amap.com", "com.autonavi.minimap");
        HOST_APPS.put("uri.amap.com", "com.autonavi.minimap");
        HOST_APPS.put("amap.com", "com.autonavi.minimap");
        HOST_APPS.put("ditu.amap.com", "com.autonavi.minimap");
        HOST_APPS.put("j.map.baidu.com", "com.baidu.BaiduMap");
        HOST_APPS.put("map.baidu.com", "com.baidu.BaiduMap");
        HOST_APPS.put("map.qq.com", "com.tencent.map");
        HOST_APPS.put("maps.app.goo.gl", "com.google.android.apps.maps");
        HOST_APPS.put("maps.google.com", "com.google.android.apps.maps");
        HOST_APPS.put("maps.apple.com", "com.apple.maps");
        HOST_APPS.put("maps.yandex.com", "ru.yandex.yandexmaps");

        SCHEME_APPS.put("androidamap", "com.autonavi.minimap");
        SCHEME_APPS.put("baidumap", "com.baidu.BaiduMap");
        SCHEME_APPS.put("qqmap", "com.tencent.map");
        SCHEME_APPS.put("google.navigation", "com.google.android.apps.maps");
        SCHEME_APPS.put("geo", null);
        SCHEME_APPS.put("taobao", "com.taobao.taobao");
        SCHEME_APPS.put("tmall", "com.tmall.wireless");
        SCHEME_APPS.put("weixin", "com.tencent.mm");
        SCHEME_APPS.put("alipays", "com.eg.android.AlipayGphone");
        SCHEME_APPS.put("alipay", "com.eg.android.AlipayGphone");
        SCHEME_APPS.put("bilibili", "tv.danmaku.bili");
        SCHEME_APPS.put("snssdk1128", "com.ss.android.ugc.aweme");
        SCHEME_APPS.put("xhsdiscover", "com.xingin.xhs");
        SCHEME_APPS.put("zhihu", "com.zhihu.android");
        SCHEME_APPS.put("sinaweibo", "com.sina.weibo");
        SCHEME_APPS.put("qqmusic", "com.tencent.qqmusic");
        SCHEME_APPS.put("orpheus", "com.netease.cloudmusic");
        SCHEME_APPS.put("mqqapi", "com.tencent.mobileqq");
    }

    // --- phone -------------------------------------------------------------------------
    private static final Pattern PHONE_TEXT = Pattern.compile("(?i)^(?:tel:)?[+]?[\\d\\s()\\-—–]{5,28}$");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s()\\-—–]");
    private static final Pattern CN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern CN_LANDLINE = Pattern.compile("^0\\d{9,11}$");
    private static final Pattern CN_LOCAL = Pattern.compile("^[2-9]\\d{6,7}$");
    private static final Pattern CN_SERVICE = Pattern.compile("^(?:[48]00\\d{3,7}|\\d{5})$");
    private static final Pattern INTL_PHONE = Pattern.compile("^\\+\\d{8,15}$");

    // --- links -------------------------------------------------------------------------
    private static final Pattern HTTP_URL =
            Pattern.compile("(?i)https?://[^\\s<>\"'，。；、）】》〉」』]+");
    private static final Pattern URL_TRAILING = Pattern.compile("[.,;:!?)\\]}>]+$");
    private static final Pattern BARE_HOST = Pattern.compile(
            "(?i)^(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d{1,5})?(?:[/?#]\\S*)?$");
    private static final Pattern DEEP_LINK = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://(\\S{3,})$");
    private static final Pattern SCHEME_ONLY = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):.*");

    // --- express -----------------------------------------------------------------------
    private static final Pattern UPU_CODE = Pattern.compile("^[A-Z]{2}\\d{9}[A-Z]{2}$");
    private static final Pattern PREFIXED_EXPRESS = Pattern.compile(
            "^(?:SF|YT|YD|ZTO|STO|JT|JD|DPK|EMS|CNS|CN|LP|RB|1Z)[A-Z0-9]{6,22}$");
    private static final Pattern DIGIT_EXPRESS = Pattern.compile("^\\d{10,20}$");
    private static final Pattern TRACKING_TOKEN = Pattern.compile("(?i)[A-Z]{0,2}\\d{9,20}[A-Z]{0,2}");
    private static final Pattern EXPRESS_KEYWORDS = Pattern.compile(
            "快递|物流|包裹|运单|揽收|派送|派件|签收|驿站|菜鸟|顺丰|中通|圆通|申通|韵达|极兔|德邦|邮政|京东物流");

    // --- address / coordinates ---------------------------------------------------------
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?；;]");
    private static final Pattern HAS_CJK = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");
    private static final String[] CITY_WORDS = {"省", "自治区", "特别行政区", "市", "州"};
    private static final String[] DISTRICT_WORDS = {"区", "县", "镇", "街道", "乡", "开发区", "新区"};
    private static final String[] STREET_WORDS = {
            "路", "街", "道", "巷", "号", "小区", "大厦", "广场", "村", "园", "苑", "里", "弄",
            "胡同", "大道", "号楼", "座", "栋", "中心", "商场", "酒店", "公寓", "产业园", "科技园"
    };
    /** House number / building number, i.e. the part that makes a line an address. */
    private static final Pattern HOUSE_NUMBER = Pattern.compile("\\d+\\s*(?:号|栋|座|楼|层|室|弄)");
    /** Leading labels map apps put before the address. */
    private static final Pattern ADDRESS_LABEL = Pattern.compile(
            "(?i)^(?:详细)?(?:地址|位置|地点|导航到|目的地|收货地址|所在位置)\\s*[:：]\\s*");
    /** Leading label of a phone line in a shared place card. */
    private static final Pattern PHONE_LABEL = Pattern.compile(
            "(?i)^(?:联系)?(?:电话|手机|手机号|手机号码|座机|热线|tel|phone)\\s*[:：]?\\s*");
    /** Metadata lines of a shared place card: they are never part of the address. */
    private static final Pattern MAP_METADATA_LINE = Pattern.compile(
            "电话|营业时间|人均|评分|点评|距离|距您|公里|千米|导航|路线|公交|驾车|步行|骑行|"
                    + "分享|复制|收藏|来源|打开|查看|停车场|停车场入口|出入口|楼层|营业中|休息|"
                    + "地图|高德|百度地图|腾讯地图|Google Maps|Apple 地图");
    /** "116.481028,39.989643" style coordinate pairs (decimal degrees). */
    private static final Pattern COORDINATE_PAIR = Pattern.compile(
            "(-?\\d{1,3}\\.\\d{3,9})\\s*[,，]\\s*(-?\\d{1,3}\\.\\d{3,9})");
    /** English/US style address: "1600 Amphitheatre Pkwy, Mountain View, CA 94043". */
    private static final Pattern ADDRESS_EN = Pattern.compile(
            "^\\d{1,6}\\s+[^,]{2,60},\\s*[^,]{2,40}(?:,\\s*[A-Z]{2})?\\s*\\d{5}(?:-\\d{4})?$");
    private static final Pattern ADDRESS_EN_SUFFIX = Pattern.compile(
            "(?i)\\b(?:st|street|ave|avenue|rd|road|blvd|boulevard|dr|drive|ln|lane|way|"
                    + "pkwy|parkway|ct|court|pl|place|hwy|highway|sq|square|ter|terrace)\\b");

    private ClipboardClassifier() {
    }

    /** First (highest priority) actionable item, or null. */
    static Result classify(String rawText) {
        List<Result> results = classifyAll(rawText);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Every actionable item in the text, in priority order and without duplicates. A map place
     * card for example yields an address, a phone number and a share link at once, and each of
     * them deserves its own bubble.
     */
    static List<Result> classifyAll(String rawText) {
        List<Result> results = new ArrayList<>();
        if (rawText == null) return results;
        String text = rawText.trim();
        if (text.isEmpty() || text.length() > 4096) return results;
        String display = shorten(text, 140);

        // Share cards mix a link with the address/phone; scan the text without URLs so those
        // items are still found next to the link.
        String plain = HTTP_URL.matcher(text).replaceAll(" ");
        String phone = phoneNumber(text);
        if (phone == null) phone = phoneInLines(plain);
        if (phone != null) results.add(new Result(KIND_PHONE, phone, display, null));

        String coordinates = coordinates(plain);
        if (coordinates != null) {
            results.add(new Result(KIND_COORDINATES, coordinates, display, null));
        }

        String url = firstUrl(text);
        if (url != null) {
            boolean shopping = isShoppingUrl(url);
            results.add(new Result(shopping ? KIND_SHOPPING : KIND_LINK, url, display,
                    appPackageForUrl(url)));
        } else {
            String deepLink = deepLink(text);
            if (deepLink != null) {
                results.add(new Result(KIND_APP_LINK, deepLink, display,
                        appPackageForScheme(deepLink)));
            }
        }

        String express = expressNumber(plain);
        if (express != null) results.add(new Result(KIND_EXPRESS, express, display, null));

        String address = address(plain);
        if (address != null) results.add(new Result(KIND_ADDRESS, address, display, null));

        // Keep the highest priority items only, and never the same value twice.
        List<Result> unique = new ArrayList<>();
        for (Result candidate : results) {
            boolean duplicate = false;
            for (Result kept : unique) {
                if (kept.value.equals(candidate.value)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(candidate);
            if (unique.size() >= MAX_RESULTS) break;
        }
        return unique;
    }

    // --- phone -------------------------------------------------------------------------

    /** Dialable number, or null when the text is not a phone number. */
    static String phoneNumber(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (value.length() < 5 || value.length() > 40) return null;
        if (!PHONE_TEXT.matcher(value).matches()) return null;
        String digits = PHONE_SEPARATORS.matcher(value).replaceAll("");
        if (digits.regionMatches(true, 0, "tel:", 0, 4)) digits = digits.substring(4);
        if (digits.startsWith("0086")) digits = "+86" + digits.substring(4);
        if (digits.startsWith("+")) {
            return INTL_PHONE.matcher(digits).matches() ? digits : null;
        }
        if (CN_MOBILE.matcher(digits).matches()
                || CN_LANDLINE.matcher(digits).matches()
                || CN_LOCAL.matcher(digits).matches()
                || CN_SERVICE.matcher(digits).matches()) {
            return digits;
        }
        return null;
    }

    // --- links -------------------------------------------------------------------------

    /** First http(s) URL inside the text (share texts wrap the link in promo copy). */
    static String firstUrl(String text) {
        if (text == null) return null;
        String value = text.trim();
        Matcher matcher = HTTP_URL.matcher(value);
        if (matcher.find()) return stripTrailingUrlPunctuation(matcher.group());
        if (BARE_HOST.matcher(value).matches()) {
            return "https://" + stripTrailingUrlPunctuation(value);
        }
        return null;
    }

    static boolean isShoppingUrl(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        for (String shopping : SHOPPING_HOSTS) {
            if (host.equals(shopping) || host.endsWith("." + shopping)) return true;
        }
        return false;
    }

    static String appPackageForUrl(String url) {
        String host = hostOf(url);
        if (host == null) return null;
        for (Map.Entry<String, String> entry : HOST_APPS.entrySet()) {
            String key = entry.getKey();
            if (host.equals(key) || host.endsWith("." + key)) return entry.getValue();
        }
        return null;
    }

    /** A bare, non-web app link such as {@code taobao://item/123}. */
    static String deepLink(String text) {
        if (text == null) return null;
        String value = text.trim();
        Matcher matcher = DEEP_LINK.matcher(value);
        if (!matcher.matches()) return null;
        String scheme = matcher.group(1).toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) return null;
        return value;
    }

    static String appPackageForScheme(String uri) {
        if (uri == null) return null;
        Matcher matcher = SCHEME_ONLY.matcher(uri.trim());
        if (!matcher.matches()) return null;
        return SCHEME_APPS.get(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    static String hostOf(String url) {
        if (url == null) return null;
        String value = url.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int port = value.indexOf(':');
        if (port >= 0) value = value.substring(0, port);
        value = value.toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private static String stripTrailingUrlPunctuation(String url) {
        String value = URL_TRAILING.matcher(url).replaceAll("");
        while (value.endsWith(")") && count(value, '(') < count(value, ')')) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static int count(String value, char target) {
        int total = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) total++;
        }
        return total;
    }

    // --- express -----------------------------------------------------------------------

    /** Tracking number, or null. Pure digit runs long enough to be a waybill are accepted. */
    static String expressNumber(String text) {
        if (text == null) return null;
        String value = text.trim();
        String compact = value.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
        if (UPU_CODE.matcher(compact).matches()
                || PREFIXED_EXPRESS.matcher(compact).matches()
                || DIGIT_EXPRESS.matcher(compact).matches()) {
            return compact;
        }
        if (EXPRESS_KEYWORDS.matcher(value).find()) {
            Matcher matcher = TRACKING_TOKEN.matcher(compact);
            while (matcher.find()) {
                String token = matcher.group();
                if (token.length() >= 10 && token.length() <= 24) return token;
            }
        }
        return null;
    }

    // --- address -----------------------------------------------------------------------

    /**
     * Conservative Chinese address detection: needs an administrative level, a district/street
     * level and a house number, so plain sentences that merely mention 市/区 are rejected.
     */
    /**
     * Address detection tuned for what map apps actually put on the clipboard: either a single
     * "省市区街道门牌" line, or a place card with the POI name, address, phone, opening hours and
     * a share link stacked on separate lines. The best-looking line wins, metadata is ignored.
     */
    static String address(String text) {
        if (text == null) return null;
        String value = stripLabels(text.trim());
        if (value.length() < 6) return null;

        String best = null;
        int bestScore = 0;
        for (String rawLine : value.split("[\\r\\n]+")) {
            String line = stripLabels(rawLine.trim());
            if (line.length() < 6 || line.length() > 120) continue;
            int score = addressScore(line);
            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        // A single-line address still has to clear the same bar as one line of a place card.
        if (best == null && !value.contains("\n")) {
            if (addressScore(value) > 0) best = value;
        }
        return best;
    }

    /** 0 means "not an address"; higher is a more complete address. */
    private static int addressScore(String line) {
        if (line.length() < 6 || line.length() > 120) return 0;
        if (!HAS_CJK.matcher(line).find() && !ADDRESS_EN.matcher(line).matches()) return 0;
        if (SENTENCE_END.matcher(line).find()) return 0;
        if (firstUrl(line) != null || phoneNumber(line) != null) return 0;

        if (ADDRESS_EN.matcher(line).matches()
                && ADDRESS_EN_SUFFIX.matcher(line).find()) {
            return 6;
        }
        if (!HAS_CJK.matcher(line).find()) return 0;

        boolean city = containsAny(line, CITY_WORDS);
        boolean district = containsAny(line, DISTRICT_WORDS);
        boolean street = containsAny(line, STREET_WORDS);
        boolean house = HOUSE_NUMBER.matcher(line).find() || HAS_DIGIT.matcher(line).find();
        if (MAP_METADATA_LINE.matcher(line).find()) return 0;
        if (!street) return 0;
        // 省市区 + 街道/门牌, 市区 + 门牌, or 街道 + 门牌 are all plausible addresses.
        if (city && district) return house ? 5 : 4;
        if (district) return house ? 4 : 0;
        if (city) return house ? 3 : 0;
        return house ? 2 : 0;
    }

    /** Leading "地址：" style label, trailing sentence punctuation and stray quotes. */
    private static String stripLabels(String text) {
        String value = text;
        for (int i = 0; i < 3; i++) {
            String next = ADDRESS_LABEL.matcher(value).replaceFirst("");
            if (next.equals(value)) break;
            value = next;
        }
        value = value.replaceAll("^[\\s:：,，、。.]+", "");
        value = value.replaceAll("[\\s,，、;；]+$", "");
        return value.trim();
    }

    /**
     * Coordinate pair copied from a map app, normalized to "lat,lng". Chinese map apps usually
     * copy "lng,lat", Google copies "lat,lng"; the magnitudes disambiguate the two.
     */
    /** First phone number appearing on its own line (place cards label them "电话："). */
    private static String phoneInLines(String text) {
        if (text == null || !text.contains("\n")) return null;
        for (String rawLine : text.split("[\\r\\n]+")) {
            String line = PHONE_LABEL.matcher(rawLine.trim()).replaceFirst("").trim();
            String number = phoneNumber(line);
            if (number != null) return number;
        }
        return null;
    }

    static String coordinates(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (value.isEmpty() || value.length() > 200) return null;
        if (firstUrl(value) != null) return null;
        Matcher matcher = COORDINATE_PAIR.matcher(value);
        while (matcher.find()) {
            try {
                double first = Double.parseDouble(matcher.group(1));
                double second = Double.parseDouble(matcher.group(2));
                double lat;
                double lng;
                if (Math.abs(first) > 90 && Math.abs(second) <= 90) {
                    lng = first;
                    lat = second;
                } else if (Math.abs(second) > 90 && Math.abs(first) <= 90) {
                    lat = first;
                    lng = second;
                } else if (first > 72 && first < 136 && second > 3 && second < 56) {
                    // Typical mainland China bounds: the longitude is the larger-looking one.
                    lng = first;
                    lat = second;
                } else {
                    lat = first;
                    lng = second;
                }
                if (Math.abs(lat) > 90 || Math.abs(lng) > 180) continue;
                return String.format(Locale.ROOT, "%.6f,%.6f", lat, lng);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean containsAny(String value, String[] words) {
        for (String word : words) {
            if (value.contains(word)) return true;
        }
        return false;
    }

    private static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
