package io.mavendoc.model;

import tools.jackson.databind.JsonNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;

/** An MCP tool invocation bundled with its JSON parameters and optional exchange. */
public record ToolRequest(String id, String tool, JsonNode params, McpSyncServerExchange exchange) {
  public ToolRequest(String id, String tool, JsonNode params) {
    this(id, tool, params, null);
  }
}
