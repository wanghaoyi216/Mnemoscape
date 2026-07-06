package com.mnemoscape.memory.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "drift_bottles")
@Getter
@Setter
@NoArgsConstructor
public class DriftBottle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String memoryId;

    @Column(nullable = false, length = 500)
    private String snippet;

    @Column
    private String emotion;

    @Column
    private String location;

    @Column
    private Integer year;

    @Column
    private String pickedByUserId;

    @Column
    private LocalDateTime pickedAt;

    @Column(nullable = false)
    private LocalDateTime thrownAt;

    @Column(nullable = false)
    private Boolean isActive = true;

    public DriftBottle(String userId, String memoryId, String snippet) {
        this.userId = userId;
        this.memoryId = memoryId;
        this.snippet = snippet;
        this.thrownAt = LocalDateTime.now();
    }
}
