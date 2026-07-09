package com.mnemoscape.auth.service;

import com.mnemoscape.auth.model.dto.AvatarProfileRequest;
import com.mnemoscape.auth.model.entity.UserAvatarProfile;
import com.mnemoscape.auth.repository.UserAvatarProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Avatar 档案持久化服务 - 独立 bean 承载事务边界。
 *
 * <p>从 {@link AvatarProfileService} 拆出，使 LLM 调用（5-30s）不再持有 DB 连接：
 * AvatarProfileService 先无事务调 LLM 拿结果，再委托本 bean 在短事务内 upsert。
 * 同类自调用会让 @Transactional 失效，所以必须抽成独立 bean。
 */
@Service
public class AvatarProfilePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AvatarProfilePersistenceService.class);

    private final UserAvatarProfileRepository repository;

    public AvatarProfilePersistenceService(UserAvatarProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserAvatarProfile saveProfile(String userId, AvatarProfileRequest request,
                                         String avatarTitle, String avatarStory,
                                         String traitsJson, String emotionToneJson, String tagsJson) {
        UserAvatarProfile profile = repository.findByUserId(userId)
                .orElseGet(() -> {
                    UserAvatarProfile p = new UserAvatarProfile();
                    p.setUserId(userId);
                    return p;
                });
        profile.setSelfDescription(request.getSelfDescription());
        profile.setAvatarTraits(traitsJson);
        profile.setEmotionTone(emotionToneJson);
        profile.setPersonalityTags(tagsJson);
        profile.setAvatarTitle(avatarTitle);
        profile.setAvatarStory(avatarStory);
        profile.setIsPublic(request.getIsPublic() != null ? request.getIsPublic() : false);
        profile = repository.save(profile);
        log.info("[AvatarProfile] Saved avatar profile id={} for userId={}", profile.getId(), userId);
        return profile;
    }
}
