package com.mnemoscape.ai;

import com.mnemoscape.common.EnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.mnemoscape.ai", "com.mnemoscape.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.mnemoscape.ai.client")
@org.springframework.cache.annotation.EnableCaching
public class AiApplication {
    public static void main(String[] args) {
        EnvLoader.load();
        SpringApplication.run(AiApplication.class, args);
    }
}
