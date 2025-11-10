package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "search-engine")
@Getter
@Setter
public class SearchEngineProperties {
    private String userAgent;
    private String referrer;
    private int delayMinMs;
    private int delayMaxMs;
}