package org.zalava.modules.tika;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;
import org.zalava.ZalavaServiceContract;
import org.zalava.ZalavaServiceDescriptor;
import org.zalava.ZalavaServiceFactory;
import org.zalava.ZalavaServiceFactoryContext;
import org.zalava.content.ContentExtractionFailure;
import org.zalava.content.ContentExtractionFailureCategory;
import org.zalava.content.ContentExtractionOutcome;
import org.zalava.content.ContentExtractionRequest;
import org.zalava.content.ContentExtractionResult;
import org.zalava.content.ContentExtractor;
import org.zalava.content.ContentProcessor;
import org.zalava.content.ContentSourceInput;

final class TikaContentExtractorFactory implements ZalavaServiceFactory<ContentExtractor> {
  private static final ContentProcessor PROCESSOR = new ContentProcessor("apache-tika", "4.0.0");

  @Override
  public ZalavaServiceDescriptor descriptor() {
    return new ZalavaServiceDescriptor(
        ContentExtractor.CONTRACT.serviceId(),
        TikaSeaModule.MODULE_ID,
        ContentExtractor.CONTRACT.contractVersion());
  }

  @Override
  public ZalavaServiceContract<ContentExtractor> contract() {
    return ContentExtractor.CONTRACT;
  }

  @Override
  public ContentExtractor create(ZalavaServiceFactoryContext context) {
    ContentExtractor tika = this::extract;
    WorkerOcrClient worker =
        WorkerOcrClient.from(context == null ? Map.of() : context.configuration());
    if (worker == null) return tika;
    return request -> {
      byte[] bytes;
      try (var input = request.input().openStream()) {
        bytes = input.readAllBytes();
      } catch (IOException exception) {
        return failure(
            request,
            ContentExtractionFailureCategory.MALFORMED_INPUT,
            "Tika could not read source");
      }
      ContentExtractionRequest tikaRequest =
          new ContentExtractionRequest(
              request.source(),
              ContentSourceInput.singleUse(new ByteArrayInputStream(bytes), bytes.length),
              request.limits());
      ContentExtractionOutcome outcome = tika.extract(tikaRequest);
      if (!(outcome instanceof ContentExtractionResult result)
          || !result.text().isBlank()
          || !worker.supports(request.source().mediaType())) return outcome;
      return worker.extract(request, bytes);
    };
  }

  private ContentExtractionOutcome extract(ContentExtractionRequest request) {
    ContentExtractor extractor =
        candidate -> {
          Metadata metadata = new Metadata();
          BodyContentHandler handler =
              new BodyContentHandler(request.limits().maximumTextCharacters());
          try (TikaInputStream input = TikaInputStream.get(request.input().openStream())) {
            new AutoDetectParser().parse(input, handler, metadata, new ParseContext());
            return ContentExtractionResult.forRequest(
                request,
                PROCESSOR,
                handler.toString(),
                normalized(
                    metadata,
                    request.limits().maximumMetadataEntries(),
                    request.limits().maximumMetadataValueCharacters()),
                List.of());
          } catch (EncryptedDocumentException exception) {
            return failure(
                request,
                ContentExtractionFailureCategory.ENCRYPTED,
                "Tika could not open an encrypted source");
          } catch (SAXException exception) {
            if (WriteLimitReachedException.isWriteLimitReached(exception)) {
              return failure(
                  request,
                  ContentExtractionFailureCategory.OUTPUT_LIMIT_EXCEEDED,
                  "Tika text output exceeded the requested limit");
            }
            return failure(
                request,
                ContentExtractionFailureCategory.MALFORMED_INPUT,
                "Tika could not parse the source");
          } catch (IOException exception) {
            return failure(
                request,
                ContentExtractionFailureCategory.MALFORMED_INPUT,
                "Tika could not read the source");
          } catch (TikaException exception) {
            return failure(
                request,
                ContentExtractionFailureCategory.MALFORMED_INPUT,
                "Tika could not parse the source");
          } catch (RuntimeException exception) {
            return failure(
                request, ContentExtractionFailureCategory.INTERNAL, "Tika extraction failed");
          }
        };
    return extractor.extract(request);
  }

  private static ContentExtractionFailure failure(
      org.zalava.content.ContentExtractionRequest request,
      ContentExtractionFailureCategory category,
      String detail) {
    return ContentExtractionFailure.forRequest(request, PROCESSOR, category, detail);
  }

  private static Map<String, List<String>> normalized(
      Metadata metadata, int maximumEntries, int maximumValueCharacters) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (String name : metadata.names()) {
      if (result.size() == maximumEntries) {
        break;
      }
      if (name == null || name.isBlank()) {
        continue;
      }
      List<String> values =
          java.util.Arrays.stream(metadata.getValues(name))
              .filter(java.util.Objects::nonNull)
              .map(value -> value.substring(0, Math.min(value.length(), maximumValueCharacters)))
              .toList();
      if (!values.isEmpty()) {
        result.put(name, values);
      }
    }
    return result;
  }
}
