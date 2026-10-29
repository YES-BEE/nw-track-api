package com.yesbee.nwtrack.transport;

import com.yesbee.nwtrack.shared.DomainEnums.TransportStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name="nwtrack.transport.provider", havingValue="simulator", matchIfMissing=true)
public class SimulatorTransportProvider implements TransportProvider {
    private final Map<String, MissionResult> missions = new ConcurrentHashMap<>();
    @Override public String code() { return "simulator"; }
    @Override public ProviderHealth health() { return new ProviderHealth(true, "Simulator is available"); }
    @Override public MissionResult createMission(MissionCommand command) {
        String id = "SIM-" + UUID.randomUUID();
        MissionResult result = new MissionResult(id, TransportStatus.ACCEPTED, "Simulation mission accepted");
        missions.put(id, result); return result;
    }
    @Override public MissionResult getMission(String providerMissionId) {
        return missions.getOrDefault(providerMissionId, new MissionResult(providerMissionId, TransportStatus.REJECTED, "Unknown simulation mission"));
    }
    @Override public MissionResult cancelMission(String providerMissionId, String reason) {
        MissionResult result = new MissionResult(providerMissionId, TransportStatus.CANCELLED, reason);
        missions.put(providerMissionId, result); return result;
    }
}
