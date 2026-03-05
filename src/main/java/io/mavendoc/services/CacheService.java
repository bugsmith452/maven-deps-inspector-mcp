package io.mavendoc.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Persistent disk cache for rendered tool responses. */
public final class CacheService {
  private final Path cacheRoot;

  public CacheService(Path cacheRoot) {
    this.cacheRoot = cacheRoot;
  }

  public void initialize() throws IOException {
    Files.createDirectories(cacheRoot);
  }

  public Optional<String> read(String key) {
    Path path = entryPath(key);
    if (Files.exists(path)) {
      try {
        return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
      } catch (IOException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  public void write(String key, String content) {
    Path path = entryPath(key);
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Unable to write cache entry", e);
    }
  }

  public Path rootPath() {
    return cacheRoot;
  }

  public void clear() throws IOException {
    if (Files.exists(cacheRoot)) {
      try (var stream = Files.list(cacheRoot)) {
        stream.forEach(
            path -> {
              try {
                Files.walk(path)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(
                        p -> {
                          try {
                            Files.deleteIfExists(p);
                          } catch (IOException ex) {
                            // ignore
                          }
                        });
              } catch (IOException e) {
                // ignore
              }
            });
      }
    }
  }

  private Path entryPath(String key) {
    String hashed = hash(key);
    return cacheRoot.resolve(hashed.substring(0, 2)).resolve(hashed + ".txt");
  }

  private String hash(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] result = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(result);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
