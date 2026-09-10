package com.keepasskey.app.ui.screens.generator

import java.security.SecureRandom

/**
 * 现代 Diceware 词库与密码生成核心工具
 */
object PasswordGenerationEngine {
    private val secureRandom = SecureRandom()

    // 精选 EFF / KeePassDX 风格的常用高频安全单词库 (精简高效纯词表)
    private val DICEWARE_WORDS = listOf(
        "ability", "absent", "absorb", "abstract", "academy", "accent", "account", "acquire",
        "action", "active", "actor", "adapt", "address", "advance", "advice", "aerobic",
        "affair", "afford", "again", "agency", "agent", "agree", "ahead", "airport",
        "alarm", "album", "alert", "alien", "allow", "almost", "alone", "alpha",
        "already", "alter", "always", "amaze", "amber", "anchor", "ancient", "angel",
        "angry", "animal", "ankle", "announce", "annual", "another", "answer", "antenna",
        "antique", "anxiety", "apart", "apology", "appear", "apple", "approve", "april",
        "arch", "arctic", "area", "arena", "argue", "armor", "army", "around",
        "arrange", "arrest", "arrive", "arrow", "artist", "artwork", "aspect", "assault",
        "asset", "assist", "assume", "athlete", "atom", "attack", "attend", "attitude",
        "attract", "auction", "audit", "august", "aunt", "author", "auto", "autumn",
        "average", "avocado", "avoid", "awake", "aware", "awesome", "awful", "axis",
        "baby", "bachelor", "bacon", "badge", "bag", "balance", "balcony", "ball",
        "bamboo", "banana", "banner", "bar", "barely", "bargain", "barrel", "base",
        "basic", "basket", "battle", "beach", "bean", "beauty", "because", "become",
        "beef", "before", "begin", "behave", "behind", "believe", "below", "belt",
        "bench", "benefit", "best", "betray", "better", "between", "beyond", "bicycle",
        "bid", "bike", "bind", "biology", "bird", "birth", "bitter", "black",
        "blade", "blame", "blanket", "blast", "bleak", "bless", "blind", "blood",
        "blossom", "blouse", "blue", "blur", "blush", "board", "boat", "body",
        "boil", "bomb", "bone", "bonus", "book", "boost", "border", "boring",
        "borrow", "boss", "bottom", "bounce", "box", "boy", "bracket", "brain",
        "brand", "brass", "brave", "bread", "breeze", "brick", "bridge", "brief",
        "bright", "bring", "brisk", "broccoli", "broken", "bronze", "broom", "brother",
        "brown", "brush", "bubble", "buddy", "budget", "buffalo", "build", "bulb",
        "bulk", "bullet", "bundle", "bunker", "burden", "burger", "burst", "bus",
        "business", "busy", "butter", "buyer", "buzz", "cabbage", "cabin", "cable",
        "cactus", "cage", "cake", "call", "calm", "camera", "camp", "can",
        "canal", "cancel", "candy", "cannon", "canoe", "canvas", "canyon", "capable",
        "capital", "captain", "car", "carbon", "card", "cargo", "carpet", "carry",
        "cart", "case", "cash", "casino", "castle", "casual", "cat", "catalog",
        "catch", "category", "cattle", "cause", "caution", "cave", "ceiling", "celery",
        "cement", "census", "century", "cereal", "certain", "chair", "chalk", "champion",
        "change", "chaos", "chapter", "charge", "chase", "chat", "cheap", "check",
        "cheese", "chef", "cherry", "chest", "chicken", "chief", "child", "chimney",
        "choice", "choose", "chronic", "chuckle", "chunk", "churn", "cigar", "cinnamon",
        "circle", "citizen", "city", "civil", "claim", "clap", "clarify", "claw",
        "clay", "clean", "clerk", "clever", "click", "client", "cliff", "climb",
        "clinic", "clip", "clock", "clog", "close", "cloth", "cloud", "clown",
        "club", "clump", "cluster", "clutch", "coach", "coast", "coconut", "code",
        "coffee", "coil", "coin", "collect", "color", "column", "combine", "come",
        "comfort", "comic", "common", "company", "concert", "conduct", "confirm", "congress",
        "connect", "consider", "control", "convince", "cook", "cool", "copper", "copy",
        "coral", "core", "corn", "correct", "cost", "cotton", "couch", "country",
        "couple", "course", "cousin", "cover", "coyote", "crack", "cradle", "craft",
        "cram", "crane", "crash", "crater", "crawl", "crazy", "cream", "credit",
        "creek", "crew", "cricket", "crime", "crisp", "critic", "crop", "cross",
        "crouch", "crowd", "crucial", "cruel", "cruise", "crumble", "crunch", "crush",
        "cry", "crystal", "cube", "culture", "cup", "cupboard", "curious", "current",
        "curtain", "curve", "cushion", "custom", "cute", "cycle", "dad", "damage",
        "damp", "dance", "danger", "daring", "dash", "daughter", "dawn", "day",
        "deal", "debate", "debris", "decade", "december", "decide", "decline", "decorate",
        "decrease", "deer", "defense", "define", "defy", "degree", "delay", "deliver",
        "demand", "demise", "denial", "dentist", "deny", "depart", "depend", "deposit",
        "depth", "deputy", "derive", "describe", "desert", "design", "desk", "despair",
        "destroy", "detail", "detect", "develop", "device", "devote", "diagram", "dial",
        "diamond", "diary", "dice", "diesel", "diet", "differ", "digital", "dignity",
        "dilemma", "dinner", "dinosaur", "direct", "dirt", "disagree", "discover", "disease",
        "dish", "dismiss", "disorder", "display", "distance", "divert", "divide", "divorce",
        "dizzy", "doctor", "document", "dog", "doll", "dolphin", "domain", "donate",
        "donkey", "donor", "door", "dose", "double", "dove", "draft", "dragon",
        "drama", "drastic", "draw", "dream", "dress", "drift", "drill", "drink",
        "drip", "drive", "drop", "drum", "dry", "duck", "dumb", "dune",
        "during", "dust", "dutch", "duty", "dwarf", "dynamic", "eager", "eagle",
        "early", "earn", "earth", "easily", "east", "easy", "echo", "ecology",
        "economy", "edge", "edit", "educate", "effort", "egg", "eight", "either",
        "elbow", "elder", "electric", "elegant", "element", "elephant", "elevator", "elite",
        "else", "embark", "embody", "embrace", "emerge", "emotion", "employ", "empower",
        "empty", "enable", "enact", "end", "endless", "endorse", "enemy", "energy",
        "enforce", "engage", "engine", "enhance", "enjoy", "enlist", "enough", "enrich",
        "enroll", "ensure", "enter", "entire", "entry", "envelope", "episode", "equal",
        "equip", "era", "erase", "erode", "erosion", "error", "erupt", "escape",
        "essay", "essence", "estate", "eternal", "ethics", "evidence", "evil", "evoke",
        "evolve", "exact", "example", "excess", "exchange", "excite", "exclude", "excuse",
        "execute", "exercise", "exhaust", "exhibit", "exile", "exist", "exit", "exotic",
        "expand", "expect", "expire", "explain", "expose", "express", "extend", "extra",
        "eye", "eyebrow", "fabric", "face", "faculty", "fade", "faint", "faith",
        "fall", "false", "fame", "family", "famous", "fan", "fancy", "fantasy",
        "farm", "fashion", "fat", "fatal", "father", "fatigue", "fault", "favorite",
        "feature", "february", "federal", "fee", "feed", "feel", "female", "fence",
        "festival", "fetch", "fever", "few", "fiber", "fiction", "field", "figure",
        "file", "film", "filter", "final", "find", "fine", "finger", "finish",
        "fire", "firm", "first", "fiscal", "fish", "fit", "fitness", "fix",
        "flag", "flame", "flash", "flat", "flavor", "flee", "flight", "flip",
        "float", "flock", "floor", "flower", "fluid", "flush", "fly", "foam",
        "focus", "fog", "foil", "fold", "follow", "food", "foot", "force",
        "forest", "forget", "fork", "fortune", "forum", "forward", "fossil", "foster",
        "found", "fox", "fragile", "frame", "frequent", "fresh", "friend", "fringe",
        "frog", "front", "frost", "frown", "frozen", "fruit", "fuel", "fun",
        "funny", "furnace", "fury", "future", "gadget", "gain", "galaxy", "gallery",
        "game", "gap", "garage", "garbage", "garden", "garlic", "garment", "gas",
        "gasp", "gate", "gather", "gauge", "gaze", "general", "genius", "genre",
        "gentle", "genuine", "gesture", "ghost", "giant", "gift", "giggle", "ginger",
        "giraffe", "girl", "give", "glad", "glance", "glare", "glass", "glide",
        "glimpse", "globe", "gloom", "glory", "glove", "glow", "glue", "goat",
        "goddess", "gold", "good", "goose", "gorilla", "gospel", "gossip", "govern",
        "gown", "grab", "grace", "grain", "grant", "grape", "grass", "gravity",
        "great", "green", "grid", "grief", "grit", "grocery", "group", "grow",
        "grunt", "guard", "guess", "guide", "guilt", "guitar", "gun", "gym",
        "habit", "hair", "half", "hammer", "hamster", "hand", "happy", "harbor",
        "hard", "harsh", "harvest", "hat", "have", "hawk", "hazard", "head",
        "health", "heart", "heavy", "hedgehog", "height", "hello", "helmet", "help",
        "hen", "hero", "hidden", "high", "hill", "hint", "hip", "hire",
        "history", "hobby", "hockey", "hold", "hole", "holiday", "hollow", "home",
        "honey", "hood", "hope", "horn", "horror", "horse", "hospital", "host",
        "hotel", "hour", "house", "hover", "hub", "huge", "human", "humble",
        "humor", "hundred", "hungry", "hunt", "hurdle", "hurry", "hurt", "husband",
        "hybrid", "ice", "icon", "idea", "identify", "idle", "ignore", "illness",
        "image", "imitate", "immense", "immune", "impact", "impose", "improve", "impulse",
        "inch", "include", "income", "increase", "index", "indicate", "indoor", "industry",
        "infant", "inflict", "inform", "inhale", "inherit", "initial", "inject", "injury",
        "inmate", "inner", "innocent", "input", "inquiry", "insane", "insect", "inside",
        "inspire", "install", "intact", "interest", "into", "invest", "invite", "involve",
        "iron", "island", "isolate", "issue", "item", "ivory", "jacket", "jaguar",
        "jar", "jazz", "jealous", "jeans", "jelly", "jewel", "job", "join",
        "joke", "journey", "joy", "judge", "juice", "jump", "jungle", "junior",
        "junk", "just", "kangaroo", "keen", "keep", "ketchup", "key", "kick",
        "kid", "kidney", "kind", "kingdom", "kiss", "kit", "kitchen", "kite",
        "kitten", "kiwi", "knee", "knife", "knock", "know", "lab", "label",
        "labor", "ladder", "lady", "lake", "lamp", "language", "laptop", "large",
        "later", "latin", "laugh", "laundry", "lava", "law", "lawn", "lawsuit",
        "layer", "lazy", "leader", "leaf", "learn", "leave", "lecture", "left",
        "leg", "legal", "legend", "leisure", "lemon", "lend", "length", "lens",
        "leopard", "lesson", "letter", "level", "liar", "liberty", "library", "license",
        "life", "lift", "light", "like", "limb", "limit", "link", "lion",
        "liquid", "list", "little", "live", "lizard", "load", "loan", "lobster",
        "local", "lock", "logic", "lonely", "long", "loop", "lottery", "loud",
        "lounge", "love", "loyal", "lucky", "luggage", "lumber", "lunar", "lunch",
        "luxury", "lyrics", "machine", "mad", "magic", "magnet", "maid", "mail",
        "main", "major", "make", "mammal", "man", "manage", "mandate", "mango",
        "mansion", "manual", "maple", "marble", "march", "margin", "marine", "market",
        "marriage", "mask", "mass", "master", "match", "material", "math", "matrix",
        "matter", "maximum", "maze", "meadow", "mean", "measure", "meat", "mechanic",
        "medal", "media", "melody", "melt", "member", "memory", "mention", "menu",
        "mercy", "merge", "merit", "merry", "mesh", "message", "metal", "method",
        "middle", "midnight", "milk", "million", "mimic", "mind", "minimum", "minor",
        "minute", "miracle", "mirror", "misery", "miss", "mistake", "mix", "mixed",
        "mixture", "mobile", "model", "modify", "mom", "moment", "monitor", "monkey",
        "monster", "month", "moon", "moral", "more", "morning", "mosquito", "mother",
        "motion", "motor", "mountain", "mouse", "move", "movie", "much", "muffin",
        "mule", "multiply", "muscle", "museum", "mushroom", "music", "must", "mutual",
        "myself", "mystery", "myth", "naive", "name", "napkin", "narrow", "nasty",
        "nation", "nature", "near", "neck", "need", "negative", "neglect", "neither",
        "nephew", "nerve", "nest", "net", "network", "neutral", "never", "news",
        "next", "nice", "night", "noble", "noise", "nominee", "noodle", "normal",
        "north", "nose", "notable", "note", "nothing", "notice", "novel", "now",
        "nuclear", "number", "nurse", "nut", "oak", "obey", "object", "oblige",
        "obscure", "observe", "obtain", "obvious", "occur", "ocean", "october", "odor",
        "off", "offer", "office", "often", "oil", "okay", "old", "olive",
        "olympic", "omit", "once", "one", "onion", "online", "only", "open",
        "opera", "opinion", "oppose", "option", "orange", "orbit", "orchard", "order",
        "ordinary", "organ", "orient", "original", "orphan", "ostrich", "other", "outdoor",
        "outer", "output", "outside", "oval", "oven", "over", "own", "owner",
        "oxygen", "oyster", "ozone", "pact", "paddle", "page", "pair", "palace",
        "palm", "panda", "panel", "panic", "panther", "paper", "parade", "parent",
        "park", "parrot", "party", "pass", "patch", "path", "patient", "patrol",
        "pattern", "pause", "pave", "payment", "peace", "peach", "peacock", "peak",
        "peanut", "pear", "peasant", "pelican", "pen", "penalty", "pencil", "people",
        "pepper", "perfect", "permit", "person", "pet", "phone", "photo", "phrase",
        "physical", "piano", "picnic", "picture", "piece", "pig", "pigeon", "pill",
        "pilot", "pin", "pine", "pink", "pipe", "pistol", "pitch", "pizza",
        "place", "planet", "plastic", "plate", "play", "please", "pledge", "pluck",
        "plug", "plunge", "poem", "poet", "point", "polar", "pole", "police",
        "pond", "pony", "pool", "popular", "portion", "position", "possible", "post",
        "potato", "pottery", "poverty", "powder", "power", "practice", "praise", "predict",
        "prefer", "prepare", "present", "pretty", "prevent", "price", "pride", "primary",
        "print", "priority", "prison", "private", "prize", "problem", "process", "produce",
        "profit", "program", "project", "promote", "proof", "property", "prosper", "protect",
        "proud", "provide", "public", "pudding", "pull", "pulp", "pulse", "pumpkin",
        "punch", "pupil", "puppy", "purchase", "purity", "purpose", "purse", "push",
        "put", "puzzle", "pyramid", "quality", "quantum", "quarter", "question", "quick",
        "quit", "quiz", "quote", "rabbit", "raccoon", "race", "rack", "radar",
        "radio", "rail", "rain", "raise", "rally", "ramp", "ranch", "random",
        "range", "rapid", "rare", "rate", "rather", "raven", "raw", "razor",
        "ready", "real", "reason", "rebel", "rebuild", "recall", "receive", "recipe",
        "record", "recycle", "reduce", "reflect", "reform", "refuse", "region", "regret",
        "regular", "reject", "relax", "release", "relief", "rely", "remain", "remember",
        "remind", "remove", "render", "renew", "rent", "reopen", "repair", "repeat",
        "replace", "report", "require", "rescue", "resemble", "resist", "resource", "response",
        "result", "retire", "retreat", "return", "reunion", "reveal", "review", "reward",
        "rhythm", "rib", "ribbon", "rice", "rich", "ride", "ridge", "rifle",
        "right", "rigid", "ring", "riot", "ripple", "risk", "ritual", "rival",
        "river", "road", "roast", "robot", "robust", "rocket", "romance", "roof",
        "rookie", "room", "rose", "rotate", "rough", "round", "route", "royal",
        "rubber", "rude", "rug", "rule", "run", "runway", "rural", "sad",
        "saddle", "sadness", "safe", "sail", "salad", "salmon", "salon", "salt",
        "salute", "same", "sample", "sand", "satisfy", "satoshi", "sauce", "sausage",
        "save", "say", "scale", "scan", "scare", "scatter", "scene", "scheme",
        "school", "science", "scissors", "scooter", "scope", "score", "scout", "scrap",
        "screen", "script", "scrub", "sea", "search", "season", "seat", "second",
        "secret", "section", "security", "seed", "seek", "segment", "select", "sell",
        "seminar", "senior", "sense", "sentence", "series", "service", "session", "settle",
        "setup", "seven", "shadow", "shaft", "shallow", "share", "shed", "shell",
        "sheriff", "shield", "shift", "shine", "ship", "shiver", "shock", "shoe",
        "shoot", "shop", "short", "shoulder", "shove", "shrimp", "shrug", "shuffle",
        "shy", "sibling", "sick", "side", "siege", "sight", "sign", "silent",
        "silk", "silly", "silver", "similar", "simple", "since", "sing", "siren",
        "sister", "situate", "six", "size", "skate", "sketch", "ski", "skill",
        "skin", "skirt", "skull", "slab", "slam", "sleep", "slender", "slice",
        "slide", "slight", "slim", "slogan", "slot", "slow", "slush", "small",
        "smart", "smile", "smoke", "smooth", "snack", "snake", "snap", "sniff",
        "snow", "soap", "soccer", "social", "sock", "soda", "soft", "solar",
        "soldier", "solid", "solution", "solve", "someone", "song", "soon", "sorry",
        "sort", "soul", "sound", "soup", "source", "south", "space", "spare",
        "spatial", "spawn", "speak", "special", "speed", "spell", "spend", "sphere",
        "spice", "spider", "spike", "spin", "spirit", "split", "spoil", "sponsor",
        "spoon", "sport", "spot", "spray", "spread", "spring", "spy", "square",
        "squeeze", "squirrel", "stable", "stadium", "staff", "stage", "stairs", "stamp",
        "stand", "start", "state", "stay", "steak", "steel", "stem", "step",
        "stereo", "stick", "still", "sting", "stock", "stomach", "stone", "stool",
        "story", "stove", "strategy", "street", "strike", "strong", "struggle", "student",
        "stuff", "stumble", "style", "subject", "submit", "subway", "success", "such",
        "sudden", "suffer", "sugar", "suggest", "suit", "summer", "sun", "sunny",
        "sunset", "super", "supply", "supreme", "sure", "surface", "surge", "surprise",
        "surround", "survey", "suspect", "sustain", "swallow", "swamp", "swap", "swarm",
        "swear", "sweet", "swift", "swim", "swing", "switch", "sword", "symbol",
        "symptom", "syrup", "system", "table", "tackle", "tag", "tail", "talent",
        "talk", "tank", "tape", "target", "task", "taste", "tattoo", "taxi",
        "teach", "team", "tell", "ten", "tenant", "tennis", "tent", "term",
        "test", "text", "thank", "that", "theme", "then", "theory", "there",
        "they", "thing", "this", "thought", "three", "thrive", "throw", "thumb",
        "thunder", "ticket", "tide", "tiger", "tilt", "timber", "time", "tiny",
        "tip", "tired", "tissue", "title", "toast", "tobacco", "today", "toddler",
        "toe", "together", "toilet", "token", "tomato", "tomorrow", "tone", "tongue",
        "tonight", "tool", "tooth", "top", "topic", "topple", "torch", "tornado",
        "tortoise", "toss", "total", "tourist", "toward", "tower", "town", "toy",
        "track", "trade", "traffic", "train", "transfer", "trap", "trash", "travel",
        "tray", "treat", "tree", "trend", "trial", "tribe", "trick", "trigger",
        "trim", "trip", "trophy", "trouble", "truck", "true", "truly", "trumpet",
        "trust", "truth", "try", "tube", "tuition", "tumble", "tuna", "tunnel",
        "turkey", "turn", "turtle", "twelve", "twenty", "twice", "twin", "twist",
        "two", "type", "typical", "ugly", "umbrella", "unable", "unaware", "uncle",
        "uncover", "under", "undo", "unfair", "unfold", "unhappy", "uniform", "unique",
        "unit", "universe", "unknown", "unlock", "until", "unusual", "unveil", "update",
        "upgrade", "uphold", "upon", "upper", "upset", "urban", "urge", "usage",
        "use", "used", "useful", "useless", "usual", "utility", "vacant", "vacuum",
        "vague", "valid", "valley", "valve", "van", "vanish", "vapor", "various",
        "vast", "vault", "vehicle", "velvet", "vendor", "venture", "venue", "verb",
        "verify", "version", "very", "vessel", "veteran", "viable", "vibrant", "vicious",
        "victory", "video", "view", "village", "vintage", "violin", "virtual", "virus",
        "visa", "visit", "visual", "vital", "vivid", "vocal", "voice", "void",
        "volcano", "volume", "vote", "voyage", "wage", "wagon", "wait", "walk",
        "wall", "walnut", "want", "warfare", "warm", "warrior", "wash", "wasp",
        "waste", "water", "wave", "way", "wealth", "weapon", "wear", "weasel",
        "weather", "web", "wedding", "week", "weird", "welcome", "west", "wet",
        "whale", "what", "wheat", "wheel", "when", "where", "whip", "whisper",
        "wide", "width", "wife", "wild", "will", "win", "window", "wine",
        "wing", "wink", "winner", "winter", "wire", "wisdom", "wise", "wish",
        "witness", "wolf", "woman", "wonder", "wood", "wool", "word", "work",
        "world", "worry", "worth", "wrap", "wreck", "wrestle", "wrist", "write",
        "wrong", "yard", "year", "yellow", "you", "young", "youth", "zebra",
        "zero", "zone", "zoo"
    )

