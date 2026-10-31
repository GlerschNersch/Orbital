package dev.watchreborn.apps

/**
 * Category groups for Honeycomb Auto Folders.
 * [compassOrder] defines the fixed spatial neighborhood order around the honeycomb ring so each
 * category stays in a consistent compass direction when "Fixed Neighborhoods" mode is active.
 */
enum class AppGroup(val title: String, val tint: Int, val compassOrder: Int) {
    // North / North-East: AI, Work & Reading
    AI("AI", 0xFFA6E36E.toInt(), 0),
    PRODUCTIVITY("Work", 0xFFFF9F0A.toInt(), 1),
    NEWS("News", 0xFFFF6961.toInt(), 2),

    // East: Social & Web
    SOCIAL("Social", 0xFF64D2FF.toInt(), 3),
    INTERNET("Web", 0xFF5AC8FA.toInt(), 4),

    // South-East: Media & Games
    VIDEO("Video", 0xFFFF453A.toInt(), 5),
    MUSIC("Music", 0xFFFF375F.toInt(), 6),
    PHOTOS("Photos", 0xFFFFD60A.toInt(), 7),
    GAMES("Games", 0xFFFF6482.toInt(), 8),

    // South / South-West: Home, Utilities & Health
    SMART_HOME("Home", 0xFFFFD60A.toInt(), 9),
    TOOLS("Utilities", 0xFF98989D.toInt(), 10),
    HEALTH("Fitness", 0xFFA6E36E.toInt(), 11),

    // West / North-West: Finance, Shopping, Food & Travel
    FINANCE("Finance", 0xFF30D158.toInt(), 12),
    SHOPPING("Shopping", 0xFFBF5AF2.toInt(), 13),
    FOOD("Food", 0xFFFF9500.toInt(), 14),
    TRAVEL("Travel", 0xFF64D2FF.toInt(), 15),
    OTHER("Other", 0xFF787880.toInt(), 16),
}

/** Default handler roles on the phone. */
enum class AppRole { BROWSER, DIALER, SMS, CAMERA, EMAIL }

/**
 * Classifies an app into an [AppGroup] offline: first by default role, then narrow name rules,
 * then manifest category, then broad name rules.
 */
object AppClassifier {
    private const val CAT_GAME = 0
    private const val CAT_AUDIO = 1
    private const val CAT_VIDEO = 2
    private const val CAT_IMAGE = 3
    private const val CAT_SOCIAL = 4
    private const val CAT_NEWS = 5
    private const val CAT_MAPS = 6
    private const val CAT_PRODUCTIVITY = 7
    private const val CAT_ACCESSIBILITY = 8

