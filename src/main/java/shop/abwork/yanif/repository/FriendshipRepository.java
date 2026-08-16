package shop.abwork.yanif.repository;

import shop.abwork.yanif.entity.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Friendship entity.
 */
@Repository
public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    /**
     * Find accepted friendships for a user.
     * Returns all friendships where the user is either userId1 or userId2 and status is ACCEPTED.
     */
    @Query("SELECT f FROM Friendship f WHERE " +
           "(f.userId1 = :userId OR f.userId2 = :userId) AND f.status = 'ACCEPTED'")
    List<Friendship> findAcceptedFriendships(String userId);

    /**
     * Returns relationships created by both the current and former friend flows.
     * PENDING rows are upgraded by the service when read.
     */
    @Query("SELECT f FROM Friendship f WHERE " +
           "(f.userId1 = :userId OR f.userId2 = :userId) AND " +
           "f.status IN ('ACCEPTED', 'PENDING')")
    List<Friendship> findActiveFriendships(String userId);

    /**
     * Find pending friend requests for a user (where user is userId2 and status is PENDING).
     */
    @Query("SELECT f FROM Friendship f WHERE f.userId2 = :userId AND f.status = 'PENDING'")
    List<Friendship> findPendingRequests(String userId);

    /**
     * Find friendship between two users (bidirectional).
     */
    @Query("SELECT f FROM Friendship f WHERE " +
           "((f.userId1 = :userId1 AND f.userId2 = :userId2) OR " +
           "(f.userId1 = :userId2 AND f.userId2 = :userId1))")
    Optional<Friendship> findBetween(String userId1, String userId2);

    /**
     * Check if a friendship exists between two users.
     */
    @Query("SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END FROM Friendship f WHERE " +
           "((f.userId1 = :userId1 AND f.userId2 = :userId2) OR " +
           "(f.userId1 = :userId2 AND f.userId2 = :userId1)) AND f.status = 'ACCEPTED'")
    boolean areAcceptedFriends(String userId1, String userId2);
}
