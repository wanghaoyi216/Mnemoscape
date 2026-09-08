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
public class SceneAudioSource {
    private String type;
    private String file;
    private double volume;
    private Boolean loop;
    private List<Double> position;
}
