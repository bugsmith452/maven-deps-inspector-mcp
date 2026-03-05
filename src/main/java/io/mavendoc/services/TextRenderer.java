package io.mavendoc.services;

import io.mavendoc.services.SymbolRepository.ClassLocation;
import io.mavendoc.services.SymbolRepository.ClassSymbol;
import io.mavendoc.services.SymbolRepository.FieldInfo;
import io.mavendoc.services.SymbolRepository.MethodInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static java.util.stream.Collectors.joining;

/**
 * Converts bytecode symbol metadata into plain-text documentation strings.
 * No Markdown or HTML is used; output is readable without a renderer.
 */
public final class TextRenderer {
  private final SymbolRepository repository;

  public TextRenderer(SymbolRepository repository) {
    this.repository = repository;
  }

  // ── class ─────────────────────────────────────────────────────────────────

  public String renderClass(ClassSymbol symbol) {
    StringBuilder sb = new StringBuilder();
    sb.append("Class: ").append(simpleName(symbol.className())).append('\n');
    sb.append("Full Name: ").append(symbol.className()).append('\n');
    sb.append("Kind: ").append(kind(symbol.access())).append('\n');
    sb.append("Modifiers: ").append(modifiers(symbol.access())).append('\n');
    if (symbol.superInternalName() != null) {
      sb.append("Extends: ").append(symbol.superInternalName().replace('/', '.')).append('\n');
    }
    if (!symbol.interfaceInternalNames().isEmpty()) {
      sb.append("Implements: ")
          .append(symbol.interfaceInternalNames().stream()
              .map(n -> n.replace('/', '.')).collect(joining(", ")))
          .append('\n');
    }
    sb.append("Coordinates: ").append(symbol.coordinates().gav()).append('\n');

    List<String> missingAncestors = new ArrayList<>();

    appendFields(sb, "Own Fields", symbol.fields());
    appendMethods(sb, "Own Methods", symbol.methods());

    Map<String, List<FieldInfo>> inheritedFields = collectInheritedFields(symbol, missingAncestors);
    for (Map.Entry<String, List<FieldInfo>> entry : inheritedFields.entrySet()) {
      appendFields(sb, "Fields from " + entry.getKey(), entry.getValue());
    }
    Map<String, List<MethodInfo>> inheritedMethods = collectInheritedMethods(symbol, missingAncestors);
    for (Map.Entry<String, List<MethodInfo>> entry : inheritedMethods.entrySet()) {
      appendMethods(sb, "Methods from " + entry.getKey(), entry.getValue());
    }

    if (!missingAncestors.isEmpty()) {
      LinkedHashSet<String> unique = new LinkedHashSet<>(missingAncestors);
      sb.append("\nNote: Ancestor metadata not available for: ")
          .append(String.join(", ", unique)).append('\n');
    }
    return sb.toString();
  }

  // ── method ────────────────────────────────────────────────────────────────

  public String renderMethod(ClassSymbol symbol, MethodInfo method,
      String declaringClass, boolean overridesParent) {
    StringBuilder sb = new StringBuilder();
    sb.append("Class: ").append(simpleName(symbol.className())).append('\n');
    sb.append("Full Name: ").append(symbol.className()).append('\n');
    sb.append("Method: ").append(displayName(method.name())).append('\n');
    sb.append("Signature: ")
        .append(returnType(method.descriptor())).append(' ')
        .append(displayName(method.name()))
        .append(paramsAsString(method.descriptor())).append('\n');
    sb.append("Declared In: ").append(declaringClass).append('\n');
    sb.append("Modifiers: ").append(modifiers(method.access())).append('\n');
    sb.append("Return Type: ").append(returnType(method.descriptor())).append('\n');
    sb.append("Static: ").append(isStatic(method.access()) ? "yes" : "no").append('\n');
    sb.append("Overrides Parent: ").append(overridesParent ? "yes" : "no").append('\n');
    List<String> params = paramTypes(method.descriptor());
    if (!params.isEmpty()) {
      sb.append("Parameters:\n");
      for (int i = 0; i < params.size(); i++) {
        sb.append("  ").append(i + 1).append(": ").append(params.get(i)).append('\n');
      }
    }
    if (!method.exceptions().isEmpty()) {
      sb.append("Throws: ")
          .append(method.exceptions().stream()
              .map(n -> n.replace('/', '.')).collect(joining(", ")))
          .append('\n');
    }
    return sb.toString();
  }

  // ── field ─────────────────────────────────────────────────────────────────

  public String renderField(ClassSymbol symbol, FieldInfo field, String declaringClass) {
    StringBuilder sb = new StringBuilder();
    sb.append("Class: ").append(simpleName(symbol.className())).append('\n');
    sb.append("Full Name: ").append(symbol.className()).append('\n');
    sb.append("Field: ").append(field.name()).append('\n');
    sb.append("Type: ").append(fieldType(field.descriptor())).append('\n');
    sb.append("Declared In: ").append(declaringClass).append('\n');
    sb.append("Modifiers: ").append(modifiers(field.access())).append('\n');
    sb.append("Static: ").append(isStatic(field.access()) ? "yes" : "no").append('\n');
    sb.append("Final: ").append(isFinal(field.access()) ? "yes" : "no").append('\n');
    boolean inherited = !declaringClass.equals(symbol.className());
    if (inherited) {
      sb.append("Inherited: yes (from ").append(declaringClass).append(")\n");
    } else {
      sb.append("Inherited: no\n");
    }
    return sb.toString();
  }

