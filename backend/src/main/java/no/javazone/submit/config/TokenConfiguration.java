package no.javazone.submit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "token")
public class TokenConfiguration {

    public String generateonbehalfofother;

    public void setGenerateonbehalfofother(String generateonbehalfofother) {
        this.generateonbehalfofother = generateonbehalfofother;
    }

}
