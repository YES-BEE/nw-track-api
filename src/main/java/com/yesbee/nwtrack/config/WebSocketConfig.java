package com.yesbee.nwtrack.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final String[] allowedOrigins;
    private final com.yesbee.nwtrack.auth.AccessPolicy access;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public WebSocketConfig(@Value("${nwtrack.allowed-origins}") String[] allowedOrigins,com.yesbee.nwtrack.auth.AccessPolicy access,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.allowedOrigins = allowedOrigins;
        this.access=access;this.jdbc=jdbc;
    }
    @Override public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration){
        registration.interceptors(new org.springframework.messaging.support.ChannelInterceptor(){
            @Override public org.springframework.messaging.Message<?> preSend(org.springframework.messaging.Message<?> message,org.springframework.messaging.MessageChannel channel){
                var headers=org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(message,org.springframework.messaging.simp.stomp.StompHeaderAccessor.class);
                if(headers!=null && headers.getCommand()!=null && java.util.Set.of(org.springframework.messaging.simp.stomp.StompCommand.SUBSCRIBE,org.springframework.messaging.simp.stomp.StompCommand.SEND).contains(headers.getCommand())){
                    if(!(headers.getUser() instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken auth))throw new org.springframework.security.access.AccessDeniedException("Authentication required");
                    var jwt=auth.getToken();access.channel(jwt.getSubject(),java.util.Objects.requireNonNullElse(jwt.getClaimAsString("clientType"),"PC"));
                    java.util.UUID actor=jdbc.queryForObject("select id from users where lower(username)=lower(?) and enabled",java.util.UUID.class,jwt.getSubject());
                    if(!access.effectivePermissions(actor,java.util.Objects.requireNonNullElse(jwt.getClaimAsString("clientType"),"PC")).contains("READ")||!jwt.getClaimAsStringList("roles").contains(access.role(actor)))throw new org.springframework.security.access.AccessDeniedException("Read access or role changed; log in again");
                    String destination=headers.getDestination();
                    if(headers.getCommand()!=org.springframework.messaging.simp.stomp.StompCommand.SUBSCRIBE)throw new org.springframework.security.access.AccessDeniedException("Client messages are not supported");
                    if(destination!=null&&destination.startsWith("/topic/stations/"))access.station(actor,java.util.UUID.fromString(destination.substring("/topic/stations/".length())),false);
                    else if(!"/user/queue/events".equals(destination))throw new org.springframework.security.access.AccessDeniedException("Subscription not allowed");
                }
                return message;
            }
        });
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    }
}
