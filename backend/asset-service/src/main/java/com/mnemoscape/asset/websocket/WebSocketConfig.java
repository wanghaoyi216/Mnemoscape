package com.mnemoscape.asset.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册静态资源热更新 WebSocket 端点。
 *
 * <p>端点路径 {@code /ws/assets}。网关把 {@code /ws/**} 透传到对应服务；
 * 前端通过 {@code ws(s)://<host>/ws/assets} 连接，监听 {@code RESOURCE_CHANGED} 帧。
 *
 * <p>{@code setAllowedOriginPatterns("*")} 与项目其它 WS 端点（resonance-service）
 * 一致，开发期允许跨域；生产部署收紧到具体域名。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ResourceWebSocketHandler resourceHandler;

    public WebSocketConfig(ResourceWebSocketHandler resourceHandler) {
        this.resourceHandler = resourceHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(resourceHandler, "/ws/assets")
                .setAllowedOriginPatterns("*");
    }
}
