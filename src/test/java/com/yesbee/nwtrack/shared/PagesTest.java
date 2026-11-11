package com.yesbee.nwtrack.shared;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;

class PagesTest {
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @Test void parsesDefaultsAndBoundsFromHttpRequest(){
        var request=new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(Pages.options()).isEqualTo(new Pages.Options(0,20,""));
        request.setParameter("page","2147483647");request.setParameter("size","100");request.setParameter("search","  %_ Mixed Case  ");
        assertThat(Pages.options().offset()).isEqualTo(214748364700L);
        assertThat(Pages.options().search()).isEqualTo("%_ Mixed Case");
        request.setParameter("size","101");
        assertThatThrownBy(Pages::options).isInstanceOf(IllegalArgumentException.class);
        request.setParameter("size","bad");
        assertThatThrownBy(Pages::options).hasMessage("page and size must be integers");
    }
    @Test void rejectsInvalidPageAndOversizedSearch(){
        assertThatThrownBy(()->new Pages.Options(-1,20,"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Pages.Options(0,0,"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Pages.Options(0,20,"x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }
}
