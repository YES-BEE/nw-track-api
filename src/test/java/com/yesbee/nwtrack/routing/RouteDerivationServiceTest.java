package com.yesbee.nwtrack.routing;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RouteDerivationServiceTest {
    private final RouteDerivationService service = new RouteDerivationService();

    @Test void groupsConsecutiveProcessesAtTheSameStation() {
        UUID s1=UUID.randomUUID(), s2=UUID.randomUUID();
        var route=service.derive(List.of(p("P1",1,s1,"S1"),p("P2",2,s1,"S1"),p("P3",3,s2,"S2")));
        assertThat(route).hasSize(2);
        assertThat(route.getFirst().processes()).extracting(RouteDerivationService.SelectedProcess::processCode).containsExactly("P1","P2");
        assertThat(route.get(1).stationCode()).isEqualTo("S2");
    }

    @Test void rejectsARepeatedStationVisit() {
        UUID s1=UUID.randomUUID(), s2=UUID.randomUUID();
        assertThatThrownBy(() -> service.derive(List.of(p("P1",1,s1,"S1"),p("P2",2,s2,"S2"),p("P3",3,s1,"S1"))))
                .isInstanceOf(BusinessRuleException.class).extracting(e -> ((BusinessRuleException)e).code()).isEqualTo("REPEATED_STATION_NOT_ALLOWED");
    }

    @Test void rejectsMissingStationMapping() {
        assertThatThrownBy(() -> service.derive(List.of(new RouteDerivationService.SelectedProcess(UUID.randomUUID(),"P1",1,null,null))))
                .isInstanceOf(BusinessRuleException.class);
    }
    private static RouteDerivationService.SelectedProcess p(String code,int sequence,UUID station,String stationCode){return new RouteDerivationService.SelectedProcess(UUID.randomUUID(),code,sequence,station,stationCode);}
}
