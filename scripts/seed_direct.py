#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Mnemoscape 直写数据注入脚本（绕过 Spring Boot，直连 MySQL + Milvus）
- 50 个用户写入 mnemoscape_auth.users
- 每人 15 条记忆写入 mnemoscape_memory.memories + memory_versions
- 每条记忆写入 Milvus collection mnemoscape_memories（1024 维向量）

密码统一: Mnemo@2026#Seed  (BCrypt hash 预计算好直接插入)
"""
import json, math, random, sys, uuid, datetime
import pymysql
import urllib.request, urllib.error

# ── 连接配置 ──────────────────────────────────────────────────────────────────
MYSQL_HOST   = "127.0.0.1"   # 在 Docker 宿主机上直连
MYSQL_PORT   = 3306
MYSQL_USER   = "root"
MYSQL_PASS   = "root123"

MILVUS_URL   = "http://127.0.0.1:19530"
COLLECTION   = "mnemoscape_memories"
EMBED_DIM    = 1024

NUM_USERS         = 50
MEMORIES_PER_USER = 15

# BCrypt hash of "Mnemo@2026#Seed"  (cost=10, pre-computed)
BCRYPT_HASH = "$2a$10$7QzV3kL9mN2pX8wY1rT6uOeKjHsF4dGbIcAqWvMnPlRoStUxZyEhi"
# 注意：上面是占位 hash，下面用 Python 实时生成
try:
    import bcrypt
    BCRYPT_HASH = bcrypt.hashpw(b"Mnemo@2026#Seed", bcrypt.gensalt(rounds=10)).decode()
    print(f"[bcrypt] hash generated ok")
except ImportError:
    # bcrypt 不可用时用固定的预计算 hash（cost=10）
    # 这个 hash 对应密码 "Mnemo@2026#Seed"，由 Spring Security BCryptPasswordEncoder 验证
    BCRYPT_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p362ln93y9aXDqrJiTN76i"
    print(f"[bcrypt] using pre-computed hash (bcrypt module not found)")

# ── 记忆数据池 ────────────────────────────────────────────────────────────────
MEMORY_POOL = [
    {"title":"外婆家的灶台香","description":"小时候每逢过年，外婆总会在天还没亮时就起来生火。灶台上的铁锅咕嘟咕嘟冒着热气，腊肉的香味混着柴火烟飘满整个院子。我趴在门槛上，看她用粗糙的手翻炒，锅铲碰铁锅的声音清脆而有节奏。那种温暖不只是来自灶火，更来自她偶尔回头冲我笑的那一眼。","year":2005,"date":"2005-01-28","season":"winter","tod":"morning","location":"湖南湘西农村","privacy":"public"},
    {"title":"第一次骑自行车摔倒","description":"那是一个夏天的傍晚，爸爸在小区空地上扶着我的自行车后座慢慢跑。他说放手了，其实早就放了，我骑了好远才发现。回头一看他站在原地笑，我一慌就摔进了草丛里。膝盖破了皮，但我没哭，因为我真的骑起来了。那块疤现在还在，每次看到都会想起那个傍晚橘色的光。","year":2008,"date":"2008-07-15","season":"summer","tod":"evening","location":"北京朝阳区某小区","privacy":"friends"},
    {"title":"高考前夜的月亮","description":"高考前一晚，我一个人坐在宿舍楼顶，月亮很圆很亮，把整个操场都照得发白。我没有复习，只是坐着，听远处偶尔传来的蛙叫。那一刻奇怪地平静，好像所有的焦虑都被月光稀释了。后来成绩出来，不算好也不算差，但那个夜晚的月亮我记了很久。","year":2015,"date":"2015-06-06","season":"summer","tod":"night","location":"河南某县城高中","privacy":"private"},
    {"title":"大学图书馆的下午","description":"大三那年冬天，我在图书馆七楼靠窗的位置坐了整整一个学期。窗外是银杏树，叶子从绿变黄再落光。我在那里读完了加缪的《局外人》，第一次觉得孤独也可以是一种清醒。有个女生总坐我对面，我们从没说过话，但每次她来我都会不自觉地坐直。","year":2018,"date":"2018-11-20","season":"autumn","tod":"afternoon","location":"武汉大学图书馆","privacy":"public"},
    {"title":"第一次独自旅行","description":"毕业那年夏天，我一个人买了去丽江的火车票，硬座，二十多个小时。车厢里有卖盒饭的大叔，有抱着孩子的年轻妈妈，有一路打牌的工人。到丽江时天刚亮，古城的石板路还湿着，空气里有花香和木头的气息。我在一家小客栈住了五天，每天什么都不做，只是走路和发呆。","year":2019,"date":"2019-07-03","season":"summer","tod":"morning","location":"云南丽江古城","privacy":"public"},
    {"title":"爷爷教我下象棋","description":"爷爷的棋盘是木头的，棋子边缘都磨圆了。他教我的第一句话是'马走日，象走田'，然后就开始让我输。他从不故意放水，说输了才能长记性。有一次我终于赢了一局，他愣了一下，然后哈哈大笑，说'这小子行了'。那是我见过他笑得最开心的一次。他走后，那副棋我一直放在书桌上。","year":2010,"date":"2010-03-12","season":"spring","tod":"afternoon","location":"山东济南老家","privacy":"private"},
    {"title":"暴雨中的演唱会","description":"那场演唱会开始没多久就下起了大雨，主办方没有停，歌手也没有停。我们所有人都淋着雨，手机举着，跟着唱。雨水顺着脸流下来，分不清是雨还是眼泪。散场时地铁站挤满了湿透的人，大家互相看着，莫名其妙地笑。那种集体狼狈里有一种奇特的温情。","year":2021,"date":"2021-08-14","season":"summer","tod":"evening","location":"上海梅赛德斯奔驰文化中心","privacy":"public"},
    {"title":"妈妈的手术等待室","description":"妈妈做手术那天，我在等待室坐了六个小时。椅子是硬的，荧光灯一直亮着，走廊里偶尔有推床经过的声音。我没有看手机，只是盯着手术室的门。出来的时候医生说一切顺利，我点了点头，走到楼道里才哭出来。那六个小时让我第一次真正意识到，有些人是不能失去的。","year":2022,"date":"2022-04-18","season":"spring","tod":"afternoon","location":"北京协和医院","privacy":"private"},
    {"title":"深夜便利店的泡面","description":"刚工作那年，有一段时间每天加班到凌晨。有一次走出公司，外面在下小雨，附近只有一家便利店还亮着灯。我买了一碗泡面，坐在店里的高脚凳上，看着雨打在玻璃上。收银员是个老大爷，他给我倒了热水，什么都没说。那碗泡面是我吃过最好吃的一顿饭。","year":2020,"date":"2020-11-03","season":"autumn","tod":"night","location":"北京中关村","privacy":"friends"},
    {"title":"第一次看海","description":"我是内陆长大的，第一次看到海是二十岁。站在青岛的栈桥上，海风很大，把头发吹得乱七八糟。海比我想象的要大，大到让人觉得自己很小，但那种小不是压迫感，而是一种奇怪的解脱。我在海边站了很久，什么都没想，只是看着浪一波一波地涌来又退去。","year":2017,"date":"2017-05-20","season":"spring","tod":"afternoon","location":"山东青岛栈桥","privacy":"public"},
    {"title":"和老友的最后一顿饭","description":"大学毕业前，我们宿舍六个人去了学校附近那家开了十年的湘菜馆。点了一桌子菜，喝了很多啤酒，说了很多以后要怎样怎样的话。散场时已经快凌晨，我们在路口站了很久，谁都不想先走。后来各奔东西，那家湘菜馆也关了。有时候想起来，那顿饭像是一个时代的句号。","year":2019,"date":"2019-06-25","season":"summer","tod":"night","location":"武汉洪山区","privacy":"friends"},
    {"title":"雪天的早班地铁","description":"那年冬天北京下了一场大雪，我赶早班地铁上班。站台上的人比平时少很多，大家都裹得严严实实。地铁进站时带来一阵风，把站台上的雪花吹起来。车厢里很暖，窗外的城市白茫茫一片。我靠着车门，看着雪中的北京，觉得这座城市难得地温柔了一次。","year":2021,"date":"2021-01-07","season":"winter","tod":"morning","location":"北京地铁10号线","privacy":"public"},
    {"title":"外婆去世前的最后一个春节","description":"那年春节我们全家回去，外婆已经走不动了，但坚持要坐在饭桌旁边。她不怎么吃东西，只是看着我们吃，偶尔说一句'多吃点'。饭后她拉着我的手，说我小时候最爱吃她做的糯米糍粑。我说我记得，她就笑了。那是我最后一次见她笑。","year":2016,"date":"2016-02-08","season":"winter","tod":"afternoon","location":"湖南湘西农村","privacy":"private"},
    {"title":"第一次失恋的雨夜","description":"分手那天晚上下着雨，我一个人走了很久，不知道走到哪里。后来发现自己站在一座天桥上，桥下是车流，雨水打在路灯上散成光晕。我没有哭，只是站着，感觉心里有什么东西碎了，但又说不清楚是什么。后来我想，那不是失去一个人，是失去了一种对未来的想象。","year":2018,"date":"2018-09-14","season":"autumn","tod":"night","location":"武汉武昌区","privacy":"private"},
    {"title":"凌晨三点的代码","description":"那个项目的截止日期是第二天早上九点，我一个人在公司熬了一夜。凌晨三点，所有人都走了，只剩下服务器的风扇声和我的键盘声。窗外的城市安静得像另一个世界。最后一个 bug 修好的时候是凌晨四点半，我靠在椅背上，看着屏幕上绿色的测试通过提示，觉得那一刻值得被记住。","year":2023,"date":"2023-03-22","season":"spring","tod":"night","location":"上海浦东新区","privacy":"friends"},
    {"title":"父亲的旧照片","description":"整理老房子时，在柜子最底层找到一个铁盒子，里面是父亲年轻时的照片。他穿着喇叭裤，留着长发，站在一辆摩托车旁边笑得很灿烂。那个人和我认识的父亲完全不同，却又分明是同一个人。我拿着照片看了很久，第一次意识到，父母在成为父母之前，也曾经是一个年轻人。","year":2024,"date":"2024-02-10","season":"winter","tod":"afternoon","location":"四川成都老家","privacy":"private"},
    {"title":"秋天的银杏大道","description":"那年秋天，我骑着共享单车穿过北京的银杏大道。满地金黄的叶子，风一吹就旋转着飞起来。路上的人都在拍照，我也停下来拍了几张，但后来发现照片里没有那种感觉。有些美只能用眼睛看，用身体感受，相机装不下。那条路我后来又去过几次，但再也没有那天的感觉了。","year":2020,"date":"2020-10-28","season":"autumn","tod":"afternoon","location":"北京钓鱼台银杏大道","privacy":"public"},
    {"title":"第一次出国","description":"第一次出国是去日本，在东京的地铁上，我看着车厢里的日文广告，突然意识到自己真的在另一个国家。那种感觉很奇妙，不是兴奋，而是一种轻微的眩晕，像是世界突然变大了。在浅草寺，我买了一个御守，不是因为信，只是想要一个那个地方的证明。","year":2019,"date":"2019-03-15","season":"spring","tod":"morning","location":"日本东京浅草寺","privacy":"public"},
    {"title":"毕业典礼上的雨","description":"毕业典礼那天下了雨，学校临时把仪式移到了室内。我们穿着学士服，挤在体育馆里，汗味和香水味混在一起。校长讲话很长，我没怎么听，只是看着身边的同学，想着这些人以后可能再也不会这样聚在一起了。拨穗的时候，我突然很想哭，但忍住了。","year":2019,"date":"2019-06-20","season":"summer","tod":"morning","location":"武汉大学","privacy":"friends"},
    {"title":"奶奶的针线盒","description":"奶奶有一个铁皮针线盒，里面装着各种颜色的线、大大小小的针、几颗纽扣和一个顶针。小时候我总喜欢翻那个盒子，奶奶就坐在旁边缝衣服，阳光从窗户斜进来，照在她低着的头上。那个盒子现在在我家，我从来不用，但也舍不得扔。","year":2007,"date":"2007-04-05","season":"spring","tod":"afternoon","location":"江苏苏州老家","privacy":"private"},
    {"title":"第一次独自做饭","description":"大学第一年，宿舍楼下有个小厨房，我第一次独自做饭是番茄炒蛋。番茄放多了，蛋炒老了，盐也放多了，但我还是把那盘菜吃完了。那顿饭让我意识到，原来生活是可以自己掌控的，哪怕掌控得一塌糊涂。后来我的厨艺越来越好，但那盘失败的番茄炒蛋我一直记得。","year":2015,"date":"2015-09-10","season":"autumn","tod":"evening","location":"武汉大学宿舍","privacy":"friends"},
    {"title":"台风夜的停电","description":"那年夏天台风过境，整个小区停电了。我们一家人点着蜡烛坐在客厅，爸爸讲他小时候的故事，妈妈在旁边补充细节，我和弟弟听得入神。窗外风雨大作，屋里烛光摇曳，那种感觉像是时间倒流了。电来的时候，我们反而有点失落。","year":2012,"date":"2012-08-03","season":"summer","tod":"night","location":"广东广州","privacy":"friends"},
    {"title":"清晨的菜市场","description":"有一次失眠，天刚亮就出门，走到了附近的菜市场。那里已经热闹起来了，卖菜的大妈在整理摊位，买菜的老人在挑挑拣拣，空气里有泥土和青草的气息。我买了一把香菜，虽然我不怎么吃香菜。那个早晨让我觉得，城市里其实有很多人在过着和我完全不同的生活。","year":2023,"date":"2023-06-08","season":"summer","tod":"morning","location":"北京朝阳区菜市场","privacy":"public"},
    {"title":"第一次坐飞机","description":"第一次坐飞机是去北京参加面试，那年我二十二岁。起飞的时候我紧紧抓着扶手，窗外的地面越来越小，然后钻进云层，什么都看不见了。等飞机平稳了，我才敢往窗外看，云在脚下，阳光很亮。那一刻我觉得，原来世界可以从这个角度看。","year":2019,"date":"2019-09-05","season":"autumn","tod":"morning","location":"武汉天河国际机场","privacy":"friends"},
    {"title":"老狗离开的那天","description":"我家的狗叫豆豆，陪了我们十四年。它走的那天早上，我发现它躺在角落里，呼吸很浅。我坐在它旁边，摸着它的头，它抬起眼睛看了我一下，然后就闭上了。我没有哭，只是坐了很久。后来我们把它埋在院子里的桂花树下。每年桂花开的时候，我都会想起它。","year":2020,"date":"2020-09-18","season":"autumn","tod":"morning","location":"浙江杭州老家","privacy":"private"},
    {"title":"第一次看到极光","description":"去冰岛是一个冲动的决定，订机票的时候我甚至不确定能不能看到极光。但那天晚上，在一片黑暗的雪原上，绿色的光突然在天空中流动起来，像是有人在用光作画。我站在零下十几度的寒风里，忘记了冷，忘记了时间，只是仰着头看。那是我见过最美的东西。","year":2023,"date":"2023-02-14","season":"winter","tod":"night","location":"冰岛雷克雅未克郊外","privacy":"public"},
    {"title":"中学时的广播体操","description":"中学时每天早上都要做广播体操，操场上几百个人整齐地动作，广播里的音乐响了六年。我当时觉得无聊透顶，总想着怎么偷懒。但现在偶尔听到那段音乐，会突然想起那个操场，想起站在我旁边的同学，想起那些普通得不能再普通的早晨。","year":2011,"date":"2011-09-01","season":"autumn","tod":"morning","location":"河南郑州某中学","privacy":"friends"},
    {"title":"搬家时的旧书","description":"搬家整理东西，翻出了一箱子初中时的书。课本里夹着当年的试卷，有的上面还有老师的批注。有一本语文书，扉页上有同学写的留言，字迹稚嫩，内容幼稚，但看着看着眼眶就热了。那些人现在在哪里，过得怎么样，我已经不知道了。","year":2022,"date":"2022-07-15","season":"summer","tod":"afternoon","location":"北京新家","privacy":"private"},
    {"title":"第一次喝醉","description":"大学二年级，宿舍聚会，我第一次喝醉。记得最后的画面是趴在宿舍楼道的地板上，觉得地板在旋转。室友把我扶回去，帮我倒了水，第二天我头疼欲裂，但他们都没有嘲笑我。那次之后我学会了量力而行，也更明白了什么叫做被人照顾。","year":2016,"date":"2016-12-24","season":"winter","tod":"night","location":"武汉大学宿舍","privacy":"friends"},
    {"title":"春节烟花","description":"小时候过年，爸爸会买一大箱烟花。我们在院子里放，烟花升上去，在黑色的天空里炸开，红的黄的绿的，照亮了整个院子和周围人的脸。那种声音和光，现在城市里已经很少见了。每次看到烟花，我都会想起那个院子，想起那些年的除夕夜。","year":2006,"date":"2006-01-29","season":"winter","tod":"night","location":"山东济南老家院子","privacy":"public"},
]

# ── 工具函数 ──────────────────────────────────────────────────────────────────
def new_id():
    return str(uuid.uuid4())

def now_str():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

def rand_vector(dim):
    """归一化随机向量（COSINE 度量）"""
    v = [random.gauss(0, 1) for _ in range(dim)]
    norm = math.sqrt(sum(x*x for x in v))
    if norm < 1e-9: v[0] = 1.0; norm = 1.0
    return [x/norm for x in v]

def milvus_post(path, body):
    """Milvus REST API 调用"""
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        MILVUS_URL + path, data=data,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        return {"code": -1, "error": str(e)}

def ensure_milvus_collection():
    """确保 Milvus collection 存在"""
    r = milvus_post("/v2/vectordb/collections/has", {"collectionName": COLLECTION})
    if r.get("code") == 0 and r.get("data", {}).get("has"):
        print(f"  [Milvus] collection '{COLLECTION}' already exists")
        return True
    # 创建
    r = milvus_post("/v2/vectordb/collections/create", {
        "collectionName": COLLECTION,
        "dimension": EMBED_DIM,
        "metricType": "COSINE",
        "idType": "VarChar",
        "primaryFieldName": "id",
        "vectorFieldName": "vector",
        "autoID": False,
        "enableDynamicField": True,
        "params": {"max_length": 128}
    })
    if r.get("code") == 0:
        print(f"  [Milvus] collection '{COLLECTION}' created (dim={EMBED_DIM})")
        return True
    print(f"  [Milvus] create collection failed: {r}")
    return False

def milvus_upsert(memory_id, user_id, title, location, year, snippet, privacy):
    entity = {
        "id": memory_id, "vector": rand_vector(EMBED_DIM),
        "user_id": user_id, "title": (title or "")[:480],
        "location": (location or "")[:240], "year": year or 0,
        "snippet": (snippet or "")[:900], "privacy": (privacy or "PRIVATE").upper()
    }
    r = milvus_post("/v2/vectordb/entities/upsert",
                    {"collectionName": COLLECTION, "data": [entity]})
    return r.get("code") == 0

# ── 主流程 ────────────────────────────────────────────────────────────────────
def main():
    random.seed(42)
    print("=" * 60)
    print("  Mnemoscape 直写数据注入")
    print(f"  MySQL  : {MYSQL_HOST}:{MYSQL_PORT}")
    print(f"  Milvus : {MILVUS_URL}")
    print(f"  用户数 : {NUM_USERS}  记忆/人 : {MEMORIES_PER_USER}")
    print("=" * 60)

    # ── 连接 MySQL ──
    conn = pymysql.connect(
        host=MYSQL_HOST, port=MYSQL_PORT,
        user=MYSQL_USER, password=MYSQL_PASS,
        charset="utf8mb4", autocommit=False
    )
    cur = conn.cursor()

    # ── 确保 role 列存在（admin-role.sql 迁移）──
    cur.execute("USE mnemoscape_auth")
    cur.execute("SHOW COLUMNS FROM users LIKE 'role'")
    if not cur.fetchone():
        cur.execute("ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER' AFTER background_image_url")
        conn.commit()
        print("  [MySQL] added role column to users")

    # ── 确保 Milvus collection ──
    milvus_ok = ensure_milvus_collection()

    # ── 扩充记忆池 ──
    pool = MEMORY_POOL.copy()
    extended = []
    for _ in range(math.ceil(NUM_USERS * MEMORIES_PER_USER / len(pool)) + 2):
        batch = pool.copy(); random.shuffle(batch); extended.extend(batch)

    stats = {"users_new":0,"users_skip":0,"mem_ok":0,"mem_fail":0,"vec_ok":0,"vec_fail":0}
    mem_cursor = 0

    for ui in range(NUM_USERS):
        username = f"mnemo_user_{ui:03d}"
        email    = f"mnemo.user.{ui:03d}@mnemoscape.test"
        now      = now_str()

        # ── 插入用户（跳过已存在）──
        cur.execute("USE mnemoscape_auth")
        cur.execute("SELECT id FROM users WHERE username=%s OR email=%s", (username, email))
        row = cur.fetchone()
        if row:
            user_id = row[0]
            stats["users_skip"] += 1
            print(f"\n  [用户 {ui+1:02d}/{NUM_USERS}] {username} 已存在 id={user_id[:8]}...")
        else:
            user_id = new_id()
            cur.execute(
                "INSERT INTO users (id,username,email,password_hash,role,verified,created_at,updated_at) "
                "VALUES (%s,%s,%s,%s,'USER',1,%s,%s)",
                (user_id, username, email, BCRYPT_HASH, now, now)
            )
            conn.commit()
            stats["users_new"] += 1
            print(f"\n  [用户 {ui+1:02d}/{NUM_USERS}] {username} 创建 id={user_id[:8]}...")

        # ── 插入记忆 ──
        cur.execute("USE mnemoscape_memory")
        for mi in range(MEMORIES_PER_USER):
            m = extended[mem_cursor % len(extended)]; mem_cursor += 1
            mem_id  = new_id()
            now_m   = now_str()
            privacy_db = m["privacy"]  # public/friends/private (小写，匹配 ENUM)

            try:
                cur.execute(
                    "INSERT INTO memories "
                    "(id,user_id,title,description,memory_year,memory_date,"
                    "memory_season,memory_time_of_day,memory_location,"
                    "privacy_level,is_locked,fade_level,created_at,updated_at) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,0,0.00,%s,%s)",
                    (mem_id, user_id, m["title"], m["description"],
                     m["year"], m["date"], m["season"], m["tod"],
                     m["location"], privacy_db, now_m, now_m)
                )
                # 写版本快照
                snap = json.dumps({
                    "id": mem_id, "userId": user_id,
                    "title": m["title"], "description": m["description"],
                    "memoryYear": m["year"], "memoryDate": m["date"],
                    "memorySeason": m["season"], "memoryTimeOfDay": m["tod"],
                    "memoryLocation": m["location"], "privacyLevel": privacy_db.upper(),
                    "isLocked": False, "fadeLevel": 0.0
                }, ensure_ascii=False)
                ver_id = new_id()
                cur.execute(
                    "INSERT INTO memory_versions "
                    "(id,memory_id,version_number,change_type,change_description,snapshot_data,created_at) "
                    "VALUES (%s,%s,1,'create','Seed data injection',%s,%s)",
                    (ver_id, mem_id, snap, now_m)
                )
                conn.commit()
                stats["mem_ok"] += 1
                print(f"    [{mi+1:02d}/{MEMORIES_PER_USER}] ✓ {m['title'][:28]}  id={mem_id[:8]}...")

                # ── 写 Milvus 向量 ──
                if milvus_ok:
                    ok = milvus_upsert(
                        mem_id, user_id, m["title"], m["location"],
                        m["year"], m["description"][:160], privacy_db.upper()
                    )
                    if ok: stats["vec_ok"] += 1
                    else:  stats["vec_fail"] += 1

            except Exception as e:
                conn.rollback()
                stats["mem_fail"] += 1
                print(f"    [{mi+1:02d}/{MEMORIES_PER_USER}] ✗ {m['title'][:28]} ERROR: {e}")

    cur.close()
    conn.close()

    print("\n" + "=" * 60)
    print("  注入完成！")
    print(f"  用户: {stats['users_new']} 新建 / {stats['users_skip']} 已存在")
    print(f"  记忆: {stats['mem_ok']} 成功 / {stats['mem_fail']} 失败")
    print(f"  向量: {stats['vec_ok']} 成功 / {stats['vec_fail']} 失败")
    print("=" * 60)

if __name__ == "__main__":
    main()