    // 字符集常量定义 (遵循工程规范杜绝魔法字符串)
    const val CHARS_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val CHARS_LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val CHARS_DIGITS = "0123456789"
    const val CHARS_SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?"
    const val AMBIGUOUS_CHARS = "0OIl1"

    /**
     * 生成随机字符密码。
     *
     * ISSUE-P2-16：返回 **CharArray 独占副本**（归调用方所有，用毕须 `fill('0')`），
     * 生成边界不再物化不可擦 String。
     */
    fun generateRandomPassword(
        length: Int,
        useUpper: Boolean,
        useLower: Boolean,
        useDigits: Boolean,
        useSymbols: Boolean,
        excludeAmbiguous: Boolean
    ): CharArray {
        var pool = buildString {
            if (useUpper) append(CHARS_UPPER)
            if (useLower) append(CHARS_LOWER)
            if (useDigits) append(CHARS_DIGITS)
            if (useSymbols) append(CHARS_SYMBOLS)
        }

        if (excludeAmbiguous) {
            pool = pool.filterNot { it in AMBIGUOUS_CHARS }
        }

        if (pool.isEmpty()) {
            pool = CHARS_LOWER
        }

        val chars = CharArray(length)
        for (i in 0 until length) {
            val idx = secureRandom.nextInt(pool.length)
            chars[i] = pool[idx]
        }
        return chars
    }

