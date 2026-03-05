package io.mavendoc.services;

import io.mavendoc.config.ProjectConfig;
import io.mavendoc.config.ServerConfig;
import io.mavendoc.model.DependencyCoordinates;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Indexes Maven dependency artifacts (JAR / applib or any artifact) discovered via POM files and
 * provides class symbol lookup by simple name or fully qualified name.
 *
 * <p>Dependency graph traversal is recursive (up to depth 10) and tracks visited artifacts
 * to avoid cycles.  Scope {@code test} and {@code provided} are excluded.
 */
public final class SymbolRepository {
  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolRepository.class);
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
  private static final int MAX_DEPTH = 10;

  private final ServerConfig config;
  private final Path localRepository;

  /** Simple name → list of all matching class locations. */
  private final Map<String, List<ClassLocation>> simpleIndex = new ConcurrentHashMap<>();

  /** Fully-qualified name → single class location. */
  private final Map<String, ClassLocation> fqnIndex = new ConcurrentHashMap<>();

  /** Parsed bytecode cache to avoid re-reading JARs. */
  private final Map<String, ClassSymbol> symbolCache = new ConcurrentHashMap<>();

  private final MavenXpp3Reader pomReader = new MavenXpp3Reader();

  public SymbolRepository(ServerConfig config) {
    this.config = config;
    String repoValue = config.localRepository();
    if (repoValue == null || repoValue.isBlank()) {
      repoValue = Path.of(System.getProperty("user.home"), ".m2", "repository").toString();
    } else if (repoValue.startsWith("~/") || repoValue.equals("~")) {
      repoValue = System.getProperty("user.home") + repoValue.substring(1);
    }
    this.localRepository = Path.of(repoValue);
  }

  /** Rebuilds the symbol index from scratch. Called once at startup and periodically. */
  public void refresh() {
    simpleIndex.clear();
    fqnIndex.clear();
    symbolCache.clear();
    List<DependencyArtifact> artifacts = collectArtifacts();
    int indexed = 0;
    for (DependencyArtifact artifact : artifacts) {
      if (!Files.exists(artifact.path())) {
        LOGGER.debug("Artifact not found on disk: {}", artifact.path());
        continue;
      }
      try (JarFile jar = new JarFile(artifact.path().toFile())) {
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
          if (entry.getName().equals("module-info.class")) continue;
          String internalName = entry.getName().substring(0, entry.getName().length() - 6);
          String className = internalName.replace('/', '.');
          ClassLocation location =
              new ClassLocation(className, internalName, artifact.path(), entry.getName(), artifact.coordinates());
          simpleIndex.computeIfAbsent(simpleName(className), k -> new ArrayList<>()).add(location);
          fqnIndex.putIfAbsent(className, location);
          indexed++;
        }
      } catch (IOException ex) {
        LOGGER.warn("Unable to index artifact {}", artifact.path(), ex);
      }
    }
    LOGGER.info("Symbol index built: {} classes from {} artifacts", indexed, artifacts.size());
  }

  /** Returns all classes whose simple name matches, optionally filtered by base package. */
  public MatchResult matches(String simpleName, Optional<String> basePackage) {
    List<ClassLocation> candidates = simpleIndex.getOrDefault(simpleName, List.of());
    if (candidates.isEmpty()) {
      return new MatchResult(List.of(), 0, basePackage.isPresent(), false, false,
          basePackage.map(String::trim).orElse(null));
    }
    int total = candidates.size();
    if (basePackage.isEmpty() || basePackage.map(String::isBlank).orElse(true)) {
      return new MatchResult(List.copyOf(candidates), total, false, false, false, null);
    }
    String prefix = basePackage.get().trim();
    List<ClassLocation> filtered = candidates.stream()
        .filter(loc -> loc.className().startsWith(prefix))
        .toList();
    if (filtered.isEmpty()) {
      return new MatchResult(List.copyOf(candidates), total, true, false, true, prefix);
    }
    return new MatchResult(List.copyOf(filtered), total, true, filtered.size() != total, false, prefix);
  }

  /** Looks up a class by fully-qualified name. */
  public Optional<ClassLocation> byClassName(String fqn) {
    return Optional.ofNullable(fqnIndex.get(fqn));
  }

  /** Loads (and caches) the full symbol for a class location. */
  public ClassSymbol loadSymbol(ClassLocation location) {
    return symbolCache.computeIfAbsent(location.className(), k -> parseSymbol(location));
  }

  /** Attempts to load a symbol for an internal class name (e.g. from a superclass reference). */
  public Optional<ClassSymbol> tryLoadInternal(String internalName) {
    if (internalName == null) return Optional.empty();
    ClassLocation loc = fqnIndex.get(internalName.replace('/', '.'));
    return loc == null ? Optional.empty() : Optional.of(loadSymbol(loc));
  }

  // ── private: bytecode ─────────────────────────────────────────────────────

  private ClassSymbol parseSymbol(ClassLocation location) {
    try (JarFile jar = new JarFile(location.artifactPath().toFile())) {
      JarEntry entry = jar.getJarEntry(location.entryName());
      if (entry == null) throw new IllegalStateException("Missing entry " + location.entryName());
      try (InputStream stream = jar.getInputStream(entry)) {
        ClassReader reader = new ClassReader(stream);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        @SuppressWarnings("unchecked")
        List<FieldNode> asmFields = node.fields;
        @SuppressWarnings("unchecked")
        List<MethodNode> asmMethods = node.methods;
        List<FieldInfo> fields = new ArrayList<>();
        for (FieldNode f : asmFields) fields.add(new FieldInfo(f.name, f.desc, f.access));
        List<MethodInfo> methods = new ArrayList<>();
        for (MethodNode m : asmMethods) {
          @SuppressWarnings("unchecked")
          List<String> exceptions = m.exceptions == null ? List.of() : (List<String>) m.exceptions;
          methods.add(new MethodInfo(m.name, m.desc, m.access, List.copyOf(exceptions)));
        }
        @SuppressWarnings("unchecked")
        List<String> ifaces = node.interfaces == null ? List.of() : (List<String>) node.interfaces;
        return new ClassSymbol(location.className(), location.internalName(), node.access,
            node.superName, List.copyOf(ifaces), fields, methods, location.coordinates());
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to read class " + location.className(), ex);
    }
  }

  // ── private: artifact collection ──────────────────────────────────────────

  private List<DependencyArtifact> collectArtifacts() {
    Set<DependencyCoordinates> coordinates = new LinkedHashSet<>();
    if (config.projects() != null) {
      for (ProjectConfig project : config.projects()) {
        if (project.pomPath() != null) {
          coordinates.addAll(parsePom(Path.of(project.pomPath())));
        }
        if (project.modules() != null && project.root() != null) {
          Path root = Path.of(project.root());
          for (String module : project.modules()) {
            coordinates.addAll(parsePom(root.resolve(module).resolve("pom.xml")));
          }
        }
      }
    }
    List<DependencyArtifact> artifacts = new ArrayList<>();
    for (DependencyCoordinates coord : coordinates) {
      Path path = artifactPath(coord.groupId(), coord.artifactId(), coord.version(), coord.type());
      artifacts.add(new DependencyArtifact(coord, path));
    }
    return artifacts;
  }

  // ── private: POM parsing ──────────────────────────────────────────────────

  private List<DependencyCoordinates> parsePom(Path pomPath) {
    if (pomPath == null || !Files.exists(pomPath)) return List.of();
    Set<String> visited = new LinkedHashSet<>();
    List<DependencyCoordinates> result = new ArrayList<>();
    parsePomRecursive(pomPath, result, visited, 0);
    return result;
  }

  private void parsePomRecursive(Path pomPath, List<DependencyCoordinates> out,
      Set<String> visited, int depth) {
    if (depth > MAX_DEPTH || pomPath == null || !Files.exists(pomPath)) return;
    try (var reader = Files.newBufferedReader(pomPath)) {
      Model model = pomReader.read(reader);
      if (model.getParent() != null) {
        Model parent = loadParentPom(model.getParent(), pomPath);
        if (parent != null) model = mergeWithParent(model, parent);
      }
      String groupId = resolve(firstNonBlank(model.getGroupId(), parentGroup(model.getParent())), model);
      String version = resolve(firstNonBlank(model.getVersion(), parentVersion(model.getParent())), model);
      if (!blank(groupId) && !blank(model.getArtifactId()) && !blank(version)) {
        String key = groupId + ":" + model.getArtifactId() + ":" + version;
        if (visited.add(key)) {
          out.add(new DependencyCoordinates(groupId, model.getArtifactId(), version, resolve(model.getPackaging(), model)));
        }
      }
      addDepsRecursive(out, model.getDependencies(), model, visited, depth + 1);
      DependencyManagement mgmt = model.getDependencyManagement();
      if (mgmt != null) addDepsRecursive(out, mgmt.getDependencies(), model, visited, depth + 1);
    } catch (IOException | XmlPullParserException ex) {
      LOGGER.warn("Unable to parse POM {}", pomPath, ex);
    }
  }

  private void addDepsRecursive(List<DependencyCoordinates> out, List<Dependency> deps,
      Model model, Set<String> visited, int depth) {
    if (deps == null) return;
    for (Dependency dep : deps) {
      String g = resolve(dep.getGroupId(), model);
      String a = resolve(dep.getArtifactId(), model);
      String v = resolve(dep.getVersion(), model);
      String t = resolve(dep.getType(), model);
      String scope = resolve(dep.getScope(), model);
      if (blank(g) || blank(a) || blank(v)) continue;
      if ("test".equals(scope) || "provided".equals(scope)) continue;
      String key = g + ":" + a + ":" + v;
      if (!visited.add(key)) continue;
      out.add(new DependencyCoordinates(g, a, v, t));
      Path depPom = artifactPath(g, a, v, "pom");
      if (Files.exists(depPom)) {
        parsePomRecursive(depPom, out, visited, depth);
      }
    }
  }

  private Model loadParentPom(Parent parent, Path childPom) {
    if (parent == null) return null;
    try {
      String rel = parent.getRelativePath();
      if (rel != null && !rel.isBlank()) {
        Path p = childPom.getParent().resolve(rel).normalize();
        if (Files.isDirectory(p)) p = p.resolve("pom.xml");
        if (Files.exists(p)) {
          try (var r = Files.newBufferedReader(p)) { return pomReader.read(r); }
        }
      }
      if (!blank(parent.getGroupId()) && !blank(parent.getArtifactId()) && !blank(parent.getVersion())) {
        Path p = artifactPath(parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), "pom");
        if (Files.exists(p)) {
          try (var r = Files.newBufferedReader(p)) { return pomReader.read(r); }
        }
      }
    } catch (IOException | XmlPullParserException ex) {
      LOGGER.debug("Unable to load parent POM {}", parent, ex);
    }
    return null;
  }

  private Model mergeWithParent(Model child, Model parent) {
    Model merged = child.clone();
    if (parent.getProperties() != null) {
      java.util.Properties props = new java.util.Properties();
      props.putAll(parent.getProperties());
      if (child.getProperties() != null) props.putAll(child.getProperties());
      merged.setProperties(props);
    }
    if (merged.getDependencyManagement() == null && parent.getDependencyManagement() != null) {
      merged.setDependencyManagement(parent.getDependencyManagement());
    }
    return merged;
  }

  // ── private: helpers ──────────────────────────────────────────────────────

  private Path artifactPath(String groupId, String artifactId, String version, String type) {
    String ext = (type == null || type.isBlank()) ? "jar" : type;
    return localRepository.resolve(
        Path.of(groupId.replace('.', '/'), artifactId, version,
            artifactId + "-" + version + "." + ext));
  }

  private String resolve(String value, Model model) {
    if (value == null || !value.contains("${")) return value;
    Matcher m = PLACEHOLDER.matcher(value);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String key = m.group(1);
      String rep = lookupProp(model, key);
      m.appendReplacement(sb, Matcher.quoteReplacement(rep != null ? rep : m.group(0)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private String lookupProp(Model model, String key) {
    if (key == null) return null;
    if (key.startsWith("env.")) return System.getenv(key.substring(4));
    if (model.getProperties() != null && model.getProperties().containsKey(key)) {
      return model.getProperties().getProperty(key);
    }
    return switch (key) {
      case "project.groupId" -> firstNonBlank(model.getGroupId(), parentGroup(model.getParent()));
      case "project.artifactId" -> model.getArtifactId();
      case "project.version" -> firstNonBlank(model.getVersion(), parentVersion(model.getParent()));
      case "parent.groupId" -> parentGroup(model.getParent());
      case "parent.version" -> parentVersion(model.getParent());
      default -> System.getProperty(key);
    };
  }

  private static String simpleName(String fqn) {
    int i = fqn.lastIndexOf('.');
    return i >= 0 ? fqn.substring(i + 1) : fqn;
  }

  private static String firstNonBlank(String a, String b) {
    return !blank(a) ? a : b;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String parentGroup(Parent p) { return p != null ? p.getGroupId() : null; }
  private static String parentVersion(Parent p) { return p != null ? p.getVersion() : null; }

  // ── public records ────────────────────────────────────────────────────────

  private record DependencyArtifact(DependencyCoordinates coordinates, Path path) {}

  public record ClassLocation(String className, String internalName,
      Path artifactPath, String entryName, DependencyCoordinates coordinates) {}

  public record FieldInfo(String name, String descriptor, int access) {}

  public record MethodInfo(String name, String descriptor, int access, List<String> exceptions) {}

  public record ClassSymbol(String className, String internalName, int access,
      String superInternalName, List<String> interfaceInternalNames,
      List<FieldInfo> fields, List<MethodInfo> methods, DependencyCoordinates coordinates) {}

  public record MatchResult(List<ClassLocation> matches, int totalCandidates,
      boolean basePackageApplied, boolean basePackageReduced, boolean basePackageFallback,
      String requestedBasePackage) {}
}
