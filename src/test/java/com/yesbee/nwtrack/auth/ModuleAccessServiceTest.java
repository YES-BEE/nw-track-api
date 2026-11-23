package com.yesbee.nwtrack.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleAccessServiceTest {
    @Test void pendingInitialPasswordAllowsPasswordSetupButBlocksApplicationData(){
        var jdbc=mock(JdbcTemplate.class);var access=mock(AccessPolicy.class);
        UUID actor=UUID.randomUUID();when(access.actor()).thenReturn(actor);
        when(access.clientType()).thenReturn("PAD");when(access.role()).thenReturn("STYLE_CONFIGURATOR");
        when(jdbc.queryForObject("select count(*) from users where id=? and initial_password_expires_at is not null",Integer.class,actor)).thenReturn(1);
        var modules=new ModuleAccessService(jdbc,access);
        modules.authorize("/api/v1/auth/me","GET");
        modules.authorize("/api/v1/auth/change-password","POST");
        modules.authorize("/api/v1/auth/logout","POST");
        assertThatThrownBy(()->modules.authorize("/api/v1/lots","GET")).isInstanceOf(AccessDeniedException.class).hasMessageContaining("initial password");
        assertThatThrownBy(()->modules.authorize("/api/v1/auth/refresh-tokens","POST")).isInstanceOf(AccessDeniedException.class);
    }
    @Test void grantsConfiguratorDraftEditingAndConfigurationModulesWithoutOperations(){
        var jdbc=mock(JdbcTemplate.class);var access=mock(AccessPolicy.class);
        UUID actor=UUID.randomUUID();when(access.actor()).thenReturn(actor);
        when(jdbc.queryForObject("select count(*) from users where id=? and initial_password_expires_at is not null",Integer.class,actor)).thenReturn(0);
        when(access.clientType()).thenReturn("PC");when(access.role()).thenReturn("STYLE_CONFIGURATOR");
        when(access.ownStylesOnly()).thenReturn(true);
        when(access.token()).thenReturn(Jwt.withTokenValue("test").header("alg","HS256").subject("creator").build());
        when(access.modulesForUsername("creator")).thenReturn(List.of(
            Map.of("key","lots"),Map.of("key","newstyle"),Map.of("key","process"),
            Map.of("key","tags"),Map.of("key","storage"),Map.of("key","trolleys")));
        var modules=new ModuleAccessService(jdbc,access);
        modules.authorize("/api/v1/styles/"+UUID.randomUUID()+"/lots","POST");
        modules.authorize("/api/v1/lots/"+UUID.randomUUID()+"/cancel","POST");
        modules.authorize("/api/v1/lots/"+UUID.randomUUID()+"/trolley","PUT");
        modules.authorize("/api/v1/lots/"+UUID.randomUUID()+"/trolley","DELETE");
        modules.authorize("/api/v1/trolleys/available","GET");
        modules.authorize("/api/v1/styles/"+UUID.randomUUID()+"/release","POST");
        modules.authorize("/api/v1/styles/"+UUID.randomUUID(),"PUT");
        modules.authorize("/api/v1/lots/"+UUID.randomUUID(),"PUT");
        modules.authorize("/api/v1/processes","POST");
        modules.authorize("/api/v1/nfc-tags","POST");
        modules.authorize("/api/v1/storage-areas","POST");
        modules.authorize("/api/v1/trolleys","GET");
        assertThatThrownBy(()->modules.authorize("/api/v1/styles/"+UUID.randomUUID()+"/processes","PUT")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->modules.authorize("/api/v1/lots/"+UUID.randomUUID()+"/dispatch","POST")).isInstanceOf(AccessDeniedException.class);
    }
    @Test void limitsPadConfiguratorToOwnedStyleLotAndTrolleyEndpoints(){
        var jdbc=mock(JdbcTemplate.class);var access=mock(AccessPolicy.class);
        UUID actor=UUID.randomUUID();when(access.actor()).thenReturn(actor);
        when(jdbc.queryForObject("select count(*) from users where id=? and initial_password_expires_at is not null",Integer.class,actor)).thenReturn(0);
        when(access.clientType()).thenReturn("PAD");when(access.role()).thenReturn("STYLE_CONFIGURATOR");
        var modules=new ModuleAccessService(jdbc,access);String id=UUID.randomUUID().toString();
        modules.authorize("/api/v1/auth/me","GET");
        modules.authorize("/api/v1/auth/change-password","POST");
        modules.authorize("/api/v1/auth/refresh-tokens","POST");
        modules.authorize("/api/v1/auth/logout","POST");
        modules.authorize("/api/v1/auth/sessions","GET");
        modules.authorize("/api/v1/auth/sessions/"+id,"DELETE");
        assertThatThrownBy(()->modules.authorize("/api/v1/auth/other","GET")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->modules.authorize("/api/v1/sync/bootstrap","GET")).isInstanceOf(AccessDeniedException.class);
        for(String path:List.of("/api/v1/styles","/api/v1/styles/"+id,"/api/v1/styles/"+id+"/lots",
            "/api/v1/lots","/api/v1/lots/"+id,"/api/v1/trolleys","/api/v1/trolleys/available","/api/v1/trolleys/"+id))
            modules.authorize(path,"GET");
        modules.authorize("/api/v1/lots/"+id+"/trolley","PUT");
        modules.authorize("/api/v1/lots/"+id+"/trolley","DELETE");
        assertThatThrownBy(()->modules.authorize("/api/v1/styles","POST")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->modules.authorize("/api/v1/lots/"+id+"/dispatch","POST")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->modules.authorize("/api/v1/processes","GET")).isInstanceOf(AccessDeniedException.class);
    }
}
