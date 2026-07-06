package com.mnemoscape.ai.model.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EntityExtractResponse {

    @Builder.Default
    private List<String> people = new ArrayList<>();
    @Builder.Default
    private List<String> locations = new ArrayList<>();
    @Builder.Default
    private List<String> objects = new ArrayList<>();
    @Builder.Default
    private List<String> emotionTags = new ArrayList<>();
}
