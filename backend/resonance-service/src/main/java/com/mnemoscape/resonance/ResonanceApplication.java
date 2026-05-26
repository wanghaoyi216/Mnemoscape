package com.mnemoscape.resonance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.mnemoscape.resonance", "com.mnemoscape.common"})
@EnableDiscoveryClient
public class ResonanceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResonanceApplication.class, args);
    }
}
