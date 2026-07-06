package com.mnemoscape.ai.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SceneFragment {
    private String fragmentType;
    private String content;
    private Position3d position3d;
    private double triggerRadius;
    private boolean isDiscovered;
}
