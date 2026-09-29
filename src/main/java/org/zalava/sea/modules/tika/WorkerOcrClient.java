package org.zalava.modules.tika;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.zalava.content.ContentExtractionFailure;
import org.zalava.content.ContentExtractionFailureCategory;
import org.zalava.content.ContentExtractionOutcome;
import org.zalava.content.ContentExtractionRequest;
import org.zalava.content.ContentExtractionResult;
import org.zalava.content.ContentProcessor;

/** Explicit, bounded client for the separately isolated OCR worker. */
final class WorkerOcrClient {
  private static final ContentProcessor PROCESSOR = new ContentProcessor("sea-ocr-worker", "1");
  private static final Pattern CODE = Pattern.compile("\\\"code\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
  private static final Pattern TEXT = Pattern.compile("\\\"text\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
  private final URI endpoint;
  private final String languages;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private WorkerOcrClient(URI endpoint, String languages) { this.endpoint = endpoint; this.languages = languages; }

  static WorkerOcrClient from(Map<String, Object> configuration) {
    Object value = configuration.get("ocrWorkerUrl");
    if (!(value instanceof String raw) || raw.isBlank()) return null;
    URI uri = URI.create(raw);
    if (!"http".equals(uri.getScheme()) || !("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost())))
      throw new IllegalArgumentException("ocrWorkerUrl must be a loopback http URI");
    Object configuredLanguages = configuration.get("ocrLanguages");
    String languages = configuredLanguages instanceof String text && text.matches("[a-z]{3}(\\+[a-z]{3})*") ? text : "eng";
    return new WorkerOcrClient(uri.resolve("/v1/ocr"), languages);
  }

  boolean supports(String mediaType) { return "image/png".equals(mediaType) || "image/jpeg".equals(mediaType) || "application/pdf".equals(mediaType); }

  ContentExtractionOutcome extract(ContentExtractionRequest request, byte[] bytes) {
    try {
      HttpRequest call = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(16))
          .header("Content-Type", request.source().mediaType()).header("X-Sea-Ocr-Languages", languages)
          .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
      HttpResponse<String> response = client.send(call, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        Matcher text = TEXT.matcher(response.body());
        if (!text.find()) return failure(request, ContentExtractionFailureCategory.MALFORMED_INPUT, "OCR worker returned an invalid response");
        String output = unescape(text.group(1));
        if (output.isBlank()) return failure(request, ContentExtractionFailureCategory.UNAVAILABLE, "OCR worker returned no recognized text");
        if (output.length() > request.limits().maximumTextCharacters()) {
          return failure(request, ContentExtractionFailureCategory.OUTPUT_LIMIT_EXCEEDED, "OCR worker output exceeded requested limit");
        }
        return ContentExtractionResult.forRequest(request, PROCESSOR, output, Map.of(), java.util.List.of());
      }
      return failure(request, category(response.body()), "OCR worker could not extract source");
    } catch (java.net.http.HttpTimeoutException exception) {
      return failure(request, ContentExtractionFailureCategory.TIMED_OUT, "OCR worker deadline exceeded");
    } catch (Exception exception) {
      return failure(request, ContentExtractionFailureCategory.UNAVAILABLE, "OCR worker is unavailable");
    }
  }
  private static ContentExtractionFailureCategory category(String body) {
    Matcher code = CODE.matcher(body);
    if (!code.find()) return ContentExtractionFailureCategory.UNAVAILABLE;
    return switch (code.group(1)) { case "EMPTY_RESULT" -> ContentExtractionFailureCategory.UNAVAILABLE; case "INPUT_LIMIT_EXCEEDED", "PAGE_LIMIT_EXCEEDED", "PIXEL_LIMIT_EXCEEDED" -> ContentExtractionFailureCategory.INPUT_LIMIT_EXCEEDED; case "OUTPUT_LIMIT_EXCEEDED" -> ContentExtractionFailureCategory.OUTPUT_LIMIT_EXCEEDED; case "DEADLINE_EXCEEDED" -> ContentExtractionFailureCategory.TIMED_OUT; default -> ContentExtractionFailureCategory.MALFORMED_INPUT; };
  }
  private static String unescape(String value) { return value.replace("\\\\n", "\n").replace("\\\\\"", "\"").replace("\\\\\\\\", "\\"); }
  private static ContentExtractionFailure failure(ContentExtractionRequest request, ContentExtractionFailureCategory category, String detail) { return ContentExtractionFailure.forRequest(request, PROCESSOR, category, detail); }
}
