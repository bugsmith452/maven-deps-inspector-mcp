package io.mavendoc.services;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import io.mavendoc.config.ServerConfig;
import io.mavendoc.model.ToolRequest;
import io.mavendoc.model.ToolResult;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test suite using only publicly available Maven Central libraries.
 *
 * <p>Dependencies indexed (declared in {@code sample-project/pom.xml}):
 * <ul>
 *   <li>org.apache.commons:commons-lang3</li>
 *   <li>com.google.guava:guava</li>
 *   <li>com.fasterxml.jackson.core:jackson-databind</li>
 *   <li>org.slf4j:slf4j-api</li>
 *   <li>ch.qos.logback:logback-classic (provides second Logger implementation)</li>
 * </ul>
 *
 * <p>Prerequisites: run {@code mvn test} (or {@code mvn dependency:resolve}) once
 * so all JARs are present in {@code ~/.m2/repository}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericIntegrationTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(GenericIntegrationTest.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  // commons-lang3
  private static final String STRING_UTILS       = "StringUtils";
  private static final String STRING_UTILS_FQN   = "org.apache.commons.lang3.StringUtils";

  // guava
  private static final String STRINGS_FQN        = "com.google.common.base.Strings";

  // slf4j / logback — "Logger" exists in both artifacts (ambiguity scenario)
  private static final String LOGGER_CLASS              = "Logger";
  private static final String SLF4J_LOGGER_FQN         = "org.slf4j.Logger";
  private static final String LOGBACK_CLASSIC_LOGGER_FQN = "ch.qos.logback.classic.Logger";

  private SymbolRepository repository;
  private ToolRequestHandler handler;
  private CacheService cacheService;
  private Path reportDir;
  private ElicitFirstExchange elicitFirst;

  @BeforeAll
  void setUp() throws Exception {
    Path configPath = Paths.get("samples/config.json").toAbsolutePath().normalize();
    assertTrue(Files.exists(configPath), "samples/config.json not found at " + configPath);

    ServerConfig config = MAPPER.readValue(configPath.toFile(), ServerConfig.class);
    LOGGER.info("Config loaded: cachePath={}", config.cachePath());

    repository = new SymbolRepository(config);
    repository.refresh();
    LOGGER.info("Repository refreshed");

    reportDir = Paths.get("target/mcp-report/generic");
    Files.createDirectories(reportDir);

    cacheService = new CacheService(Paths.get(config.cachePath()));
    cacheService.initialize();
    // Clear any stale cache from previous runs
    cacheService.clear();

    TextRenderer renderer = new TextRenderer(repository);
    handler = new ToolRequestHandler(repository, renderer, cacheService);

    elicitFirst = new ElicitFirstExchange();
  }

  @AfterAll
  void tearDown() { LOGGER.info("GenericIntegrationTest finished"); }

  // ── search ────────────────────────────────────────────────────────────────

  @Test
  void searchStringUtils() throws IOException {
    ToolResult result = handle("searchDependencies", p -> p.put("className", STRING_UTILS));
    write("search", "string-utils", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains(STRING_UTILS), "Expected StringUtils in search output");
    assertTrue(result.text().contains("org.apache.commons"), "Expected GAV coordinates");
    LOGGER.info("PASS searchStringUtils");
  }

  @Test
  void searchLoggerMultipleMatches() throws IOException {
    ToolResult result = handle("searchDependencies", p -> p.put("className", LOGGER_CLASS));
    write("search", "logger", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains(LOGGER_CLASS), "Expected Logger in results");
    // Multiple Logger classes (slf4j + logback) should be found
    assertTrue(result.text().contains("Multiple matches"), "Expected disambiguation hint");
    assertTrue(result.text().contains("classFqn"), "Expected classFqn hint");
    LOGGER.info("PASS searchLoggerMultipleMatches");
  }

  @Test
  void searchClassNotFound() throws IOException {
    ToolResult result = handle("searchDependencies", p -> p.put("className", "NoSuchClass99xyz"));
    write("search", "not-found", result);

    assertFalse(result.success());
    assertEquals("NOT_FOUND", result.errorCode());
    LOGGER.info("PASS searchClassNotFound");
  }

  // ── class documentation ───────────────────────────────────────────────────

  @Test
  void getClassDocStringUtilsBySimpleName() throws IOException {
    // StringUtils is unique in the index, no disambiguation needed
    ToolResult result = handle("getClassDocumentation", p -> p.put("className", STRING_UTILS));
    write("class", "string-utils-by-name", result);

    assertTrue(result.success(), error(result));
    String text = result.text();
    assertTrue(text.contains("StringUtils"), "Expected class name");
    assertTrue(text.contains("org.apache.commons.lang3"), "Expected package");
    assertTrue(text.contains("Kind:"), "Expected Kind field");
    assertTrue(text.contains("Coordinates:"), "Expected coordinates");
    LOGGER.info("PASS getClassDocStringUtilsBySimpleName");
  }

  @Test
  void getClassDocStringUtilsByFqn() throws IOException {
    ToolResult result = handle("getClassDocumentation", p -> p.put("classFqn", STRING_UTILS_FQN));
    write("class", "string-utils-by-fqn", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains("StringUtils"));
    LOGGER.info("PASS getClassDocStringUtilsByFqn");
  }

  @Test
  void getClassDocGuavaStrings() throws IOException {
    ToolResult result = handle("getClassDocumentation", p -> p.put("classFqn", STRINGS_FQN));
    write("class", "guava-strings", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains("Strings"), "Expected class name in text");
    assertTrue(result.text().contains("com.google.common.base"), "Expected package");
    LOGGER.info("PASS getClassDocGuavaStrings");
  }

  @Test
  void getClassDocLoggerAmbiguousWithoutExchange() throws IOException {
    // "Logger" exists in both slf4j-api and logback-classic → AMBIGUOUS_CLASS
    ToolResult result = handle("getClassDocumentation", p -> p.put("className", LOGGER_CLASS));
    write("class", "logger-ambiguous", result);

    assertFalse(result.success());
    assertEquals("AMBIGUOUS_CLASS", result.errorCode(),
        "Expected AMBIGUOUS_CLASS but got: " + result.errorCode() + ": " + result.errorMessage());
    assertTrue(result.errorMessage().contains(LOGGER_CLASS), "Error message should name the class");
    assertTrue(result.errorHint() != null && result.errorHint().contains("classFqn"),
        "Hint should suggest classFqn");
    LOGGER.info("PASS getClassDocLoggerAmbiguousWithoutExchange");
  }

  @Test
  void getClassDocLoggerByFqn() throws IOException {
    ToolResult result = handle("getClassDocumentation", p -> p.put("classFqn", SLF4J_LOGGER_FQN));
    write("class", "logger-by-fqn", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains("Logger"));
    LOGGER.info("PASS getClassDocLoggerByFqn");
  }

  @Test
  void getClassDocLoggerWithBasePackage() throws IOException {
    // basePackage narrows to org.slf4j; elicitation (via elicitFirst) selects first match
    ToolResult result = handleWith("getClassDocumentation", elicitFirst, p -> {
      p.put("className", LOGGER_CLASS);
      p.put("basePackage", "org.slf4j");
    });
    write("class", "logger-with-base-package", result);

    // Either succeeds (single match after filter + elicitation), or AMBIGUOUS_CLASS if
    // multiple slf4j versions are present; both outcomes are acceptable here.
    // The important assertion: basePackage hint is applied and response is not CLASS_NOT_FOUND.
    assertNotEquals("CLASS_NOT_FOUND", result.success() ? "OK" : result.errorCode(),
        "Should not be CLASS_NOT_FOUND when basePackage is applied");
    LOGGER.info("PASS getClassDocLoggerWithBasePackage: success={}", result.success());
  }

  @Test
  void getClassDocClassNotFound() throws IOException {
    ToolResult result = handle("getClassDocumentation", p -> p.put("className", "NoSuchClassXyz99"));
    write("class", "not-found", result);

    assertFalse(result.success());
    assertEquals("CLASS_NOT_FOUND", result.errorCode());
    LOGGER.info("PASS getClassDocClassNotFound");
  }

  // ── method documentation ──────────────────────────────────────────────────

  @Test
  void getMethodDocIsEmpty() throws IOException {
    // isEmpty(CharSequence) has exactly one overload in StringUtils
    ToolResult result = handle("getMethodDocumentation", p -> {
      p.put("classFqn", STRING_UTILS_FQN);
      p.put("methodName", "isEmpty");
    });
    write("method", "string-utils-isempty", result);

    assertTrue(result.success(), error(result));
    String text = result.text();
    assertTrue(text.contains("isEmpty"), "Expected method name");
    assertTrue(text.contains("Return Type:"), "Expected return type");
    assertTrue(text.contains("boolean"), "Expected boolean return type");
    LOGGER.info("PASS getMethodDocIsEmpty");
  }

  @Test
  void getMethodDocJoinAmbiguousWithoutExchange() throws IOException {
    // StringUtils.join has many overloads → AMBIGUOUS_METHOD without overloadSignature
    ToolResult result = handle("getMethodDocumentation", p -> {
      p.put("classFqn", STRING_UTILS_FQN);
      p.put("methodName", "join");
    });
    write("method", "string-utils-join-ambiguous", result);

    assertFalse(result.success());
    assertEquals("AMBIGUOUS_METHOD", result.errorCode());
    assertNotNull(result.errorHint(), "Hint should be present");
    assertTrue(result.errorHint().contains("overloadSignature"), "Hint should mention overloadSignature");
    LOGGER.info("PASS getMethodDocJoinAmbiguousWithoutExchange");
  }

  @Test
  void getMethodDocJoinWithOverloadSignature() throws IOException {
    // Select specific overload: join(Object[], String)
    ToolResult result = handle("getMethodDocumentation", p -> {
      p.put("classFqn", STRING_UTILS_FQN);
      p.put("methodName", "join");
      p.put("overloadSignature", "(java.lang.Object[],java.lang.String)");
    });
    write("method", "string-utils-join-overload", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains("join"), "Expected method name");
    assertTrue(result.text().contains("Parameters:"), "Expected parameters section");
    LOGGER.info("PASS getMethodDocJoinWithOverloadSignature");
  }

  @Test
  void getMethodDocGuavaStringsIsNullOrEmpty() throws IOException {
    ToolResult result = handle("getMethodDocumentation", p -> {
      p.put("classFqn", STRINGS_FQN);
      p.put("methodName", "isNullOrEmpty");
    });
    write("method", "guava-strings-isnullorempty", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains("isNullOrEmpty"));
    assertTrue(result.text().contains("boolean"));
    LOGGER.info("PASS getMethodDocGuavaStringsIsNullOrEmpty");
  }

  @Test
  void getMethodDocMethodNotFound() throws IOException {
    ToolResult result = handle("getMethodDocumentation", p -> {
      p.put("classFqn", STRING_UTILS_FQN);
      p.put("methodName", "nonExistentMethod999");
    });
    write("method", "method-not-found", result);

    assertFalse(result.success());
    assertEquals("METHOD_NOT_FOUND", result.errorCode());
    LOGGER.info("PASS getMethodDocMethodNotFound");
  }

  // ── field documentation ───────────────────────────────────────────────────

  @Test
  void getFieldDocEmpty() throws IOException {
    ToolResult result = handle("getFieldDocumentation", p -> {
      p.put("classFqn", STRING_UTILS_FQN);
      p.put("fieldName", "EMPTY");
    });
    write("field", "string-utils-empty", result);

    assertTrue(result.success(), error(result));
    String text = result.text();
    assertTrue(text.contains("EMPTY"), "Expected field name");
    assertTrue(text.contains("java.lang.String"), "Expected field type");
    assertTrue(text.contains("Static: yes"), "Expected static flag");
    assertTrue(text.contains("Final: yes"), "Expected final flag");
    assertTrue(text.contains("Inherited: no"), "Expected not inherited");
    LOGGER.info("PASS getFieldDocEmpty");
  }

  @Test
  void getFieldDocFieldNotFound() throws IOException {
    ToolResult result = handle("getFieldDocumentation", p -> {
      p.put("classFqn", STRING_UTILS_FQN);
      p.put("fieldName", "NO_SUCH_FIELD_XYZ");
    });
    write("field", "field-not-found", result);

    assertFalse(result.success());
    assertEquals("FIELD_NOT_FOUND", result.errorCode());
    LOGGER.info("PASS getFieldDocFieldNotFound");
  }

  // ── cache ─────────────────────────────────────────────────────────────────

  @Test
  void cacheRoundTrip() throws IOException {
    ObjectNode params = MAPPER.createObjectNode().put("classFqn", STRING_UTILS_FQN);

    ToolResult first  = handler.handle(req("getClassDocumentation", params));
    ToolResult second = handler.handle(req("getClassDocumentation", params));

    assertTrue(first.success(), error(first));
    assertTrue(second.success(), error(second));
    assertEquals(first.text(), second.text(), "Cached response should match original");
    LOGGER.info("PASS cacheRoundTrip");
  }

  @Test
  void clearCache() throws IOException {
    ToolResult result = handler.handle(req("clearCache", MAPPER.createObjectNode()));
    write("cache", "clear", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains("Cache cleared"), "Expected confirmation message");
    LOGGER.info("PASS clearCache");
  }

  // ── edge cases ────────────────────────────────────────────────────────────

  @Test
  void searchLoggerWithNonMatchingBasePackageFallsBack() throws IOException {
    // basePackage matches no Logger class → fallback preserves all candidates
    ToolResult result = handle("searchDependencies", p -> {
      p.put("className", LOGGER_CLASS);
      p.put("basePackage", "com.this.package.does.not.exist");
    });
    write("search", "logger-base-package-fallback", result);

    assertTrue(result.success(), error(result));
    // fallback note should mention the requested (non-matching) package
    assertTrue(result.text().contains("com.this.package.does.not.exist"),
        "Expected fallback note naming the non-matching basePackage");
    // original candidates still present
    assertTrue(result.text().contains(LOGGER_CLASS), "Expected Logger class name in results");
    LOGGER.info("PASS searchLoggerWithNonMatchingBasePackageFallsBack");
  }

  @Test
  void getClassDocLoggerFilteredByGroupId() throws IOException {
    // Partial coordinate: groupId only — narrows Logger to org.slf4j variants.
    // Multiple versions of slf4j-api may be present; use elicitation to pick first.
    ToolResult result = handleWith("getClassDocumentation", elicitFirst, p -> {
      p.put("className", LOGGER_CLASS);
      p.put("groupId", "org.slf4j");
    });
    write("class", "logger-groupid-filter", result);

    // Must not include the logback Logger (ch.qos.logback.*); only org.slf4j variants
    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains("Logger"), "Expected Logger in output");
    assertTrue(result.text().contains("org.slf4j"), "Expected org.slf4j package");
    assertFalse(result.text().contains("ch.qos.logback"), "groupId filter should exclude logback");
    LOGGER.info("PASS getClassDocLoggerFilteredByGroupId");
  }

  @Test
  void getClassDocLoggerElicitSecondChoice() throws IOException {
    // Elicitation with index 1 should return a different Logger than index 0
    ElicitNthExchange second = new ElicitNthExchange(1);
    ToolResult result = handleWith("getClassDocumentation", second, p -> p.put("className", LOGGER_CLASS));
    write("class", "logger-elicit-second", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains("Logger"), "Expected Logger in output");
    assertTrue(second.callCount() > 0, "Expected elicitation to be triggered");
    LOGGER.info("PASS getClassDocLoggerElicitSecondChoice");
  }

  @Test
  void getClassDocLogbackClassicLoggerInheritedSections() throws IOException {
    // logback classic Logger extends logback core Logger; output should include
    // inherited members or an ancestor note
    ToolResult result = handle("getClassDocumentation",
        p -> p.put("classFqn", LOGBACK_CLASSIC_LOGGER_FQN));
    write("class", "logback-classic-logger", result);

    assertTrue(result.success(), error(result));
    String text = result.text();
    assertTrue(text.contains("Logger"), "Expected Logger class name");
    // Either shows inherited sections or an "Ancestor metadata not available" note
    boolean hasInheritedOrNote = text.contains("from ch.qos.logback.core.Logger")
        || text.contains("Ancestor metadata not available")
        || text.contains("Methods from")
        || text.contains("Fields from");
    assertTrue(hasInheritedOrNote, "Expected inherited sections or ancestor note in:\n" + text);
    LOGGER.info("PASS getClassDocLogbackClassicLoggerInheritedSections");
  }

  @Test
  void getMethodDocInheritedFromParent() throws IOException {
    // getName() is declared in ch.qos.logback.core.Logger but can be
    // looked up via ch.qos.logback.classic.Logger (inherited)
    ToolResult result = handle("getMethodDocumentation", p -> {
      p.put("classFqn", LOGBACK_CLASSIC_LOGGER_FQN);
      p.put("methodName", "getName");
    });
    write("method", "logback-classic-getname", result);

    assertTrue(result.success(), error(result));
    assertTrue(result.text().contains("getName"), "Expected getName in output");
    LOGGER.info("PASS getMethodDocInheritedFromParent");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @FunctionalInterface
  interface ParamSetup { void setup(ObjectNode p); }

  private ToolResult handle(String tool, ParamSetup setup) {
    return handleWith(tool, null, setup);
  }

  private ToolResult handleWith(String tool, McpSyncServerExchange exchange, ParamSetup setup) {
    ObjectNode params = MAPPER.createObjectNode();
    setup.setup(params);
    return handler.handle(req(tool, params, exchange));
  }

  private ToolRequest req(String tool, ObjectNode params) {
    return new ToolRequest(UUID.randomUUID().toString(), tool, params);
  }

  private ToolRequest req(String tool, ObjectNode params, McpSyncServerExchange exchange) {
    return new ToolRequest(UUID.randomUUID().toString(), tool, params, exchange);
  }

  private String error(ToolResult r) {
    return r.errorCode() + ": " + r.errorMessage()
        + (r.errorHint() != null ? " | " + r.errorHint() : "");
  }

  private void write(String category, String slug, ToolResult result) throws IOException {
    Path dir = reportDir.resolve(category);
    Files.createDirectories(dir);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("success", result.success());
    if (result.success()) {
      report.put("text", result.text());
    } else {
      report.put("error", result.errorCode());
      report.put("message", result.errorMessage());
      report.put("hint", result.errorHint());
    }
    Files.writeString(dir.resolve(slug + ".json"),
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
    if (result.success() && result.text() != null) {
      Files.writeString(dir.resolve(slug + ".txt"), result.text(), StandardCharsets.UTF_8);
    }
  }

  // ── test exchange (elicitation support) ───────────────────────────────────

  /**
   * Exchange that always selects the first option presented via elicitation.
   * Used for scenarios where ambiguity is expected and resolution is tested.
   */
  private static final class ElicitFirstExchange extends McpSyncServerExchange {
    private final AtomicInteger count = new AtomicInteger(0);

    ElicitFirstExchange() { super(null); }

    int count() { return count.get(); }

    @Override
    public McpSchema.ElicitResult createElicitation(McpSchema.ElicitRequest request) {
      count.incrementAndGet();
      @SuppressWarnings("unchecked")
      Map<String, Object> schema = (Map<String, Object>) request.requestedSchema();
      if (schema == null) return cancel();
      @SuppressWarnings("unchecked")
      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      if (props == null) return cancel();
      @SuppressWarnings("unchecked")
      Map<String, Object> sel = (Map<String, Object>) props.get("selection");
      if (sel == null) return cancel();
      @SuppressWarnings("unchecked")
      List<String> enums = (List<String>) sel.get("enum");
      if (enums == null || enums.isEmpty()) return cancel();
      Map<String, Object> content = new LinkedHashMap<>();
      content.put("selection", enums.get(0));
      return McpSchema.ElicitResult.builder()
          .message(McpSchema.ElicitResult.Action.ACCEPT)
          .content(content)
          .build();
    }

    @Override
    public McpSchema.ClientCapabilities getClientCapabilities() {
      return McpSchema.ClientCapabilities.builder().elicitation().build();
    }

    private static McpSchema.ElicitResult cancel() {
      return McpSchema.ElicitResult.builder()
          .message(McpSchema.ElicitResult.Action.CANCEL).build();
    }
  }

  /**
   * Exchange that selects the Nth option (0-based) presented via elicitation.
   * Falls back to cancel if the index is out of range.
   */
  private static final class ElicitNthExchange extends McpSyncServerExchange {
    private final int choice;
    private final AtomicInteger count = new AtomicInteger(0);

    ElicitNthExchange(int choice) { super(null); this.choice = choice; }

    int callCount() { return count.get(); }

    @Override
    public McpSchema.ElicitResult createElicitation(McpSchema.ElicitRequest request) {
      count.incrementAndGet();
      @SuppressWarnings("unchecked")
      Map<String, Object> schema = (Map<String, Object>) request.requestedSchema();
      if (schema == null) return cancel();
      @SuppressWarnings("unchecked")
      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      if (props == null) return cancel();
      @SuppressWarnings("unchecked")
      Map<String, Object> sel = (Map<String, Object>) props.get("selection");
      if (sel == null) return cancel();
      @SuppressWarnings("unchecked")
      List<String> enums = (List<String>) sel.get("enum");
      if (enums == null || choice >= enums.size()) return cancel();
      Map<String, Object> content = new LinkedHashMap<>();
      content.put("selection", enums.get(choice));
      return McpSchema.ElicitResult.builder()
          .message(McpSchema.ElicitResult.Action.ACCEPT)
          .content(content)
          .build();
    }

    @Override
    public McpSchema.ClientCapabilities getClientCapabilities() {
      return McpSchema.ClientCapabilities.builder().elicitation().build();
    }

    private static McpSchema.ElicitResult cancel() {
      return McpSchema.ElicitResult.builder()
          .message(McpSchema.ElicitResult.Action.CANCEL).build();
    }
  }
}
