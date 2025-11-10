package searchengine.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SitesList.class, SearchEngineProperties.class})
public class AppConfig {
}