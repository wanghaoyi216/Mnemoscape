package com.mnemoscape.resonance.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "memory_notes")
public class MemoryNote {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "author_id", nullable = false, length = 36)
    private String authorId;

    @Column(name = "resonance_id", nullable = false, length = 36)
    private String resonanceId;

    @Column(name = "position_3d", columnDefinition = "JSON")
    private String position3d;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 50)
    private String mood;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public MemoryNote() {}

    public MemoryNote(String id, String authorId, String resonanceId, String position3d, String content, String mood, LocalDateTime createdAt) {
        this.id = id;
        this.authorId = authorId;
        this.resonanceId = resonanceId;
        this.position3d = position3d;
        this.content = content;
        this.mood = mood;
        this.createdAt = createdAt;
    }

    public static MemoryNoteBuilder builder() {
        return new MemoryNoteBuilder();
    }

    public static class MemoryNoteBuilder {
        private String id;
        private String authorId;
        private String resonanceId;
        private String position3d;
        private String content;
        private String mood;
        private LocalDateTime createdAt;

        MemoryNoteBuilder() {}

        public MemoryNoteBuilder id(String id) { this.id = id; return this; }
        public MemoryNoteBuilder authorId(String authorId) { this.authorId = authorId; return this; }
        public MemoryNoteBuilder resonanceId(String resonanceId) { this.resonanceId = resonanceId; return this; }
        public MemoryNoteBuilder position3d(String position3d) { this.position3d = position3d; return this; }
        public MemoryNoteBuilder content(String content) { this.content = content; return this; }
        public MemoryNoteBuilder mood(String mood) { this.mood = mood; return this; }
        public MemoryNoteBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public MemoryNote build() {
            return new MemoryNote(id, authorId, resonanceId, position3d, content, mood, createdAt);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }
    public String getResonanceId() { return resonanceId; }
    public void setResonanceId(String resonanceId) { this.resonanceId = resonanceId; }
    public String getPosition3d() { return position3d; }
    public void setPosition3d(String position3d) { this.position3d = position3d; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMood() { return mood; }
    public void setMood(String mood) { this.mood = mood; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
    }
}
