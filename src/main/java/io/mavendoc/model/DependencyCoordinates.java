package io.mavendoc.model;

/** Maven artifact coordinates (groupId:artifactId:version) plus optional type. */
public record DependencyCoordinates(String groupId, String artifactId, String version, String type) {
  public String gav() {
    return groupId + ":" + artifactId + ":" + version;
  }
}
