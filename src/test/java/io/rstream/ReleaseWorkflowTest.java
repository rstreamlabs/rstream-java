package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

final class ReleaseWorkflowTest {
  private static String read(String path) throws IOException {
    return Files.readString(Path.of(path));
  }

  @Test
  void releaseWorkflowsUseTheConfiguredComponentTag() throws IOException {
    var config =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(read("release-please-config.json"))
            .path("packages")
            .path(".");
    assertThat(config.path("include-component-in-tag").asBoolean()).isTrue();
    assertThat(config.path("include-v-in-tag").asBoolean()).isTrue();
    String prefix = config.path("component").asText() + "-v";
    var workflow =
        new Load(LoadSettings.builder().build())
            .loadFromString(read(".github/workflows/release-candidate.yml"));
    assertThat(workflow).isInstanceOf(Map.class);
    Object events = ((Map<?, ?>) workflow).get("on");
    assertThat(events).isInstanceOf(Map.class);
    var push = ((Map<?, ?>) events).get("push");
    assertThat(push).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) push).get("tags")).isEqualTo(java.util.List.of(prefix + "*"));
    assertThat(read(".github/workflows/release-candidate.yml"))
        .contains("${GITHUB_REF_NAME#" + prefix + "}");
    assertThat(read(".github/workflows/publish.yml"))
        .contains(
            "^" + prefix + "[0-9]",
            "${RELEASE_TAG#" + prefix + "}",
            "${latest_tag#" + prefix + "}");
  }

  @Test
  void promotionCanFindTheDraftBeforePublishing() throws IOException {
    String workflow = read(".github/workflows/publish.yml");
    String lookup = workflow.substring(0, workflow.indexOf("version=${RELEASE_TAG#"));
    assertThat(lookup)
        .contains("gh release view \"$RELEASE_TAG\" --repo \"$GITHUB_REPOSITORY\" --json isDraft")
        .contains("--jq '.isDraft')\" != true")
        .doesNotContain("/releases/tags/");
  }

  @Test
  void releaseCandidateDoesNotPublish() throws IOException {
    String workflow = read(".github/workflows/release-candidate.yml");
    assertThat(workflow).contains("actions/upload-artifact@");
    assertThat(workflow).doesNotContain("workflow_dispatch:");
    assertThat(workflow).doesNotContain("publisher/upload");
  }

  @Test
  void promotionPublishesGitHubReleaseLast() throws IOException {
    String workflow = read(".github/workflows/publish.yml");
    assertThat(workflow).contains("environment: stable-release");
    assertThat(workflow.indexOf("Publish GitHub release"))
        .isGreaterThan(workflow.indexOf("Publish and verify Maven Central bundle"));
  }

  @Test
  void releasePleaseCreatesTaggedDraft() throws IOException {
    String config = read("release-please-config.json");
    assertThat(config).contains("\"draft\": true", "\"force-tag-creation\": true");
  }
}
