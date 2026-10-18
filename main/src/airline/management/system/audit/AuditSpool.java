package airline.management.system.audit;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * Append-only durable local spool for audit events that cannot be persisted to MySQL.
 * Format: AERODESK_AUDIT_V1\t<spoolId>\t<orgId>\t<ts>\t<userId>\t<username>\t<role>\t<action>\t<targetType>\t<targetId>\t<details>\t<clientIp>\t<crc32>\tEND_OF_RECORD\n
 */
final class AuditSpool {
    public static final String CONFIG_KEY_SPOOL_PATH = "AERODESK_AUDIT_SPOOL_PATH";
    private static final String RECORD_VERSION = "AERODESK_AUDIT_V1";
    private static final String RECORD_TERMINATOR = "END_OF_RECORD";
    private static final String NULL_TOKEN = "\\N";
    private static final Logger LOGGER = Logger.getLogger(AuditSpool.class.getName());

    private final Path spoolPath;
    private final Object lock = new Object();

    AuditSpool() {
        this(resolveDefaultSpoolPath());
    }

    AuditSpool(Path spoolPath) {
        this.spoolPath = Objects.requireNonNull(spoolPath, "spoolPath cannot be null").toAbsolutePath().normalize();
    }

    public static Path resolveDefaultSpoolPath() {
        String configured = System.getProperty(CONFIG_KEY_SPOOL_PATH);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(CONFIG_KEY_SPOOL_PATH);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        String userHome = System.getProperty("user.home", ".");
        return Path.of(userHome, ".aerodesk", "audit-spool.log");
    }

    public Path getSpoolPath() {
        return spoolPath;
    }

