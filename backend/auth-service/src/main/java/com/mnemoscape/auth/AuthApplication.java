package com.mnemoscape.auth;

import com.ulisesbocchio.jasyptspringboot.annotation.EnableEncryptableProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * auth-service 启动类。
 *
 * <p>{@link EnableEncryptableProperties} 开启 Jasypt 的属性解密支持。
 * 之后 application.yml 里的 {@code ENC(...)} 字段会在绑定到 @Value /
 * Environment 时被自动解密。该注解只对当前 Spring 上下文生效，不会污染
 * common 模块里的其他服务 — 每个服务要单独开启。
 */
@SpringBootApplication(scanBasePackages = {"com.mnemoscape.auth", "com.mnemoscape.common"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableAsync
@EnableEncryptableProperties
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
