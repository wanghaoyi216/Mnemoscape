package com.mnemoscape.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.auth.model.dto.AvatarProfileRequest;
import com.mnemoscape.auth.model.dto.AvatarProfileResponse;
import com.mnemoscape.auth.model.entity.UserAvatarProfile;
import com.mnemoscape.auth.repository.UserAvatarProfileRepository;
import com.mnemoscape.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 用户 3D 刻画档案服务。
 *
 * <p>负责：
 * <ol>
 *   <li>接收用户自我描述，调用 {@link AvatarGeneratorService} 生成角色特征</li>
 *   <li>持久化到 {@code user_avatar_profiles} 表（每用户一条，upsert 语义）</li>
 *   <li>提供查询接口（按 userId 查询）</li>
 * </ol>
 */
@Service
public class AvatarProfileService {

    private static final Logger log = LoggerFactory.getLogger(AvatarProfileService.class);

    private final UserAvatarProfileRepository repository;
    private final AvatarGeneratorService generatorService;
    private final AvatarProfilePersistenceService persistenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AvatarProfileService(UserAvatarProfileRepository repository,
                                 AvatarGeneratorService generatorService,
                                 AvatarProfilePersistenceService persistenceService) {
        this.repository = repository;
        this.generatorService = generatorService;
        this.persistenceService = persistenceService;
    }

    /**
     * 创建或更新用户的 3D 刻画档案（upsert 语义）。
     *
     * @param userId  当前用户 ID
     * @param request 包含自我描述和公开设置
     * @return 生成的角色档案响应
     */
    public AvatarProfileResponse createOrUpdate(String userId, AvatarProfileRequest request) {
        if (userId == null || userId.isBlank()) {
            throw BizException.unauthorized();
        }

        log.info("[AvatarProfile] Generating avatar for userId={}, descLen={}",
                userId, request.getSelfDescription() == null ? 0 : request.getSelfDescription().length());

        // 调 AI 生成角色特征（无事务：LLM 耗时 5-30s，持有 DB 连接会耗尽 Hikari 池）
        AvatarGeneratorService.AvatarGenerationResult result =
                generatorService.generate(request.getSelfDescription());

        String traitsJson = toJson(result.traits);
        String emotionToneJson = toJson(result.emotionTone);
        String tagsJson = toJson(result.personalityTags);

        // 事务保存委托给独立 bean，保证事务边界短
        UserAvatarProfile profile = persistenceService.saveProfile(userId, request,
                result.avatarTitle, result.avatarStory, traitsJson, emotionToneJson, tagsJson);

        return toResponse(profile);
    }

    /**
     * 获取用户的 3D 刻画档案。
     *
     * @param userId 用户 ID
     * @return 档案响应，若不存在则返回 null
     */
    public AvatarProfileResponse getByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw BizException.unauthorized();
        }
        Optional<UserAvatarProfile> opt = repository.findByUserId(userId);
        return opt.map(this::toResponse).orElse(null);
    }

    /**
     * 删除用户的 3D 刻画档案。
     */
    @Transactional
    public void deleteByUserId(String userId) {
        repository.findByUserId(userId).ifPresent(repository::delete);
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────

    private AvatarProfileResponse toResponse(UserAvatarProfile profile) {
        AvatarProfileResponse resp = new AvatarProfileResponse();
        resp.setId(profile.getId());
        resp.setUserId(profile.getUserId());
        resp.setSelfDescription(profile.getSelfDescription());
        resp.setAvatarTraits(profile.getAvatarTraits());
        resp.setEmotionTone(profile.getEmotionTone());
        resp.setPersonalityTags(profile.getPersonalityTags());
        resp.setAvatarTitle(profile.getAvatarTitle());
        resp.setAvatarStory(profile.getAvatarStory());
        resp.setIsPublic(profile.getIsPublic());
        resp.setCreatedAt(profile.getCreatedAt());
        resp.setUpdatedAt(profile.getUpdatedAt());
        return resp;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[AvatarProfile] Failed to serialize object: {}", e.getMessage());
            return null;
        }
    }
}
