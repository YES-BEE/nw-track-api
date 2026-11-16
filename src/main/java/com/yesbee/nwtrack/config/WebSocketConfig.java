package com.yesbee.nwtrack.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import com.yesbee.nwtrack.auth.AccessPolicy;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final String[] allowedOrigins;
    private final AccessPolicy access;
    private final JdbcTemplate jdbc;

    public WebSocketConfig(@Value("${nwtrack.allowed-origins}") String[] allowedOrigins,AccessPolicy access,JdbcTemplate jdbc) {
        this.allowedOrigins = allowedOrigins;
        this.access=access;this.jdbc=jdbc;
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration){
        registration.interceptors(new ChannelInterceptor(){
            @Override public Message<?> preSend(Message<?> message,MessageChannel channel){
                var headers=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
                if(headers!=null && headers.getCommand()!=null && Set.of(StompCommand.SUBSCRIBE,StompCommand.SEND).contains(headers.getCommand())){
                    if(!(headers.getUser() instanceof JwtAuthenticationToken auth))throw new AccessDeniedException("Authentication required");
                    var jwt=auth.getToken();access.channel(jwt.getSubject(),Objects.requireNonNullElse(jwt.getClaimAsString("clientType"),"PC"));
                    UUID actor=jdbc.queryForObject("select id from users where lower(username)=lower(?) and enabled",UUID.class,jwt.getSubject());
                    if(!access.effectivePermissions(actor,Objects.requireNonNullElse(jwt.getClaimAsString("clientType"),"PC")).contains("READ")||!jwt.getClaimAsStringList("roles").contains(access.role(actor)))throw new AccessDeniedException("Read access or role changed; log in again");
                    String destination=headers.getDestination();
                    if(headers.getCommand()!=StompCommand.SUBSCRIBE)throw new AccessDeniedException("Client messages are not supported");
                    if(destination!=null&&destination.startsWith("/topic/stations/"))access.station(actor,UUID.fromString(destination.substring("/topic/stations/".length())),false);
                    else if(!"/user/queue/events".equals(destination))throw new AccessDeniedException("Subscription not allowed");
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
