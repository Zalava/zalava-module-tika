package org.zalava.modules.tika;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.zalava.api.extensions.content.*;

class WorkerOcrBoundaryTest {
  @Test
  void restrictsWorkerEndpointAndMediaTypes() {
    for (Object value : List.of("", 3))
      assertThat(WorkerOcrClient.from(Map.of("ocrWorkerUrl", value))).isNull();
    assertThat(WorkerOcrClient.from(Map.of())).isNull();
    for (String url : List.of("https://localhost", "http://example.com", "file:///tmp/a"))
      assertThatThrownBy(() -> WorkerOcrClient.from(Map.of("ocrWorkerUrl", url)))
          .isInstanceOf(IllegalArgumentException.class);
    for (Object languages : List.of("eng", "eng+spa", "bad-value", 3)) {
      var worker =
          WorkerOcrClient.from(
              Map.of("ocrWorkerUrl", "http://127.0.0.1:8080", "ocrLanguages", languages));
      for (String type : List.of("image/png", "image/jpeg", "application/pdf"))
        assertThat(worker.supports(type)).isTrue();
      assertThat(worker.supports("text/plain")).isFalse();
      assertThat(worker.supports(null)).isFalse();
    }
    assertThat(WorkerOcrClient.from(Map.of("ocrWorkerUrl", "http://localhost:8080"))).isNotNull();
  }

  @Test
  void translatesWorkerResultsAndLimits() throws Exception {
    HttpClient client = mock(HttpClient.class);
    var worker = new WorkerOcrClient(URI.create("http://localhost/v1/ocr"), "eng", client);
    for (String[] example :
        List.of(
            new String[] {"{}", "MALFORMED_INPUT"},
            new String[] {"{\"text\":\" \"}", "UNAVAILABLE"},
            new String[] {"{\"text\":\"too long text\"}", "OUTPUT_LIMIT_EXCEEDED"})) {
      HttpResponse<String> response = response(200, example[0]);
      when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
          .thenReturn(response);
      assertCategory(worker.extract(request(5), new byte[] {1}), example[1]);
    }
    HttpResponse<String> success = response(200, "{\"text\":\"hello\\nworld\"}");
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(success);
    assertThat(((ContentExtractionResult) worker.extract(request(100), new byte[] {1})).text())
        .isEqualTo("hello\nworld");
    for (String code :
        List.of(
            "EMPTY_RESULT",
            "INPUT_LIMIT_EXCEEDED",
            "PAGE_LIMIT_EXCEEDED",
            "PIXEL_LIMIT_EXCEEDED",
            "OUTPUT_LIMIT_EXCEEDED",
            "DEADLINE_EXCEEDED",
            "UNKNOWN")) {
      HttpResponse<String> error = response(422, "{\"code\":\"" + code + "\"}");
      when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
          .thenReturn(error);
      String expected =
          code.equals("EMPTY_RESULT")
              ? "UNAVAILABLE"
              : code.equals("DEADLINE_EXCEEDED")
                  ? "TIMED_OUT"
                  : code.equals("OUTPUT_LIMIT_EXCEEDED")
                      ? code
                      : code.equals("UNKNOWN") ? "MALFORMED_INPUT" : "INPUT_LIMIT_EXCEEDED";
      assertCategory(worker.extract(request(100), new byte[] {1}), expected);
    }
    HttpResponse<String> error = response(500, "{}");
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(error);
    assertCategory(worker.extract(request(100), new byte[0]), "UNAVAILABLE");
  }

  @Test
  void translatesTimeoutAndTransportFailure() throws Exception {
    for (Exception error :
        List.of(new HttpTimeoutException("timeout"), new IOException("broken"))) {
      HttpClient client = mock(HttpClient.class);
      when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
          .thenThrow(error);
      assertCategory(
          new WorkerOcrClient(URI.create("http://localhost"), "eng", client)
              .extract(request(100), new byte[0]),
          error instanceof HttpTimeoutException ? "TIMED_OUT" : "UNAVAILABLE");
    }
  }

  private static ContentExtractionRequest request(int maximum) {
    return new ContentExtractionRequest(
        new ContentSourceMetadata("image.png", "image/png", 1, "0".repeat(64)),
        ContentSourceInput.singleUse(new ByteArrayInputStream(new byte[] {1}), 1),
        new ContentExtractionLimits(1, maximum, 32, 256, 0));
  }

  private static void assertCategory(ContentExtractionOutcome outcome, String category) {
    assertThat(outcome).isInstanceOf(ContentExtractionFailure.class);
    assertThat(((ContentExtractionFailure) outcome).category().name()).isEqualTo(category);
  }

  private static HttpResponse<String> response(int status, String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    return response;
  }
}
