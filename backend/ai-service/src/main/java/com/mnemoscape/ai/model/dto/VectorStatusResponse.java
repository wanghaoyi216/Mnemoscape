package com.mnemoscape.ai.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorStatusResponse {

    private boolean enabled;
    private boolean available;
    private String model;
    private int dim;
    private int observedDim;
    private MilvusStatus milvus;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MilvusStatus {
        private String collection;
        private boolean ready;
        private String indexType;
        private String metricType;
        private long entityCount;
        private long userCount;
        private boolean entityCountTruncated;
        private String entityCountAsOf;
        private String lastUpsertOkAt;
        private String lastUpsertFailAt;
        private String lastError;
    }
}