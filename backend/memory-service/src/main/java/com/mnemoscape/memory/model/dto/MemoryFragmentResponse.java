package com.mnemoscape.memory.model.dto;

import com.mnemoscape.memory.model.entity.MemoryFragment;

public class MemoryFragmentResponse {
    private String id;
    private String memoryId;
    private String fragmentType;
    private String content;
    private String position3d;
    private String triggerCondition;
    private boolean isDiscovered;

    public MemoryFragmentResponse() {}

    public static MemoryFragmentResponse fromEntity(MemoryFragment fragment) {
        MemoryFragmentResponse response = new MemoryFragmentResponse();
        response.id = fragment.getId();
        response.memoryId = fragment.getMemoryId();
        response.fragmentType = fragment.getFragmentType();
        response.content = fragment.getContent();
        response.position3d = fragment.getPosition3d();
        response.triggerCondition = fragment.getTriggerCondition();
        response.isDiscovered = fragment.getIsDiscovered() != null && fragment.getIsDiscovered();
        return response;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMemoryId() { return memoryId; }
    public void setMemoryId(String memoryId) { this.memoryId = memoryId; }
    public String getFragmentType() { return fragmentType; }
    public void setFragmentType(String fragmentType) { this.fragmentType = fragmentType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getPosition3d() { return position3d; }
    public void setPosition3d(String position3d) { this.position3d = position3d; }
    public String getTriggerCondition() { return triggerCondition; }
    public void setTriggerCondition(String triggerCondition) { this.triggerCondition = triggerCondition; }
    public boolean getIsDiscovered() { return isDiscovered; }
    public void setIsDiscovered(boolean isDiscovered) { this.isDiscovered = isDiscovered; }
}
