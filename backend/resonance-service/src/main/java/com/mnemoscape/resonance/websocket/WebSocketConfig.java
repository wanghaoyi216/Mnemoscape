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
    private final SupportWebSocketHandler supportHandler;

    public WebSocketConfig(ResonanceWebSocketHandler resonanceHandler,
                           ChatWebSocketHandler chatHandler,
                           SupportWebSocketHandler supportHandler) {
        this.resonanceHandler = resonanceHandler;
        this.chatHandler = chatHandler;
        this.supportHandler = supportHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(resonanceHandler, "/ws/resonance")
                .setAllowedOrigins("*");
        registry.addHandler(chatHandler, "/ws/chat")
                .setAllowedOrigins("*");
        registry.addHandler(supportHandler, "/ws/support")
                .setAllowedOrigins("*");
    }
}
