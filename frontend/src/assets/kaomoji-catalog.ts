/**
 * 颜文字 & 表情字符目录（v8 新增）。
 *
 * <p>用途：
 * <ul>
 *   <li>ChatView 的 emoji picker — 替代之前硬编码的 16 个 emoji 数组；</li>
 *   <li>聊天消息快速插入表情（避免每次打完整 Unicode）；</li>
 *   <li>支持按 category 过滤（happy / sad / angry / cute / shrug / 中式 / 颜文字 等）。</li>
 * </ul>
 *
 * <p>设计原则：
 * <ol>
 *   <li>数据纯静态 — 200+ 条目，全部内联到 JS（gzip 后 < 6KB），无网络请求；</li>
 *   <li>按 category 分桶 — 渲染 emoji picker 时按桶展示；</li>
 *   <li>每个 entry 都有搜索关键字（keywords），picker 支持中英文模糊搜索；</li>
 *   <li>所有字符是 Unicode 13+ 表情字符 / 颜文字 ASCII 字符，浏览器原生支持；</li>
 *   <li>不包含国旗 / 政治人物 / 宗教 / 露骨内容。</li>
 * </ol>
 *
 * <p>v8 后续可通过 {@code loadEmojiFromMinio} 把这些内置 catalog 覆盖为从
 * MinIO 拉的远程 catalog（asset-service 暴露 /api/v1/assets/list 端点），
 * 这样运营想加新表情时直接上传到 bucket 即可，无需发版。
 */

// ─── 公共类型 ───────────────────────────────────────────────────────

/** 单个表情 / 颜文字 entry。 */
export interface KaomojiEntry {
  /** Unicode 字符 / 颜文字 ASCII 字符串 */
  glyph: string
  /** 中文名 — 用于 picker tooltip / 搜索 */
  name: string
  /** 英文名 — 国际化搜索 */
  nameEn: string
  /** 主分类 — 决定 picker 哪一栏 */
  category: KaomojiCategory
  /** 搜索关键字（用 ' | ' 分隔的多个关键词） */
  keywords: string
  /** 自定义 emoji 标签 — 比如 'A' 'B' 'OK' 字符画；null 表示无 */
  shortLabel?: string
}

export type KaomojiCategory =
  | 'happy'       // 开心 / 得意
  | 'sad'         // 难过 / 失落
  | 'angry'       // 生气 / 不爽
  | 'cute'        // 卖萌 / 撒娇
  | 'love'        // 爱心 / 暧昧
  | 'shrug'       // 摊手 / 无奈
  | 'think'       // 思考 / 疑惑
  | 'greet'       // 问候 / 打招呼
  | 'food'        // 食物
  | 'animal'      // 动物
  | 'object'      // 物件 / 工具
  | 'symbol'      // 符号 / 装饰
  | 'meme'        // 中式网络热词 / 表情包字符替代
  | 'kaomoji'     // 颜文字 ASCII / Unicode art
  | 'flag'        // 不在国际化 flags 集合中（保留空类目）

export const KAOMOJI_CATEGORIES: { key: KaomojiCategory; labelZh: string; labelEn: string; icon: string }[] = [
  { key: 'happy',   labelZh: '开心得意', labelEn: 'Happy',   icon: '😊' },
  { key: 'sad',     labelZh: '难过失落', labelEn: 'Sad',     icon: '😢' },
  { key: 'angry',   labelZh: '生气不爽', labelEn: 'Angry',   icon: '😠' },
  { key: 'cute',    labelZh: '卖萌撒娇', labelEn: 'Cute',    icon: '🥰' },
  { key: 'love',    labelZh: '爱心暧昧', labelEn: 'Love',    icon: '💕' },
  { key: 'shrug',   labelZh: '摊手无奈', labelEn: 'Shrug',   icon: '🤷' },
  { key: 'think',   labelZh: '思考疑惑', labelEn: 'Think',   icon: '🤔' },
  { key: 'greet',   labelZh: '问候招呼', labelEn: 'Greet',   icon: '👋' },
  { key: 'food',    labelZh: '食物',     labelEn: 'Food',    icon: '🍔' },
  { key: 'animal',  labelZh: '动物',     labelEn: 'Animal',  icon: '🐱' },
  { key: 'object',  labelZh: '物件工具', labelEn: 'Object',  icon: '💡' },
  { key: 'symbol',  labelZh: '符号装饰', labelEn: 'Symbol',  icon: '✨' },
  { key: 'meme',    labelZh: '中式热梗', labelEn: 'Meme',    icon: '🔥' },
  { key: 'kaomoji', labelZh: '颜文字',   labelEn: 'Kaomoji', icon: '¯\\_(ツ)_/¯' },
] as const

