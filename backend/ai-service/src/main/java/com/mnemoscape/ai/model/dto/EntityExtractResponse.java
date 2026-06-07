package com.mnemoscape.ai.model.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体提取结果。
 *
 * <p>当前是规则版（关键词 + 正则），后续接入真正的 NLP/LLM 后可保持相同形状：
 * <ul>
 *   <li>{@link #people} — 出现的人物角色（"妈妈"、"朋友"、"老师"…）</li>
 *   <li>{@link #locations} — 地点提及（含结构化 memoryLocation）</li>
 *   <li>{@link #objects} — 核心物件（"咖啡"、"伞"、"雨"…）</li>
 *   <li>{@link #emotionTags} — 情绪关键词（"思念"、"宁静"、"忐忑"…）</li>
 * </ul>
 */
public class EntityExtractResponse {

    private List<String> people = new ArrayList<>();
    private List<String> locations = new ArrayList<>();
    private List<String> objects = new ArrayList<>();
    private List<String> emotionTags = new ArrayList<>();

    public List<String> getPeople() { return people; }
    public void setPeople(List<String> people) { this.people = people; }

    public List<String> getLocations() { return locations; }
    public void setLocations(List<String> locations) { this.locations = locations; }

    public List<String> getObjects() { return objects; }
    public void setObjects(List<String> objects) { this.objects = objects; }

    public List<String> getEmotionTags() { return emotionTags; }
    public void setEmotionTags(List<String> emotionTags) { this.emotionTags = emotionTags; }
}
