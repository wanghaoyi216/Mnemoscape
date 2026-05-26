package com.mnemoscape.ai.service;

import com.mnemoscape.ai.model.dto.EntityExtractRequest;
import com.mnemoscape.ai.model.dto.EntityExtractResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则版实体提取器 —— 当前以中文场景词汇为主，配套常见英文关键词。
 *
 * <p>设计目的：在没有真正接入大模型之前，让"记忆 → Neo4j 图谱"这条管线先
 * 能跑通端到端。规则虽然粗，但比 stub 强；当后续把这里替换成 OpenAI / 本地
 * LLM 时，{@link #extract(EntityExtractRequest)} 的契约（同样返回四个列表）
 * 不需要改动。
 *
 * <p>策略：
 * <ol>
 *   <li>关键词字典直接命中（人物 / 物件 / 情绪）</li>
 *   <li>"在 X" / "去 X" / "到 X" 类正则抓地点</li>
 *   <li>结构化字段（memoryLocation）始终注入</li>
 *   <li>结果去重保序（LinkedHashSet）</li>
 * </ol>
 */
@Service
public class EntityExtractor {

    // 人物关键词 — 覆盖常见亲属、社会角色，避免漏抓主体
    private static final String[] PEOPLE_KEYWORDS = {
            "妈妈", "爸爸", "母亲", "父亲", "奶奶", "爷爷", "外婆", "外公",
            "姐姐", "妹妹", "哥哥", "弟弟", "舅舅", "姨妈", "阿姨", "叔叔",
            "朋友", "同学", "同事", "老师", "教授", "学长", "学姐", "学弟", "学妹",
            "男朋友", "女朋友", "丈夫", "妻子", "恋人", "爱人",
            "孩子", "儿子", "女儿",
            "friend", "mom", "dad", "sister", "brother", "teacher",
    };

    // 物件 / 场景元素 — 这些是承载情绪的"道具"
    private static final String[] OBJECT_KEYWORDS = {
            "咖啡", "茶", "酒", "蛋糕", "面包", "饭",
            "雨", "雪", "风", "雾", "云", "星空", "月亮", "太阳", "海", "河", "湖",
            "桥", "灯", "蜡烛", "钢琴", "吉他", "唱片", "电影",
            "信", "照片", "书", "日记", "钢笔",
            "雨伞", "围巾", "毛衣", "外套",
            "rain", "snow", "coffee", "tea", "letter", "book", "photo", "moon",
    };

    // 情绪关键词 — 直接落到 emotionTags（与 emotionVector 数值结果互补）
    private static final String[] EMOTION_KEYWORDS = {
            "开心", "快乐", "欣喜", "兴奋", "甜蜜",
            "难过", "悲伤", "失落", "心碎",
            "怀念", "思念", "想念", "怀旧",
            "害怕", "紧张", "忐忑", "焦虑",
            "平静", "安静", "宁静", "释怀",
            "孤独", "寂寞", "失望",
            "感动", "温暖", "温馨", "幸福",
            "happy", "sad", "miss", "afraid", "calm", "lonely", "warm",
    };

    // 地点正则：从"在/去/到 X"提取后一个名词词组（不超 8 个字符避免吞句子）
    private static final Pattern LOCATION_PATTERN =
            Pattern.compile("[在去到回从](.{1,8}?)(?:[，。、；！？\\s]|$)");

    public EntityExtractResponse extract(EntityExtractRequest request) {
        EntityExtractResponse out = new EntityExtractResponse();
        String text = request.getDescription() == null ? "" : request.getDescription();

        out.setPeople(toList(scanKeywords(text, PEOPLE_KEYWORDS)));
        out.setObjects(toList(scanKeywords(text, OBJECT_KEYWORDS)));
        out.setEmotionTags(toList(scanKeywords(text, EMOTION_KEYWORDS)));

        Set<String> locations = new LinkedHashSet<>();
        if (request.getMemoryLocation() != null && !request.getMemoryLocation().isBlank()) {
            locations.add(request.getMemoryLocation().trim());
        }
        Matcher m = LOCATION_PATTERN.matcher(text);
        while (m.find()) {
            String captured = m.group(1).trim();
            if (captured.isEmpty()) continue;
            // 过滤明显不是地点的词（动词残留 / 太短）
            if (captured.length() < 2) continue;
            if (looksLikeVerbPhrase(captured)) continue;
            locations.add(captured);
        }
        out.setLocations(toList(locations));
        return out;
    }

    private static Set<String> scanKeywords(String text, String[] dict) {
        Set<String> hits = new LinkedHashSet<>();
        if (text.isEmpty()) return hits;
        for (String kw : dict) {
            if (text.contains(kw)) hits.add(kw);
        }
        return hits;
    }

    private static List<String> toList(Set<String> set) {
        return new ArrayList<>(set);
    }

    /** 启发式：排除"那年" / "几天" 等明显的时间词残留 */
    private static boolean looksLikeVerbPhrase(String s) {
        String[] suspects = {"那年", "那时", "那天", "几天", "几年", "回家", "回去", "之前", "之后"};
        for (String x : suspects) {
            if (s.contains(x)) return true;
        }
        return false;
    }
}
