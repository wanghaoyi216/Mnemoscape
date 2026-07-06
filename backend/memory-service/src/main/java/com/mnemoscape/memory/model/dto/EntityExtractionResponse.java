package com.mnemoscape.memory.model.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 与 ai-service 的 EntityExtractResponse 形状对应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EntityExtractionResponse {
    private List<String> people;
    private List<String> locations;
    private List<String> objects;
    private List<String> emotionTags;
}
