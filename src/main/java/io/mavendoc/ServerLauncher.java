package io.mavendoc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.mavendoc.config.ServerConfig;
import io.mavendoc.model.ToolRequest;
import io.mavendoc.model.ToolResult;
import io.mavendoc.services.CacheService;
import io.mavendoc.services.SymbolRepository;
import io.mavendoc.services.TextRenderer;
import io.mavendoc.services.ToolRequestHandler;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point for the Maven Deps Inspector MCP server.
 *
 * <p>Usage:
 * <pre>
 *   java -jar maven-deps-inspector-mcp.jar --config /path/to/config.json
 * </pre>
 * Or set the {@code MAVEN_DEPS_INSPECTOR_CONFIG} environment variable.
 */
public final class ServerLauncher {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerLauncher.class);
  private static final String SERVER_NAME = "Maven Deps Inspector";
  private static final String SERVER_VERSION = "1.0.0";

  public static void main(String[] args) throws Exception {
    String configPath = null;
    for (int i = 0; i < args.length; i++) {
      if ("--config".equals(args[i]) && i + 1 < args.length) {
        configPath = args[++i];
      }
    }
    if (configPath == null || configPath.isBlank()) {
      configPath = System.getenv("MAVEN_DEPS_INSPECTOR_CONFIG");
    }
    if (configPath == null || configPath.isBlank()) {
      throw new IllegalArgumentException(
          "Missing configuration. Use --config <path> or set MAVEN_DEPS_INSPECTOR_CONFIG.");
    }

    ObjectMapper mapper = JsonMapper.builder().build();
    ServerConfig config = mapper.readValue(Path.of(configPath).toFile(), ServerConfig.class);

    CacheService cacheService = new CacheService(Path.of(config.cachePath()));
    cacheService.initialize();

    SymbolRepository repository = new SymbolRepository(config);
    repository.refresh();
    TextRenderer renderer = new TextRenderer(repository);
    ToolRequestHandler handler = new ToolRequestHandler(repository, renderer, cacheService);

    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    executor.scheduleAtFixedRate(repository::refresh, 5, 5, TimeUnit.MINUTES);

    McpSyncServer server = createServer(handler, mapper);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try { server.closeGracefully(); } catch (Exception e) { LOGGER.warn("Shutdown error", e); }
      executor.shutdownNow();
    }));

    try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private static McpSyncServer createServer(ToolRequestHandler handler, ObjectMapper mapper) {
    var jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    var transport = new StdioServerTransportProvider(jsonMapper);
    var capabilities = ServerCapabilities.builder().tools(true).logging().resources(false, false).build();

    List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
    tools.add(tool("getClassDocumentation",
        "Returns class signature information including fields, methods, inheritance, and coordinates.",
        classSchema(), handler, mapper));
    tools.add(tool("getMethodDocumentation",
        "Returns method signature including parameters, return type, and override information.",
        methodSchema(), handler, mapper));
    tools.add(tool("getFieldDocumentation",
        "Returns field signature including type, modifiers, and whether it is inherited.",
        fieldSchema(), handler, mapper));
    tools.add(tool("searchDependencies",
        "Lists all indexed classes matching a simple name, with GAV coordinates.",
        searchSchema(), handler, mapper));
    tools.add(tool("clearCache",
        "Clears the cached documentation entries.",
        emptySchema(), handler, mapper));

    return McpServer.sync(transport)
        .serverInfo(new McpSchema.Implementation(SERVER_NAME, SERVER_VERSION))
        .jsonMapper(jsonMapper)
        .capabilities(capabilities)
        .tools(tools)
        .build();
  }

  private static McpServerFeatures.SyncToolSpecification tool(String name, String description,
      JsonSchema schema, ToolRequestHandler handler, ObjectMapper mapper) {
    McpSchema.Tool t = McpSchema.Tool.builder().name(name).description(description)
        .inputSchema(schema).build();
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(t)
        .callHandler((exchange, req) -> dispatch(handler, mapper, name, req, exchange))
        .build();
  }

  private static CallToolResult dispatch(ToolRequestHandler handler, ObjectMapper mapper,
      String toolName, McpSchema.CallToolRequest req, McpSyncServerExchange exchange) {
    JsonNode params = mapper.valueToTree(req.arguments() == null ? Map.of() : req.arguments());
    ToolResult result = handler.handle(new ToolRequest(UUID.randomUUID().toString(), toolName, params, exchange));
    return toCallToolResult(result);
  }

  private static CallToolResult toCallToolResult(ToolResult result) {
    return CallToolResult.builder()
        .isError(!result.success())
        .addContent(new TextContent(result.toJson()))
        .build();
  }

  // ── schema builders ───────────────────────────────────────────────────────

  private static JsonSchema classSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("className",   str("Simple class name (e.g. StringUtils). Required unless classFqn is provided."));
    props.put("classFqn",    str("Fully-qualified class name. Bypasses simple-name lookup."));
    props.put("basePackage", str("Optional package prefix to narrow matches (e.g. org.apache.commons)."));
    props.put("groupId",     str("Optional Maven groupId override."));
    props.put("artifactId",  str("Optional Maven artifactId."));
    props.put("version",     str("Optional Maven version."));
    return new JsonSchema("object", props, List.of(), false, null, null);
  }

  private static JsonSchema methodSchema() {
    Map<String, Object> props = new LinkedHashMap<>(classSchema().properties());
    props.put("methodName",        str("Method name to document."));
    props.put("overloadSignature", str("Optional parameter types to pick an overload, e.g. (java.lang.String,int)."));
    return new JsonSchema("object", props, List.of("methodName"), false, null, null);
  }

  private static JsonSchema fieldSchema() {
    Map<String, Object> props = new LinkedHashMap<>(classSchema().properties());
    props.put("fieldName", str("Field name to document."));
    return new JsonSchema("object", props, List.of("fieldName"), false, null, null);
  }

  private static JsonSchema searchSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("className",   str("Simple class name to search for."));
    props.put("basePackage", str("Optional package prefix to filter results."));
    return new JsonSchema("object", props, List.of("className"), false, null, null);
  }

  private static JsonSchema emptySchema() {
    return new JsonSchema("object", Map.of(), List.of(), false, null, null);
  }

  private static Map<String, Object> str(String description) {
    return Map.of("type", "string", "description", description);
  }
}
