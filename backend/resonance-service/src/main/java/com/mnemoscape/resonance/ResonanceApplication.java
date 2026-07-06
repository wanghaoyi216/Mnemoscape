package com.mnemoscape.resonance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = {"com.mnemoscape.resonance", "com.mnemoscape.common"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableAsync
public class ResonanceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResonanceApplication.class, args);
    }
}