  // ── inheritance helpers ───────────────────────────────────────────────────

  private Map<String, List<FieldInfo>> collectInheritedFields(ClassSymbol symbol,
      List<String> missing) {
    Map<String, List<FieldInfo>> result = new LinkedHashMap<>();
    Optional<ClassSymbol> parent = resolveParent(symbol.superInternalName(), missing);
    while (parent.isPresent()) {
      ClassSymbol p = parent.get();
      if (!p.fields().isEmpty()) result.put(p.className(), p.fields());
      parent = resolveParent(p.superInternalName(), missing);
    }
    return result;
  }

  private Map<String, List<MethodInfo>> collectInheritedMethods(ClassSymbol symbol,
      List<String> missing) {
    Map<String, List<MethodInfo>> result = new LinkedHashMap<>();
    Optional<ClassSymbol> parent = resolveParent(symbol.superInternalName(), missing);
    while (parent.isPresent()) {
      ClassSymbol p = parent.get();
      if (!p.methods().isEmpty()) result.put(p.className(), p.methods());
      parent = resolveParent(p.superInternalName(), missing);
    }
    return result;
  }

  private Optional<ClassSymbol> resolveParent(String internalName, List<String> missing) {
    if (internalName == null) return Optional.empty();
    Optional<ClassSymbol> resolved = repository.tryLoadInternal(internalName);
    if (resolved.isEmpty() && missing != null) missing.add(internalName.replace('/', '.'));
    return resolved;
  }

  // ── section builders ──────────────────────────────────────────────────────

  private void appendFields(StringBuilder sb, String title, List<FieldInfo> fields) {
    if (fields == null || fields.isEmpty()) return;
    sb.append('\n').append(title).append(":\n");
    for (FieldInfo f : fields) {
      sb.append("  ").append(modifiers(f.access())).append(' ')
          .append(fieldType(f.descriptor())).append(' ').append(f.name()).append('\n');
    }
  }

  private void appendMethods(StringBuilder sb, String title, List<MethodInfo> methods) {
    if (methods == null || methods.isEmpty()) return;
    sb.append('\n').append(title).append(":\n");
    for (MethodInfo m : methods) {
      sb.append("  ").append(modifiers(m.access())).append(' ')
          .append(returnType(m.descriptor())).append(' ')
          .append(displayName(m.name())).append(paramsAsString(m.descriptor())).append('\n');
    }
  }

  // ── type / descriptor helpers ─────────────────────────────────────────────

  private static String returnType(String descriptor) {
    return Type.getReturnType(descriptor).getClassName();
  }

  private static List<String> paramTypes(String descriptor) {
    List<String> list = new ArrayList<>();
    for (Type t : Type.getArgumentTypes(descriptor)) list.add(t.getClassName());
    return list;
  }

  private static String paramsAsString(String descriptor) {
    return "(" + String.join(", ", paramTypes(descriptor)) + ")";
  }

  private static String fieldType(String descriptor) {
    return Type.getType(descriptor).getClassName();
  }

  private static String displayName(String methodName) {
    return "<init>".equals(methodName) ? "<constructor>" : methodName;
  }

  private static String simpleName(String fqn) {
    int i = fqn.lastIndexOf('.');
    return i >= 0 ? fqn.substring(i + 1) : fqn;
  }

  // ── access flag helpers ───────────────────────────────────────────────────

  private static String modifiers(int access) {
    TreeSet<String> names = new TreeSet<>();
    if ((access & Opcodes.ACC_PUBLIC) != 0)       names.add("public");
    if ((access & Opcodes.ACC_PROTECTED) != 0)    names.add("protected");
    if ((access & Opcodes.ACC_PRIVATE) != 0)      names.add("private");
    if ((access & Opcodes.ACC_ABSTRACT) != 0)     names.add("abstract");
    if ((access & Opcodes.ACC_FINAL) != 0)        names.add("final");
    if ((access & Opcodes.ACC_STATIC) != 0)       names.add("static");
    if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) names.add("synchronized");
    return names.isEmpty() ? "package-private" : String.join(" ", names);
  }

  private static boolean isStatic(int access) { return (access & Opcodes.ACC_STATIC) != 0; }
  private static boolean isFinal(int access)  { return (access & Opcodes.ACC_FINAL)  != 0; }

  private static String kind(int access) {
    if ((access & Opcodes.ACC_ANNOTATION) != 0) return "annotation";
    if ((access & Opcodes.ACC_ENUM)       != 0) return "enum";
    if ((access & Opcodes.ACC_INTERFACE)  != 0) return "interface";
    return "class";
  }
}
