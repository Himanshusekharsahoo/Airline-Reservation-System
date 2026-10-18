package airline.management.system;

import static org.junit.jupiter.api.Assertions.*;
import airline.management.system.staff.StaffService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class PasswordUtilsTest {
    @Test void correctPasswordVerifiesAndHashIsNotPlaintext() {
        String hash = PasswordUtils.hash("JUnitSecret".toCharArray());
        assertNotEquals("JUnitSecret", hash);
        assertTrue(PasswordUtils.matches("JUnitSecret".toCharArray(), hash));
    }
    @Test void wrongPasswordFails() {
        String hash = PasswordUtils.hash("JUnitSecret".toCharArray());
        assertFalse(PasswordUtils.matches("wrong".toCharArray(), hash));
    }

    @Test void legacySha256VerificationSupportsUtf8WithoutMutatingCallerArray() throws Exception {
        char[] password = "Pässwörd航空".toCharArray();
        String legacyHash = "sha256$" + hex(MessageDigest.getInstance("SHA-256")
            .digest(new String(password).getBytes(StandardCharsets.UTF_8)));

        assertTrue(PasswordUtils.matches(password, legacyHash));
        assertArrayEquals("Pässwörd航空".toCharArray(), password);
    }

    @Test void staffCredentialFlowsClearOwnedArraysOnEarlyFailure() {
        char[] createPassword = "short".toCharArray();
        char[] resetPassword = "short".toCharArray();

        assertThrows(RuntimeException.class, () -> StaffService.create("operator1", createPassword, "OPERATIONSMANAGER"));
        assertThrows(RuntimeException.class, () -> StaffService.resetPassword(10L, resetPassword));
        assertArrayEquals(new char[createPassword.length], createPassword);
        assertArrayEquals(new char[resetPassword.length], resetPassword);
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format("%02x", b));
        return value.toString();
    }
}
