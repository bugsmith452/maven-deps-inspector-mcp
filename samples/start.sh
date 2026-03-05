#!/usr/bin/env bash
# Starts the Maven Deps Inspector MCP server.
# Run from the project root: bash samples/start.sh
# Or with a custom config: bash samples/start.sh --config /path/to/config.json

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR=$(ls "$PROJECT_DIR"/target/maven-deps-inspector-mcp-*.jar 2>/dev/null | head -1)

if [ -z "$JAR" ]; then
  echo "ERROR: No JAR found in target/. Build first with: mvn package -DskipTests" >&2
  exit 1
fi

if [ "$#" -eq 0 ]; then
  # Auto-select config: prefer test1-config.json if present (gitignored),
  # fall back to the generic config.json.
  if [ -f "$SCRIPT_DIR/test1-config.json" ]; then
    exec java -jar "$JAR" --config "$SCRIPT_DIR/test1-config.json"
  else
    exec java -jar "$JAR" --config "$SCRIPT_DIR/config.json"
  fi
else
  exec java -jar "$JAR" "$@"
fi
