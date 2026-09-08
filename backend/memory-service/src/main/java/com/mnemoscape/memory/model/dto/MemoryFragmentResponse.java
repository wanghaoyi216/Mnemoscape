package com.mnemoscape.memory.model.dto;

import com.mnemoscape.memory.model.entity.MemoryFragment;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MemoryFragmentResponse {
    private String id;
    private String memoryId;
    private String fragmentType;
    private String content;
    private String position3d;
    private String triggerCondition;
    private boolean isDiscovered;

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
}
