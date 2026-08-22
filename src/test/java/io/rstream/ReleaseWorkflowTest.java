package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ReleaseWorkflowTest {
  private static String read(String path) throws IOException {
    return Files.readString(Path.of(path));
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
