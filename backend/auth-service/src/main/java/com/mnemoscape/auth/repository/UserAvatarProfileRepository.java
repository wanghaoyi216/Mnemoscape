package com.mnemoscape.auth.repository;

import com.mnemoscape.auth.model.entity.UserAvatarProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserAvatarProfileRepository extends JpaRepository<UserAvatarProfile, String> {

    /** 按 userId 查找用户的 3D 刻画档案（每人最多一条）。 */
    Optional<UserAvatarProfile> findByUserId(String userId);

    /** 检查用户是否已有 3D 刻画档案。 */
    boolean existsByUserId(String userId);
}
