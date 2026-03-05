package io.mavendoc.services;

import io.mavendoc.model.DependencyCoordinates;
import io.mavendoc.model.ToolRequest;
import io.mavendoc.model.ToolResult;
import io.mavendoc.services.SymbolRepository.ClassLocation;
import io.mavendoc.services.SymbolRepository.ClassSymbol;
import io.mavendoc.services.SymbolRepository.FieldInfo;
import io.mavendoc.services.SymbolRepository.MatchResult;
import io.mavendoc.services.SymbolRepository.MethodInfo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes MCP tool invocations to the appropriate service methods.
 *
 * <p>Class resolution order (for simple-name lookups):
 * <ol>
 *   <li>If {@code classFqn} provided → direct FQN lookup.</li>
 *   <li>Match simple name; apply optional {@code basePackage} filter.</li>
 *   <li>Apply coordinate overrides (groupId / artifactId / version).</li>
 *   <li>If multiple remain and client supports elicitation → interactive dialog.</li>
 *   <li>Otherwise → {@code AMBIGUOUS_CLASS} error with FQN hint.</li>
 * </ol>
 */
public final class ToolRequestHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ToolRequestHandler.class);

  private final SymbolRepository repository;
  private final TextRenderer renderer;
  private final CacheService cache;
  private final ObjectMapper mapper = JsonMapper.builder().build();

  public ToolRequestHandler(SymbolRepository repository, TextRenderer renderer, CacheService cache) {
    this.repository = repository;
    this.renderer = renderer;
    this.cache = cache;
  }

  public ToolResult handle(ToolRequest request) {
    return switch (request.tool()) {
      case "getClassDocumentation"  -> handleClass(request);
      case "getMethodDocumentation" -> handleMethod(request);
      case "getFieldDocumentation"  -> handleField(request);
      case "searchDependencies"     -> handleSearch(request);
      case "clearCache"             -> handleClearCache(request);
      default -> ToolResult.failure(request.id(), "UNKNOWN_TOOL",
          "Unknown tool: " + request.tool(), null);
    };
  }

  // ── tool handlers ─────────────────────────────────────────────────────────

  private ToolResult handleClass(ToolRequest request) {
    JsonNode p = request.params();
    String className = text(p, "className");
    String classFqn  = text(p, "classFqn");
    if (blank(className) && blank(classFqn)) {
      return invalidInput(request.id(), "className (or classFqn) is required");
    }
    if (blank(className)) className = classFqn;
    String basePackage = text(p, "basePackage");
    ClassResolution resolution = resolveClass(request, className,
        Optional.ofNullable(basePackage), Optional.ofNullable(classFqn), coordinatesFrom(p));
    if (resolution instanceof ClassResolution.Missing) {
      return notFound(request.id(), "CLASS_NOT_FOUND", className);
    }
    if (resolution instanceof ClassResolution.Ambiguous amb) {
      return ambiguousClass(request.id(), className, amb.candidates(), amb.matchResult());
    }
    ResolvedClass resolved = ((ClassResolution.Found) resolution).cls();
    String cacheKey = cacheKey("class", resolved.location().className(),
        resolved.location().coordinates(), null);
    Optional<String> cached = cache.read(cacheKey);
    if (cached.isPresent()) {
      return ToolResult.success(request.id(), prependNote(resolved.note(), cached.get()));
    }
    ClassSymbol symbol = repository.loadSymbol(resolved.location());
    String text = renderer.renderClass(symbol);
    cache.write(cacheKey, text);
    return ToolResult.success(request.id(), prependNote(resolved.note(), text));
  }

  private ToolResult handleMethod(ToolRequest request) {
    JsonNode p = request.params();
    String className  = text(p, "className");
    String classFqn   = text(p, "classFqn");
    String methodName = text(p, "methodName");
    if (blank(className) && blank(classFqn)) {
      return invalidInput(request.id(), "className (or classFqn) and methodName are required");
    }
    if (blank(methodName)) {
      return invalidInput(request.id(), "methodName is required");
    }
    if (blank(className)) className = classFqn;
    String basePackage = text(p, "basePackage");
    ClassResolution resolution = resolveClass(request, className,
        Optional.ofNullable(basePackage), Optional.ofNullable(classFqn), coordinatesFrom(p));
    if (resolution instanceof ClassResolution.Missing) {
      return notFound(request.id(), "CLASS_NOT_FOUND", className);
    }
    if (resolution instanceof ClassResolution.Ambiguous amb) {
      return ambiguousClass(request.id(), className, amb.candidates(), amb.matchResult());
    }
    ResolvedClass resolved = ((ClassResolution.Found) resolution).cls();
    ClassSymbol symbol = repository.loadSymbol(resolved.location());

    List<MethodMatch> matches = collectMethods(symbol, methodName);
    if (matches.isEmpty()) {
      return ToolResult.failure(request.id(), "METHOD_NOT_FOUND",
          "Method '" + methodName + "' not found in " + symbol.className(), null);
    }
    String overloadSig = text(p, "overloadSignature");
    if (!blank(overloadSig)) {
      matches = matches.stream()
          .filter(m -> descriptorMatches(overloadSig, m.method().descriptor()))
          .collect(Collectors.toList());
      if (matches.isEmpty()) {
        return ToolResult.failure(request.id(), "METHOD_NOT_FOUND",
            "No overload of '" + methodName + "' matches signature " + overloadSig, null);
      }
    }
    MethodMatch match;
    if (matches.size() > 1) {
      Optional<MethodMatch> elicited = elicitMethod(request, resolved.location().className(), methodName, matches);
      if (elicited.isEmpty()) {
        String sigs = matches.stream()
            .map(m -> formatDescriptor(m.method().descriptor())).collect(Collectors.joining(", "));
        return ToolResult.failure(request.id(), "AMBIGUOUS_METHOD",
            "Multiple overloads of '" + methodName + "' found: " + sigs,
            "Provide overloadSignature to disambiguate, e.g. overloadSignature: \"(java.lang.String)\"");
      }
      match = elicited.get();
    } else {
      match = matches.get(0);
    }
    String descriptor = methodToken(match);
    String cacheKey = cacheKey("method", resolved.location().className(),
        resolved.location().coordinates(), descriptor);
    Optional<String> cached = cache.read(cacheKey);
    if (cached.isPresent()) {
      return ToolResult.success(request.id(), prependNote(resolved.note(), cached.get()));
    }
    boolean overrides = match.declaredIn().equals(symbol.className())
        && overridesParent(symbol, match.method());
    String text = renderer.renderMethod(symbol, match.method(), match.declaredIn(), overrides);
    cache.write(cacheKey, text);
    return ToolResult.success(request.id(), prependNote(resolved.note(), text));
  }

  private ToolResult handleField(ToolRequest request) {
    JsonNode p = request.params();
    String className = text(p, "className");
    String classFqn  = text(p, "classFqn");
    String fieldName = text(p, "fieldName");
    if (blank(className) && blank(classFqn)) {
      return invalidInput(request.id(), "className (or classFqn) and fieldName are required");
    }
    if (blank(fieldName)) {
      return invalidInput(request.id(), "fieldName is required");
    }
    if (blank(className)) className = classFqn;
    String basePackage = text(p, "basePackage");
    ClassResolution resolution = resolveClass(request, className,
        Optional.ofNullable(basePackage), Optional.ofNullable(classFqn), coordinatesFrom(p));
    if (resolution instanceof ClassResolution.Missing) {
      return notFound(request.id(), "CLASS_NOT_FOUND", className);
    }
    if (resolution instanceof ClassResolution.Ambiguous amb) {
      return ambiguousClass(request.id(), className, amb.candidates(), amb.matchResult());
    }
    ResolvedClass resolved = ((ClassResolution.Found) resolution).cls();
    ClassSymbol symbol = repository.loadSymbol(resolved.location());
    List<FieldMatch> matches = collectFields(symbol, fieldName);
    if (matches.isEmpty()) {
      return ToolResult.failure(request.id(), "FIELD_NOT_FOUND",
          "Field '" + fieldName + "' not found in " + symbol.className(), null);
    }
    FieldMatch match = matches.get(0);
    String cacheKey = cacheKey("field", resolved.location().className(),
        resolved.location().coordinates(), match.field().name() + "#" + match.declaredIn());
    Optional<String> cached = cache.read(cacheKey);
    if (cached.isPresent()) {
      return ToolResult.success(request.id(), prependNote(resolved.note(), cached.get()));
    }
    String text = renderer.renderField(symbol, match.field(), match.declaredIn());
    cache.write(cacheKey, text);
    return ToolResult.success(request.id(), prependNote(resolved.note(), text));
  }

  private ToolResult handleSearch(ToolRequest request) {
    JsonNode p = request.params();
    String className = text(p, "className");
    if (blank(className)) return invalidInput(request.id(), "className is required");
    String basePackage = text(p, "basePackage");
    MatchResult matchResult = repository.matches(className, Optional.ofNullable(basePackage));
    List<ClassLocation> matches = matchResult.matches();
    if (matches.isEmpty()) {
      return ToolResult.failure(request.id(), "NOT_FOUND",
          "No classes found matching '" + className + "'", null);
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Found ").append(matches.size()).append(" class")
        .append(matches.size() == 1 ? "" : "es").append(" matching '").append(className).append("'");
    String note = basePackageNote(matchResult, matches.size());
    if (note != null) sb.append(" (").append(note).append(")");
    sb.append(":\n");
    matches.stream()
        .sorted(Comparator.comparing(ClassLocation::className))
        .forEach(loc -> sb.append("  ")
            .append(loc.className()).append(" (").append(loc.coordinates().gav()).append(") [")
            .append(loc.coordinates().type() == null ? "jar" : loc.coordinates().type())
            .append("]\n"));
    if (matches.size() > 1) {
      sb.append("Multiple matches found. Use classFqn to target a specific class.\n");
      sb.append("Example: getClassDocumentation(classFqn: \"")
          .append(matches.get(0).className()).append("\")");
    }
    return ToolResult.success(request.id(), sb.toString());
  }

  private ToolResult handleClearCache(ToolRequest request) {
    try {
      cache.clear();
      return ToolResult.success(request.id(), "Cache cleared at " + cache.rootPath());
    } catch (IOException ex) {
      return ToolResult.failure(request.id(), "CACHE_ERROR", ex.getMessage(), null);
    }
  }

  // ── class resolution ──────────────────────────────────────────────────────

  private ClassResolution resolveClass(ToolRequest request, String className,
      Optional<String> basePackage, Optional<String> classFqn,
      Optional<DependencyCoordinates> coordOverride) {
    if (classFqn.isPresent()) {
      return repository.byClassName(classFqn.get())
          .map(loc -> (ClassResolution) new ClassResolution.Found(new ResolvedClass(loc, null)))
          .orElse(new ClassResolution.Missing());
    }
    MatchResult matchResult = repository.matches(className, basePackage);
    List<ClassLocation> matches = new ArrayList<>(matchResult.matches());
    if (coordOverride.isPresent()) {
      DependencyCoordinates co = coordOverride.get();
      matches = matches.stream()
          .filter(loc ->
              (co.groupId()    == null || loc.coordinates().groupId().equals(co.groupId()))
           && (co.artifactId() == null || loc.coordinates().artifactId().equals(co.artifactId()))
           && (co.version()    == null || loc.coordinates().version().equals(co.version())))
          .collect(Collectors.toList());
    }
    if (matches.isEmpty()) return new ClassResolution.Missing();
    if (matches.size() == 1) {
      return new ClassResolution.Found(new ResolvedClass(matches.get(0),
          basePackageNote(matchResult, 1)));
    }
    Optional<ClassLocation> elicited = elicitClass(request, className, matches, matchResult);
    if (elicited.isPresent()) {
      return new ClassResolution.Found(new ResolvedClass(elicited.get(),
          basePackageNote(matchResult, matches.size())));
    }
    LOGGER.info("Ambiguous class '{}': {} candidates, elicitation unavailable", className, matches.size());
    return new ClassResolution.Ambiguous(List.copyOf(matches), matchResult);
  }

  // ── elicitation ───────────────────────────────────────────────────────────

  private Optional<ClassLocation> elicitClass(ToolRequest request, String className,
      List<ClassLocation> matches, MatchResult matchResult) {
    McpSyncServerExchange exchange = request.exchange();
    if (!elicitationAvailable(exchange)) return Optional.empty();
    StringBuilder msg = new StringBuilder("Multiple classes named '")
        .append(className).append("' were found.\n\n");
    if (matchResult.basePackageApplied() && matchResult.requestedBasePackage() != null) {
      msg.append("(basePackage '").append(matchResult.requestedBasePackage())
          .append("' was applied before listing.)\n\n");
    }
    msg.append("  #  Class  Coordinates  Type\n");
    for (int i = 0; i < matches.size(); i++) {
      ClassLocation c = matches.get(i);
      msg.append("  ").append(i + 1).append("  ").append(c.className())
          .append("  ").append(c.coordinates().gav())
          .append("  ").append(c.coordinates().type() == null ? "jar" : c.coordinates().type())
          .append('\n');
    }
    List<String> enumValues = matches.stream().map(ClassLocation::className).toList();
    Map<String, Object> schema = selectionSchema(enumValues, "Fully qualified class name to document.");
    try {
      ElicitResult result = exchange.createElicitation(
          ElicitRequest.builder().message(msg.toString()).requestedSchema(schema).build());
      if (result == null || result.action() != ElicitResult.Action.ACCEPT || result.content() == null) {
        return Optional.empty();
      }
      Object selected = result.content().get("selection");
      if (selected instanceof String choice) {
        return matches.stream().filter(loc -> loc.className().equals(choice)).findFirst();
      }
    } catch (Exception ex) {
      LOGGER.warn("Class elicitation failed for '{}'", className, ex);
    }
    return Optional.empty();
  }

  private Optional<MethodMatch> elicitMethod(ToolRequest request, String className,
      String methodName, List<MethodMatch> matches) {
    McpSyncServerExchange exchange = request.exchange();
    if (!elicitationAvailable(exchange)) return Optional.empty();
    StringBuilder msg = new StringBuilder("Multiple overloads of '")
        .append(className).append('.').append(methodName).append("' exist.\n\n");
    msg.append("  #  Declaring Class  Signature  Identifier\n");
    List<String> enumValues = new ArrayList<>();
    for (int i = 0; i < matches.size(); i++) {
      MethodMatch m = matches.get(i);
      String token = methodToken(m);
      enumValues.add(token);
      msg.append("  ").append(i + 1).append("  ").append(m.declaredIn())
          .append("  ").append(formatDescriptor(m.method().descriptor()))
          .append("  ").append(token).append('\n');
    }
    Map<String, Object> schema = selectionSchema(enumValues, "Select the method overload to document.");
    try {
      ElicitResult result = exchange.createElicitation(
          ElicitRequest.builder().message(msg.toString()).requestedSchema(schema).build());
      if (result == null || result.action() != ElicitResult.Action.ACCEPT || result.content() == null) {
        return Optional.empty();
      }
      Object selected = result.content().get("selection");
      if (selected instanceof String choice) {
        for (MethodMatch m : matches) {
          if (methodToken(m).equals(choice)) return Optional.of(m);
        }
      }
    } catch (Exception ex) {
      LOGGER.warn("Method elicitation failed for '{}.{}'", className, methodName, ex);
    }
    return Optional.empty();
  }

  private static boolean elicitationAvailable(McpSyncServerExchange exchange) {
    return exchange != null
        && exchange.getClientCapabilities() != null
        && exchange.getClientCapabilities().elicitation() != null;
  }

  private static Map<String, Object> selectionSchema(List<String> enumValues, String description) {
    Map<String, Object> selectionProp = new LinkedHashMap<>();
    selectionProp.put("type", "string");
    selectionProp.put("enum", enumValues);
    selectionProp.put("description", description);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("selection", selectionProp);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", List.of("selection"));
    return schema;
  }

  // ── method / field collection ─────────────────────────────────────────────

  private List<MethodMatch> collectMethods(ClassSymbol symbol, String methodName) {
    List<MethodMatch> result = new ArrayList<>();
    collectMethodsRecursive(symbol, methodName, result, symbol.className());
    return result;
  }

  private void collectMethodsRecursive(ClassSymbol symbol, String methodName,
      List<MethodMatch> out, String declaredIn) {
    for (MethodInfo m : symbol.methods()) {
      if (m.name().equals(methodName)) out.add(new MethodMatch(m, declaredIn));
    }
    repository.tryLoadInternal(symbol.superInternalName())
        .ifPresent(p -> collectMethodsRecursive(p, methodName, out, p.className()));
  }

  private List<FieldMatch> collectFields(ClassSymbol symbol, String fieldName) {
    List<FieldMatch> result = new ArrayList<>();
    collectFieldsRecursive(symbol, fieldName, result, symbol.className());
    return result;
  }

  private void collectFieldsRecursive(ClassSymbol symbol, String fieldName,
      List<FieldMatch> out, String declaredIn) {
    for (FieldInfo f : symbol.fields()) {
      if (f.name().equals(fieldName)) { out.add(new FieldMatch(f, declaredIn)); return; }
    }
    repository.tryLoadInternal(symbol.superInternalName())
        .ifPresent(p -> collectFieldsRecursive(p, fieldName, out, p.className()));
  }

  private boolean overridesParent(ClassSymbol symbol, MethodInfo candidate) {
    Optional<ClassSymbol> parent = repository.tryLoadInternal(symbol.superInternalName());
    while (parent.isPresent()) {
      ClassSymbol p = parent.get();
      for (MethodInfo m : p.methods()) {
        if (m.name().equals(candidate.name()) && m.descriptor().equals(candidate.descriptor())) {
          return true;
        }
      }
      parent = repository.tryLoadInternal(p.superInternalName());
    }
    return false;
  }

  // ── error builders ────────────────────────────────────────────────────────

  private ToolResult notFound(String id, String code, String name) {
    return ToolResult.failure(id, code,
        "No class named '" + name + "' found in indexed dependencies.",
        "Run searchDependencies to browse available classes.");
  }

  private ToolResult ambiguousClass(String id, String className,
      List<ClassLocation> candidates, MatchResult matchResult) {
    StringBuilder sb = new StringBuilder("Multiple classes named '")
        .append(className).append("' were found:\n");
    candidates.stream()
        .sorted(Comparator.comparing(ClassLocation::className))
        .forEach(loc -> sb.append("  ").append(loc.className())
            .append(" (").append(loc.coordinates().gav()).append(")\n"));
    String note = basePackageNote(matchResult, candidates.size());
    if (note != null) sb.append("(").append(note).append(")\n");
    return ToolResult.failure(id, "AMBIGUOUS_CLASS", sb.toString(),
        "Use classFqn to target a specific class. "
        + "Example: getClassDocumentation(classFqn: \""
        + candidates.get(0).className() + "\")");
  }

  private ToolResult invalidInput(String id, String message) {
    return ToolResult.failure(id, "INVALID_INPUT", message, null);
  }

  // ── misc helpers ──────────────────────────────────────────────────────────

  private String basePackageNote(MatchResult mr, int remaining) {
    if (!mr.basePackageApplied() || mr.requestedBasePackage() == null) return null;
    if (mr.basePackageFallback()) {
      return "basePackage '" + mr.requestedBasePackage() + "' matched nothing; showing all "
          + mr.totalCandidates() + " candidates";
    }
    if (mr.basePackageReduced()) {
      return "basePackage '" + mr.requestedBasePackage() + "' reduced "
          + mr.totalCandidates() + " to " + remaining;
    }
    return "basePackage '" + mr.requestedBasePackage() + "' applied across " + remaining;
  }

  private String prependNote(String note, String text) {
    return (note == null || note.isBlank()) ? text : "Note: " + note + "\n\n" + text;
  }

  private String cacheKey(String prefix, String className, DependencyCoordinates coords,
      String qualifier) {
    StringJoiner j = new StringJoiner("#");
    j.add(prefix).add(coords.gav()).add(className);
    if (qualifier != null) j.add(qualifier);
    return j.toString();
  }

  private Optional<DependencyCoordinates> coordinatesFrom(JsonNode node) {
    if (node == null) return Optional.empty();
    String g = text(node, "groupId");
    String a = text(node, "artifactId");
    String v = text(node, "version");
    String t = text(node, "type");
    if (blank(g) && blank(a) && blank(v)) return Optional.empty();
    return Optional.of(new DependencyCoordinates(
        blank(g) ? null : g, blank(a) ? null : a, blank(v) ? null : v, t));
  }

  private String methodToken(MethodMatch match) {
    return match.declaredIn() + "#" + match.method().name() + formatDescriptor(match.method().descriptor());
  }

  private String formatDescriptor(String descriptor) {
    List<String> params = new ArrayList<>();
    for (Type t : Type.getArgumentTypes(descriptor)) params.add(t.getClassName());
    return "(" + String.join(",", params) + ")";
  }

  private boolean descriptorMatches(String requested, String descriptor) {
    return formatDescriptor(descriptor).equalsIgnoreCase(requested.trim());
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.has(field)) return null;
    JsonNode v = node.get(field);
    return v.isNull() ? null : v.asText();
  }

  private static boolean blank(String s) { return s == null || s.isBlank(); }

  // ── inner records ─────────────────────────────────────────────────────────

  private record ResolvedClass(ClassLocation location, String note) {}
  private record MethodMatch(MethodInfo method, String declaredIn) {}
  private record FieldMatch(FieldInfo field, String declaredIn) {}

  private sealed interface ClassResolution
      permits ClassResolution.Found, ClassResolution.Missing, ClassResolution.Ambiguous {
    record Found(ResolvedClass cls) implements ClassResolution {}
    record Missing() implements ClassResolution {}
    record Ambiguous(List<ClassLocation> candidates, MatchResult matchResult) implements ClassResolution {}
  }
}
