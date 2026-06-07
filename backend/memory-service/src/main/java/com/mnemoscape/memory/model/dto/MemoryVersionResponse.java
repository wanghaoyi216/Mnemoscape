package com.mnemoscape.memory.model.dto;

import com.mnemoscape.memory.model.entity.MemoryVersion;

public class MemoryVersionResponse {
    private String id;
    private String memoryId;
    private Integer versionNumber;
    private String changeType;
    private String changeDescription;
    private String snapshotData;
    private String createdAt;

    public MemoryVersionResponse() {}

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemoryId() { return memoryId; }
    public void setMemoryId(String memoryId) { this.memoryId = memoryId; }
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getChangeDescription() { return changeDescription; }
    public void setChangeDescription(String changeDescription) { this.changeDescription = changeDescription; }
    public String getSnapshotData() { return snapshotData; }
    public void setSnapshotData(String snapshotData) { this.snapshotData = snapshotData; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