    private val RULES: List<Pair<AppGroup, List<String>>> = listOf(
        AppGroup.GAMES to listOf("game", "emulator", "armsx3", "retroarch", "rpcs3", "steam", "xbox", "playstation",
            "nintendo", "minecraft", "roblox", "chess", "solitaire", "puzzle", "epicgames", "lottery", "lotto", "casino", "ps app", "psapp", "scee", "=ps"),
        AppGroup.AI to listOf("claude", "anthropic", "gemini", "copilot", "chatgpt", "openai", "kimi", "moonshot",
            "perplexity", "=grok", "deepseek", "aloy", "character.ai", "=poe", "mistral"),
        AppGroup.FINANCE to listOf("=cu", "credit union", "greenlight", "turbotax", "intuit", "webull", "sofi", "=ally",
            "discover", "amex", "americanexpress", "synchrony", "bank", "becu", "chase", "wellsfargo", "capitalone", "=citi", "paypal", "venmo",
            "cash app", "squareup.cash", "zelle", "wallet", "credit", "karma", "finance", "invest", "robinhood",
            "coinbase", "crypto", "fidelity", "schwab", "vanguard", "money", "budget", "=mint", "=tax", "=pay"),
        AppGroup.SMART_HOME to listOf("home assistant", "homeassistant", "alexa", "eufy", "anker", "aqara", "=nest",
            "smartthings", "=hue", "=kasa", "tplink", "=tapo", "=ring", "tuya", "smart life", "wyze", "ecobee", "govee",
            "lutron", "meross", "switchbot", "google home", "chromecast", "=roku", "harmony", "roborock", "roomba", "irobot",
            "ecovacs", "dreame", "shark", "schlage", "kwikset", "=yale", "august home", "myq", "chamberlain", "=tile",
            "thermostat", "doorbell", "smart home", "smarthome", "unifi", "ubiquiti", "linktap", "wybot", "pool",
            "irrigation", "sprinkler", "rachio", "eero", "=orbi", "router", "mesh wifi"),
        AppGroup.FOOD to listOf("domino", "caesars", "pizza", "papajohns", "papa john", "starbucks", "mcdonald",
            "doordash", "ubereats", "uber eats", "grubhub", "postmates", "subway", "taco", "chipotle", "burger", "wendy",
            "=kfc", "dunkin", "chick-fil", "chickfil", "panera", "popeyes", "food", "restaurant", "recipe", "coffee"),
        AppGroup.SHOPPING to listOf("shop", "aliexpress", "ebay", "etsy", "temu", "shein", "walmart", "target",
            "costco", "bestbuy", "=wish", "instacart", "coupon", "deal", "newegg", "fred meyer", "fredmeyer", "kroger",
            "safeway", "albertsons", "grocery", "zillow", "redfin", "realtor", "offerup", "craigslist", "marketplace", "=qfc", "whole foods", "=aldi", "oneplus store", "=mall"),
        AppGroup.TRAVEL to listOf("maps", "waze", "transit", "airline", "airlines", "alaska", "hawaiian", "delta",
            "united", "southwest", "american air", "uber", "lyft", "airbnb", "booking", "expedia", "hotel", "tripadvisor",
            "=trip", "travel", "flight", "=rail", "parking", "gas buddy", "gasbuddy", "=ford", "fordpass", "toyota",
            "tesla", "chevrolet", "=honda", "hilton", "marriott", "hyatt", "=ihg", "honors", "wyndham", "hyundai", "=kia", "subaru", "nissan", "=bmw", "volkswagen", "=car", "vehicle"),
        AppGroup.SOCIAL to listOf("messag", "message", "chat", "discord", "whatsapp", "telegram", "signal",
            "facebook", "messenger", "instagram", "threads", "twitter", "x.android", "reddit", "snapchat", "tiktok",
            "linkedin", "patreon", "mastodon", "bluesky", "skype", "viber", "=line", "kakao", "wechat", "contacts", "social", "dating", "phone", "dialer"),
        AppGroup.VIDEO to listOf("youtube", "netflix", "hulu", "disney", "primevideo", "prime video", "=hbo", "=max",
            "paramount", "peacock", "twitch", "plex", "jellyfin", "emby", "kodi", "vlc", "mxtech", "crunchyroll",
            "tubi", "pluto", "video", "movie", "=tv", "stream"),
        AppGroup.MUSIC to listOf("guitar", "tuner", "piano", "music", "spotify", "pandora", "soundcloud", "deezer", "tidal", "podcast", "audible",
            "audiobook", "radio", "shazam", "audio", "sound", "tunein", "sirius"),
        AppGroup.PHOTOS to listOf("camera", "photo", "gallery", "snapseed", "lightroom", "picsart", "vsco", "image",
            "pixel", "selfie"),
        AppGroup.HEALTH to listOf("health", "=fit", "fitbit", "zepp", "strava", "garmin", "sleep", "workout", "physique",
            "peloton", "myfitnesspal", "=calm", "headspace", "meditat", "medical", "pharmacy", "doctor", "mychart",
            "=hims", "=hers", "=zen", "wellness", "=care"),
        AppGroup.NEWS to listOf("library", "libby", "hoopla", "news", "reader", "kindle", "books", "medium", "feedly", "magazine", "weather",
            "comic", "manga", "wikipedia"),
        AppGroup.INTERNET to listOf("browser", "chrome", "firefox", "brave", "opera", "=edge", "duckduckgo", "vivaldi",
            "samsung internet", "=kiwi"),
        AppGroup.PRODUCTIVITY to listOf("=mail", "gmail", "outlook", "calendar", "=docs", "sheets", "slides", "drive",
            "office", "=word", "excel", "powerpoint", "notion", "notes", "=keep", "todo", "to do", "tasks", "dropbox",
            "onedrive", "slack", "teams", "zoom", "=meet", "evernote", "obsidian", "trello", "asana", "translate",
            "scanner", "pdf", "=work", "workday", "indeed", "=job", "mind space", "mindspace", "notebook", "onenote"),
        AppGroup.TOOLS to listOf("duo mobile", "duosecurity", "=duo", "identity", "tailscale", "family link", "find hub",
            "google one", "=lens", "=tools", "authenticator", "=auth", "vpn", "password", "bitwarden", "1password", "lastpass",
            "security", "antivirus", "clock", "calculator", "=calc", "settings", "files", "file manager", "astro",
            "launcher", "reborn", "keyboard", "gboard", "play store", "vending", "flashlight", "recorder", "backup",
            "cleaner", "battery", "wifi", "bluetooth", "remote", "terminal", "termux", "=adb", "=tool", "utility",
            "assistant", "voice access", "accessibility", "safety", "find my", "compass", "phone manager",
            "t-life", "tmobile", "t-mobile", "verizon", "my at&t", "carrier", "wallpaper", "walls", "theme", "icon pack", "sim toolkit"),
    )

    private val CATCH_ALL = listOf("google", "android", "oplus", "oneplus", "coloros")

    private val ROLE_GROUP = mapOf(
        AppRole.BROWSER to AppGroup.INTERNET,
        AppRole.DIALER to AppGroup.SOCIAL,
        AppRole.SMS to AppGroup.SOCIAL,
        AppRole.CAMERA to AppGroup.PHOTOS,
        AppRole.EMAIL to AppGroup.PRODUCTIVITY,
    )

    fun classify(label: String, packageName: String, category: Int, isGame: Boolean, roles: Set<AppRole> = emptySet()): AppGroup {
        if (isGame || category == CAT_GAME) return AppGroup.GAMES
        roles.firstNotNullOfOrNull { ROLE_GROUP[it] }?.let { return it }
        val text = "${label.lowercase()} ${packageName.lowercase()}"
        val words = text.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        fun matches(k: String) = if (k.startsWith("=")) k.substring(1) in words else k in text
        for ((group, keys) in RULES.take(NARROW)) if (keys.any(::matches)) return group
        when (category) {
            CAT_AUDIO -> return AppGroup.MUSIC
            CAT_VIDEO -> return AppGroup.VIDEO
            CAT_IMAGE -> return AppGroup.PHOTOS
            CAT_SOCIAL -> return AppGroup.SOCIAL
            CAT_NEWS -> return AppGroup.NEWS
            CAT_MAPS -> return AppGroup.TRAVEL
            CAT_ACCESSIBILITY -> return AppGroup.TOOLS
        }
        for ((group, keys) in RULES.drop(NARROW)) if (keys.any(::matches)) return group
        if (category == CAT_PRODUCTIVITY) return AppGroup.PRODUCTIVITY
        if (CATCH_ALL.any(::matches)) return AppGroup.TOOLS
        return AppGroup.OTHER
    }

    private const val NARROW = 7
}
