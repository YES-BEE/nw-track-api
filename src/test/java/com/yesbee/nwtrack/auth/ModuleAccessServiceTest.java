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
        when(access.clientType()).thenReturn("PC");
        when(jdbc.queryForObject("select count(*) from users where id=? and initial_password_expires_at is not null",Integer.class,actor)).thenReturn(1);
        var modules=new ModuleAccessService(jdbc,access);
        modules.authorize("/api/v1/auth/me","GET");
        modules.authorize("/api/v1/auth/change-password","POST");
        assertThatThrownBy(()->modules.authorize("/api/v1/lots","GET")).isInstanceOf(AccessDeniedException.class).hasMessageContaining("initial password");
        assertThatThrownBy(()->modules.authorize("/api/v1/auth/refresh-tokens","POST")).isInstanceOf(AccessDeniedException.class);
    }
    @Test void grantsLotManagementWithoutUnlockingStyleEditingOrOperations(){
        var jdbc=mock(JdbcTemplate.class);var access=mock(AccessPolicy.class);
        UUID actor=UUID.randomUUID();when(access.actor()).thenReturn(actor);
        when(jdbc.queryForObject("select count(*) from users where id=? and initial_password_expires_at is not null",Integer.class,actor)).thenReturn(0);
        when(access.clientType()).thenReturn("PC");when(access.role()).thenReturn("STYLE_CONFIGURATOR");
        when(access.ownStylesOnly()).thenReturn(true);
        when(access.token()).thenReturn(Jwt.withTokenValue("test").header("alg","HS256").subject("creator").build());
        when(access.modulesForUsername("creator")).thenReturn(List.of(Map.of("key","lots"),Map.of("key","newstyle")));
        var modules=new ModuleAccessService(jdbc,access);
        modules.authorize("/api/v1/styles/"+UUID.randomUUID()+"/lots","POST");
        modules.authorize("/api/v1/lots/"+UUID.randomUUID()+"/cancel","POST");
        modules.authorize("/api/v1/lots/"+UUID.randomUUID()+"/trolley","PUT");
        modules.authorize("/api/v1/lots/"+UUID.randomUUID()+"/trolley","DELETE");
        modules.authorize("/api/v1/trolleys/available","GET");
        assertThatThrownBy(()->modules.authorize("/api/v1/styles/"+UUID.randomUUID()+"/release","POST")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->modules.authorize("/api/v1/lots/"+UUID.randomUUID()+"/dispatch","POST")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->modules.authorize("/api/v1/trolleys","GET")).isInstanceOf(AccessDeniedException.class);
    }
}