    /**
     * 生成 Diceware 密码短语。
     *
     * ISSUE-P2-16：返回 **CharArray 独占副本**（归调用方所有，用毕须 `fill('0')`）。
     */
    fun generatePassphrase(
        wordCount: Int,
        separator: String,
        capitalize: Boolean,
        includeNumber: Boolean
    ): CharArray {
        val selectedWords = (1..wordCount).map {
            val word = DICEWARE_WORDS[secureRandom.nextInt(DICEWARE_WORDS.size)]
            if (capitalize) word.replaceFirstChar { it.uppercase() } else word
        }.toMutableList()

        if (includeNumber) {
            val randomNum = secureRandom.nextInt(90) + 10
            selectedWords[selectedWords.lastIndex] = selectedWords.last() + randomNum
        }

        // ISSUE-P2-16：不再 joinToString 物化不可擦 String；可覆盖中间缓冲 → CharArray 出口
        val capacity = selectedWords.sumOf { it.length } +
            separator.length * (selectedWords.size - 1).coerceAtLeast(0)
        return buildChars(capacity) {
            selectedWords.forEachIndexed { index, word ->
                if (index > 0) append(separator)
                append(word)
            }
        }
    }

    /**
     * 根据自定义掩码生成密码 (d: 数字, u: 大写, l: 小写, s: 符号)。
     *
     * ISSUE-P2-16：返回 **CharArray 独占副本**（归调用方所有，用毕须 `fill('0')`）。
     */
    fun generateMaskedPassword(mask: String): CharArray = buildChars(mask.length) {
        for (ch in mask) {
            when (ch) {
                'd' -> append(CHARS_DIGITS[secureRandom.nextInt(CHARS_DIGITS.length)])
                'u' -> append(CHARS_UPPER[secureRandom.nextInt(CHARS_UPPER.length)])
                'l' -> append(CHARS_LOWER[secureRandom.nextInt(CHARS_LOWER.length)])
                's' -> append(CHARS_SYMBOLS[secureRandom.nextInt(CHARS_SYMBOLS.length)])
                'x' -> {
                    val mixed = CHARS_LOWER + CHARS_DIGITS
                    append(mixed[secureRandom.nextInt(mixed.length)])
                }
                else -> append(ch)
            }
        }
    }

