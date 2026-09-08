package com.mnemoscape.ai.model.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SceneObject {
    private String id;
    private String type;
    private String name;
    private List<Double> position;
    private String color;
    private List<Double> scale;
}
