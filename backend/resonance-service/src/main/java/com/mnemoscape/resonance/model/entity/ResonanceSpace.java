package com.mnemoscape.resonance.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "resonance_spaces", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"memory_id_1", "memory_id_2"})
})
@Getter
@Setter
@NoArgsConstructor
public class ResonanceSpace {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "memory_id_1", nullable = false, length = 36)
    private String memoryId1;

    @Column(name = "memory_id_2", nullable = false, length = 36)
    private String memoryId2;

    @Column(name = "similarity_score", nullable = false)
    private Double similarityScore;

    @Column(name = "emotion_similarity")
    private Double emotionSimilarity;

    @Column(name = "scene_similarity")
    private Double sceneSimilarity;

    @Column(name = "scene_data_url", length = 500)
    private String sceneDataUrl;

    @Column(length = 20)
    private String status = "pending";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public ResonanceSpace(String id, String memoryId1, String memoryId2, Double similarityScore, Double emotionSimilarity, Double sceneSimilarity, String sceneDataUrl, String status, LocalDateTime createdAt) {
        this.id = id;
        this.memoryId1 = memoryId1;
        this.memoryId2 = memoryId2;
        this.similarityScore = similarityScore;
        this.emotionSimilarity = emotionSimilarity;
        this.sceneSimilarity = sceneSimilarity;
        this.sceneDataUrl = sceneDataUrl;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static ResonanceSpaceBuilder builder() {
        return new ResonanceSpaceBuilder();
    }

    public static class ResonanceSpaceBuilder {
        private String id;
        private String memoryId1;
        private String memoryId2;
        private Double similarityScore;
        private Double emotionSimilarity;
        private Double sceneSimilarity;
        private String sceneDataUrl;
        private String status = "pending";
        private LocalDateTime createdAt;

        ResonanceSpaceBuilder() {}

        public ResonanceSpaceBuilder id(String id) { this.id = id; return this; }
        public ResonanceSpaceBuilder memoryId1(String memoryId1) { this.memoryId1 = memoryId1; return this; }
        public ResonanceSpaceBuilder memoryId2(String memoryId2) { this.memoryId2 = memoryId2; return this; }
        public ResonanceSpaceBuilder similarityScore(Double similarityScore) { this.similarityScore = similarityScore; return this; }
        public ResonanceSpaceBuilder emotionSimilarity(Double emotionSimilarity) { this.emotionSimilarity = emotionSimilarity; return this; }
        public ResonanceSpaceBuilder sceneSimilarity(Double sceneSimilarity) { this.sceneSimilarity = sceneSimilarity; return this; }
        public ResonanceSpaceBuilder sceneDataUrl(String sceneDataUrl) { this.sceneDataUrl = sceneDataUrl; return this; }
        public ResonanceSpaceBuilder status(String status) { this.status = status; return this; }
        public ResonanceSpaceBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public ResonanceSpace build() {
            return new ResonanceSpace(id, memoryId1, memoryId2, similarityScore, emotionSimilarity, sceneSimilarity, sceneDataUrl, status, createdAt);
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
    }
}
