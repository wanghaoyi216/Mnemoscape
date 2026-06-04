package com.mnemoscape.auth;

import com.mnemoscape.common.EnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Service entry point.
 *
 * <p>{@code @EnableFeignClients} scans the {@code com.mnemoscape.auth.client}
 * package for declarative HTTP clients — currently the
 * {@code MemoryServiceClient} introduced for the admin-dashboard
 * active-users aggregation (admin-dashboard task 6.5; Requirements 6.6).
 * Restricting {@code basePackages} to the client sub-package mirrors the
 * convention used by ai-service / resonance-service and avoids accidentally
 * picking up Feign-annotated interfaces in unrelated areas of the module.
 */
@SpringBootApplication(scanBasePackages = {"com.mnemoscape.auth", "com.mnemoscape.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.mnemoscape.auth.client")
public class AuthApplication {
    public static void main(String[] args) {
        EnvLoader.load();
        SpringApplication.run(AuthApplication.class, args);
    }
}
