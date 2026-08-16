package shop.abwork.yanif.util;

import java.util.Random;

/**
 * Utility class for generating friend codes.
 * Friend codes are random 8-character alphanumeric strings used to add friends.
 */
public class FriendCode {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int FRIEND_CODE_LENGTH = 8;
    private static final Random RANDOM = new Random();

    /**
     * Generate a random 8-character friend code.
     *
     * @return Random 8-character alphanumeric friend code
     */
    public static String generateFriendCode() {
        StringBuilder code = new StringBuilder(FRIEND_CODE_LENGTH);
        for (int i = 0; i < FRIEND_CODE_LENGTH; i++) {
            code.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return code.toString();
    }

    /**
     * Validate that a friend code is in the correct format.
     * Must be 8 characters and contain only alphanumeric characters.
     *
     * @param code Friend code to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidFriendCode(String code) {
        if (code == null || code.length() != FRIEND_CODE_LENGTH) {
            return false;
        }
        return code.matches("[A-Z0-9]{8}");
    }
}
