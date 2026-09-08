package com.mnemoscape.memory.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_achievements")
@Getter
@Setter
@NoArgsConstructor
public class Achievement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String achievementKey;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Column
    private String icon;

    @Column(nullable = false)
    private LocalDateTime unlockedAt;

    public Achievement(String userId, String achievementKey, String title, String description, String icon) {
        this.userId = userId;
        this.achievementKey = achievementKey;
        this.title = title;
        this.description = description;
        this.icon = icon;
        this.unlockedAt = LocalDateTime.now();
    }
}
