package io.mavendoc.config;

import java.util.List;

/**
 * Configuration for a single Maven project whose dependencies are indexed.
 *
 * <p>Provide {@code pomPath} to index a specific POM directly.  Use
 * {@code root} + {@code modules} to index individual sub-modules of a
 * multi-module project.
 */
public record ProjectConfig(String root, String pomPath, List<String> modules) {}
