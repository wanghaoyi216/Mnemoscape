CREATE DATABASE IF NOT EXISTS mnemoscape_memory
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE mnemoscape_memory;

CREATE TABLE IF NOT EXISTS memories (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    memory_year INT,
    memory_date DATE,
    memory_season VARCHAR(10),
    memory_time_of_day VARCHAR(10),
    memory_location VARCHAR(255),
    privacy_level ENUM('private', 'friends', 'public') DEFAULT 'private',
    is_locked BOOLEAN DEFAULT FALSE,
    fade_level DECIMAL(3,2) DEFAULT 0.00,
    last_drift_calculated_at TIMESTAMP NULL,
    scene_data_url VARCHAR(500),
    emotion_vector_id VARCHAR(100),
    visual_data JSON,
    audio_data JSON,
    emotion_profile JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_privacy_level (privacy_level),
    INDEX idx_fade_level (fade_level),
    INDEX idx_created_at (created_at),
    INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS memory_versions (
    id VARCHAR(36) PRIMARY KEY,
    memory_id VARCHAR(36) NOT NULL,
    version_number INT NOT NULL,
    change_type ENUM('create', 'modify', 'drift', 'lock', 'restore') NOT NULL,
    change_description VARCHAR(500),
    snapshot_data JSON NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE,
    UNIQUE KEY uk_memory_version (memory_id, version_number)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS memory_fragments (
    id VARCHAR(36) PRIMARY KEY,
    memory_id VARCHAR(36) NOT NULL,
    fragment_type VARCHAR(20) NOT NULL COMMENT 'forgotten_detail, emotion_flashback, linked_door',
    content TEXT,
    position_3d JSON COMMENT '{x, y, z}',
    trigger_condition JSON COMMENT 'trigger radius etc.',
    is_discovered BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE,
    INDEX idx_memory_id (memory_id),
    INDEX idx_discovered (memory_id, is_discovered)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS memory_annotations (
    id VARCHAR(36) PRIMARY KEY,
    memory_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    position_3d JSON COMMENT '{x, y, z}',
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE,
    INDEX idx_memory_id (memory_id)
) ENGINE=InnoDB;
