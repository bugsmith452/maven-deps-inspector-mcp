package io.mavendoc.services;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.mavendoc.config.ProjectConfig;
import io.mavendoc.config.ServerConfig;
import io.mavendoc.model.ToolRequest;
import io.mavendoc.model.ToolResult;
import io.mavendoc.services.SymbolRepository.MatchResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SymbolRepository} using synthetic bytecode fixtures.
 * No external Maven artifacts are required.
 */
class SymbolRepositoryTest {

  @TempDir Path tempDir;

  private Fixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    fixture = new Fixture(tempDir);
    fixture.build();
  }

  @Test
  void basePackageFilteringReducesMatches() {
    SymbolRepository repo = fixture.repository();

    MatchResult noFilter = repo.matches("Widget", Optional.empty());
    assertEquals(2, noFilter.matches().size());
    assertFalse(noFilter.basePackageApplied());

    MatchResult filtered = repo.matches("Widget", Optional.of("com.example.alpha"));
    assertEquals(1, filtered.matches().size());
    assertTrue(filtered.basePackageApplied());
    assertTrue(filtered.basePackageReduced());
    assertEquals("com.example.alpha", filtered.requestedBasePackage());

    MatchResult fallback = repo.matches("Widget", Optional.of("com.example.missing"));
    assertEquals(2, fallback.matches().size());
    assertTrue(fallback.basePackageApplied());
    assertTrue(fallback.basePackageFallback());
  }

  @Test
  void searchHandlerIncludesBasePackageNote() throws Exception {
    CacheService cache = new CacheService(tempDir.resolve("cache"));
    cache.initialize();
    ToolRequestHandler handler = new ToolRequestHandler(
        fixture.repository(), new TextRenderer(fixture.repository()), cache);
    ObjectMapper mapper = JsonMapper.builder().build();

    ToolRequest request = new ToolRequest(
        UUID.randomUUID().toString(),
        "searchDependencies",
        mapper.valueToTree(Map.of("className", "Widget", "basePackage", "com.example.alpha")));

    ToolResult result = handler.handle(request);
    assertTrue(result.success());
    assertNotNull(result.text());
    // Note about basePackage reduction should appear in the plain-text response
    assertTrue(result.text().contains("com.example.alpha"),
        "Expected basePackage name in response text");
    assertTrue(result.text().contains("Widget"),
        "Expected class name in response text");
  }

  // ── fixture ───────────────────────────────────────────────────────────────

  private static final class Fixture {
    private static final String VERSION = "1.0.0";
    private final Path root;
    private SymbolRepository repository;

    Fixture(Path root) { this.root = root; }

    void build() throws Exception {
      Path localRepo = root.resolve("m2");
      createArtifact(localRepo);

      Path pomDir = root.resolve("workspace");
      Files.createDirectories(pomDir);
      Path pom = pomDir.resolve("pom.xml");
      Files.writeString(pom, String.format("""
          <project xmlns="http://maven.apache.org/POM/4.0.0"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>fixture</artifactId>
            <version>%s</version>
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>fixture</artifactId>
                <version>%s</version>
              </dependency>
            </dependencies>
          </project>""", VERSION, VERSION));

      ServerConfig config = new ServerConfig(
          root.resolve("cache").toString(),
          localRepo.toString(),
          List.of(new ProjectConfig(null, pom.toString(), null)),
          "info");
      repository = new SymbolRepository(config);
      repository.refresh();
    }

    SymbolRepository repository() { return repository; }

    private void createArtifact(Path localRepo) throws IOException {
      Map<String, byte[]> classes = Map.of(
          "com/example/alpha/Widget.class", emptyClass("com/example/alpha/Widget"),
          "com/example/beta/Widget.class",  emptyClass("com/example/beta/Widget"),
          "com/example/alpha/Gadget.class", emptyClass("com/example/alpha/Gadget"));
      Path dir = localRepo.resolve(Path.of("com", "example", "fixture", VERSION));
      Files.createDirectories(dir);
      try (JarOutputStream jar = new JarOutputStream(
          Files.newOutputStream(dir.resolve("fixture-" + VERSION + ".jar")))) {
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
          jar.putNextEntry(new JarEntry(e.getKey()));
          jar.write(e.getValue());
          jar.closeEntry();
        }
      }
    }

    private byte[] emptyClass(String internalName) {
      ClassWriter cw = new ClassWriter(0);
      cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
      cw.visitEnd();
      return cw.toByteArray();
    }
  }
}
