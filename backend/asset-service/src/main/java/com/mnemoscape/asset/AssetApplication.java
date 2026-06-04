package com.mnemoscape.asset;

import com.mnemoscape.common.EnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"com.mnemoscape.asset", "com.mnemoscape.common"})
@ConfigurationPropertiesScan
public class AssetApplication {
    public static void main(String[] args) {
        EnvLoader.load();
        SpringApplication.run(AssetApplication.class, args);
    }
}
