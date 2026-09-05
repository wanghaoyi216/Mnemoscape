CREATE DATABASE IF NOT EXISTS mnemoscape_resonance
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE mnemoscape_resonance;

CREATE TABLE IF NOT EXISTS resonance_spaces (
    id VARCHAR(36) PRIMARY KEY,
    memory_id_1 VARCHAR(36) NOT NULL,
    memory_id_2 VARCHAR(36) NOT NULL,
    similarity_score DECIMAL(5,4) NOT NULL,
    emotion_similarity DECIMAL(5,4),
    scene_similarity DECIMAL(5,4),
    scene_data_url VARCHAR(500),
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending, accepted, active, closed',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_memory_pair (memory_id_1, memory_id_2),
    INDEX idx_memory_1 (memory_id_1),
    INDEX idx_memory_2 (memory_id_2)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS memory_notes (
    id VARCHAR(36) PRIMARY KEY,
    author_id VARCHAR(36) NOT NULL,
    resonance_id VARCHAR(36) NOT NULL,
    position_3d JSON COMMENT '{x, y, z}',
    content TEXT NOT NULL,
    mood VARCHAR(50) COMMENT 'warm, melancholic, joyful, contemplative, grateful',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (resonance_id) REFERENCES resonance_spaces(id) ON DELETE CASCADE,
    INDEX idx_resonance_id (resonance_id),
    INDEX idx_author_id (author_id)
) ENGINE=InnoDB;