    /**
     * ISSUE-P2-16：把可擦除的字符构建过程收敛到 CharArray 出口。
     * [StringBuilder] 仅作长度可变的中间缓冲，结果经逐字符拷贝后立即覆盖为零并截断，
     * 避免生成明文以不可擦 String 形态在堆中驻留。
     */
    // 刻意不用 `StringBuilder.getChars`：Android 的 API 数据库（api-versions.xml）**没有**
    // `java.lang.StringBuilder` 自身的 getChars 条目——该方法是经包私有父类
    // `AbstractStringBuilder` 暴露的（2026-09-10 经 javap 核对 android-36 与 android-37.0 的
    // android.jar，两者均可见 `public void getChars(int,int,char[],int)`），故 lint 只能把调用
    // 解析到 `java.lang.CharSequence#getChars(II[CI)V` 的 `since="37.0"` 版本，在 minSdk 36 下
    // 误报 `NewApi`（本仓 CI 首跑即因此变红）。改用下标运算符 `sb[i]`（编译为 CharSequence 的
    // 字符访问，稳定解析到 API 1），既保住「不经 toString() 物化明文 String」的零化语义，
    // 又不依赖隐藏父类方法（隐藏方法不受 API 数据库追踪，正是本次误报的根因）。
    private inline fun buildChars(capacity: Int, block: StringBuilder.() -> Unit): CharArray {
        val sb = StringBuilder(capacity.coerceAtLeast(0))
        return try {
            sb.block()
            val result = CharArray(sb.length)
            for (i in 0 until sb.length) {
                result[i] = sb[i]
            }
            result
        } finally {
            for (i in 0 until sb.length) sb.setCharAt(i, '\u0000')
            sb.setLength(0)
        }
    }

    /**
     * 计算密码熵值 (Entropy Bits)。
     * ISSUE-P2-12：改为字符数组实现，调用方可直接消费 CharArray 而不物化 String。
     */
    fun calculateEntropy(password: CharArray): Double {
        if (password.isEmpty()) return 0.0
        var poolSize = 0
        if (password.any { it in CHARS_LOWER }) poolSize += 26
        if (password.any { it in CHARS_UPPER }) poolSize += 26
        if (password.any { it in CHARS_DIGITS }) poolSize += 10
        if (password.any { it in CHARS_SYMBOLS }) poolSize += 30
        if (poolSize == 0) poolSize = 26

        val bitsPerChar = kotlin.math.log2(poolSize.toDouble())
        return password.size * bitsPerChar
    }
}
