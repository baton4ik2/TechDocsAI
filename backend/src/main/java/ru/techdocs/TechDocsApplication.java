package ru.techdocs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TechDocsApplication {

    public static void main(String[] args) {
        SpringApplication.run(TechDocsApplication.class, args);
    }
}
