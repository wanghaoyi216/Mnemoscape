package com.mnemoscape.memory.model.dto;

import com.mnemoscape.memory.model.entity.MemoryVersion;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MemoryVersionResponse {
    private String id;
    private String memoryId;
    private Integer versionNumber;
    private String changeType;
    private String changeDescription;
    private String snapshotData;
    private String createdAt;

    public static MemoryVersionResponse fromEntity(MemoryVersion version) {
        MemoryVersionResponse response = new MemoryVersionResponse();
        response.id = version.getId();
        response.memoryId = version.getMemoryId();
        response.versionNumber = version.getVersionNumber();
        response.changeType = version.getChangeType() != null ? version.getChangeType().name() : null;
        response.changeDescription = version.getChangeDescription();
        response.snapshotData = version.getSnapshotData();
        response.createdAt = version.getCreatedAt() != null ? version.getCreatedAt().toString() : null;
        return response;
    }
}
