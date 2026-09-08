package com.mnemoscape.memory.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "memory_fragments")
@Getter
@Setter
@NoArgsConstructor
public class MemoryFragment {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "memory_id", nullable = false, length = 36)
    private String memoryId;

    @Column(name = "fragment_type", nullable = false, length = 20)
    private String fragmentType;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "position_3d", columnDefinition = "JSON")
    private String position3d;

    @Column(name = "trigger_condition", columnDefinition = "JSON")
    private String triggerCondition;

    @Column(name = "is_discovered")
    private Boolean isDiscovered = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public MemoryFragment(String id, String memoryId, String fragmentType, String content, String position3d, String triggerCondition, Boolean isDiscovered, LocalDateTime createdAt) {
        this.id = id;
        this.memoryId = memoryId;
        this.fragmentType = fragmentType;
        this.content = content;
        this.position3d = position3d;
        this.triggerCondition = triggerCondition;
        this.isDiscovered = isDiscovered;
        this.createdAt = createdAt;
    }

    public static MemoryFragmentBuilder builder() {
        return new MemoryFragmentBuilder();
    }

    public static class MemoryFragmentBuilder {
        private String id;
        private String memoryId;
        private String fragmentType;
        private String content;
        private String position3d;
        private String triggerCondition;
        private Boolean isDiscovered = false;
        private LocalDateTime createdAt;

        MemoryFragmentBuilder() {}

        public MemoryFragmentBuilder id(String id) { this.id = id; return this; }
        public MemoryFragmentBuilder memoryId(String memoryId) { this.memoryId = memoryId; return this; }
        public MemoryFragmentBuilder fragmentType(String fragmentType) { this.fragmentType = fragmentType; return this; }
        public MemoryFragmentBuilder content(String content) { this.content = content; return this; }
        public MemoryFragmentBuilder position3d(String position3d) { this.position3d = position3d; return this; }
        public MemoryFragmentBuilder triggerCondition(String triggerCondition) { this.triggerCondition = triggerCondition; return this; }
        public MemoryFragmentBuilder isDiscovered(Boolean isDiscovered) { this.isDiscovered = isDiscovered; return this; }
        public MemoryFragmentBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public MemoryFragment build() {
            return new MemoryFragment(id, memoryId, fragmentType, content, position3d, triggerCondition, isDiscovered, createdAt);
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
    }
}
