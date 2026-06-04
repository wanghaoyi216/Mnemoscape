package com.mnemoscape.gateway;

import com.mnemoscape.common.EnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        EnvLoader.load();
        SpringApplication.run(GatewayApplication.class, args);
    }
}
