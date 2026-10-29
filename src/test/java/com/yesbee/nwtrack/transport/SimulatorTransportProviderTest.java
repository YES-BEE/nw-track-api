package com.yesbee.nwtrack.transport;

import com.yesbee.nwtrack.shared.DomainEnums.TransportStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorTransportProviderTest {
    private final SimulatorTransportProvider provider = new SimulatorTransportProvider();

    @Test
    void createsAndRetrievesSimulatedMission() {
        var command = new TransportProvider.MissionCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "CUTTING", "SEWING", 1);

        var created = provider.createMission(command);

        assertThat(created.providerMissionId()).startsWith("SIM-");
        assertThat(created.status()).isEqualTo(TransportStatus.ACCEPTED);
        assertThat(provider.getMission(created.providerMissionId())).isEqualTo(created);
    }

    @Test
    void cancelsMissionAndReportsUnknownMission() {
        assertThat(provider.getMission("missing").status()).isEqualTo(TransportStatus.REJECTED);
        assertThat(provider.cancelMission("SIM-1", "operator cancelled").status())
                .isEqualTo(TransportStatus.CANCELLED);
    }
}
