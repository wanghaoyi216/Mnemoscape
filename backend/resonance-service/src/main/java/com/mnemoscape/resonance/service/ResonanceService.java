package com.mnemoscape.resonance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.resonance.model.entity.MemoryNote;
import com.mnemoscape.resonance.model.entity.ResonanceSpace;
import com.mnemoscape.resonance.repository.MemoryNoteRepository;
import com.mnemoscape.resonance.repository.ResonanceSpaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ResonanceService {
    private static final Logger log = LoggerFactory.getLogger(ResonanceService.class);

    private final ResonanceSpaceRepository spaceRepository;
    private final MemoryNoteRepository noteRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResonanceService(ResonanceSpaceRepository spaceRepository, MemoryNoteRepository noteRepository) {
        this.spaceRepository = spaceRepository;
        this.noteRepository = noteRepository;
    }

    public List<Map<String, Object>> searchResonances(String memoryId) {
        log.info("Searching resonances for memory: {}", memoryId);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double emotionSim = round(0.75 + ThreadLocalRandom.current().nextDouble() * 0.25);
            double sceneSim = round(0.7 + ThreadLocalRandom.current().nextDouble() * 0.3);
            matches.add(Map.of(
                "memoryId", "mock-memory-" + (i + 1),
                "title", List.of("A warm summer evening", "Childhood playground memories", "Grandma's kitchen") .get(i),
                "similarityScore", round(0.6 * emotionSim + 0.4 * sceneSim),
                "emotionSimilarity", emotionSim,
                "sceneSimilarity", sceneSim,
                "ownerUsername", "user_" + (i + 1)
            ));
        }
        return matches;
    }

    public ResonanceSpace createSpace(String memoryId1, String memoryId2) {
        double score = round(0.75 + ThreadLocalRandom.current().nextDouble() * 0.25);
        ResonanceSpace space = ResonanceSpace.builder()
                .memoryId1(memoryId1)
                .memoryId2(memoryId2)
                .similarityScore(score)
                .emotionSimilarity(score * 1.05)
                .sceneSimilarity(score * 0.95)
                .sceneDataUrl("scene://resonance/" + UUID.randomUUID())
                .status("active")
                .build();
        return spaceRepository.save(space);
    }

    public ResonanceSpace getSpace(String spaceId) {
        return spaceRepository.findById(spaceId)
                .orElseThrow(() -> BizException.notFound("ResonanceSpace", spaceId));
    }

    public List<ResonanceSpace> getSpacesByMemory(String memoryId) {
        return spaceRepository.findByMemoryId(memoryId);
    }

    public MemoryNote placeNote(String resonanceId, String authorId, String content, String mood, Map<String, Double> position) {
        getSpace(resonanceId);
        MemoryNote note = MemoryNote.builder()
                .resonanceId(resonanceId)
                .authorId(authorId)
                .content(content)
                .mood(mood != null ? mood : "warm")
                .position3d(toJson(position))
                .build();
        return noteRepository.save(note);
    }

    public List<MemoryNote> getNotes(String resonanceId) {
        return noteRepository.findByResonanceIdOrderByCreatedAtAsc(resonanceId);
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
