package org.zalava.modules.tika;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.zalava.ZalavaServiceFactory;
import org.zalava.content.ContentExtractionFailure;
import org.zalava.content.ContentExtractionFailureCategory;
import org.zalava.content.ContentExtractionLimits;
import org.zalava.content.ContentExtractionOutcome;
import org.zalava.content.ContentExtractionRequest;
import org.zalava.content.ContentExtractionResult;
import org.zalava.content.ContentExtractor;
import org.zalava.content.ContentSourceInput;
import org.zalava.content.ContentSourceMetadata;
import org.zalava.testing.ConfigFixture;
import org.zalava.testing.ModuleContractKit;
import org.zalava.testing.ServiceFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real built module JAR at the stable {@code module-api} boundary through the released
 * contract kit. Host-owned resolution, validation, permissions and persistence stay covered by SEA.
 */
class TikaSeaModuleTest {
  private static final String MODULE_ID = "zalava-module-tika";
  private static final String SHA_256 = "0".repeat(64);

  private ModuleContractKit kit;

  @BeforeEach
  void loadTheBuiltArtifact() {
    kit =
        ModuleContractKit.load(
            Path.of(System.getProperty("module.artifact")),
            List.of(),
            MODULE_ID,
            System.getProperty("module.version"));
  }

  @AfterEach
  void closeTheArtifact() throws Exception {
    if (kit != null) {
      kit.close();
    }
  }

  @Test
  void loadsTheModuleFromTheBuiltArtifact() {
    assertThat(kit.module().getClass().getClassLoader()).isNotSameAs(getClass().getClassLoader());
    assertThat(
            kit.module()
                .getClass()
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toString())
        .endsWith(".jar");
  }

  @Test
  void exposesTheModuleOwnedDescriptorAndServiceContract() {
    assertThat(kit.moduleId()).isEqualTo(MODULE_ID);
    assertThat(kit.version()).isEqualTo(System.getProperty("module.version"));
    assertThat(kit.module().providerFactories()).isEmpty();

    List<ZalavaServiceFactory<?>> factories = kit.module().serviceFactories();
    assertThat(factories).hasSize(1);
    assertThat(factories.getFirst().contract()).isEqualTo(ContentExtractor.CONTRACT);

    try (ServiceFixture services = kit.services()) {
      assertThat(services.factories()).hasSize(1);
      assertThat(services.service(ContentExtractor.CONTRACT)).isNotNull();
    }
  }

  @Test
  void extractsPlainTextThroughTheTypedServiceContract() {
    try (ServiceFixture services = kit.services()) {
      ContentExtractor extractor = services.service(ContentExtractor.CONTRACT);

      ContentExtractionResult result =
          successful(extractor.extract(request("plain text", "note.txt", "text/plain", 1_024)));

      assertThat(result.text()).contains("plain text");
      assertThat(result.processor().id()).isEqualTo("apache-tika");
      assertThat(result.processor().version()).isEqualTo("4.0.0");
    }
  }

  @Test
  void extractsMarkupThroughTheTypedServiceContract() {
    try (ServiceFixture services = kit.services()) {
      ContentExtractor extractor = services.service(ContentExtractor.CONTRACT);

      ContentExtractionResult result =
          successful(
              extractor.extract(request("<h1>Hello</h1><p>world</p>", "page.html", "text/html", 1_024)));

      assertThat(result.text()).contains("Hello").contains("world");
    }
  }

  @Test
  void mapsTheBoundedTextLimitToThePortableOutputLimitFailure() {
    try (ServiceFixture services = kit.services()) {
      ContentExtractor extractor = services.service(ContentExtractor.CONTRACT);

      ContentExtractionOutcome outcome =
          extractor.extract(request("this output is too long", "large.txt", "text/plain", 5));

      assertThat(outcome)
          .isInstanceOfSatisfying(
              ContentExtractionFailure.class,
              failure ->
                  assertThat(failure.category())
                      .isEqualTo(ContentExtractionFailureCategory.OUTPUT_LIMIT_EXCEEDED));
    }
  }

  @Test
  void rejectsANonLoopbackOcrWorkerEndpoint() {
    ConfigFixture configuration =
        ConfigFixture.empty()
            .serviceConfiguration(MODULE_ID, Map.of("ocrWorkerUrl", "http://example.com:8080"));

    assertThatThrownBy(() -> kit.services(configuration))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loopback");
  }

  private static ContentExtractionResult successful(ContentExtractionOutcome outcome) {
    assertThat(outcome).isInstanceOf(ContentExtractionResult.class);
    return (ContentExtractionResult) outcome;
  }

  private static ContentExtractionRequest request(
      String content, String name, String mediaType, int maximumTextCharacters) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return request(
        new ByteArrayInputStream(bytes), bytes.length, name, mediaType, maximumTextCharacters);
  }

  private static ContentExtractionRequest request(
      InputStream input, long byteCount, String name, String mediaType, int maximumTextCharacters) {
    return new ContentExtractionRequest(
        new ContentSourceMetadata(name, mediaType, byteCount, SHA_256),
        ContentSourceInput.singleUse(input, byteCount),
        new ContentExtractionLimits(byteCount, maximumTextCharacters, 32, 256, 0));
  }
}
