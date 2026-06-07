package com.mnemoscape.resonance;

import com.mnemoscape.common.EnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.mnemoscape.resonance", "com.mnemoscape.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.mnemoscape.resonance.client")
public class ResonanceApplication {
    public static void main(String[] args) {
        EnvLoader.load();
        SpringApplication.run(ResonanceApplication.class, args);
    }
}
