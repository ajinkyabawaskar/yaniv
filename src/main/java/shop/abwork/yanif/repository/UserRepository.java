package shop.abwork.yanif.repository;

import shop.abwork.yanif.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for User entity.
 */
@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByFingerprintHash(String fingerprintHash);

    Optional<User> findByFriendCode(String friendCode);
}
