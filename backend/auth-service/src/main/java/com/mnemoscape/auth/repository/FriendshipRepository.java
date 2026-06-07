package com.mnemoscape.auth.repository;

import com.mnemoscape.auth.model.entity.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface FriendshipRepository extends JpaRepository<Friendship, String> {
    List<Friendship> findByUserId1AndStatus(String userId1, Friendship.FriendshipStatus status);
    List<Friendship> findByUserId2AndStatus(String userId2, Friendship.FriendshipStatus status);

    @Query("SELECT f FROM Friendship f WHERE (f.userId1 = :userId OR f.userId2 = :userId) AND f.status = :status")
    List<Friendship> findAcceptedFriendshipsWithStatus(String userId, Friendship.FriendshipStatus status);

    default List<Friendship> findAcceptedFriendships(String userId) {
        return findAcceptedFriendshipsWithStatus(userId, Friendship.FriendshipStatus.ACCEPTED);
    }

    @Query("SELECT f FROM Friendship f WHERE (f.userId1 = :userId1 AND f.userId2 = :userId2) OR (f.userId1 = :userId2 AND f.userId2 = :userId1)")
    Optional<Friendship> findByUserPair(String userId1, String userId2);
}
