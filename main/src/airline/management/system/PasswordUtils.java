package airline.management.system;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordUtils {
    private static final int ITERATIONS = 120_000, KEY_BITS = 256;
    private PasswordUtils() { }
    public static String hash(char[] password) {
        try { byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
            return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
        } catch (Exception exception) { throw new IllegalStateException("Password hashing is unavailable.", exception); }
    }
    public static boolean matches(char[] password, String stored) {
        try {
            if (stored.startsWith("sha256$")) {
                byte[] digest = legacySha256(password);
                try {
                    return MessageDigest.isEqual(hex(digest).getBytes(StandardCharsets.US_ASCII), stored.substring(7).getBytes(StandardCharsets.US_ASCII));
                } finally {
                    Arrays.fill(digest, (byte) 0);
                }
            }
            String[] parts = stored.split("\\$", 4); if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
            return MessageDigest.isEqual(derive(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1])), Base64.getDecoder().decode(parts[3]));
        } catch (Exception exception) { return false; }
    }
    private static byte[] legacySha256(char[] password) throws Exception {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .encode(CharBuffer.wrap(password));
        byte[] passwordBytes = new byte[encoded.remaining()];
        encoded.get(passwordBytes);
        try {
            return MessageDigest.getInstance("SHA-256").digest(passwordBytes);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        }
    }
    private static byte[] derive(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format("%02x", b)); return value.toString(); }
}
