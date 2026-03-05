package io.mavendoc.config;

import java.util.List;

/**
 * Top-level server configuration loaded from the JSON config file.
 *
 * <p>{@code localRepository} may be omitted; the server then defaults to
 * {@code ~/.m2/repository}.  A leading {@code ~/} is resolved to the
 * current user's home directory at startup.
 */
public record ServerConfig(
    String cachePath,
    String localRepository,
    List<ProjectConfig> projects,
    String logLevel) {}
