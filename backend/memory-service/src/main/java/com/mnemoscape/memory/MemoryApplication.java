package com.mnemoscape.memory;

import com.mnemoscape.common.EnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.mnemoscape.memory", "com.mnemoscape.common"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
@EnableCaching
@org.springframework.scheduling.annotation.EnableAsync
public class MemoryApplication {
    public static void main(String[] args) {
        EnvLoader.load();
        SpringApplication.run(MemoryApplication.class, args);
    }
}
