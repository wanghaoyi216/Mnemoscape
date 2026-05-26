package com.mnemoscape.resonance.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ResonanceWebSocketHandler resonanceHandler;
    private final ChatWebSocketHandler chatHandler;

    public WebSocketConfig(ResonanceWebSocketHandler resonanceHandler, ChatWebSocketHandler chatHandler) {
        this.resonanceHandler = resonanceHandler;
        this.chatHandler = chatHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(resonanceHandler, "/ws/resonance")
                .setAllowedOrigins("*");
        registry.addHandler(chatHandler, "/ws/chat")
                .setAllowedOrigins("*");
    }
}
