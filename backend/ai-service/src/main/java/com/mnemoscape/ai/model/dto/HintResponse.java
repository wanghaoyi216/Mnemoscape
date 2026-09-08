package com.mnemoscape.ai.model.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IntentHintController.hints（POST /hints）回包强类型。
 * 取代原 Map<String,Object> 兜数的 { "hints": List<String> }。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HintResponse {
    private List<String> hints;
}
