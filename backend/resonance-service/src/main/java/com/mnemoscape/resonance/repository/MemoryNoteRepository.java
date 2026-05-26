package com.mnemoscape.resonance.repository;

import com.mnemoscape.resonance.model.entity.MemoryNote;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MemoryNoteRepository extends JpaRepository<MemoryNote, String> {
    List<MemoryNote> findByResonanceIdOrderByCreatedAtAsc(String resonanceId);
}
