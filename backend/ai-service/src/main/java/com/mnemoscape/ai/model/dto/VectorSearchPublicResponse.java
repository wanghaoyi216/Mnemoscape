package com.mnemoscape.ai.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorSearchPublicResponse {
    private boolean available;
    @Builder.Default
    private List<Hit> hits = List.of();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Hit {
        private String memoryId;
        private String userId;
        private String title;
        private String location;
        private Integer year;
        private String snippet;
        private double score;
    }
}