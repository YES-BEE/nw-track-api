package com.yesbee.nwtrack.transport;

import com.yesbee.nwtrack.shared.DomainEnums.TransportStatus;
import java.util.UUID;

public interface TransportProvider {
    String code();
    ProviderHealth health();
    MissionResult createMission(MissionCommand command);
    MissionResult getMission(String providerMissionId);
    MissionResult cancelMission(String providerMissionId, String reason);

    record MissionCommand(UUID requestId, UUID lotId, UUID trolleyId, String source, String destination, int priority) {}
    record MissionResult(String providerMissionId, TransportStatus status, String detail) {}
    record ProviderHealth(boolean available, String detail) {}
}
