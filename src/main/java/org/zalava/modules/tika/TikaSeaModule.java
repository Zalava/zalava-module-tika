package org.zalava.modules.tika;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.zalava.api.ModuleDescriptor;
import org.zalava.api.ProviderFactory;
import org.zalava.api.ZalavaModule;
import org.zalava.api.ZalavaServiceFactory;

/** Service-only module for bounded Apache Tika 4 extraction. */
public final class TikaSeaModule implements ZalavaModule {
  public static final String MODULE_ID = "zalava-module-tika";

  @Override
  public ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        MODULE_ID, version(), "Apache Tika Content Extractor", "Bounded Tika 4 content extraction");
  }

  @Override
  public List<ProviderFactory> providerFactories() {
    return List.of();
  }

  @Override
  public List<ZalavaServiceFactory<?>> serviceFactories() {
    return List.of(new TikaContentExtractorFactory());
  }

  static String version() {
    Properties properties = new Properties();
    try (InputStream input = TikaSeaModule.class.getResourceAsStream("/module.properties")) {
      if (input == null) throw new IllegalStateException("Missing module version metadata");
      properties.load(input);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read module version metadata", exception);
    }
    return properties.getProperty("module.version");
  }
}