// ─── 内部构造助手 ───────────────────────────────────────────────────

function g(glyph: string, name: string, nameEn: string, category: KaomojiCategory,
          keywords: string, shortLabel?: string): KaomojiEntry {
  return { glyph, name, nameEn, category, keywords, shortLabel }
}

// ─── 200+ 表情数据 ───────────────────────────────────────────────────

/**
 * 12 个分类共 200+ 个条目。
 * 顺序：每个分类内按"使用频率 / 表达力"降序。
 */
export const KAOMOJI_CATALOG: KaomojiEntry[] = [
  // ── happy: 22 条 ──
  g('😀', '哈哈',     'grinning',       'happy', '笑 哈哈 grin 露齿 laugh haha'),
  g('😃', '大笑',     'smiley',         'happy', '笑 大笑 smile joy haha'),
  g('😄', '呵呵',     'smile',          'happy', '笑 呵呵 smile joy happy'),
  g('😁', '嘻嘻',     'grin',           'happy', '笑 嘻嘻 grin teeth'),
  g('😆', '笑哭',     'laughing',       'happy', '笑 笑哭 笑出眼泪 lol xd'),
  g('😅', '尴尬笑',   'sweat smile',    'happy', '笑 尴尬 紧张 sweat haha nervous'),
  g('🤣', '笑翻',     'rofl',           'happy', '笑 笑翻 笑死 rofl lmao'),
  g('😂', '笑cry',   'joy',            'happy', '笑 笑哭 笑cry 喜极而泣 joy'),
  g('🙂', '微笑',     'slightly smile', 'happy', '笑 微笑 礼貌 smile'),
  g('🙃', '倒笑脸',   'upside down',    'happy', '笑 倒笑 调皮 silly flip'),
  g('😉', '眨眼',     'wink',           'happy', '笑 眨眼 调皮 wink'),
  g('😊', '害羞笑',   'blush',          'happy', '笑 害羞 脸红 smile blush shy'),
  g('😇', '天使',     'angel',          'happy', '笑 天使 乖巧 angel innocent'),
  g('🥳', '派对',     'party',          'happy', '庆祝 派对 生日 party celebrate'),
  g('😎', '酷',       'cool',           'happy', '酷 墨镜 得意 cool sunglasses'),
  g('🤩', '星星眼',   'star eyes',      'happy', '迷妹 崇拜 期待 star-struck'),
  g('😏', '得意',     'smirk',          'happy', '得意 坏笑 smirk smug'),
  g('😺', '猫笑',     'cat smile',      'happy', '猫 笑 cat smile'),
  g('🤗', '抱抱',     'hug',            'happy', '抱 拥抱 热情 hug'),
  g('😋', '馋嘴',     'yum',            'happy', '馋 嘴馋 好吃 yum delicious tongue'),
  g('🤤', '流口水',   'drool',          'happy', '馋 流口水 渴望 drool'),
  g('🤪', '疯狂',     'zany',           'happy', '调皮 疯狂 搞怪 zany goofy'),

  // ── sad: 14 条 ──
  g('😢', '哭泣',     'cry',            'sad',   '哭 流泪 cry tear'),
  g('😭', '大哭',     'sob',            'sad',   '哭 大哭 泪崩 sob cry'),
  g('😞', '失望',     'disappointed',   'sad',   '失望 沮丧 disappointed'),
  g('😔', '郁闷',     'pensive',        'sad',   '郁闷 沉思 pensive'),
  g('😟', '担心',     'worried',        'sad',   '担心 焦虑 worried'),
  g('😕', '困惑',     'confused',       'sad',   '困惑 不解 confused'),
  g('🙁', '不开心',   'frown',          'sad',   '皱眉 不开心 frown'),
  g('☹️', '痛苦',     'frowning',       'sad',   '痛苦 难过 frowning'),
  g('😣', '痛苦',     'persevere',      'sad',   '坚持 痛苦 persevere'),
  g('😥', '释然',     'relieved',       'sad',   '释然 松口气 relieved'),
  g('😰', '焦虑',     'anxious',        'sad',   '焦虑 紧张 anxious sweat'),
  g('😱', '尖叫',     'scream',         'sad',   '惊吓 尖叫 scream'),
  g('🥺', '求求',     'pleading',       'sad',   '求 求求 卖惨 撒娇 pleading'),
  g('😿', '猫哭',     'cat cry',        'sad',   '猫 哭 cat cry'),

  // ── angry: 10 条 ──
  g('😠', '生气',     'angry',          'angry', '生气 愤怒 angry'),
  g('😡', '怒火',     'rage',           'angry', '怒火 暴怒 rage mad puffy'),
  g('🤬', '爆粗',     'cursing',        'angry', '骂人 爆粗 cursing swear'),
  g('😤', '得意气',   'triumph',        'angry', '不服 气 哼 triumph'),
  g('😖', '崩溃',     'confounded',     'angry', '崩溃 无奈 confounded'),
  g('😣', '难受',     'persevere',      'angry', '难受 痛苦 persevere'),
  g('😩', '累死',     'weary',          'angry', '累 疲惫 weary'),
  g('😫', '疲惫',     'tired',          'angry', '累 疲惫 烦 tired'),
  g('😾', '猫怒',     'cat pout',       'angry', '猫 怒 pout'),
  g('👿', '恶魔',     'imp',            'angry', '恶魔 生气 imp angry'),

  // ── cute: 12 条 ──
  g('🥰', '恋爱',     'in love',        'cute',  '恋爱 喜欢 爱心 害羞 3-heart'),
  g('😍', '花痴',     'heart eyes',     'cute',  '喜欢 爱心眼 heart-eyes'),
  g('🤩', '迷妹',     'star eyes',      'cute',  '迷妹 崇拜 期待 star-struck'),
  g('😘', '亲亲',     'kiss',           'cute',  '亲 亲亲 飞吻 kiss'),
  g('😗', '啵',       'kissing',        'cute',  '亲 啵 kissing'),
  g('☺️', '满足',     'smiling',        'cute',  '满足 暖心 smiling'),
  g('😚', '害羞亲',   'kiss closed',    'cute',  '亲 害羞 kiss'),
  g('🤭', '捂嘴',     'hand mouth',     'cute',  '捂嘴 偷笑 hand-over-mouth'),
  g('🤫', '嘘',       'shush',          'cute',  '嘘 安静 秘密 shush'),
  g('🙈', '非礼勿视', 'see no evil',    'cute',  '害羞 蒙眼 see-no-evil monkey'),
  g('🙉', '非礼勿听', 'hear no evil',   'cute',  '捂耳 hear-no-evil monkey'),
  g('🙊', '非礼勿言', 'speak no evil',  'cute',  '捂嘴 speak-no-evil monkey'),

  // ── love: 10 条 ──
  g('❤️', '红心',     'red heart',      'love',  '爱 喜欢 heart love'),
  g('🧡', '橙心',     'orange heart',   'love',  '爱 橙心 heart'),
  g('💛', '黄心',     'yellow heart',   'love',  '爱 黄心 heart'),
  g('💚', '绿心',     'green heart',    'love',  '爱 绿心 heart'),
  g('💙', '蓝心',     'blue heart',     'love',  '爱 蓝心 heart'),
  g('💜', '紫心',     'purple heart',   'love',  '爱 紫心 heart'),
  g('🖤', '黑心',     'black heart',    'love',  '爱 黑心 酷 heart'),
  g('🤍', '白心',     'white heart',    'love',  '爱 白心 心心 heart'),
  g('💕', '两颗心',   'two hearts',     'love',  '爱 双心 心心 hearts'),
  g('💖', '闪光心',   'sparkling heart','love',  '爱 闪光 心心 sparkling-heart'),
  g('💝', '礼物心',   'gift heart',     'love',  '爱 礼物 heart'),
  g('💘', '丘比特',   'cupid',          'love',  '爱 丘比特 之箭 cupid'),

  // ── shrug: 10 条 ──
  g('🤷', '摊手',     'shrug',          'shrug', '摊手 不知道 shrug'),
  g('🤷‍♀️', '女摊手', 'shrug female',   'shrug', '摊手 shrug female'),
  g('🤷‍♂️', '男摊手', 'shrug male',     'shrug', '摊手 shrug male'),
  g('😐', '面瘫',     'neutral',        'shrug', '面瘫 无语 neutral'),
  g('😑', '无表情',   'expressionless', 'shrug', '无语 expressionless'),
  g('😶', '沉默',     'no mouth',       'shrug', '沉默 闭嘴 speechless'),
  g('😒', '不开心',   'unamused',       'shrug', '不开心 嫌弃 unamused'),
  g('🙄', '翻白眼',   'eye roll',       'shrug', '翻白眼 嫌弃 eye-roll'),
  g('😬', '苦笑',     'grimace',        'shrug', '苦笑 尴尬 grimace'),
  g('🫠', '融化',     'melting',        'shrug', '融化 尴尬 melting'),

  // ── think: 12 条 ──
  g('🤔', '思考',     'thinking',       'think', '思考 怀疑 thinking'),
  g('🧐', '琢磨',     'monocle',        'think', '琢磨 鉴赏 monocle'),
  g('🤨', '挑眉',     'raised brow',    'think', '挑眉 怀疑 raised-eyebrow'),
  g('😧', '震惊',     'anguished',      'think', '震惊 anguished'),
  g('😮', '哇',       'open mouth',     'think', '哇 惊讶 open-mouth'),
  g('😯', '嘘',       'hushed',         'think', '嘘 惊 hushed'),
  g('😲', '震惊',     'astonished',     'think', '震惊 惊讶 astonished'),
  g('😳', '脸红',     'flushed',        'think', '脸红 害羞 flushed'),
  g('🥸', '伪装',     'disguise',       'think', '伪装 戴眼镜 disguise'),
  g('🫥', '隐身',     'dotted face',    'think', '隐身 dotted'),
  g('🤓', '书呆子',   'nerd',           'think', '书呆子 眼镜 nerd'),
  g('🧠', '脑子',     'brain',          'think', '脑子 智慧 brain'),

  // ── greet: 8 条 ──
  g('👋', '招手',     'wave',           'greet', '招手 你好 wave hi'),
  g('🤝', '握手',     'handshake',      'greet', '握手 合作 handshake deal'),
  g('🙏', '感谢',     'pray',           'greet', '感谢 拜托 祈祷 pray thanks'),
  g('👋🏻', '白招',   'wave light',     'greet', '招手 你好 light wave hi'),
  g('✌️', '胜利',     'victory',        'greet', '胜利 v字 peace victory'),
  g('🤞', '好运',     'fingers crossed','greet', '好运 祈祷 fingers-crossed'),
  g('👍', '赞',       'thumbs up',      'greet', '赞 好 thumbs-up'),
  g('👎', '踩',       'thumbs down',    'greet', '踩 不行 thumbs-down'),

  // ── food: 18 条 ──
  g('🍔', '汉堡',     'burger',         'food',  '汉堡 burger'),
  g('🍟', '薯条',     'fries',          'food',  '薯条 fries'),
  g('🍕', '披萨',     'pizza',          'food',  '披萨 pizza'),
  g('🌭', '热狗',     'hot dog',        'food',  '热狗 hot-dog'),
  g('🥪', '三明治',   'sandwich',       'food',  '三明治 sandwich'),
  g('🌮', '塔可',     'taco',           'food',  '塔可 taco'),
  g('🌯', '卷饼',     'burrito',        'food',  '卷饼 burrito'),
  g('🥗', '沙拉',     'salad',          'food',  '沙拉 salad'),
  g('🍿', '爆米花',   'popcorn',        'food',  '爆米花 popcorn'),
  g('🥟', '饺子',     'dumpling',       'food',  '饺子 包子 dumpling'),
  g('🍜', '拉面',     'ramen',          'food',  '拉面 拉面 ramen noodles'),
  g('🍣', '寿司',     'sushi',          'food',  '寿司 sushi'),
  g('🍱', '便当',     'bento',          'food',  '便当 bento'),
  g('🍰', '蛋糕',     'cake',           'food',  '蛋糕 cake'),
  g('🧁', '杯子蛋糕', 'cupcake',        'food',  '杯子蛋糕 cupcake'),
  g('🍩', '甜甜圈',   'donut',          'food',  '甜甜圈 donut'),
  g('🍪', '饼干',     'cookie',         'food',  '饼干 cookie'),
  g('🍫', '巧克力',   'chocolate',      'food',  '巧克力 chocolate'),
  g('☕', '咖啡',     'coffee',         'food',  '咖啡 茶 coffee tea'),
  g('🍵', '茶',       'tea',            'food',  '茶 tea'),

  // ── animal: 16 条 ──
  g('🐱', '猫',       'cat',            'animal','猫 cat'),
  g('🐶', '狗',       'dog',            'animal','狗 dog'),
  g('🐭', '老鼠',     'mouse',          'animal','老鼠 mouse'),
  g('🐹', '仓鼠',     'hamster',        'animal','仓鼠 hamster'),
  g('🐰', '兔子',     'rabbit',         'animal','兔子 rabbit bunny'),
  g('🦊', '狐狸',     'fox',            'animal','狐狸 fox'),
  g('🐻', '熊',       'bear',           'animal','熊 bear'),
  g('🐼', '熊猫',     'panda',          'animal','熊猫 panda'),
  g('🐨', '考拉',     'koala',          'animal','考拉 koala'),
  g('🐯', '虎',       'tiger',          'animal','老虎 tiger'),
  g('🦁', '狮子',     'lion',           'animal','狮子 lion'),
  g('🐮', '牛',       'cow',            'animal','牛 cow'),
  g('🐷', '猪',       'pig',            'animal','猪 pig'),
  g('🐸', '青蛙',     'frog',           'animal','青蛙 frog'),
  g('🐵', '猴',       'monkey',         'animal','猴 monkey'),
  g('🐔', '鸡',       'chicken',        'animal','鸡 chicken'),
  g('🦄', '独角兽',   'unicorn',        'animal','独角兽 unicorn'),
  g('🐝', '蜜蜂',     'bee',            'animal','蜜蜂 bee'),

  // ── object: 16 条 ──
  g('💡', '灯泡',     'bulb',           'object','灯泡 灵感 idea bulb'),
  g('🔑', '钥匙',     'key',            'object','钥匙 key'),
  g('🎁', '礼物',     'gift',           'object','礼物 惊喜 gift'),
  g('💎', '钻石',     'gem',            'object','钻石 宝石 gem'),
  g('🔔', '铃铛',     'bell',           'object','铃铛 bell'),
  g('📱', '手机',     'phone',          'object','手机 phone mobile'),
  g('💻', '电脑',     'laptop',         'object','电脑 笔记本 laptop'),
  g('⌚', '手表',     'watch',          'object','手表 watch'),
  g('📷', '相机',     'camera',         'object','相机 camera'),
  g('🎵', '音符',     'music',          'object','音符 音乐 music note'),
  g('🎬', '场记板',   'clapper',        'object','场记板 电影 clapper movie'),
  g('📚', '书',       'books',          'object','书 书本 books'),
  g('✏️', '铅笔',     'pencil',         'object','铅笔 写 pencil'),
  g('📝', '便签',     'memo',           'object','便签 写 memo note'),
  g('🗓️', '日历',     'calendar',       'object','日历 约会 calendar'),
  g('🌡️', '温度计',   'thermometer',    'object','温度 温度计 thermometer'),
  g('⏰', '闹钟',     'alarm',          'object','闹钟 提醒 alarm clock'),
  g('🚀', '火箭',     'rocket',         'object','火箭 起飞 rocket launch'),

  // ── symbol: 14 条 ──
  g('✨', '闪光',     'sparkles',       'symbol','闪光 漂亮 sparkles'),
  g('⭐', '星星',     'star',           'symbol','星星 star'),
  g('🌟', '亮星',     'glowing star',   'symbol','亮星 glowing-star'),
  g('💫', '眩晕',     'dizzy',          'symbol','眩晕 dizzy'),
  g('🔥', '火',       'fire',           'symbol','火 火爆 fire hot'),
  g('💥', 'boom',     'boom',           'symbol','爆炸 boom collision'),
  g('💢', '愤怒符号', 'anger symbol',   'symbol','怒 anger'),
  g('💯', '100分',    'hundred points', 'symbol','100 完美 hundred'),
  g('💤', 'zzz',      'zzz',            'symbol','困 睡 zzz sleep'),
  g('💨', '飞',       'dash',           'symbol','快 飞 dash'),
  g('🎉', '彩带',     'party popper',   'symbol','庆祝 彩带 party-popper'),
  g('🎊', '彩球',     'confetti',       'symbol','庆祝 彩球 confetti'),
  g('🎁', '礼物',     'wrapped gift',   'symbol','礼物 gift'),
  g('🌈', '彩虹',     'rainbow',        'symbol','彩虹 rainbow'),
  g('☀️', '太阳',     'sun',            'symbol','太阳 晴 sun'),
  g('🌙', '月亮',     'moon',           'symbol','月亮 月 night moon'),
  g('❄️', '雪花',     'snowflake',      'symbol','雪花 冷 snow snowflake'),
  g('🌸', '樱花',     'cherry blossom', 'symbol','樱花 春 cherry-blossom'),

  // ── meme (中式网络热词 / 表情包字符替代): 30 条 ──
  g('yyds', '永远的神', 'yyds',         'meme',  'yyds 永远的神'),
  g('绝绝子', '绝绝子', 'juejuezi',     'meme',  '绝绝子 绝了'),
  g('emo了', 'emo了',   'emo',          'meme',  'emo 抑郁'),
  g('锦鲤', '锦鲤',     'koi',          'meme',  '锦鲤 好运 koi'),
  g('打call', '打call', 'call',        'meme',  '打 call 应援'),
  g('真香', '真香',     'true fragrant','meme',  '真香 真香警告'),
  g('我太难了', '我太难了', 'too hard',  'meme',  '我太难了 难'),
  g('裂开', '裂开',     'crack',        'meme',  '裂开 崩溃'),
  g('好家伙', '好家伙', 'good guy',     'meme',  '好家伙 厉害'),
  g('芭比Q了', '芭比Q了', 'barbecue',   'meme',  '芭比Q 完了'),
  g('退退退', '退退退', 'retreat',      'meme',  '退退退'),
  g('栓Q', '栓Q',       'shuan q',      'meme',  '栓Q 谢谢'),
  g('元宇宙', '元宇宙', 'metaverse',    'meme',  '元宇宙'),
  g('显眼包', '显眼包', 'show off',     'meme',  '显眼包 招摇'),
  g('搭子', '搭子',     'partner',      'meme',  '搭子 饭搭子'),
  g('班味', '班味',     'work smell',   'meme',  '班味 上班气质'),
  g('哈基米', '哈基米', 'hajimi',       'meme',  '哈基米 猫咪'),
  g('嘴替', '嘴替',     'mouth sub',    'meme',  '嘴替 代言人'),
  g('松弛感', '松弛感', 'chill',        'meme',  '松弛感 放松'),
  g('班味儿', '班味',   'job smell',    'meme',  '班味儿'),
  g('显眼', '显眼',     'eye-catching', 'meme',  '显眼 招摇'),
  g('多巴胺', '多巴胺', 'dopamine',     'meme',  '多巴胺 配色'),
  g('搭子文化', '搭子', 'buddy',        'meme',  '搭子 文化'),
  g('I人', 'I人',       'introvert',    'meme',  'I人 内向'),
  g('E人', 'E人',       'extrovert',    'meme',  'E人 外向'),
  g('i了i了', 'i了i了', 'i-ed',         'meme',  'i了i了 害怕'),
  g('会谢的', '会谢的', 'will thank',   'meme',  '会谢 感激'),
  g('古早味', '古早味', 'old school',   'meme',  '古早味 复古'),
  g('芭比Q', '芭比Q',   'barbecue q',   'meme',  '芭比Q 完了'),
  g('666', '666',       'six six six',  'meme',  '666 厉害'),

  // ── kaomoji: 30 条 ASCII 颜文字 ──
  g('(╯°□°)╯︵ ┻━┻', '掀桌',     'flip table', 'kaomoji', '掀桌 生气 翻桌 flip table'),
  g('¯\\_(ツ)_/¯',     '摊手',     'shrug',      'kaomoji', '摊手 不知道 shrug'),
  g('ʕ•ᴥ•ʔ',           '熊抱',     'bear hug',   'kaomoji', '熊 抱抱 bear'),
  g('(>_<)',           '痛',       'ouch',       'kaomoji', '痛 疼 ouch'),
  g('(>_<)/',          '痛飞',     'ouch fly',   'kaomoji', '痛飞 痛 ouch'),
  g('(¬_¬)',           '斜眼',     'side eye',   'kaomoji', '斜眼 怀疑 side-eye'),
  g('(｡◕‿◕｡)',         '开心',     'happy face', 'kaomoji', '开心 笑 happy'),
  g('(￣▽￣)',         '得意',     'smug',       'kaomoji', '得意 笑 smug'),
  g('(´･ω･`)',         '害羞',     'shy',        'kaomoji', '害羞 shy'),
  g('(>///<)',         '超害羞',   'very shy',   'kaomoji', '害羞 脸红 shy'),
  g('(｀д´)',          '生气',     'angry',      'kaomoji', '生气 怒 angry'),
  g('(╬ Ò﹏Ó)',        '暴怒',     'rage',       'kaomoji', '暴怒 rage'),
  g('(─‿─)',           '得意笑',   'smug smile', 'kaomoji', '得意 笑 smug'),
  g('(ㆁωㆁ)',          '猫脸',     'cat face',   'kaomoji', '猫 笑 cat'),
  g('(=^・^=)',         '猫萌',     'cute cat',   'kaomoji', '猫 萌 cat'),
  g('(｡♥‿♥｡)',         '恋爱',     'love face',  'kaomoji', '恋爱 喜欢 love'),
  g('(✿◠‿◠)',          '花',       'flower',     'kaomoji', '花 笑 flower'),
  g('(－_－) zzZ',      '睡了',     'sleepy',     'kaomoji', '睡 困 sleepy'),
  g('(◎_◎;)',          '惊讶',     'surprised',  'kaomoji', '惊讶 吓 surprised'),
  g('(¬_¬")',           '怀疑',     'suspicious', 'kaomoji', '怀疑 斜眼 suspicious'),
  g('(⊙_☉)',           '震惊',     'shocked',    'kaomoji', '震惊 shock'),
  g('(˘•ω•˘)',         '思考',     'pondering',  'kaomoji', '思考 ponder'),
  g('(˘•ε•˘)',         '嘟嘴',     'pout',       'kaomoji', '嘟嘴 pout'),
  g('( ˘ᵕ˘ )',         '满足',     'satisfied',  'kaomoji', '满足 满足 satisfied'),
  g('( ˘ ³˘)♡',        '亲亲',     'kiss',       'kaomoji', '亲亲 kiss'),
  g('(¬‿¬)',           '奸笑',     'mischief',   'kaomoji', '奸笑 得意 mischief'),
  g('(・∀・)',          '笑',       'smile',      'kaomoji', '笑 开心 smile'),
  g('(>0<)',           '激动',     'excited',    'kaomoji', '激动 兴奋 excited'),
  g('(T_T)',           '哭',       'crying',     'kaomoji', '哭 流泪 crying'),
  g('(っ˘̩╭╮˘̩)っ',     '抱抱哭',   'cry hug',    'kaomoji', '抱抱哭 抱抱 hug cry'),
  g('┏(＾0＾)┛',       '跳舞',     'dance',      'kaomoji', '跳舞 开心 dance'),
  g('(─ω─)',           '淡定',     'chill',      'kaomoji', '淡定 chill'),
]

