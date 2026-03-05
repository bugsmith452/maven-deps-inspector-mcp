package io.mavendoc.model;

/**
 * Result of a tool invocation.
 *
 * <p>On success, {@code text} contains the plain-text documentation.
 * On failure, {@code errorCode}, {@code errorMessage}, and (optionally) {@code errorHint}
 * describe what went wrong.
 *
 * <p>{@link #toJson()} serialises the result to the JSON string that is
 * sent as the MCP {@code CallToolResult} content:
 * <ul>
 *   <li>Success: {@code {"text":"..."}}</li>
 *   <li>Failure: {@code {"error":"CODE","message":"...","hint":"..."}}</li>
 * </ul>
 */
public record ToolResult(
    String id,
    boolean success,
    String text,
    String errorCode,
    String errorMessage,
    String errorHint) {

  public static ToolResult success(String id, String text) {
    return new ToolResult(id, true, text, null, null, null);
  }

  public static ToolResult failure(String id, String code, String message, String hint) {
    return new ToolResult(id, false, null, code, message, hint);
  }

  /** Returns the JSON string to be sent as MCP tool content. */
  public String toJson() {
    if (success) {
      return "{\"text\":" + quote(text) + "}";
    }
    StringBuilder sb = new StringBuilder("{\"error\":").append(quote(errorCode));
    sb.append(",\"message\":").append(quote(errorMessage));
    if (errorHint != null && !errorHint.isBlank()) {
      sb.append(",\"hint\":").append(quote(errorHint));
    }
    sb.append("}");
    return sb.toString();
  }

  private static String quote(String s) {
    if (s == null) {
      return "null";
    }
    return "\""
        + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + "\"";
  }
}
