package com.mnemoscape.memory.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import com.mnemoscape.memory.model.converter.MemoryVersionChangeTypeConverter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "memory_versions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"memory_id", "version_number"})
})
@Getter
@Setter
@NoArgsConstructor
public class MemoryVersion {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "memory_id", nullable = false, length = 36)
    private String memoryId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Convert(converter = MemoryVersionChangeTypeConverter.class)
    @Column(name = "change_type", nullable = false)
    private ChangeType changeType;

    @Column(name = "change_description", length = 500)
    private String changeDescription;

    @Column(name = "snapshot_data", nullable = false, columnDefinition = "JSON")
    private String snapshotData;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum ChangeType { CREATE, MODIFY, DRIFT, LOCK, RESTORE }

    public MemoryVersion(String id, String memoryId, Integer versionNumber, ChangeType changeType, String changeDescription, String snapshotData, LocalDateTime createdAt) {
        this.id = id;
        this.memoryId = memoryId;
        this.versionNumber = versionNumber;
        this.changeType = changeType;
        this.changeDescription = changeDescription;
        this.snapshotData = snapshotData;
        this.createdAt = createdAt;
    }

    public static MemoryVersionBuilder builder() {
        return new MemoryVersionBuilder();
    }

    public static class MemoryVersionBuilder {
        private String id;
        private String memoryId;
        private Integer versionNumber;
        private ChangeType changeType;
        private String changeDescription;
        private String snapshotData;
        private LocalDateTime createdAt;

        MemoryVersionBuilder() {}

        public MemoryVersionBuilder id(String id) { this.id = id; return this; }
        public MemoryVersionBuilder memoryId(String memoryId) { this.memoryId = memoryId; return this; }
        public MemoryVersionBuilder versionNumber(Integer versionNumber) { this.versionNumber = versionNumber; return this; }
        public MemoryVersionBuilder changeType(ChangeType changeType) { this.changeType = changeType; return this; }
        public MemoryVersionBuilder changeDescription(String changeDescription) { this.changeDescription = changeDescription; return this; }
        public MemoryVersionBuilder snapshotData(String snapshotData) { this.snapshotData = snapshotData; return this; }
        public MemoryVersionBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public MemoryVersion build() {
            return new MemoryVersion(id, memoryId, versionNumber, changeType, changeDescription, snapshotData, createdAt);
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (id == null) id = UUID.randomUUID().toString();
    }
}