    public String write(PendingAuditEvent pending) {
        Objects.requireNonNull(pending, "pending audit event cannot be null");
        String spoolId = UUID.randomUUID().toString();
        synchronized (lock) {
            try {
                Path parent = spoolPath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                String line = formatRecord(spoolId, pending);
                Files.writeString(spoolPath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                return spoolId;
            } catch (IOException e) {
                throw new AuditSpoolException("Failed to append audit record to spool file: " + spoolPath, e);
            }
        }
    }

    public List<SpooledAuditRecord> readAllValidRecords() {
        synchronized (lock) {
            if (!Files.exists(spoolPath)) {
                return Collections.emptyList();
            }
            List<SpooledAuditRecord> records = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(spoolPath, StandardCharsets.UTF_8)) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) continue;
                    SpooledAuditRecord record = parseRecord(line, lineNumber);
                    if (record != null) {
                        records.add(record);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to read audit spool file: " + spoolPath, e);
            }
            return Collections.unmodifiableList(records);
        }
    }

    public void removeRecords(Set<String> persistedSpoolIds) {
        if (persistedSpoolIds == null || persistedSpoolIds.isEmpty()) return;
        synchronized (lock) {
            if (!Files.exists(spoolPath)) return;
            try {
                List<String> allLines = Files.readAllLines(spoolPath, StandardCharsets.UTF_8);
                List<String> remainingLines = new ArrayList<>();
                for (String line : allLines) {
                    if (line.isBlank()) continue;
                    String[] tokens = line.split("\t", -1);
                    if (tokens.length >= 2 && persistedSpoolIds.contains(tokens[1])) {
                        // Persisted; remove from spool
                        continue;
                    }
                    remainingLines.add(line);
                }

                if (remainingLines.isEmpty()) {
                    Files.deleteIfExists(spoolPath);
                } else {
                    Path tmpPath = spoolPath.resolveSibling(spoolPath.getFileName().toString() + ".tmp." + System.nanoTime());
                    Files.write(tmpPath, remainingLines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    try {
                        Files.move(tmpPath, spoolPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        Files.move(tmpPath, spoolPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException e) {
                throw new AuditSpoolException("Failed to update spool after persisting records: " + spoolPath, e);
            }
        }
    }

    public boolean hasRecords() {
        synchronized (lock) {
            try {
                return Files.exists(spoolPath) && Files.size(spoolPath) > 0;
            } catch (IOException e) {
                return false;
            }
        }
    }

    public int recordCount() {
        return readAllValidRecords().size();
    }

    public void clear() {
        synchronized (lock) {
            try {
                Files.deleteIfExists(spoolPath);
            } catch (IOException ignored) {
            }
        }
    }

    private String formatRecord(String spoolId, PendingAuditEvent pending) {
        AuditEvent event = pending.event();
        String payload = String.join("\t",
            RECORD_VERSION,
            spoolId,
            String.valueOf(pending.organizationId()),
            event.timestamp() != null ? event.timestamp().toString() : Instant.now().toString(),
            event.userId() != null ? event.userId().toString() : NULL_TOKEN,
            escape(event.username() != null ? event.username() : "SYSTEM"),
            escape(event.role() != null ? event.role() : "SYSTEM"),
            event.action() != null ? event.action().name() : "LOGIN",
            escape(event.targetType() != null ? event.targetType() : "SYSTEM"),
            escape(event.targetId()),
            escape(event.details()),
            escape(event.clientIp() != null ? event.clientIp() : "127.0.0.1")
        );

        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        String crcHex = Long.toHexString(crc.getValue());
        return payload + "\t" + crcHex + "\t" + RECORD_TERMINATOR + "\n";
    }

    private SpooledAuditRecord parseRecord(String line, int lineNumber) {
        String[] tokens = line.split("\t", -1);
        if (tokens.length != 14) {
            LOGGER.log(Level.WARNING, "Skipping malformed audit spool record at line " + lineNumber +
                ": expected 14 tokens but found " + tokens.length);
            return null;
        }

        if (!RECORD_VERSION.equals(tokens[0])) {
            LOGGER.log(Level.WARNING, "Skipping incompatible audit spool version at line " + lineNumber +
                ": found '" + tokens[0] + "', expected '" + RECORD_VERSION + "'");
            return null;
        }

        if (!RECORD_TERMINATOR.equals(tokens[13])) {
            LOGGER.log(Level.WARNING, "Skipping incomplete audit spool record at line " + lineNumber +
                ": missing terminator");
            return null;
        }

        // Validate CRC32
        String payload = String.join("\t",
            tokens[0], tokens[1], tokens[2], tokens[3], tokens[4], tokens[5],
            tokens[6], tokens[7], tokens[8], tokens[9], tokens[10], tokens[11]);
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        String expectedCrc = Long.toHexString(crc.getValue());
        if (!expectedCrc.equalsIgnoreCase(tokens[12])) {
            LOGGER.log(Level.WARNING, "Skipping corrupted audit spool record at line " + lineNumber +
                ": CRC32 mismatch (expected " + expectedCrc + " but was " + tokens[12] + ")");
            return null;
        }

        try {
            String spoolId = tokens[1];
            long orgId = Long.parseLong(tokens[2]);
            Instant timestamp = Instant.parse(tokens[3]);
            Long userId = NULL_TOKEN.equals(tokens[4]) ? null : Long.parseLong(tokens[4]);
            String username = unescape(tokens[5]);
            String role = unescape(tokens[6]);
            AuditAction action = AuditAction.valueOf(tokens[7]);
            String targetType = unescape(tokens[8]);
            String targetId = unescape(tokens[9]);
            String details = unescape(tokens[10]);
            String clientIp = unescape(tokens[11]);

            AuditEvent event = new AuditEvent(timestamp, userId, username, role, action, targetType, targetId, details, clientIp);
            return new SpooledAuditRecord(spoolId, new PendingAuditEvent(event, orgId));
        } catch (DateTimeParseException | IllegalArgumentException ex) {
            LOGGER.log(Level.WARNING, "Skipping unparseable audit spool record at line " + lineNumber + ": " + ex.getMessage());
            return null;
        }
    }

    static String escape(String s) {
        if (s == null) return NULL_TOKEN;
        return s.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    static String unescape(String s) {
        if (s == null || NULL_TOKEN.equals(s)) return null;
        StringBuilder sb = new StringBuilder(s.length());
        boolean inEscape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inEscape) {
                switch (c) {
                    case '\\' -> sb.append('\\');
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 'N' -> { return null; }
                    default -> { sb.append('\\').append(c); }
                }
                inEscape = false;
            } else if (c == '\\') {
                inEscape = true;
            } else {
                sb.append(c);
            }
        }
        if (inEscape) {
            sb.append('\\');
        }
        return sb.toString();
    }

    static class AuditSpoolException extends RuntimeException {
        public AuditSpoolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
