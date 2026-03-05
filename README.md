# Maven Deps Inspector MCP

A [Model Context Protocol](https://modelcontextprotocol.io/) server that indexes compiled Maven
dependency JARs and serves class, method, and field signature information as plain text.  AI
coding agents can use it to understand internal or private libraries they were not trained on.

No source code or javadoc JARs are needed.  Documentation is extracted directly from
`.class` bytecode using the [ASM](https://asm.ow2.io/) library.

---

## Prerequisites

| Requirement | Minimum version | Notes |
|---|---|---|
| Java (JDK) | 17 | Required to build and run the server |
| Maven | 3.8 | Required to build; also used to resolve project dependencies |
| Node.js | 18 (optional) | Only needed to run MCP Inspector (`npx`) |

Verify your versions:

```bash
java -version   # should print 17.x or higher
mvn -version    # should print 3.8.x or higher
```

---

## Features

- Indexes JAR / or any kind of artifacts from Maven dependency graphs
- Recursive transitive POM resolution with parent POM property inheritance
- Five MCP tools: `getClassDocumentation`, `getMethodDocumentation`,
  `getFieldDocumentation`, `searchDependencies`, `clearCache`
- Interactive disambiguation via the MCP elicitation capability when a class or
  method name is ambiguous
- Persistent disk cache keyed by GAV + class/method/field
- Structured JSON responses with a plain-text `text` field (no Markdown)

---

## Project Structure

```
maven-deps-inspector-mcp/
├── pom.xml                          # Single-module Maven build (Java 17, shade JAR)
├── sample-project/
│   └── pom.xml                      # Generic Maven project used by integration tests
│                                    # (commons-lang3, guava, jackson, slf4j, logback)
├── samples/
│   ├── config.json                       # Points to sample-project; uses ~/.m2 by default
│   ├── test1-config.template.json     # Template for private-artifact config 
│   ├── start.sh                          # Unix/macOS start script
│   └── start.bat                         # Windows start script
└── src/
    ├── main/java/io/mavendoc/
    │   ├── ServerLauncher.java           # Entry point
    │   ├── config/                       # ServerConfig, ProjectConfig
    │   ├── model/                        # DependencyCoordinates, ToolRequest, ToolResult
    │   └── services/
    │       ├── SymbolRepository          # POM parsing, JAR indexing, symbol lookup
    │       ├── TextRenderer              # Bytecode → plain-text documentation
    │       ├── CacheService              # SHA-256–keyed disk cache
    │       └── ToolRequestHandler        # Tool routing, elicitation, caching
    └── test/java/io/mavendoc/
        └── services/
            ├── SymbolRepositoryTest      # Unit tests (synthetic bytecode)
            └── GenericIntegrationTest    # Integration tests (Maven Central artifacts)
```

---

## Quick Start

### Build

```bash
mvn package -DskipTests
# Output: target/maven-deps-inspector-mcp-1.0.0.jar
```

### Run

```bash
# Unix/macOS
bash samples/start.sh

# Windows
samples\start.bat

# With a custom config
java -jar target/maven-deps-inspector-mcp-1.0.0.jar --config /path/to/config.json
```

### Test with MCP Inspector

```bash
npx @modelcontextprotocol/inspector \
  java -jar target/maven-deps-inspector-mcp-1.0.0.jar --config samples/config.json
```

---

## Wiring into an MCP Client

The server communicates over **stdio** (stdin/stdout).  Any MCP-capable client can
launch it as a subprocess.  Use absolute paths in all client configs.

### Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or
`%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "maven-deps-inspector": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/maven-deps-inspector-mcp-1.0.0.jar",
        "--config",
        "/absolute/path/to/config.json"
      ]
    }
  }
}
```

Restart Claude Desktop after saving.

### Claude Code (CLI)

```bash
claude mcp add maven-deps-inspector \
  java -jar /absolute/path/to/maven-deps-inspector-mcp-1.0.0.jar \
       --config /absolute/path/to/config.json
```

Or add directly to `.mcp.json` in your project root:

```json
{
  "mcpServers": {
    "maven-deps-inspector": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/maven-deps-inspector-mcp-1.0.0.jar",
        "--config",
        "/absolute/path/to/config.json"
      ]
    }
  }
}
```

### Other MCP Clients

Any client that supports the MCP stdio transport works.  The command is always:

```
java -jar /path/to/maven-deps-inspector-mcp-1.0.0.jar --config /path/to/config.json
```

You can also set the config path via environment variable instead of `--config`:

```bash
export MAVEN_DEPS_INSPECTOR_CONFIG=/path/to/config.json
java -jar maven-deps-inspector-mcp-1.0.0.jar
```

---

## Configuration

Create a JSON config file (see `samples/config.json` for a working example):

```json
{
  "cachePath": "target/.maven-deps-inspector-cache",
  "localRepository": "~/.m2/repository",
  "projects": [
    {
      "pomPath": "path/to/project/pom.xml"
    }
  ],
  "logLevel": "info"
}
```

| Field | Required | Description |
|---|---|---|
| `cachePath` | Yes | Directory where rendered documentation is cached |
| `localRepository` | No | Path to Maven local repository (defaults to `~/.m2/repository`) |
| `projects` | Yes | List of Maven projects to index |
| `projects[].pomPath` | No* | Path to a POM file to index directly |
| `projects[].root` + `.modules` | No* | Root directory + list of sub-module names |
| `logLevel` | No | Log verbosity (`trace` / `debug` / `info` / `warn` / `error`) |

*Provide either `pomPath` or both `root` + `modules` for each project entry.

Leading `~/` in `localRepository` is expanded to the user home directory at startup.

---

## MCP Tools

All tools return a JSON string as their text content:

**Success:** `{"text": "... plain-text documentation ..."}`

**Failure:** `{"error": "ERROR_CODE", "message": "...", "hint": "..."}`

### `getClassDocumentation`

Returns class signature, modifiers, inheritance, own and inherited fields and methods.

| Parameter | Description |
|---|---|
| `className` | Simple class name (required unless `classFqn` is provided) |
| `classFqn` | Fully-qualified name; bypasses simple-name ambiguity |
| `basePackage` | Package prefix to narrow multiple matches |
| `groupId` / `artifactId` / `version` | Coordinate filters |

### `getMethodDocumentation`

Returns method signature, modifiers, return type, parameters, and override flag.

| Parameter | Description |
|---|---|
| `className` / `classFqn` | Class to inspect |
| `methodName` | Method name |
| `overloadSignature` | Parameter types to select a specific overload, e.g. `(java.lang.String,int)` |
| `basePackage` / coordinate filters | Same as getClassDocumentation |

### `getFieldDocumentation`

Returns field type, modifiers, and whether it is inherited.

| Parameter | Description |
|---|---|
| `className` / `classFqn` | Class to inspect |
| `fieldName` | Field name |

### `searchDependencies`

Lists all indexed classes matching a simple name.

| Parameter | Description |
|---|---|
| `className` | Simple name to search for |
| `basePackage` | Optional package prefix filter |

### `clearCache`

Clears all cached documentation entries.  No parameters.

---

## Class Resolution Algorithm

```
1. classFqn provided? → direct FQN lookup → Found | Missing

2. Lookup all classes by simple name in the index
3. Apply basePackage filter (falls back to full set if filter leaves zero results)
4. Apply coordinate overrides (groupId / artifactId / version)

5. 0 matches → CLASS_NOT_FOUND
   1 match  → proceed

6. Multiple matches:
   Client supports elicitation? → interactive dialog → user picks one → Found
   No elicitation?              → AMBIGUOUS_CLASS error with classFqn hint
```

---

## Disambiguation (Elicitation)

When the server cannot uniquely identify a class or method, and the connected MCP client
advertises the `elicitation` capability, the server opens an interactive dialog.

**Class disambiguation** — the user selects by fully-qualified name:

```
Multiple classes named 'Logger' were found.
  1  org.slf4j.Logger            org.slf4j:slf4j-api:2.0.16           jar
  2  ch.qos.logback.classic.Logger  ch.qos.logback:logback-classic:1.5.12  jar
```

**Method disambiguation** — the user selects by declaring class + descriptor token:

```
Multiple overloads of 'StringUtils.join' exist.
  1  org.apache.commons.lang3.StringUtils  (java.lang.Object[],java.lang.String)
  2  org.apache.commons.lang3.StringUtils  (java.lang.Iterable,java.lang.String)
  ...
```

Without elicitation support, use `classFqn` or `overloadSignature` to disambiguate directly.

---

## Caching

- Entries stored as plain-text files under `cachePath`.
- Cache key: SHA-256 of `{type}#{groupId:artifactId:version}#{className}[#{qualifier}]`.
- Dynamic per-request notes (e.g. `basePackage` hints) are **not** cached; they are
  prepended in memory.
- Invalidate with `clearCache`.

---

## Running Tests

```bash
# Unit + generic integration tests (Maven Central artifacts)
mvn test

# Generic integration tests only
mvn -Dtest=GenericIntegrationTest test
```

Test reports (JSON + plain-text) are written to `target/mcp-report/`.

---

## Building Your Own Config

1. Run `mvn install` (or `mvn dependency:resolve`) on the Maven project you want to index.
2. Create a `config.json` pointing to that project's POM and your local Maven repository.
3. Start the server and use `searchDependencies` to explore available classes.

---

## Roadmap

- **Javadoc surfacing** — Many Maven artifacts ship a companion `*-javadoc.jar` alongside
  the main JAR.  A future version will extract and serve that prose documentation (parameter
  descriptions, return value semantics, thrown exceptions) alongside the bytecode-derived
  signatures, giving agents not just the shape of an API but the intent behind it.

---

## Troubleshooting

**`CLASS_NOT_FOUND` for a class visible in `searchDependencies`**

The class name is ambiguous. Use `classFqn` with the fully-qualified name shown in
the search results.

**`AMBIGUOUS_CLASS` error**

The MCP client does not support elicitation. Add `classFqn` to the call.

**`AMBIGUOUS_METHOD` error**

Multiple overloads share the same name. Add `overloadSignature`, e.g.
`overloadSignature: "(java.lang.String)"`.

**`Symbol index built: 0 classes`**

Maven artifact resolution failed. Check that `localRepository` points to a populated
`.m2/repository` and that all project POMs are accessible.
