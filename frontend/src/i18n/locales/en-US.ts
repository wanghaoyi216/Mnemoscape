/**
 * R31: en-US i18n messages.
 * Auto-converted from legacy JSON, plus R31 additions:
 *  - skeleton.*          (SkeletonBox / SkeletonCard placeholders)
 *  - errorBoundary.*     (global ErrorBoundary fallback page)
 *  - pwa.*               (offline banner, update toast, install prompt)
 *
 * To find missing keys in <template>/<script>, run: pnpm i18n:extract
 */

export default {
  "brand":
  {
      "name": "Mnemoscape",
      "tagline": "Memory Museum",
      "subtitle": "AI-powered personal memory museum",
      "footer": "Built with care · Reconstruction is suggestion, not truth",
      "version": "v1.0"
    },
  "common":
  {
      "loading": "Loading…",
      "submit": "Submit",
      "cancel": "Cancel",
      "confirm": "Confirm",
      "save": "Save",
      "delete": "Delete",
      "edit": "Edit",
      "back": "Back",
      "next": "Next",
      "previous": "Previous",
      "search": "Search",
      "refresh": "Refresh",
      "retry": "Retry",
      "more": "More",
      "less": "Less",
      "yes": "Yes",
      "no": "No",
      "empty": "No data",
      "comingSoon": "Coming soon",
      "skipToContent": "Skip to main content"
    },
  "nav":
  {
      "memories": "Memories",
      "create": "New",
      "graph": "Graph",
      "timeline": "Timeline",
      "atlas": "Atlas",
      "resonance": "Resonance",
      "chat": "Chat",
      "profile": "Profile",
      "logout": "Sign out",
      "locale": "Language",
      "menu": "Open navigation menu",
      "closeMenu": "Close navigation menu",
      "drawerHint": "Press Esc to close · tap outside to dismiss",
      "groups":
      {
          "memory": "Memory",
          "explore": "Explore",
          "social": "Social"
        },
      "theme": "Choose theme"
    },
  "theme":
  {
      "museum": "Museum violet",
      "mint": "Mint green",
      "pink": "Rose pink",
      "gold": "Gilded gold",
      "blue": "Celestial blue"
    },
  "museum":
  {
      "portal":
      {
            "eyebrow": "Private memory museum",
            "title": "Curate your memories",
            "subtitle": "Enter the collection to revisit time, connection, and resonance.",
            "open": "Enter gallery",
            "artNumber": "Accession no. {number}",
            "create":
            {
                    "kicker": "New acquisition",
                    "title": "Curate a memory",
                    "desc": "Shape a passing moment into a private piece you can revisit."
                  },
            "graph":
            {
                    "kicker": "Collection map",
                    "title": "Trace memory connections",
                    "desc": "Follow threads of emotion and meaning across your collection."
                  },
            "timeline":
            {
                    "kicker": "Chronology gallery",
                    "title": "Walk through time",
                    "desc": "Revisit each piece along the chronology of your life."
                  },
            "resonance":
            {
                    "kicker": "Resonance gallery",
                    "title": "Listen for memory echoes",
                    "desc": "Discover pieces that answer one another across time."
                  }
          }
    },
  "ai":
  {
      "shortName": "Echo",
      "title": "Echo Envoy",
      "subtitle": "Intent · Plan & Execute · Multimodal",
      "toggle": "Open AI assistant",
      "close": "Close",
      "minimize": "Minimize",
      "send": "Send",
      "sending": "Thinking…",
      "greeting": "Hi — I live in the corner, on standby.",
      "hintsTitle": "Try these openers ↓"
    },
  "atlas":
  {
      "title": "Spatiotemporal Memory Trails",
      "subtitle": "Retrace your memory routes on the globe · real data · five-level drill-down · dual trip layers",
      "control":
      {
            "title": "Controls",
            "view": "View mode",
            "view2d": "2D map",
            "view3d": "3D tilt",
            "orthographic": "Top-down",
            "perspective": "Tilted",
            "reset": "Reset view",
            "flyHome": "Fly home",
            "playback": "Playback",
            "play": "Play",
            "pause": "Pause",
            "speed": "Speed",
            "rotate": "Auto-rotate",
            "rotateOn": "On",
            "rotateOff": "Off",
            "scale": "Zoom level",
            "drill": "Drill chain",
            "layers": "Layers",
            "hint": "Drag · Wheel to zoom · Dbl-click to drill"
          },
      "scale":
      {
            "global": "Global",
            "continent": "Continent",
            "country": "Country",
            "province": "Province",
            "city": "City",
            "district": "Detail"
          },
      "layers":
      {
            "title": "Layers",
            "physical": "Base map",
            "aura": "Personal aura",
            "trips": "Personal trips",
            "others": "Others' web",
            "personalAura": "Personal aura",
            "personalTrips": "Personal trips",
            "othersWeb": "Others' web",
            "extrusion": "Memory columns",
            "marquee": "Marquee grid"
          },
      "signal":
      {
            "title": "Telemetry",
            "zoom": "Zoom",
            "pitch": "Pitch",
            "bearing": "Bearing",
            "fps": "FPS",
            "memories": "Memories",
            "mapped": "Mapped",
            "unmapped": "Unmapped",
            "legendTitle": "Legend",
            "untitled": "Untitled memory",
            "noLocation": "No location",
            "moreUnmapped": "{count} more unmapped"
          },
      "timeline":
      {
            "title": "Timeline",
            "all": "All time",
            "from": "From",
            "to": "To",
            "play": "Play",
            "pause": "Pause"
          },
      "graph":
      {
            "eyebrow": "Mnemosyne starmap",
            "title": "Your memories form a galaxy.",
            "lead": "Each memory is a star with its own halo. Position is hashed onto a Fibonacci sphere, color follows the AI-detected dominant emotion, and brightness fades with drift level.",
            "metrics":
            {
                    "total": "Stars",
                    "distribution": "Distribution",
                    "fibonacci": "Fibonacci sphere",
                    "engine": "Renderer",
                    "engineValue": "WebGL"
                  },
            "stage":
            {
                    "title": "Memory galaxy",
                    "hint": "Drag to rotate, scroll to zoom. Click any star to fly to its detail."
                  },
            "loading": "Charting your memory stars…",
            "selected": "Focused",
            "openDetail": "Open memory archive"
          },
      "detail":
      {
            "title": "Memory detail",
            "location": "Location",
            "date": "Date",
            "year": "Year",
            "fade": "Fade",
            "locked": "Locked",
            "open": "Open detail",
            "flyTo": "Fly here",
            "untitled": "Untitled memory",
            "empty": "Select a point on the map to view details"
          },
      "physical":
      {
            "title": "Physical coordinates",
            "lat": "Lat",
            "lng": "Lng",
            "now": "Current focus",
            "note": "Coordinates resolved from location text via local gazetteer"
          },
      "others":
      {
            "title": "Others' web",
            "hint": "Awaiting community data — placeholder nodes shown",
            "note": "Others' nodes are illustrative; awaiting community feed"
          },
      "legend":
      {
            "title": "Legend",
            "physical": "Base map",
            "memory": "Personal",
            "trip": "Trip line",
            "others": "Others",
            "personal": "Personal",
            "locked": "Locked"
          },
      "unmapped":
      {
            "title": "Unmapped memories",
            "hint": "Location text didn't match the gazetteer — refine in the memory detail"
          },
      "loading":
      {
            "title": "Calibrating spacetime…",
            "hint": "MapLibre and deck.gl assets load on first entry"
          },
      "error":
      {
            "title": "Atlas failed to load",
            "hint": "Check network and retry"
          },
      "emptyHints":
      {
            "noCoordsTitle": "No memory coordinates yet",
            "noCoordsBody": "Head to \"{link}\" and fill in the \"location\" field (e.g. Beijing / Dali / Tokyo).",
            "noCoordsLink": "Create memory",
            "noCoordsFooter": "The atlas will weave your memories into a spacetime trail automatically.",
            "oneCoordTitle": "One more memory will light up the trail",
            "oneCoordBody": "Only 1 geo-tagged memory so far — \"{link}\" with another location to see a colored memory flow between them.",
            "oneCoordLink": "create one more"
          }
    },
  "locale":
  {
      "zh-CN": "简体中文",
      "en-US": "English"
    },
  "login":
  {
      "eyebrow": "Private memory studio",
      "title": "Let the forgotten<br/>shimmer again.",
      "lead": "Sign back in to revisit reconstructed scenes, lock fragile memories, and trace resonance between moments. Built for long-form work — one focused canvas at a time.",
      "features":
      {
            "jwt":
            {
                    "title": "JWT-secured",
                    "desc": "Stateless sessions, instant revocation."
                  },
            "scene":
            {
                    "title": "Immersive 3D",
                    "desc": "Three.js scenes with spatial audio."
                  },
            "drift":
            {
                    "title": "Drift engine",
                    "desc": "Ebbinghaus-modelled fade over time."
                  },
            "resonance":
            {
                    "title": "Resonance match",
                    "desc": "Emotion-vector + scene similarity."
                  }
          },
      "quote": "Every life is a museum with rooms that never close. Mnemoscape reconstructs those rooms from words alone — and lets them age the way memory does.",
      "card":
      {
            "eyebrow": "Secure access",
            "title": "Sign in",
            "subtitle": "Return to the museum with your existing account."
          },
      "username": "Username",
      "usernamePlaceholder": "your.username",
      "password": "Password",
      "passwordPlaceholder": "••••••••••",
      "submit": "Sign in",
      "submitting": "Signing in…",
      "noAccount": "No account yet?",
      "goRegister": "Create one →",
      "backgrounds":
      {
            "choose": "Choose sign-in background",
            "title": "Background gallery",
            "current": "Artwork {number}",
            "list": "Sign-in backgrounds",
            "auto": "Rotate backgrounds automatically",
            "artAlt": "Memory museum background artwork {number}"
          },
      "error":
      {
            "fallback": "Login failed. Verify your credentials."
          }
    },
  "register":
  {
      "eyebrow": "Create your studio",
      "title": "Build a museum<br/>of your own memory.",
      "lead": "Set up a secure workspace. Reconstruct moments into 3D scenes, track every revision, and discover memories that resonate emotionally with yours.",
      "highlights":
      {
            "private": "Private by default — share only when you choose",
            "secure": "BCrypt-hashed credentials, JWT sessions",
            "history": "Full version history with one-click restore",
            "export": "Export anytime — your memories, your data"
          },
      "card":
      {
            "eyebrow": "New account",
            "title": "Create account",
            "subtitle": "A few details and you're inside the museum."
          },
      "username": "Username",
      "usernamePlaceholder": "your.username",
      "email": "Email",
      "emailPlaceholder": "name{'@'}company.com",
      "password": "Password",
      "passwordPlaceholder": "At least 8 characters",
      "confirmPassword": "Confirm password",
      "confirmPasswordPlaceholder": "Repeat password",
      "submit": "Create account",
      "submitting": "Creating account…",
      "haveAccount": "Already have an account?",
      "goLogin": "Sign in →",
      "rules":
      {
            "length": "8+ characters",
            "upper": "Uppercase letter",
            "lower": "Lowercase letter",
            "digit": "Number",
            "symbol": "Symbol"
          },
      "error":
      {
            "mismatch": "Passwords do not match",
            "weak": "Password must satisfy all the strength requirements below.",
            "fallback": "Registration failed"
          }
    },
  "memory":
  {
      "list":
      {
            "title": "Memory collection",
            "subtitle": "Collect, revisit, and curate the moments that belong to you.",
            "empty": "No memories yet. Start with \"New memory\".",
            "createButton": "New memory",
            "openButton": "Open",
            "fadeLevel": "Drift",
            "error":
            {
                    "title": "Memory service temporarily unavailable",
                    "summary": "This is usually a backend or database failure, not a frontend mock.",
                    "backendHint": "Try again later; if it keeps happening, check memory-service, MySQL, and the deployed environment.",
                    "requestId": "Request ID: {id}",
                    "detailsToggle": "Show raw error",
                    "detailLabel": "Backend error details"
                  },
            "privacy":
            {
                    "PRIVATE": "Private",
                    "FRIENDS": "Friends",
                    "PUBLIC": "Public"
                  },
            "guide":
            {
                    "title": "Museum guide and spacetime log",
                    "subtitle": "Reconstruction is suggestion — discover and curate your lifetime galleries",
                    "toggleExpand": "Expand Guide",
                    "toggleCollapse": "Collapse Guide",
                    "reconstruction":
                    {
                              "title": "Memory reconstruction",
                              "desc": "Describe your memory on the creation page. The AI engine will deconstruct its visuals, sounds, and emotions to weave a dedicated 3D space you can walk through."
                            },
                    "drift":
                    {
                              "title": "Memory drift",
                              "desc": "Simulated Ebbinghaus forgetting curve. Over time, unlocked memory galleries will fade, blur, and drift. Click 'Lock' to preserve, or 'Rewrite' to update."
                            },
                    "fragments":
                    {
                              "title": "Hidden fragments",
                              "desc": "AI hides forgotten details and sensory flashbacks inside your 3D gallery. Retrace your steps in walkthrough mode and press 'E' near them to rediscover."
                            },
                    "resonance":
                    {
                              "title": "Memory resonance",
                              "desc": "Choose a memory as a baseline. The similarity engine will match other users with echoing emotional structures to co-generate a shared 3D resonance starspace."
                            }
                  }
          },
      "builder":
      {
            "eyebrow": "Memory builder",
            "title": "Compose a memory<br/>in cinematic detail.",
            "lead": "Describe the scene with enough specificity for the reconstruction engine to shape lighting, audio, and the hidden fragments worth rediscovering.",
            "metrics":
            {
                    "min": "Min description",
                    "minValue": "5 chars",
                    "coverage": "Sensory coverage",
                    "engine": "Engine",
                    "engineValue": "AI + Three.js"
                  },
            "senses":
            {
                    "visual": "Visual",
                    "audio": "Audio",
                    "scent": "Scent",
                    "touch": "Touch",
                    "emotion": "Emotion"
                  },
            "hint": "Mention what you saw, heard, smelled, touched, and felt — even one detail per sense raises reconstruction quality.",
            "card":
            {
                    "eyebrow": "New memory",
                    "title": "Start with a strong title",
                    "subtitle": "Add the sensory detail that will make reconstruction feel deliberate."
                  },
            "fields":
            {
                    "title": "Title",
                    "titlePlaceholder": "A warm summer evening on the terrace",
                    "description": "Description",
                    "descriptionPlaceholder": "Describe the place, the people, the light, the smells, the sounds, and the emotional atmosphere…",
                    "year": "Year",
                    "date": "Date",
                    "season": "Season",
                    "timeOfDay": "Time of day",
                    "location": "Location",
                    "locationPlaceholder": "Grandma's house, rooftop, station platform…",
                    "privacy": "Privacy"
                  },
            "seasons":
            {
                    "placeholder": "Select season",
                    "SPRING": "Spring",
                    "SUMMER": "Summer",
                    "AUTUMN": "Autumn",
                    "WINTER": "Winter"
                  },
            "times":
            {
                    "placeholder": "Select time",
                    "MORNING": "Morning",
                    "NOON": "Noon",
                    "AFTERNOON": "Afternoon",
                    "EVENING": "Evening",
                    "NIGHT": "Night"
                  },
            "privacy":
            {
                    "PRIVATE": "Private — only you",
                    "FRIENDS": "Friends — your network",
                    "PUBLIC": "Public — anyone"
                  },
            "submit": "Create memory",
            "submitting": "Building scene…",
            "ready": "✓ Ready for reconstruction",
            "needMore": "Add {count} more sensory detail for a stronger scene",
            "hints":
            {
                    "writeMore": "Sensory detail is sparse; recommended to write at least 150 characters",
                    "recommended": "✓ Character count is in recommended range (150-300 chars), outstanding detail!",
                    "sufficient": "✓ Descriptive length is sufficient (301-500 chars), perfect for deep reconstruction.",
                    "exceeded": "Exceeded 500 characters. Submitting is allowed, but AI works best under 500 characters."
                  },
            "error":
            {
                    "tooShort": "Description must be at least 5 characters",
                    "fallback": "Creation failed"
                  },
            "cover":
            {
                    "label": "Memory cover artwork",
                    "subtitle": "Give this memory a cinematic cover. It appears on lists, the timeline and the detail page.",
                    "openPicker": "Choose cover",
                    "change": "Change cover",
                    "current": "Current cover",
                    "none": "No cover selected yet",
                    "clear": "Clear"
                  },
            "coverPicker":
            {
                    "title": "Choose a memory cover",
                    "subtitle": "Pick from the built-in gallery, reuse one from your asset library, or upload a local image.",
                    "tabs":
                    {
                              "builtIn": "Built-in",
                              "library": "Asset library",
                              "upload": "Upload"
                            },
                    "badge":
                    {
                              "builtIn": "Built-in",
                              "minio": "MinIO",
                              "local": "Local",
                              "uploaded": "Just uploaded"
                            },
                    "search": "Search covers…",
                    "empty":
                    {
                              "library": "Your asset library is empty. Upload an image to populate it.",
                              "search": "No covers match \"{q}\". Try another keyword or upload a new image."
                            },
                    "upload":
                    {
                              "drag": "Drop an image here, or use the button below",
                              "btn": "Choose local image",
                              "uploading": "Uploading to MinIO…",
                              "success": "Upload complete. Added to your library.",
                              "errorSize": "Image must be smaller than {mb} MB",
                              "errorType": "Please pick a JPEG / PNG / WebP / GIF image",
                              "errorNetwork": "Upload failed. Check that asset-service is online."
                            },
                    "actions":
                    {
                              "select": "Use this cover",
                              "close": "Close",
                              "preview": "Preview"
                            }
                  },
            "reconstructing":
            {
                    "eyebrow": "正在打捞这段时空",
                    "title": "AI 正在重建你的记忆…",
                    "lead": "沙漏在倒流，神经网络在拼接你写下的每一个细节、每一缕情绪。请稍候，几秒之内就能进入这段被重建的场景。"
                  }
          },
      "detail":
      {
            "back": "Back",
            "enterScene": "Enter memory graph",
            "lock": "Lock memory",
            "unlock": "Unlock",
            "drift": "Drift",
            "fragments": "Fragments",
            "versions": "Versions",
            "createdAt": "Created",
            "updatedAt": "Updated",
            "regenError": "Failed to regenerate the scene. Try again or check the AI service.",
            "fragmentsPanel":
            {
                    "regenerate": "Regenerate scene",
                    "regenerating": "AI is rereading the memory…",
                    "regenHint": "Let the AI rebuild the 3D scene + fragments grounded in the current description (about 10-30s)",
                    "fragmentTypes":
                    {
                              "forgotten_detail": "Forgotten detail",
                              "emotion_flashback": "Emotion flashback",
                              "sensory_echo": "Sensory echo",
                              "ambient_clue": "Ambient clue"
                            },
                    "title": "记忆碎片",
                    "subtitle": "在该记忆空间中检测到 {count} 个隐藏细节。",
                    "discovered": "已发现",
                    "hidden": "待探索",
                    "emptyTitle": "暂无碎片",
                    "emptyText": "继续重建或让记忆漂移，将生成可探索的碎片。",
                    "legacyContent":
                    {
                              "A small bird nest hidden in the branches above": "枝丫深处藏着一个小小的鸟巢",
                              "Faded initials carved into the tree trunk": "树干上有人刻下的、已经褪色的姓名缩写",
                              "A forgotten toy half-buried in the warm soil": "被遗忘的玩具半埋在温热的泥土里",
                              "An old photograph tucked behind the bench": "长椅背后塞着一张旧照片",
                              "Wild mushrooms growing in a shaded corner": "阴影角落里悄悄长出几朵野菇",
                              "A sudden wave of warmth and safety washes over you": "一阵温暖与安全感忽然将你包裹",
                              "You feel a brief pang of bittersweet nostalgia": "你心底掠过一丝甜中带苦的怀念",
                              "A moment of pure childhood joy flashes through": "童年里某个纯粹的快乐瞬间一闪而过",
                              "The quiet peace of that moment returns briefly": "那个静谧的瞬间又短暂地回到身边",
                              "Frost etched patterns on the window glass": "玻璃窗上结着精细的冰花",
                              "A woolen scarf left on the bench": "长椅上忘了拿走的羊毛围巾",
                              "Boot prints leading away from the snowman": "雪人旁延伸出去的一串靴印",
                              "Icicles shimmering under a pale sky": "苍白天光下闪着光的冰柱",
                              "A hush of stillness settles in your chest": "胸口涌起一阵静默的安宁",
                              "A memory of laughter echoes softly across the snow": "雪地里似乎传来某段久远的笑声",
                              "You feel the calm that comes with fresh snowfall": "那种新雪初落时特有的安心感又回来了",
                              "A light flickers behind the distant window": "远处那扇窗里有一束灯光在闪烁",
                              "Constellations you used to trace as a child": "你小时候反复描绘过的那几颗星座",
                              "A quiet footstep echoing on stone": "石阶上传来的、轻轻的脚步回响",
                              "A tranquil hush wraps around you": "一种安静的温柔把你整个人裹住",
                              "You feel the comfort of a familiar night": "你感到一种与某个熟悉夜晚同源的安心",
                              "A note of thunder rolling in the distance": "一道闷雷在远处缓缓滚过",
                              "Raindrops rippling across the puddle surface": "雨滴在水洼表面荡出层层涟漪",
                              "A lone streetlight buzzing softly": "孤零零的路灯发出轻微的电流声",
                              "A familiar storm brings back a quiet comfort": "熟悉的雨势带来一种安静的慰藉",
                              "You recall sharing an umbrella in the rain": "你想起某次共撑一把伞穿过雨幕的瞬间",
                              "Petals caught in a small breeze": "几片花瓣被风裹着飞过身边",
                              "A ribbon tied to the bench arm": "长椅扶手上系着的一条丝带",
                              "Fresh footprints in the soft soil": "松软泥土上一串新鲜的脚印",
                              "A hopeful warmth rises with the sunlight": "随着阳光升起，希望也悄悄涨起来",
                              "You feel the excitement of beginnings": "你心底涌起一股关于「开始」的雀跃",
                              "A single leaf spiraling down from the canopy": "一片叶子从树冠缓缓盘旋落下",
                              "Warm light spilling from the house window": "屋内透出的暖光从窗里漫开",
                              "A soft crunch underfoot": "脚下传来落叶被踩碎的轻响",
                              "A wistful memory of autumn evenings returns": "某个秋夜的回忆若有似无地回到心里",
                              "You feel a calm acceptance settle in": "你心底浮起一种温和的、接受一切的平静"
                            }
                  },
            "versionsPanel":
            {
                    "versionTypes":
                    {
                              "CREATE": "Create",
                              "MODIFY": "Modify",
                              "LOCK": "Lock",
                              "RESTORE": "Restore"
                            },
                    "versionMessages":
                    {
                              "Memory created": "Memory created",
                              "Memory updated": "Memory updated",
                              "Memory locked": "Memory locked",
                              "Memory unlocked": "Memory unlocked",
                              "Memory restored": "Memory restored to previous version"
                            },
                    "title": "版本历史",
                    "subtitle": "回到之前的快照，或观察这段记忆是如何演化的。",
                    "restore": "恢复此版本",
                    "restoring": "正在恢复…",
                    "empty": "暂无版本记录"
                  },
            "locked": "已锁定",
            "fadeLevel": "漂移程度",
            "eyebrow": "记忆档案",
            "loadError": "无法加载该记忆，可能已被删除或访问受限。",
            "loading":
            {
                    "title": "正在唤醒这段记忆…",
                    "text": "正在拉取记忆档案与重建元数据。"
                  },
            "error":
            {
                    "title": "记忆不可用"
                  },
            "driftPanel":
            {
                    "title": "记忆漂移",
                    "subtitle": "漂移模型会实时调整场景的色温、饱和度与景深。",
                    "fade": "漂移",
                    "fadedPct": "已漂移 {pct}%",
                    "daysSinceCreation": "创建距今",
                    "saturation": "色彩饱和度",
                    "fog": "雾气浓度"
                  }
          },
      "timeline":
      {
            "eyebrow": "时光长河",
            "title": "把记忆放回时间的几何里。",
            "lead": "将每段记忆按发生时间排列在一条长河上。色块的宽度暗示时间精度 — 精确到日、跨整年、或仅有系统时间。",
            "metrics":
            {
                    "total": "记忆数",
                    "precision": "时间精度",
                    "precisionValue": "日/年/系统",
                    "layout": "排版",
                    "layoutValue": "左右交替"
                  },
            "shell":
            {
                    "title": "时光长河",
                    "hint": "顶端是最早的记忆，越往下越接近现在。点击任意卡片直达详情。"
                  },
            "loading": "正在编织时光河…",
            "legend":
            {
                    "day": "精确到日",
                    "year": "跨整年",
                    "system": "系统时间"
                  }
          },
      "graph":
      {
            "eyebrow": "Mnemosyne 星图",
            "title": "你的记忆，是一片星空。",
            "lead": "每一段记忆都是一颗有自身光晕的恒星。位置由 ID 哈希到斐波那契球面，颜色由 AI 解析的主导情绪决定，亮度随漂移程度褪色。",
            "metrics":
            {
                    "total": "记忆星数",
                    "distribution": "分布算法",
                    "fibonacci": "斐波那契球面",
                    "engine": "渲染引擎",
                    "engineValue": "WebGL"
                  },
            "stage":
            {
                    "title": "记忆星空全景",
                    "hint": "拖动旋转视角，滚轮缩放。点击任意恒星可平滑推进至该记忆。"
                  },
            "loading": "正在打捞你的记忆星辰…",
            "selected": "聚焦",
            "openDetail": "打开记忆档案"
          }
    },
  "scene":
  {
      "eyebrow": "Memory graph reconstruction",
      "loading": "Constructing graph…",
      "lead": "Explore your memory's structural connections across five dimensions (Time, Location, Details, Process, and Associations) powered by Neo4j graph databases.",
      "metrics":
      {
            "objects": "Graph entities",
            "fragments": "Memory fragments",
            "drift": "Drift"
          },
      "hud":
      {
            "reconstructing": "Constructing graph…",
            "lightingSuffix": "lighting",
            "terrainSuffix": "terrain",
            "objects": "{count} objects",
            "fragments": "{count} fragments",
            "saturation": "{pct}% saturation"
          },
      "errorTitle": "Scene unavailable",
      "loadError": "Unable to load memory scene",
      "reconstructError": "Scene reconstruction is unavailable",
      "environments":
      {
            "outdoor_courtyard": "Outdoor courtyard",
            "snowy_landscape": "Snowy landscape",
            "night_courtyard": "Night courtyard",
            "rainy_street": "Rainy street",
            "flower_garden": "Flower garden",
            "autumn_path": "Autumn path",
            "schoolyard": "校园操场",
            "indoor_room": "温馨室内",
            "city_street": "都市街头",
            "seaside": "海滨沙滩",
            "mountain_path": "山间小道",
            "kitchen": "柴火厨房"
          },
      "lighting":
      {
            "warm_sunset": "Warm sunset",
            "diffuse_winter": "Diffuse winter",
            "moonlight": "Moonlight",
            "overcast": "Overcast",
            "morning_sun": "Morning sun",
            "golden_hour": "Golden hour",
            "ambient": "Ambient"
          },
      "terrain":
      {
            "flat": "Flat ground",
            "snow": "Snow",
            "wet": "Wet ground",
            "grass": "Grass",
            "leaves": "Leaves",
            "terrain": "Terrain"
          }
    },
  "resonance":
  {
      "hub":
      {
            "title": "Resonance hub",
            "subtitle": "Find souls who echo your memories.",
            "empty":
            {
                    "title": "暂无搜索结果",
                    "text": "在上方选择一段记忆并搜索，可挖掘出与之情绪结构相近的共鸣。"
                  },
            "eyebrow": "灵魂共鸣大厅",
            "lead": "选一段你写下的记忆，让相似度引擎在全网公开记忆中挑出与之共振的灵魂，然后开启一个融合的共鸣空间。",
            "title_short": "共鸣大厅",
            "metrics":
            {
                    "threshold": "匹配阈值",
                    "ranking": "排序模型",
                    "rankingValue": "情绪 60%",
                    "output": "产出",
                    "outputValue": "融合空间"
                  },
            "search":
            {
                    "title": "搜索共鸣",
                    "subtitle": "从你的记忆中挑出一段作为基准，相似度引擎会按情绪与场景维度排序候选。",
                    "baseMemory": "基准记忆",
                    "placeholder": "选择一段记忆…",
                    "searching": "搜索中…",
                    "submit": "搜索共鸣"
                  },
            "result":
            {
                    "note": "情绪与场景维度均衡的潜在共鸣候选。",
                    "overall": "综合分",
                    "emotion": "情绪",
                    "scene": "场景",
                    "creating": "创建中…",
                    "openSpace": "进入共鸣空间"
                  }
          },
      "space":
      {
            "title": "Shared resonance space",
            "placeNote": "Place note",
            "eyebrow": "灵魂共鸣空间",
            "title_long": "两段记忆，共享同一个时空。",
            "lead": "在这里，两段被算法挑出来的记忆共享同一个空间上下文，留言、漂浮指针与情绪振幅都将实时同步。",
            "metrics":
            {
                    "status": "连接状态",
                    "connected": "已连接",
                    "reconnecting": "正在重连",
                    "notes": "留言信标",
                    "similarity": "记忆相似度"
                  },
            "hud":
            {
                    "emotion": "情绪",
                    "scene": "场景",
                    "plantTip": "在地面上任意位置点击即可种下一颗信标，提交后会以光柱形式出现在场景中。",
                    "notesInSpace": "空间内信标 {count}",
                    "pressEHint": "靠近信标，按 E 键查看留言"
                  }
          },
      "beacon":
      {
            "moods":
            {
                    "warm": "Warm",
                    "joyful": "Joyful",
                    "melancholic": "Melancholic",
                    "contemplative": "Contemplative",
                    "grateful": "Grateful"
                  },
            "eyebrow": "全息展开",
            "title": "信标留言",
            "anonymous": "匿名访客",
            "from": "来自：{author}"
          },
      "noteComposer":
      {
            "title": "Leave a note",
            "subtitle": "Attach a brief thought to the current resonance space.",
            "placeholder": "What do you feel in this space?",
            "submit": "Place note",
            "cancel": "Cancel"
          }
    },
  "profile":
  {
      "title": "Profile",
      "eyebrow": "Soul Archive",
      "lead": "View your credentials, memory ecosystem health, and emotional chaotic trajectories. All data comes from your current authenticated session.",
      "note": "Profile data is loaded from your active session; future updates will extend this to avatars, preferences, subscriptions, and multi-device sync.",
      "stats":
      {
            "memories": "Memories",
            "fragments": "Fragments",
            "resonances": "Resonances"
          },
      "metrics":
      {
            "status": "Account Status",
            "verified": "Verified",
            "pending": "Pending Verification",
            "email": "Email",
            "memories": "Total Memories"
          },
      "avatar":
      {
            "custom": "Custom Avatar",
            "default": "Default Avatar"
          },
      "tags":
      {
            "privacy": "Memory Privacy",
            "history": "Revision History",
            "resonance": "Resonance Access"
          },
      "avatar3d":
      {
            "eyebrow": "Soul Portrait",
            "title": "My 3D Soul Avatar",
            "subtitle": "Describe yourself in words, and AI will sculpt a unique 3D soul avatar that represents you in resonance spaces.",
            "editBtn": "Re-sculpt",
            "empty":
            {
                    "title": "Your soul avatar hasn't been sculpted yet",
                    "text": "Describe yourself in a few sentences — your personality, hobbies, inner world — and AI will generate a unique 3D soul avatar for you.",
                    "createBtn": "✨ Start sculpting"
                  },
            "info":
            {
                    "titleLabel": "Soul Title",
                    "storyLabel": "Soul Story",
                    "tagsLabel": "Personality Tags",
                    "descLabel": "Self Description",
                    "public": "🌐 Public",
                    "private": "🔒 Private",
                    "updatedAt": "Last updated: "
                  },
            "builder":
            {
                    "eyebrow": "AI Soul Sculpting Engine",
                    "title": "Describe yourself",
                    "subtitle": "The more specific, the better — your personality, hobbies, inner world, favorite time of day, how you feel about the world… AI will sculpt your unique 3D soul avatar from this.",
                    "exampleLabel": "Reference examples",
                    "exampleBtn": "Example {n}",
                    "descLabel": "Self description",
                    "descPlaceholder": "I'm someone who loves reading late at night, with a quiet starfield inside…",
                    "publicLabel": "Show my soul avatar publicly",
                    "publicHint": "(Others can see it in resonance spaces)",
                    "generate": "✨ Generate my soul avatar",
                    "regenerate": "🔄 Regenerate",
                    "saving": "AI is sculpting…",
                    "delete": "Delete avatar",
                    "deleteConfirm": "Delete your 3D soul avatar? This cannot be undone.",
                    "hints":
                    {
                              "tooShort": "Too short — at least 10 characters needed",
                              "minimal": "Add more detail for a more accurate portrait",
                              "good": "Good! AI can already sense your character",
                              "excellent": "✓ Rich description — AI will sculpt your most accurate soul avatar"
                            },
                    "error":
                    {
                              "saveFailed": "Generation failed. Please try again.",
                              "deleteFailed": "Deletion failed. Please try again."
                            }
                  }
          },
      "attractor":
      {
            "eyebrow": "Emotional Archaeology",
            "title": "Emotional Chaotic Attractor",
            "subtitle": "Plot your five-dimensional emotional index into the Lorenz equation to chart a unique soul trajectory.",
            "intro": "This is your emotional chaotic trajectory. The AI reads the emotional distribution (joy, sorrow, fear, calm, nostalgia) across all your memories and uses the Lorenz dynamical equations to map out a unique 'soul trajectory'. Joy makes it light and vibrant, nostalgia blooms and unfolds the curves, and peace brings it to a quiet, elegant convergence.",
            "mockNote": "Your emotional trajectory is computed in real time based on your active memories. If no memories exist yet, a default cosmic nebula is rendered.",
            "dim":
            {
                    "joy": "Joy",
                    "sorrow": "Sorrow",
                    "fear": "Fear",
                    "calm": "Calm",
                    "nostalgia": "Nostalgia"
                  }
          },
      "emotion":
      {
            "loading": "Aggregating your emotional profile…",
            "loadFailed": "Failed to load emotional profile",
            "unavailable": "Emotional profile temporarily unavailable",
            "empty": "No emotional data yet — add a few memories with emotion tags to start charting",
            "hint": "Data is averaged across your 50 most recent memories with emotion tags, then drives the chaotic attractor.",
            "retry": "Retry"
          }
    },
  "chat":
  {
      "ai":
      {
            "name": "Echo Envoy",
            "icebreakerBtn": "Ask Echo Envoy",
            "icebreakerHint": "Let AI read your conversation and suggest an icebreaker / topic",
            "icebreakerError": "Echo Envoy is offline right now, try again later.",
            "suggestionTitle": "Echo Envoy's icebreaker",
            "useSuggestion": "Use this",
            "mentionTip": "Mention {'@'}AI in a group to summon me",
            "mentionHint": "Start a message with {'@'}AI or {'@'}Echo and I'll reply using recent group context",
            "thoughtProcess": "Thinking Process"
          },
      "tabs":
      {
            "chats": "Chats",
            "discover": "Discover",
            "groups": "Groups",
            "theme": "Theme"
          },
      "sidebar":
      {
            "friendsTitle": "Approved Friends",
            "noFriends": "No friends found. Use the Discover tab to search and add friends.",
            "searchPlaceholder": "Search usernames...",
            "searchBtn": "Go",
            "matchesTitle": "Matches",
            "noMatches": "Enter a username and search to discover new users.",
            "addFriendBtn": "+ Add",
            "createGroupTitle": "Create New Group",
            "groupNamePlaceholder": "Group Name",
            "selectFriendsLabel": "Select Friends:",
            "createGroupBtn": "Create",
            "myGroupsTitle": "My Joined Groups",
            "noGroups": "No active groups. Create one above!",
            "groupOwner": "Owner: You",
            "groupMember": "Owner: Friend",
            "themeTitle": "Soul Wallpapers",
            "themeSubtitle": "Personalize your profile settings. Custom wallpapers appear as your conversational backdrop!",
            "presetsLabel": "Presets Library",
            "avatarUrlLabel": "Avatar URL",
            "avatarUrlPlaceholder": "HTTP(S) link to avatar image",
            "bgUrlLabel": "Chat Wallpaper URL",
            "bgUrlPlaceholder": "HTTP(S) link to wallpaper (WebP/JPG/PNG)",
            "saveThemeBtn": "Save Theme"
          },
      "body":
      {
            "connectedStatus": "Connected",
            "offlineStatus": "Offline",
            "inputTextPlaceholder": "Type a message...",
            "sendBtn": "Send",
            "noSelectionTitle": "Reconstruct conversations in real-time.",
            "noSelectionSubtitle": "Select a friend from the Approved tab, search for other museum curators, or assemble a group chat to exchange thoughts."
          }
    },
  "errors":
  {
      "network": "Network error. Check your connection.",
      "unauthorized": "Session expired. Please sign in again.",
      "forbidden": "You don't have permission for this resource.",
      "notFound": "Resource not found.",
      "conflict": "Conflict: required field missing or value not unique.",
      "server": "Service error. Please try again later.",
      "unavailable": "Service temporarily unavailable.",
      "unknown": "Unknown error. Please try again."
    },
  "support":
  {
      "fab": "Contact Support",
      "title": "Support Online",
      "subtitle": "Tell us your issue — we'll get back as soon as possible",
      "newTicket": "New ticket",
      "myTickets": "My tickets",
      "back": "Back to list",
      "createTitle": "Submit a new ticket",
      "subjectLabel": "Subject",
      "subjectPlaceholder": "One-line summary (e.g. cannot view atlas after login)",
      "descriptionLabel": "Description",
      "descriptionPlaceholder": "Describe the issue. You can attach images after creating the ticket.",
      "priorityLabel": "Priority",
      "submit": "Submit",
      "submitting": "Submitting…",
      "emptyTickets": "No tickets yet. Click \"New ticket\" to start a conversation.",
      "ticketStatus":
      {
            "OPEN": "Open",
            "IN_PROGRESS": "In progress",
            "RESOLVED": "Resolved",
            "CLOSED": "Closed"
          },
      "priority":
      {
            "LOW": "Low",
            "NORMAL": "Normal",
            "HIGH": "High",
            "URGENT": "Urgent"
          },
      "messages":
      {
            "you": "You",
            "support": "Support",
            "noMessages": "Awaiting support reply…",
            "placeholder": "Type a message (supports emoji & images)",
            "send": "Send",
            "image": "Image",
            "emoji": "Emoji",
            "imageTooLarge": "Image must be smaller than 5 MB",
            "uploading": "Uploading…"
          },
      "close": "Close ticket",
      "closeConfirm": "Close this ticket? You won't be able to send more messages.",
      "closed": "Ticket closed",
      "errors":
      {
            "createFailed": "Failed to submit ticket. Please retry.",
            "sendFailed": "Failed to send message. Check your connection and retry.",
            "uploadFailed": "Image upload failed: {msg}"
          }
    },
  "admin":
  {
      "hud":
      {
            "toggle": "Cinematic HUD Mode",
            "exit": "Exit HUD Mode",
            "title": "Quantum Memory Global Controller HUD",
            "description": "Real-time 3D spatial topology, network resonance mapping, AI reconstruction factors and memory micro-nebula health telemetry"
          },
      "nav":
      {
            "entry": "Admin",
            "home": "Overview",
            "activeUsers": "Active Users",
            "memoryTrends": "Memory Trends",
            "emotion": "Emotion Distribution",
            "heatmap": "Global Heatmap",
            "contributors": "Top Contributors",
            "fragments": "Fragment Discovery",
            "resonance": "Resonance Overview",
            "health": "System Health",
            "usersManagement": "Users",
            "memoriesManagement": "Memories",
            "resonanceManagement": "Edges",
            "supportInbox": "Support",
            "maintenance": "Maintenance"
          },
      "guard":
      {
            "notAdmin": "Admin only — redirected to your memories",
            "loginRequired": "Please sign in with an admin account"
          },
      "common":
      {
            "empty": "No data yet",
            "error": "Failed to load",
            "retry": "Retry",
            "degradedBadge": "Degraded",
            "degradedHint": "Some upstream services are unavailable. Showing partial results:",
            "loading": "Loading…",
            "lastUpdated": "Last updated",
            "diagnosticHead": "Diagnostics",
            "diagUpstream": "Failing service",
            "diagFailureKind": "Failure kind",
            "diagDetail": "Detail",
            "diagCauseType": "Cause type",
            "diagHint": "Check the System Health panel for live status or run backend/scripts/Diagnose-AdminPanel.ps1 for an end-to-end probe."
          },
      "errors":
      {
            "INVALID_DIMENSION": "Invalid dimension. Choose DAILY / WEEKLY / MONTHLY / YEARLY.",
            "INVALID_RANGE": "Invalid range or more than 366 buckets",
            "UPSTREAM_UNAVAILABLE": "Upstream service unavailable",
            "ADMIN_AGG_FEIGN": "Cross-service aggregation failed",
            "ADMIN_AGG_REDIS": "Cache layer unavailable",
            "ADMIN_REQUIRED": "Administrator permission required",
            "NETWORK": "Network error. Check your connection and retry.",
            "BOOTSTRAP_DISABLED": "Bootstrap endpoint disabled",
            "BOOTSTRAP_REJECTED": "Bootstrap secret rejected"
          },
      "activeUsers":
      {
            "title": "Active Users",
            "subtitle": "Unique users who created or modified memories",
            "dimensions":
            {
                    "DAILY": "Daily",
                    "WEEKLY": "Weekly",
                    "MONTHLY": "Monthly",
                    "YEARLY": "Yearly"
                  },
            "legend":
            {
                    "activeUsers": "Active users"
                  }
          },
      "memoryTrends":
      {
            "title": "Memory Trends",
            "subtitle": "New / modified memories per bucket",
            "legend":
            {
                    "created": "Created",
                    "modified": "Modified"
                  }
          },
      "emotion":
      {
            "title": "Emotion Distribution",
            "subtitle": "Mean emotion vector across PUBLIC memories",
            "sampleSize": "Sample size {count}",
            "components":
            {
                    "joy": "Joy",
                    "sadness": "Sadness",
                    "anger": "Anger",
                    "fear": "Fear",
                    "surprise": "Surprise",
                    "nostalgia": "Nostalgia",
                    "peace": "Peace",
                    "melancholy": "Melancholy"
                  }
          },
      "heatmap":
      {
            "title": "Global Memory Heatmap",
            "subtitle": "PUBLIC memories aggregated to a lat/lon grid",
            "resolution":
            {
                    "LOW": "Low (5°)",
                    "MEDIUM": "Medium (1°)",
                    "HIGH": "High (0.25°)"
                  },
            "intensity": "Relative intensity",
            "noData": "暂无地理位置数据",
            "noDataHint": "创建记忆时填写「地点」字段，系统将自动解析坐标并在此显示",
            "pointsLabel": "个热力点"
          },
      "contributors":
      {
            "title": "Top Contributors",
            "subtitle": "Ranked by memories created",
            "countSuffix": "memories",
            "usernameUnknown": "Unknown user"
          },
      "fragments":
      {
            "title": "Fragment Discovery",
            "subtitle": "Share of fragments that have been discovered",
            "gauge":
            {
                    "label": "Overall discovery rate"
                  },
            "byType": "By fragment type",
            "types":
            {
                    "forgotten_detail": "Forgotten detail",
                    "emotion_flashback": "Emotion flashback",
                    "scene_artifact": "Scene artifact",
                    "audio_echo": "Audio echo"
                  }
          },
      "resonance":
      {
            "title": "Resonance Overview",
            "subtitle": "Total edges / average score / status breakdown",
            "kpi":
            {
                    "totalEdges": "Total edges",
                    "averageScore": "Average score"
                  },
            "statusBreakdown": "Status breakdown",
            "status":
            {
                    "pending": "Pending",
                    "active": "Active",
                    "archived": "Archived"
                  },
            "top":
            {
                    "title": "Top {n} resonances",
                    "scoreLabel": "Score"
                  }
          },
      "health":
      {
            "title": "System Health",
            "subtitle": "Real-time status of downstream services",
            "overall": "Overall",
            "status":
            {
                    "UP": "UP",
                    "DEGRADED": "DEGRADED",
                    "DOWN": "DOWN"
                  },
            "latency": "Latency {ms} ms"
          },
      "usersMgmt":
      {
            "title": "User Management",
            "subtitle": "View / search / change user roles and status",
            "search": "Search username / email",
            "filters":
            {
                    "all": "All",
                    "role": "Role",
                    "verified": "Verified",
                    "unverified": "Unverified"
                  },
            "columns":
            {
                    "username": "Username",
                    "email": "Email",
                    "role": "Role",
                    "verified": "Status",
                    "createdAt": "Joined",
                    "actions": "Actions"
                  },
            "actions":
            {
                    "promote": "Promote",
                    "demote": "Demote",
                    "verify": "Verify",
                    "unverify": "Unverify",
                    "delete": "Delete",
                    "view": "View"
                  },
            "batch":
            {
                    "selected": "{n} selected",
                    "delete": "Delete selected",
                    "deleteConfirm": "Delete {n} users? This cannot be undone.",
                    "clearSelection": "Clear selection"
                  },
            "confirm":
            {
                    "deleteOne": "Delete user {name}? Cannot be undone.",
                    "promote": "Promote {name} to admin?",
                    "demote": "Demote {name} to regular user?",
                    "verify": "Mark {name} as verified?",
                    "unverify": "Revoke {name}'s verified status?"
                  },
            "toast":
            {
                    "deleted": "Deleted {n} users",
                    "deleteFailed": "{n} users failed to delete",
                    "roleChanged": "Role updated",
                    "verifiedChanged": "Status updated",
                    "operationFailed": "Operation failed: {msg}"
                  }
          },
      "memoriesMgmt":
      {
            "title": "Memory Management",
            "subtitle": "Audit / adjust privacy / lock / delete memories",
            "search": "Search title",
            "filters":
            {
                    "userId": "Owner user ID",
                    "privacy": "Privacy",
                    "locked": "Locked",
                    "all": "All",
                    "yes": "Locked",
                    "no": "Unlocked"
                  },
            "columns":
            {
                    "title": "Title",
                    "owner": "Owner",
                    "privacy": "Privacy",
                    "locked": "Lock",
                    "year": "Year",
                    "location": "Location",
                    "createdAt": "Created",
                    "actions": "Actions"
                  },
            "actions":
            {
                    "lock": "Lock",
                    "unlock": "Unlock",
                    "makePrivate": "Set Private",
                    "makeFriends": "Set Friends",
                    "makePublic": "Set Public",
                    "delete": "Delete",
                    "viewDetail": "View detail"
                  },
            "batch":
            {
                    "selected": "{n} selected",
                    "delete": "Delete selected",
                    "deleteConfirm": "Delete {n} memories? Fragments and versions will be removed too.",
                    "lock": "Lock selected",
                    "unlock": "Unlock selected",
                    "privacy": "Change privacy",
                    "clearSelection": "Clear selection"
                  },
            "confirm":
            {
                    "deleteOne": "Delete memory \"{title}\"?"
                  },
            "toast":
            {
                    "deleted": "Deleted {n} memories",
                    "updated": "Updated {n} items",
                    "operationFailed": "Operation failed: {msg}"
                  }
          },
      "resonanceMgmt":
      {
            "title": "Resonance Management",
            "subtitle": "View / adjust resonance edge status, bulk delete",
            "filters":
            {
                    "all": "All statuses",
                    "pending": "Pending",
                    "accepted": "Accepted",
                    "rejected": "Rejected",
                    "archived": "Archived"
                  },
            "columns":
            {
                    "memoryAId": "Memory A",
                    "memoryBId": "Memory B",
                    "score": "Score",
                    "status": "Status",
                    "createdAt": "Created",
                    "actions": "Actions"
                  },
            "actions":
            {
                    "accept": "Mark accepted",
                    "reject": "Mark rejected",
                    "archive": "Archive",
                    "delete": "Delete"
                  },
            "batch":
            {
                    "selected": "{n} selected",
                    "delete": "Delete selected",
                    "status": "Change status",
                    "clearSelection": "Clear selection"
                  }
          },
      "maintenance":
      {
            "title": "Maintenance",
            "limit": "Limit",
            "done": "Operation dispatched",
            "failed": "Operation failed",
            "vector":
            {
                    "title": "Vector backfill",
                    "desc": "Re-index existing memories into the Milvus vector store. Run once after first enabling vector search, or after changing the embedding model / dimension — otherwise the store stays empty and AI search / resonance fall back to keyword matching.",
                    "run": "Run backfill",
                    "result": "Dispatched {dispatched} of {total} memories."
                  },
            "visual":
            {
                    "title": "visualData cleanup",
                    "desc": "Scan memories whose visualData is empty or still uses the old English template, and regenerate their 3D scene data so SceneViewer no longer shows stale fake scenes.",
                    "run": "Run cleanup",
                    "result": "Scanned {scanned}, dispatched {dispatched} rebuild jobs."
                  },
            "geo":
            {
                    "title": "Coordinate backfill",
                    "desc": "Scan memories with non-empty location text but empty coordinates, re-resolve their longitude and latitude and persist them to the database. Retraced trails on Atlas and heatmap charts will display these locations after backfilling.",
                    "run": "Run backfill",
                    "result": "Scanned {scanned}, resolved {resolved}, skipped {skipped} memories."
                  },
            "fragments":
            {
                    "title": "Fragments batch rebuild",
                    "desc": "Scan and purge historical legacy English mock fragments, and asynchronously rebuild new, grounded Chinese fragments matching the memory descriptions using the LLM or localized rule pipeline.",
                    "run": "Run rebuild",
                    "result": "Scanned {scanned}, dispatched {dispatched} fragment rebuild tasks."
                  },
            "orphan":
            {
                    "title": "MinIO legacy orphan migration",
                    "desc": "Early uploads have no users/ prefix and get exposed as public assets to everyone. This tool moves such legacy private objects under the legacy-orphan/ prefix. Preview first, then apply.",
                    "preview": "Preview candidates",
                    "apply": "Apply migration",
                    "dryRun": "Preview (not moved)",
                    "result": "Scanned {scanned} objects, {candidates} candidates, {migrated} migrated."
                  }
          },
      "supportInbox":
      {
            "title": "Support Tickets",
            "subtitle": "Review / respond to user tickets",
            "stats":
            {
                    "open": "Open",
                    "inProgress": "In progress",
                    "resolved": "Resolved",
                    "closed": "Closed",
                    "total": "Total"
                  },
            "filters":
            {
                    "search": "Search subject / content",
                    "status": "Status",
                    "priority": "Priority",
                    "userId": "User ID",
                    "all": "All"
                  },
            "status":
            {
                    "OPEN": "Open",
                    "IN_PROGRESS": "In progress",
                    "RESOLVED": "Resolved",
                    "CLOSED": "Closed"
                  },
            "priority":
            {
                    "LOW": "Low",
                    "NORMAL": "Normal",
                    "HIGH": "High",
                    "URGENT": "Urgent"
                  },
            "columns":
            {
                    "subject": "Subject",
                    "user": "User",
                    "priority": "Priority",
                    "status": "Status",
                    "lastMessageAt": "Last message",
                    "actions": "Actions"
                  },
            "actions":
            {
                    "open": "Open",
                    "resolve": "Mark resolved",
                    "close": "Close ticket",
                    "delete": "Delete"
                  },
            "detail":
            {
                    "ticketTitle": "Ticket #{id}",
                    "from": "From",
                    "subject": "Subject",
                    "description": "Description",
                    "messages": "Conversation",
                    "replyPlaceholder": "Reply to customer…",
                    "send": "Send",
                    "uploadImage": "Image",
                    "emoji": "Emoji",
                    "noMessages": "No messages yet"
                  }
          }
    },
  "skeleton":
  {
      "box": "Loading…",
      "memoryCard":
      {
            "title": "Memory title placeholder",
            "meta": "time · location",
            "excerpt": "Memory excerpt placeholder — this is an AI-reconstructed description waiting to load.",
            "tag": "tag"
          },
      "chapterList":
      {
            "heading": "Chapter heading",
            "item": "Chapter list item",
            "timestamp": "Timestamp"
          },
      "chatMessage":
      {
            "user": "Typing a message…",
            "ai": "Echo is thinking…",
            "timestamp": "Just now"
          }
    },
  "errorBoundary":
  {
      "title": "Something went sideways",
      "subtitle": "A memory hall could not be opened — it has been isolated so the rest of the museum keeps running.",
      "detail": "Error details",
      "stackLabel": "Stack",
      "componentLabel": "Component",
      "retry": "Reload this memory",
      "goHome": "Back to the museum entrance",
      "reportHint": "If this keeps happening, screenshot it and contact support.",
      "copySuccess": "Copied to clipboard",
      "copyFailed": "Copy failed, please select the text manually"
    },
  "pwa":
  {
      "offline":
      {
            "title": "You are offline",
            "body": "The memory museum is unreachable — previously visited memories are still browsable.",
            "retry": "Try again",
            "queued": "Queued",
            "synced": "Synced",
            "queueHint": "{count} AI requests are queued offline and will be sent automatically once reconnected."
          },
      "update":
      {
            "available": "A new version is ready",
            "body": "R{version} has been downloaded. Refresh to switch over.",
            "refresh": "Refresh now",
            "later": "Later"
          },
      "install":
      {
            "title": "Install the memory museum",
            "body": "Install for offline browsing, beacon notifications and a more immersive feel.",
            "install": "Install",
            "dismiss": "Not now"
          }
    }
} as const
