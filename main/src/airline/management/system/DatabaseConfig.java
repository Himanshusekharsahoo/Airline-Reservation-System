package airline.management.system;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Database settings: environment variables override optional local config.properties. */
public final class DatabaseConfig {
    private final String host, port, name, user, password;

    private DatabaseConfig(String host, String port, String name, String user, String password) {
        this.host = required("DB_HOST", host); this.port = required("DB_PORT", port);
        this.name = required("DB_NAME", name); this.user = required("DB_USER", user); this.password = password;
        if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) > 65535) throw new IllegalStateException("DB_PORT must be a valid port number.");
        if (!name.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("DB_NAME contains unsupported characters.");
    }

    public static DatabaseConfig load() {
        Properties properties = new Properties();
        Path localFile = resolveConfigFile();
        if (localFile != null && Files.isRegularFile(localFile)) try (InputStream in = Files.newInputStream(localFile)) {
            properties.load(in);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read local database configuration.", exception);
        }
        return new DatabaseConfig(value("DB_HOST", properties, "localhost"), value("DB_PORT", properties, "3306"),
                value("DB_NAME", properties, "project4"), value("DB_USER", properties, "root"), password(properties));
    }

    private static Path resolveConfigFile() {
        Path cwdFile = Path.of("config.properties");
        if (Files.isRegularFile(cwdFile)) {
            return cwdFile;
        }

        Path subDirFile = Path.of("main", "config.properties");
        if (Files.isRegularFile(subDirFile)) {
            return subDirFile;
        }

        try {
            var codeSource = DatabaseConfig.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path codePath = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
                Path dir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
                while (dir != null) {
                    Path candidate = dir.resolve("config.properties");
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                    Path subCandidate = dir.resolve("main").resolve("config.properties");
                    if (Files.isRegularFile(subCandidate)) {
                        return subCandidate;
                    }
                    dir = dir.getParent();
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static String value(String key, Properties properties, String fallback) {
        String systemProperty = System.getProperty(key);
        if (systemProperty != null && !systemProperty.isBlank()) return systemProperty.trim();
        String environment = System.getenv(key);
        return environment != null && !environment.isBlank() ? environment.trim() : properties.getProperty(key, fallback).trim();
    }
    private static String password(Properties properties) {
        String systemProperty = System.getProperty("DB_PASSWORD");
        if (systemProperty != null) return systemProperty;
        String environment = System.getenv("DB_PASSWORD");
        return environment != null ? environment : properties.getProperty("DB_PASSWORD", "");
    }
    private static String required(String key, String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required.");
        return value;
    }
    public String host() { return host; }
    public String port() { return port; }
    public String name() { return name; }
    public String url() { return "jdbc:mysql://" + host + ":" + port + "/" + name + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"; }
    public String user() { return user; }
    public String password() { return password; }
    public String description() { return "Database: " + name + System.lineSeparator() + "Host: " + host + System.lineSeparator() + "Port: " + port; }
}
