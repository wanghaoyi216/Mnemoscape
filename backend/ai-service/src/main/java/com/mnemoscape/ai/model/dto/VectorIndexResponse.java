package com.mnemoscape.ai.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorIndexResponse {
    private String memoryId;
    private boolean indexed;
    private boolean ready;
}