// ─── 查询助手 ──────────────────────────────────────────────────────

/**
 * 按 category 过滤
 */
export function kaomojiByCategory(c: KaomojiCategory): KaomojiEntry[] {
  return KAOMOJI_CATALOG.filter(e => e.category === c)
}

/**
 * 模糊搜索（中英 / 关键字都支持）
 */
export function searchKaomoji(query: string, limit = 30): KaomojiEntry[] {
  if (!query || !query.trim()) return []
  const q = query.toLowerCase().trim()
  const hits: { entry: KaomojiEntry; score: number }[] = []
  for (const e of KAOMOJI_CATALOG) {
    let score = 0
    if (e.glyph.toLowerCase().includes(q)) score += 5
    if (e.name.toLowerCase().includes(q)) score += 3
    if (e.nameEn.toLowerCase().includes(q)) score += 3
    if (e.keywords.toLowerCase().includes(q)) score += 1
    if (score > 0) hits.push({ entry: e, score })
  }
  hits.sort((a, b) => b.score - a.score)
  return hits.slice(0, limit).map(h => h.entry)
}

/**
 * 兼容老调用方 — 仍接受 16 个硬编码 emoji 时使用的扁平数组
 */
export const EMOJI_LIST_FLAT: string[] = KAOMOJI_CATALOG.map(e => e.glyph)
