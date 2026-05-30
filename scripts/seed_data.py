#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mnemoscape 数据注入脚本
向远程 Docker 注入 50 个用户 + 每人 15 条真实记忆（MySQL + Milvus）

策略：
  1. 通过 auth-service API 注册用户（获取真实 JWT + userId）
  2. 通过 memory-service API 创建记忆（走完整业务逻辑，含版本快照）
  3. 直接调 Milvus REST API 写入向量（用语义化随机向量，因无 NVIDIA key 时降级）

用法：
  python scripts/seed_data.py
  python scripts/seed_data.py --gateway http://100.66.166.46:8080
  python scripts/seed_data.py --dry-run   # 只打印不执行
"""

import argparse
import json
import math
import random
import sys
import time
import uuid
from typing import Optional

import requests

# 绕过系统代理（Tailscale 内网直连，不走 127.0.0.1:7890 等本地代理）
NO_PROXY = {"http": None, "https": None}

# ─────────────────────────────────────────────
# 配置
# ─────────────────────────────────────────────
DEFAULT_GATEWAY = "http://100.66.166.46:8080"
DEFAULT_MILVUS  = "http://100.66.166.46:19530"
MILVUS_COLLECTION = "mnemoscape_memories"
EMBEDDING_DIM     = 1024
NUM_USERS         = 50
MEMORIES_PER_USER = 15
REQUEST_TIMEOUT   = 15   # 秒
SLEEP_BETWEEN_USERS = 0.3  # 秒，避免打爆服务

# ─────────────────────────────────────────────
# 真实记忆数据池（中文，覆盖多种情绪/场景/地点）
# ─────────────────────────────────────────────
MEMORY_POOL = [
    {
        "title": "外婆家的灶台香",
        "description": "小时候每逢过年，外婆总会在天还没亮时就起来生火。灶台上的铁锅咕嘟咕嘟冒着热气，腊肉的香味混着柴火烟飘满整个院子。我趴在门槛上，看她用粗糙的手翻炒，锅铲碰铁锅的声音清脆而有节奏。那种温暖不只是来自灶火，更来自她偶尔回头冲我笑的那一眼。",
        "memoryYear": 2005, "memoryDate": "2005-01-28", "memorySeason": "winter",
        "memoryTimeOfDay": "morning", "memoryLocation": "湖南湘西农村",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "第一次骑自行车摔倒",
        "description": "那是一个夏天的傍晚，爸爸在小区空地上扶着我的自行车后座慢慢跑。他说放手了，其实早就放了，我骑了好远才发现。回头一看他站在原地笑，我一慌就摔进了草丛里。膝盖破了皮，但我没哭，因为我真的骑起来了。那块疤现在还在，每次看到都会想起那个傍晚橘色的光。",
        "memoryYear": 2008, "memoryDate": "2008-07-15", "memorySeason": "summer",
        "memoryTimeOfDay": "evening", "memoryLocation": "北京朝阳区某小区",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "高考前夜的月亮",
        "description": "高考前一晚，我一个人坐在宿舍楼顶，月亮很圆很亮，把整个操场都照得发白。我没有复习，只是坐着，听远处偶尔传来的蛙叫。那一刻奇怪地平静，好像所有的焦虑都被月光稀释了。后来成绩出来，不算好也不算差，但那个夜晚的月亮我记了很久。",
        "memoryYear": 2015, "memoryDate": "2015-06-06", "memorySeason": "summer",
        "memoryTimeOfDay": "night", "memoryLocation": "河南某县城高中",
        "privacyLevel": "PRIVATE"
    },
    {
        "title": "大学图书馆的下午",
        "description": "大三那年冬天，我在图书馆七楼靠窗的位置坐了整整一个学期。窗外是银杏树，叶子从绿变黄再落光。我在那里读完了加缪的《局外人》，第一次觉得孤独也可以是一种清醒。有个女生总坐我对面，我们从没说过话，但每次她来我都会不自觉地坐直。",
        "memoryYear": 2018, "memoryDate": "2018-11-20", "memorySeason": "autumn",
        "memoryTimeOfDay": "afternoon", "memoryLocation": "武汉大学图书馆",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "第一次独自旅行",
        "description": "毕业那年夏天，我一个人买了去丽江的火车票，硬座，二十多个小时。车厢里有卖盒饭的大叔，有抱着孩子的年轻妈妈，有一路打牌的工人。到丽江时天刚亮，古城的石板路还湿着，空气里有花香和木头的气息。我在一家小客栈住了五天，每天什么都不做，只是走路和发呆。",
        "memoryYear": 2019, "memoryDate": "2019-07-03", "memorySeason": "summer",
        "memoryTimeOfDay": "morning", "memoryLocation": "云南丽江古城",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "爷爷教我下象棋",
        "description": "爷爷的棋盘是木头的，棋子边缘都磨圆了。他教我的第一句话是'马走日，象走田'，然后就开始让我输。他从不故意放水，说输了才能长记性。有一次我终于赢了一局，他愣了一下，然后哈哈大笑，说'这小子行了'。那是我见过他笑得最开心的一次。他走后，那副棋我一直放在书桌上。",
        "memoryYear": 2010, "memoryDate": "2010-03-12", "memorySeason": "spring",
        "memoryTimeOfDay": "afternoon", "memoryLocation": "山东济南老家",
        "privacyLevel": "PRIVATE"
    },
    {
        "title": "暴雨中的演唱会",
        "description": "那场演唱会开始没多久就下起了大雨，主办方没有停，歌手也没有停。我们所有人都淋着雨，手机举着，跟着唱。雨水顺着脸流下来，分不清是雨还是眼泪。散场时地铁站挤满了湿透的人，大家互相看着，莫名其妙地笑。那种集体狼狈里有一种奇特的温情。",
        "memoryYear": 2021, "memoryDate": "2021-08-14", "memorySeason": "summer",
        "memoryTimeOfDay": "evening", "memoryLocation": "上海梅赛德斯奔驰文化中心",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "妈妈的手术等待室",
        "description": "妈妈做手术那天，我在等待室坐了六个小时。椅子是硬的，荧光灯一直亮着，走廊里偶尔有推床经过的声音。我没有看手机，只是盯着手术室的门。出来的时候医生说一切顺利，我点了点头，走到楼道里才哭出来。那六个小时让我第一次真正意识到，有些人是不能失去的。",
        "memoryYear": 2022, "memoryDate": "2022-04-18", "memorySeason": "spring",
        "memoryTimeOfDay": "afternoon", "memoryLocation": "北京协和医院",
        "privacyLevel": "PRIVATE"
    },
    {
        "title": "深夜便利店的泡面",
        "description": "刚工作那年，有一段时间每天加班到凌晨。有一次走出公司，外面在下小雨，附近只有一家便利店还亮着灯。我买了一碗泡面，坐在店里的高脚凳上，看着雨打在玻璃上。收银员是个老大爷，他给我倒了热水，什么都没说。那碗泡面是我吃过最好吃的一顿饭。",
        "memoryYear": 2020, "memoryDate": "2020-11-03", "memorySeason": "autumn",
        "memoryTimeOfDay": "night", "memoryLocation": "北京中关村",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "第一次看海",
        "description": "我是内陆长大的，第一次看到海是二十岁。站在青岛的栈桥上，海风很大，把头发吹得乱七八糟。海比我想象的要大，大到让人觉得自己很小，但那种小不是压迫感，而是一种奇怪的解脱。我在海边站了很久，什么都没想，只是看着浪一波一波地涌来又退去。",
        "memoryYear": 2017, "memoryDate": "2017-05-20", "memorySeason": "spring",
        "memoryTimeOfDay": "afternoon", "memoryLocation": "山东青岛栈桥",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "和老友的最后一顿饭",
        "description": "大学毕业前，我们宿舍六个人去了学校附近那家开了十年的湘菜馆。点了一桌子菜，喝了很多啤酒，说了很多以后要怎样怎样的话。散场时已经快凌晨，我们在路口站了很久，谁都不想先走。后来各奔东西，那家湘菜馆也关了。有时候想起来，那顿饭像是一个时代的句号。",
        "memoryYear": 2019, "memoryDate": "2019-06-25", "memorySeason": "summer",
        "memoryTimeOfDay": "night", "memoryLocation": "武汉洪山区",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "雪天的早班地铁",
        "description": "那年冬天北京下了一场大雪，我赶早班地铁上班。站台上的人比平时少很多，大家都裹得严严实实。地铁进站时带来一阵风，把站台上的雪花吹起来。车厢里很暖，窗外的城市白茫茫一片。我靠着车门，看着雪中的北京，觉得这座城市难得地温柔了一次。",
        "memoryYear": 2021, "memoryDate": "2021-01-07", "memorySeason": "winter",
        "memoryTimeOfDay": "morning", "memoryLocation": "北京地铁10号线",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "外婆去世前的最后一个春节",
        "description": "那年春节我们全家回去，外婆已经走不动了，但坚持要坐在饭桌旁边。她不怎么吃东西，只是看着我们吃，偶尔说一句'多吃点'。饭后她拉着我的手，说我小时候最爱吃她做的糯米糍粑。我说我记得，她就笑了。那是我最后一次见她笑。",
        "memoryYear": 2016, "memoryDate": "2016-02-08", "memorySeason": "winter",
        "memoryTimeOfDay": "afternoon", "memoryLocation": "湖南湘西农村",
        "privacyLevel": "PRIVATE"
    },
    {
        "title": "第一次失恋的雨夜",
        "description": "分手那天晚上下着雨，我一个人走了很久，不知道走到哪里。后来发现自己站在一座天桥上，桥下是车流，雨水打在路灯上散成光晕。我没有哭，只是站着，感觉心里有什么东西碎了，但又说不清楚是什么。后来我想，那不是失去一个人，是失去了一种对未来的想象。",
        "memoryYear": 2018, "memoryDate": "2018-09-14", "memorySeason": "autumn",
        "memoryTimeOfDay": "night", "memoryLocation": "武汉武昌区",
        "privacyLevel": "PRIVATE"
    },
    {
        "title": "凌晨三点的代码",
        "description": "那个项目的截止日期是第二天早上九点，我一个人在公司熬了一夜。凌晨三点，所有人都走了，只剩下服务器的风扇声和我的键盘声。窗外的城市安静得像另一个世界。最后一个 bug 修好的时候是凌晨四点半，我靠在椅背上，看着屏幕上绿色的测试通过提示，觉得那一刻值得被记住。",
        "memoryYear": 2023, "memoryDate": "2023-03-22", "memorySeason": "spring",
        "memoryTimeOfDay": "night", "memoryLocation": "上海浦东新区",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "父亲的旧照片",
        "description": "整理老房子时，在柜子最底层找到一个铁盒子，里面是父亲年轻时的照片。他穿着喇叭裤，留着长发，站在一辆摩托车旁边笑得很灿烂。那个人和我认识的父亲完全不同，却又分明是同一个人。我拿着照片看了很久，第一次意识到，父母在成为父母之前，也曾经是一个年轻人。",
        "memoryYear": 2024, "memoryDate": "2024-02-10", "memorySeason": "winter",
        "memoryTimeOfDay": "afternoon", "memoryLocation": "四川成都老家",
        "privacyLevel": "PRIVATE"
    },
    {
        "title": "秋天的银杏大道",
        "description": "那年秋天，我骑着共享单车穿过北京的银杏大道。满地金黄的叶子，风一吹就旋转着飞起来。路上的人都在拍照，我也停下来拍了几张，但后来发现照片里没有那种感觉。有些美只能用眼睛看，用身体感受，相机装不下。那条路我后来又去过几次，但再也没有那天的感觉了。",
        "memoryYear": 2020, "memoryDate": "2020-10-28", "memorySeason": "autumn",
        "memoryTimeOfDay": "afternoon", "memoryLocation": "北京钓鱼台银杏大道",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "第一次出国",
        "description": "第一次出国是去日本，在东京的地铁上，我看着车厢里的日文广告，突然意识到自己真的在另一个国家。那种感觉很奇妙，不是兴奋，而是一种轻微的眩晕，像是世界突然变大了。在浅草寺，我买了一个御守，不是因为信，只是想要一个那个地方的证明。",
        "memoryYear": 2019, "memoryDate": "2019-03-15", "memorySeason": "spring",
        "memoryTimeOfDay": "morning", "memoryLocation": "日本东京浅草寺",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "毕业典礼上的雨",
        "description": "毕业典礼那天下了雨，学校临时把仪式移到了室内。我们穿着学士服，挤在体育馆里，汗味和香水味混在一起。校长讲话很长，我没怎么听，只是看着身边的同学，想着这些人以后可能再也不会这样聚在一起了。拨穗的时候，我突然很想哭，但忍住了。",
        "memoryYear": 2019, "memoryDate": "2019-06-20", "memorySeason": "summer",
        "memoryTimeOfDay": "morning", "memoryLocation": "武汉大学",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "奶奶的针线盒",
        "description": "奶奶有一个铁皮针线盒，里面装着各种颜色的线、大大小小的针、几颗纽扣和一个顶针。小时候我总喜欢翻那个盒子，奶奶就坐在旁边缝衣服，阳光从窗户斜进来，照在她低着的头上。那个盒子现在在我家，我从来不用，但也舍不得扔。",
        "memoryYear": 2007, "memoryDate": "2007-04-05", "memorySeason": "spring",
        "memoryTimeOfDay": "afternoon", "memoryLocation": "江苏苏州老家",
        "privacyLevel": "PRIVATE"
    },
    {
        "title": "第一次独自做饭",
        "description": "大学第一年，宿舍楼下有个小厨房，我第一次独自做饭是番茄炒蛋。番茄放多了，蛋炒老了，盐也放多了，但我还是把那盘菜吃完了。那顿饭让我意识到，原来生活是可以自己掌控的，哪怕掌控得一塌糊涂。后来我的厨艺越来越好，但那盘失败的番茄炒蛋我一直记得。",
        "memoryYear": 2015, "memoryDate": "2015-09-10", "memorySeason": "autumn",
        "memoryTimeOfDay": "evening", "memoryLocation": "武汉大学宿舍",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "台风夜的停电",
        "description": "那年夏天台风过境，整个小区停电了。我们一家人点着蜡烛坐在客厅，爸爸讲他小时候的故事，妈妈在旁边补充细节，我和弟弟听得入神。窗外风雨大作，屋里烛光摇曳，那种感觉像是时间倒流了。电来的时候，我们反而有点失落。",
        "memoryYear": 2012, "memoryDate": "2012-08-03", "memorySeason": "summer",
        "memoryTimeOfDay": "night", "memoryLocation": "广东广州",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "清晨的菜市场",
        "description": "有一次失眠，天刚亮就出门，走到了附近的菜市场。那里已经热闹起来了，卖菜的大妈在整理摊位，买菜的老人在挑挑拣拣，空气里有泥土和青草的气息。我买了一把香菜，虽然我不怎么吃香菜。那个早晨让我觉得，城市里其实有很多人在过着和我完全不同的生活。",
        "memoryYear": 2023, "memoryDate": "2023-06-08", "memorySeason": "summer",
        "memoryTimeOfDay": "morning", "memoryLocation": "北京朝阳区菜市场",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "第一次坐飞机",
        "description": "第一次坐飞机是去北京参加面试，那年我二十二岁。起飞的时候我紧紧抓着扶手，窗外的地面越来越小，然后钻进云层，什么都看不见了。等飞机平稳了，我才敢往窗外看，云在脚下，阳光很亮。那一刻我觉得，原来世界可以从这个角度看。",
        "memoryYear": 2019, "memoryDate": "2019-09-05", "memorySeason": "autumn",
        "memoryTimeOfDay": "morning", "memoryLocation": "武汉天河国际机场",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "老狗离开的那天",
        "description": "我家的狗叫豆豆，陪了我们十四年。它走的那天早上，我发现它躺在角落里，呼吸很浅。我坐在它旁边，摸着它的头，它抬起眼睛看了我一下，然后就闭上了。我没有哭，只是坐了很久。后来我们把它埋在院子里的桂花树下。每年桂花开的时候，我都会想起它。",
        "memoryYear": 2020, "memoryDate": "2020-09-18", "memorySeason": "autumn",
        "memoryTimeOfDay": "morning", "memoryLocation": "浙江杭州老家",
        "privacyLevel": "PRIVATE"
    },
    {
        "title": "第一次看到极光",
        "description": "去冰岛是一个冲动的决定，订机票的时候我甚至不确定能不能看到极光。但那天晚上，在一片黑暗的雪原上，绿色的光突然在天空中流动起来，像是有人在用光作画。我站在零下十几度的寒风里，忘记了冷，忘记了时间，只是仰着头看。那是我见过最美的东西。",
        "memoryYear": 2023, "memoryDate": "2023-02-14", "memorySeason": "winter",
        "memoryTimeOfDay": "night", "memoryLocation": "冰岛雷克雅未克郊外",
        "privacyLevel": "PUBLIC"
    },
    {
        "title": "中学时的广播体操",
        "description": "中学时每天早上都要做广播体操，操场上几百个人整齐地动作，广播里的音乐响了六年。我当时觉得无聊透顶，总想着怎么偷懒。但现在偶尔听到那段音乐，会突然想起那个操场，想起站在我旁边的同学，想起那些普通得不能再普通的早晨。",
        "memoryYear": 2011, "memoryDate": "2011-09-01", "memorySeason": "autumn",
        "memoryTimeOfDay": "morning", "memoryLocation": "河南郑州某中学",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "搬家时的旧书",
        "description": "搬家整理东西，翻出了一箱子初中时的书。课本里夹着当年的试卷，有的上面还有老师的批注。有一本语文书，扉页上有同学写的留言，字迹稚嫩，内容幼稚，但看着看着眼眶就热了。那些人现在在哪里，过得怎么样，我已经不知道了。",
        "memoryYear": 2022, "memoryDate": "2022-07-15", "memorySeason": "summer",
        "memoryTimeOfDay": "afternoon", "memoryLocation": "北京新家",
        "privacyLevel": "PRIVATE"
    },
    {
        "title": "第一次喝醉",
        "description": "大学二年级，宿舍聚会，我第一次喝醉。记得最后的画面是趴在宿舍楼道的地板上，觉得地板在旋转。室友把我扶回去，帮我倒了水，第二天我头疼欲裂，但他们都没有嘲笑我。那次之后我学会了量力而行，也更明白了什么叫做被人照顾。",
        "memoryYear": 2016, "memoryDate": "2016-12-24", "memorySeason": "winter",
        "memoryTimeOfDay": "night", "memoryLocation": "武汉大学宿舍",
        "privacyLevel": "FRIENDS"
    },
    {
        "title": "春节烟花",
        "description": "小时候过年，爸爸会买一大箱烟花。我们在院子里放，烟花升上去，在黑色的天空里炸开，红的黄的绿的，照亮了整个院子和周围人的脸。那种声音和光，现在城市里已经很少见了。每次看到烟花，我都会想起那个院子，想起那些年的除夕夜。",
        "memoryYear": 2006, "memoryDate": "2006-01-29", "memorySeason": "winter",
        "memoryTimeOfDay": "night", "memoryLocation": "山东济南老家院子",
        "privacyLevel": "PUBLIC"
    },
]

# ─────────────────────────────────────────────
# 用户名池
# ─────────────────────────────────────────────
USER_NAMES = [
    "晨曦微光", "星河漫步", "落叶归根", "云端漫游", "月影追风",
    "碧海蓝天", "山间清风", "雨后彩虹", "暮色苍茫", "春日暖阳",
    "秋水长天", "冬雪飘零", "夏荷清香", "松间明月", "竹影婆娑",
    "梅花傲雪", "桃花流水", "柳絮飞扬", "菊花残秋", "荷塘月色",
    "烟雨江南", "塞外孤烟", "大漠黄沙", "草原牧歌", "海浪涛声",
    "林间鸟鸣", "溪水潺潺", "瀑布飞流", "湖光山色", "古道西风",
    "长亭送别", "渡口斜阳", "古镇烟火", "街巷深处", "屋檐听雨",
    "灯火阑珊", "夜市喧嚣", "晨钟暮鼓", "书香墨韵", "琴声悠扬",
    "画里江山", "诗中岁月", "梦里故乡", "心中远方", "眼前风景",
    "脚下路途", "手中温度", "耳边低语", "唇边微笑", "眸中星光",
]

# ─────────────────────────────────────────────
# 工具函数
# ─────────────────────────────────────────────

def make_username(idx: int) -> str:
    """生成合法用户名（英文，3-50字符）"""
    return f"mnemo_user_{idx:03d}"

def make_email(idx: int) -> str:
    return f"mnemo.user.{idx:03d}@mnemoscape.test"

def make_password() -> str:
    """满足密码策略：大小写+数字+特殊符号，8位以上"""
    return "Mnemo@2026#Seed"

def make_nickname(idx: int) -> str:
    return USER_NAMES[idx % len(USER_NAMES)]

def random_unit_vector(dim: int) -> list:
    """生成归一化随机向量（模拟 embedding，COSINE 度量需要归一化）"""
    v = [random.gauss(0, 1) for _ in range(dim)]
    norm = math.sqrt(sum(x * x for x in v))
    if norm < 1e-9:
        v[0] = 1.0
        norm = 1.0
    return [x / norm for x in v]

def log(msg: str, level: str = "INFO"):
    prefix = {"INFO": "✓", "WARN": "⚠", "ERROR": "✗", "STEP": "→"}.get(level, "·")
    print(f"  {prefix} {msg}", flush=True)

# ─────────────────────────────────────────────
# API 客户端
# ─────────────────────────────────────────────

class MnemoscapeClient:
    def __init__(self, gateway: str, dry_run: bool = False):
        self.gateway = gateway.rstrip("/")
        self.dry_run = dry_run
        self.session = requests.Session()
        self.session.headers.update({"Content-Type": "application/json"})

    def register(self, username: str, email: str, password: str) -> Optional[dict]:
        """注册用户，返回 AuthResponse 或 None"""
        if self.dry_run:
            log(f"[DRY] register {username}", "INFO")
            return {"userId": str(uuid.uuid4()), "accessToken": "dry-token", "username": username}
        url = f"{self.gateway}/api/v1/auth/register"
        try:
            r = self.session.post(url, json={"username": username, "email": email, "password": password},
                                  timeout=REQUEST_TIMEOUT, proxies=NO_PROXY)
            if r.status_code == 201:
                data = r.json().get("data", {})
                return data
            elif r.status_code == 409:
                # 用户已存在，尝试登录
                return self.login(username, password)
            else:
                log(f"register {username} failed: {r.status_code} {r.text[:200]}", "WARN")
                return None
        except Exception as e:
            log(f"register {username} exception: {e}", "ERROR")
            return None

    def login(self, username: str, password: str) -> Optional[dict]:
        """登录，返回 AuthResponse 或 None"""
        if self.dry_run:
            return {"userId": str(uuid.uuid4()), "accessToken": "dry-token", "username": username}
        url = f"{self.gateway}/api/v1/auth/login"
        try:
            r = self.session.post(url, json={"username": username, "password": password},
                                  timeout=REQUEST_TIMEOUT, proxies=NO_PROXY)
            if r.status_code == 200:
                return r.json().get("data", {})
            else:
                log(f"login {username} failed: {r.status_code} {r.text[:200]}", "WARN")
                return None
        except Exception as e:
            log(f"login {username} exception: {e}", "ERROR")
            return None

    def create_memory(self, token: str, memory_data: dict) -> Optional[dict]:
        """创建记忆，返回 MemoryResponse 或 None"""
        if self.dry_run:
            log(f"[DRY] create memory: {memory_data['title']}", "INFO")
            return {"id": str(uuid.uuid4()), "title": memory_data["title"]}
        url = f"{self.gateway}/api/v1/memories"
        headers = {"Authorization": f"Bearer {token}"}
        try:
            r = self.session.post(url, json=memory_data, headers=headers, timeout=REQUEST_TIMEOUT,
                                  proxies=NO_PROXY)
            if r.status_code == 201:
                return r.json().get("data", {})
            else:
                log(f"create memory '{memory_data['title']}' failed: {r.status_code} {r.text[:300]}", "WARN")
                return None
        except Exception as e:
            log(f"create memory exception: {e}", "ERROR")
            return None

# ─────────────────────────────────────────────
# Milvus 直写客户端
# ─────────────────────────────────────────────

class MilvusClient:
    def __init__(self, base_url: str, collection: str, dim: int, dry_run: bool = False):
        self.base_url = base_url.rstrip("/")
        self.collection = collection
        self.dim = dim
        self.dry_run = dry_run
        self.session = requests.Session()
        self.session.headers.update({"Content-Type": "application/json", "Accept": "application/json"})
        self._ready = False

    def ensure_collection(self) -> bool:
        """确保 collection 存在，不存在则创建"""
        if self._ready:
            return True
        if self.dry_run:
            self._ready = True
            return True
        # 检查是否存在
        try:
            r = self.session.post(f"{self.base_url}/v2/vectordb/collections/has",
                                  json={"collectionName": self.collection}, timeout=10,
                                  proxies=NO_PROXY)
            if r.status_code == 200:
                resp = r.json()
                exists = resp.get("code") == 0 and resp.get("data", {}).get("has", False)
                if exists:
                    log(f"Milvus collection '{self.collection}' already exists", "INFO")
                    self._ready = True
                    return True
        except Exception as e:
            log(f"Milvus check collection failed: {e}", "WARN")

        # 创建 collection
        try:
            body = {
                "collectionName": self.collection,
                "dimension": self.dim,
                "metricType": "COSINE",
                "idType": "VarChar",
                "primaryFieldName": "id",
                "vectorFieldName": "vector",
                "autoID": False,
                "enableDynamicField": True,
                "params": {"max_length": 128}
            }
            r = self.session.post(f"{self.base_url}/v2/vectordb/collections/create",
                                  json=body, timeout=15, proxies=NO_PROXY)
            if r.status_code == 200 and r.json().get("code") == 0:
                log(f"Milvus collection '{self.collection}' created (dim={self.dim})", "INFO")
                self._ready = True
                return True
            else:
                log(f"Milvus create collection failed: {r.status_code} {r.text[:200]}", "WARN")
                return False
        except Exception as e:
            log(f"Milvus create collection exception: {e}", "ERROR")
            return False

    def upsert(self, memory_id: str, user_id: str, title: str, location: str,
               year: int, snippet: str, privacy: str) -> bool:
        """写入一条向量记录"""
        if not self.ensure_collection():
            return False
        if self.dry_run:
            return True
        vector = random_unit_vector(self.dim)
        entity = {
            "id": memory_id,
            "vector": vector,
            "user_id": user_id,
            "title": (title or "")[:480],
            "location": (location or "")[:240],
            "year": year or 0,
            "snippet": (snippet or "")[:900],
            "privacy": privacy or "PRIVATE",
        }
        try:
            r = self.session.post(
                f"{self.base_url}/v2/vectordb/entities/upsert",
                json={"collectionName": self.collection, "data": [entity]},
                timeout=10, proxies=NO_PROXY
            )
            if r.status_code == 200 and r.json().get("code") == 0:
                return True
            else:
                log(f"Milvus upsert failed for {memory_id}: {r.status_code} {r.text[:200]}", "WARN")
                return False
        except Exception as e:
            log(f"Milvus upsert exception for {memory_id}: {e}", "WARN")
            return False

# ─────────────────────────────────────────────
# 主流程
# ─────────────────────────────────────────────

def check_services(gateway: str, milvus: str) -> bool:
    """预检：确认服务可达"""
    print("\n── 预检服务连通性 ──────────────────────────────")
    ok = True
    # 检查网关
    try:
        r = requests.get(f"{gateway}/actuator/health", timeout=5, proxies=NO_PROXY)
        if r.status_code == 200:
            log(f"api-gateway {gateway} → UP", "INFO")
        else:
            log(f"api-gateway {gateway} → HTTP {r.status_code}", "WARN")
    except Exception as e:
        log(f"api-gateway {gateway} → 不可达: {e}", "ERROR")
        ok = False

    # 检查 Milvus
    try:
        r = requests.post(f"{milvus}/v2/vectordb/collections/list",
                          json={}, headers={"Content-Type": "application/json"}, timeout=5,
                          proxies=NO_PROXY)
        if r.status_code == 200:
            log(f"Milvus {milvus} → UP", "INFO")
        else:
            log(f"Milvus {milvus} → HTTP {r.status_code} (向量写入将跳过)", "WARN")
    except Exception as e:
        log(f"Milvus {milvus} → 不可达: {e} (向量写入将跳过)", "WARN")
        # Milvus 不可达不阻断主流程

    return ok


def seed(gateway: str, milvus_url: str, dry_run: bool):
    print(f"\n{'='*60}")
    print(f"  Mnemoscape 数据注入脚本")
    print(f"  Gateway : {gateway}")
    print(f"  Milvus  : {milvus_url}")
    print(f"  用户数  : {NUM_USERS}")
    print(f"  记忆/人 : {MEMORIES_PER_USER}")
    print(f"  DRY RUN : {dry_run}")
    print(f"{'='*60}\n")

    if not dry_run:
        if not check_services(gateway, milvus_url):
            print("\n[ABORT] 网关不可达，请先确认后端服务已启动。")
            sys.exit(1)

    client = MnemoscapeClient(gateway, dry_run)
    milvus = MilvusClient(milvus_url, MILVUS_COLLECTION, EMBEDDING_DIM, dry_run)

    stats = {"users_ok": 0, "users_fail": 0, "memories_ok": 0, "memories_fail": 0,
             "vectors_ok": 0, "vectors_fail": 0}

    # 打乱记忆池，确保每个用户拿到不同的记忆组合
    all_memories = MEMORY_POOL.copy()
    # 扩充到足够数量（50用户×15条 = 750条，池子30条，循环使用并加变体）
    extended_pool = []
    for i in range(math.ceil(NUM_USERS * MEMORIES_PER_USER / len(all_memories)) + 1):
        batch = all_memories.copy()
        random.shuffle(batch)
        extended_pool.extend(batch)

    memory_cursor = 0

    for user_idx in range(NUM_USERS):
        print(f"\n── 用户 {user_idx + 1:02d}/{NUM_USERS} ─────────────────────────────────")
        username = make_username(user_idx)
        email    = make_email(user_idx)
        password = make_password()
        nickname = make_nickname(user_idx)

        log(f"注册/登录: {username} ({nickname})", "STEP")
        auth = client.register(username, email, password)
        if not auth:
            log(f"跳过用户 {username}（注册/登录失败）", "ERROR")
            stats["users_fail"] += 1
            memory_cursor += MEMORIES_PER_USER
            continue

        user_id = auth.get("userId", "")
        token   = auth.get("accessToken", "")
        log(f"userId={user_id[:8]}... token={'ok' if token else 'MISSING'}", "INFO")
        stats["users_ok"] += 1

        # 为该用户创建 MEMORIES_PER_USER 条记忆
        for mem_idx in range(MEMORIES_PER_USER):
            base = extended_pool[memory_cursor % len(extended_pool)]
            memory_cursor += 1

            # 给标题加轻微变体，避免完全重复
            variant_suffix = f"（{nickname}的记忆）" if mem_idx == 0 else ""
            mem_data = {
                "title":          base["title"] + variant_suffix,
                "description":    base["description"],
                "memoryYear":     base.get("memoryYear"),
                "memoryDate":     base.get("memoryDate"),
                "memorySeason":   base.get("memorySeason"),
                "memoryTimeOfDay": base.get("memoryTimeOfDay"),
                "memoryLocation": base.get("memoryLocation"),
                "privacyLevel":   base.get("privacyLevel", "PRIVATE"),
            }

            log(f"  [{mem_idx+1:02d}/{MEMORIES_PER_USER}] 创建记忆: {mem_data['title'][:30]}...", "STEP")
            mem_resp = client.create_memory(token, mem_data)

            if mem_resp and mem_resp.get("id"):
                memory_id = mem_resp["id"]
                stats["memories_ok"] += 1
                log(f"  memoryId={memory_id[:8]}... ✓", "INFO")

                # 写入 Milvus 向量
                snippet = base["description"][:160]
                ok = milvus.upsert(
                    memory_id=memory_id,
                    user_id=user_id,
                    title=mem_data["title"],
                    location=mem_data.get("memoryLocation", ""),
                    year=mem_data.get("memoryYear", 0),
                    snippet=snippet,
                    privacy=mem_data.get("privacyLevel", "PRIVATE"),
                )
                if ok:
                    stats["vectors_ok"] += 1
                else:
                    stats["vectors_fail"] += 1
            else:
                stats["memories_fail"] += 1
                log(f"  记忆创建失败", "WARN")

            # 小间隔，避免打爆服务
            if not dry_run:
                time.sleep(0.1)

        if not dry_run:
            time.sleep(SLEEP_BETWEEN_USERS)

    # ── 汇总 ──
    print(f"\n{'='*60}")
    print(f"  注入完成！")
    print(f"  用户: {stats['users_ok']} 成功 / {stats['users_fail']} 失败")
    print(f"  记忆: {stats['memories_ok']} 成功 / {stats['memories_fail']} 失败")
    print(f"  向量: {stats['vectors_ok']} 成功 / {stats['vectors_fail']} 失败")
    print(f"{'='*60}\n")

    if stats["memories_fail"] > 0 or stats["users_fail"] > 0:
        print("⚠  有部分失败，请检查上方日志。常见原因：")
        print("   - 后端服务未完全启动（Nacos 注册延迟）")
        print("   - 密码不符合策略（当前密码: Mnemo@2026#Seed）")
        print("   - 网关路由未就绪")


# ─────────────────────────────────────────────
# 入口
# ─────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Mnemoscape 数据注入脚本")
    parser.add_argument("--gateway", default=DEFAULT_GATEWAY,
                        help=f"API 网关地址（默认: {DEFAULT_GATEWAY}）")
    parser.add_argument("--milvus", default=DEFAULT_MILVUS,
                        help=f"Milvus REST 地址（默认: {DEFAULT_MILVUS}）")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印，不实际发请求")
    parser.add_argument("--users", type=int, default=NUM_USERS,
                        help=f"注入用户数（默认: {NUM_USERS}）")
    parser.add_argument("--memories", type=int, default=MEMORIES_PER_USER,
                        help=f"每用户记忆数（默认: {MEMORIES_PER_USER}）")
    args = parser.parse_args()

    NUM_USERS = args.users
    MEMORIES_PER_USER = args.memories

    random.seed(42)  # 固定随机种子，保证可复现
    seed(args.gateway, args.milvus, args.dry_run)
