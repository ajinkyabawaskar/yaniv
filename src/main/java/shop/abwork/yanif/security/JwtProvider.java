package shop.abwork.yanif.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Token Provider.
 * Handles generation, validation, and claim extraction from JWT tokens.
 */
@Component
public class JwtProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    private JwtParser jwtParser;

    /**
     * Initialize JWT parser (lazy initialization to handle @Value injection).
     */
    private JwtParser getJwtParser() {
        if (jwtParser == null) {
            jwtParser = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build();
        }
        return jwtParser;
    }

    /**
     * Generate JWT token with userId and fingerprintHash claims.
     *
     * @param userId         User ID (UUID)
     * @param fingerprintHash Browser fingerprint hash
     * @return JWT token string
     */
    public String generateToken(String userId, String fingerprintHash) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);

        return Jwts.builder()
                .subject(userId)
                .claim("fingerprintHash", fingerprintHash)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS512)
                .compact();
    }

    /**
     * Extract user ID from JWT token.
     *
     * @param token JWT token string
     * @return User ID extracted from token subject
     */
    public String extractUserId(String token) {
        return getJwtParser()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /**
     * Extract fingerprint hash from JWT token.
     *
     * @param token JWT token string
     * @return Fingerprint hash from token claims
     */
    public String extractFingerprintHash(String token) {
        return getJwtParser()
                .parseSignedClaims(token)
                .getPayload()
                .get("fingerprintHash", String.class);
    }

    /**
     * Validate JWT token signature and expiration.
     *
     * @param token JWT token string
     * @return true if token is valid, false otherwise
     */
    public boolean validateToken(String token) {
        try {
            getJwtParser().parseSignedClaims(token);
            return true;
        } catch (SecurityException e) {
            // Invalid signature
            return false;
        } catch (MalformedJwtException e) {
            // Invalid token format
            return false;
        } catch (ExpiredJwtException e) {
            // Token expired
            return false;
        } catch (UnsupportedJwtException e) {
            // Unsupported JWT
            return false;
        } catch (IllegalArgumentException e) {
            // Empty or null token
            return false;
        }
    }

    /**
     * Get signing key from secret.
     *
     * @return SecretKey for JWT signing/verification
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }
}
