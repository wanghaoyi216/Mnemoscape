package com.mnemoscape.ai.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ChatController.chatOnce（POST /chat，同步路径）回包强类型。
 *
 * <p>取代原 Map<String,Object> 兜数。字段名逐字对应原 Map 的 key；
 * 其中 vision_used / vision_model / attachment_count 沿用前端已有的 snake_case
 * 契约（原 Map 即是蛇形 key），因此这里显式 {@code @JsonProperty} 锁死，
 * 不依赖 Lombok getter→Jackson 属性派生的隐式链路。
 *
 * <p>可选字段（plan / vision_*）标 {@code @JsonInclude(NON_NULL)}：未设值时不序列化，
 * 与原 Map "没 put 就不存在" 的行为逐字等价。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiChatResponse {

    private String intent;
    private String traceId;
    private String answer;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> plan;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("vision_used")
    private Boolean vision_used;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("vision_model")
    private String vision_model;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("attachment_count")
    private Integer attachment_count;
}