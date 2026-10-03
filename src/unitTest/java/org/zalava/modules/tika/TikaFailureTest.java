package org.zalava.modules.tika;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.tika.exception.*;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;
import org.zalava.api.*;
import org.zalava.api.extensions.content.*;

class TikaFailureTest {
  @Test
  void classifiesParserFailures() throws Exception {
    for (Exception failure :
        List.of(
            new EncryptedDocumentException(),
            new SAXException("malformed"),
            new TikaException("malformed"),
            new IllegalStateException("internal"),
            new IOException("broken"))) {
      try (var parsers =
          mockConstruction(
              AutoDetectParser.class,
              (parser, ctx) ->
                  doThrow(failure)
                      .when(parser)
                      .parse(any(org.apache.tika.io.TikaInputStream.class), any(), any(), any()))) {
        var result =
            new TikaContentExtractorFactory()
                .create(null)
                .extract(request("text/plain", new ByteArrayInputStream(new byte[] {1}), 32));
        assertThat(result).isInstanceOf(ContentExtractionFailure.class);
        assertThat(((ContentExtractionFailure) result).category())
            .isEqualTo(
                failure instanceof EncryptedDocumentException
                    ? ContentExtractionFailureCategory.ENCRYPTED
                    : failure instanceof IllegalStateException
                        ? ContentExtractionFailureCategory.INTERNAL
                        : ContentExtractionFailureCategory.MALFORMED_INPUT);
      }
    }
  }

  @Test
  void normalizesMetadataWithinBounds() throws Exception {
    try (var parsers =
        mockConstruction(
            AutoDetectParser.class,
            (parser, ctx) ->
                doAnswer(
                        call -> {
                          Metadata metadata = call.getArgument(2);
                          metadata.add("long", "very long metadata value");
                          metadata.add("second", "second");
                          metadata.add("", "blank name");
                          return null;
                        })
                    .when(parser)
                    .parse(any(org.apache.tika.io.TikaInputStream.class), any(), any(), any()))) {
      var result =
          (ContentExtractionResult)
              new TikaContentExtractorFactory()
                  .create(null)
                  .extract(request("text/plain", new ByteArrayInputStream(new byte[] {1}), 1));
      assertThat(result.metadata()).hasSize(1);
      assertThat(result.metadata().values().iterator().next())
          .allMatch(value -> value.length() <= 5);
    }
  }

  @Test
  void handlesWorkerInputAndFallback() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/ocr",
        exchange -> {
          byte[] body = "{\"text\":\"recognized\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var context =
          new ZalavaServiceFactoryContext(
              TikaZalavaModule.MODULE_ID,
              Map.of(),
              Map.of(),
              Map.of("ocrWorkerUrl", "http://127.0.0.1:" + server.getAddress().getPort()),
              null);
      var extractor = new TikaContentExtractorFactory().create(context);
      InputStream broken =
          new InputStream() {
            public int read() throws IOException {
              throw new IOException("unreadable");
            }
          };
      assertThat(extractor.extract(request("image/png", broken, 32)))
          .isInstanceOf(ContentExtractionFailure.class);
      try (var parsers = mockConstruction(AutoDetectParser.class, (parser, ctx) -> {})) {
        assertThat(
                ((ContentExtractionResult)
                        extractor.extract(
                            request("image/png", new ByteArrayInputStream(new byte[] {1}), 32)))
                    .text())
            .isEqualTo("recognized");
        assertThat(
                ((ContentExtractionResult)
                        extractor.extract(
                            request("text/plain", new ByteArrayInputStream(new byte[] {1}), 32)))
                    .text())
            .isEmpty();
      }
      assertThat(
              extractor.extract(
                  request(
                      "text/plain",
                      new ByteArrayInputStream("normal text".getBytes(StandardCharsets.UTF_8)),
                      32)))
          .isInstanceOf(ContentExtractionResult.class);
      try (var parsers =
          mockConstruction(
              AutoDetectParser.class,
              (parser, ctx) ->
                  doThrow(new TikaException("bad"))
                      .when(parser)
                      .parse(any(org.apache.tika.io.TikaInputStream.class), any(), any(), any()))) {
        assertThat(
                extractor.extract(
                    request("image/png", new ByteArrayInputStream(new byte[] {1}), 32)))
            .isInstanceOf(ContentExtractionFailure.class);
      }
    } finally {
      server.stop(0);
    }
  }

  private static ContentExtractionRequest request(
      String media, InputStream input, int metadataEntries) {
    int bytes = input instanceof ByteArrayInputStream stream ? stream.available() : 1;
    return new ContentExtractionRequest(
        new ContentSourceMetadata("source", media, bytes, "0".repeat(64)),
        ContentSourceInput.singleUse(input, bytes),
        new ContentExtractionLimits(100, 100, metadataEntries, 5, 0));
  }
}
