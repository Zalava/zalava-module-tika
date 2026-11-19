package org.zalava.modules.tika;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.zalava.api.ModuleConfigurationDescriptor;
import org.zalava.api.ModuleDescriptor;
import org.zalava.api.ProviderFactory;
import org.zalava.api.RequirementMode;
import org.zalava.api.ZalavaModule;
import org.zalava.api.ZalavaServiceFactory;
import org.zalava.api.ZalavaServiceRequirement;
import org.zalava.api.extensions.managed.ManagedOcrService;
import org.zalava.api.extensions.managed.ManagedServiceDeclaration;
import org.zalava.api.extensions.managed.ManagedServiceDesiredState;
import org.zalava.api.extensions.managed.ManagedServiceLifecycle;
import org.zalava.api.extensions.managed.ManagedServiceLimits;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Service-only module for bounded Apache Tika 4 extraction. */
public final class TikaZalavaModule implements ZalavaModule {
  public static final String MODULE_ID = "zalava-module-tika";

  @Override
  public ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        MODULE_ID, version(), "Apache Tika Content Extractor", "Bounded Tika 4 content extraction");
  }

  @Override
  public ModuleConfigurationDescriptor configuration() {
    try (InputStream input =
        TikaZalavaModule.class.getResourceAsStream("/module-configuration.json")) {
      if (input == null) throw new IllegalStateException("Missing module configuration schema");
      Map<String, Object> schema = new JsonMapper().readValue(input, new TypeReference<>() {});
      return new ModuleConfigurationDescriptor(schema);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read module configuration schema", exception);
    }
  }

  @Override
  public List<ProviderFactory> providerFactories() {
    return List.of();
  }

  @Override
  public List<ZalavaServiceFactory<?>> serviceFactories() {
    return List.of(new TikaContentExtractorFactory());
  }

  @Override
  public List<ZalavaServiceRequirement> serviceRequirements() {
    var contract = ManagedOcrService.CONTRACT;
    return List.of(
        new ZalavaServiceRequirement(
            contract.serviceId(), contract.contractVersion(), RequirementMode.OPTIONAL));
  }

  @Override
  public List<ManagedServiceDeclaration> managedServices() {
    String id = ManagedOcrService.SERVICE_ID;
    var state =
        new ManagedServiceDesiredState(
            id,
            "ghcr.io/zalava/zalava-tika-ocr-worker@sha256:ec6134c799a81b0239b850df1c6698b8bcfee2632cae95b769bad77f86ca3c76",
            "0.2.0",
            ManagedServiceLifecycle.RUNNING,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            new ManagedServiceLimits(1000, 512L * 1024 * 1024, 64),
            Duration.ofSeconds(40),
            2,
            true);
    return List.of(new ManagedServiceDeclaration(id, state));
  }

  static String version() {
    Properties properties = new Properties();
    try (InputStream input = TikaZalavaModule.class.getResourceAsStream("/module.properties")) {
      if (input == null) throw new IllegalStateException("Missing module version metadata");
      properties.load(input);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read module version metadata", exception);
    }
    return properties.getProperty("module.version");
  }
}
