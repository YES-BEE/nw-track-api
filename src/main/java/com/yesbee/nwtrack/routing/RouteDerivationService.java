package com.yesbee.nwtrack.routing;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RouteDerivationService {
    public List<RouteEntry> derive(List<SelectedProcess> orderedProcesses) {
        if (orderedProcesses.isEmpty()) throw new BusinessRuleException("STYLE_PROCESS_REQUIRED", "At least one Process is required");
        List<RouteEntry> route = new ArrayList<>(); Set<UUID> closedStations = new HashSet<>();
        UUID currentStation = null; List<SelectedProcess> current = null;
        for (SelectedProcess process : orderedProcesses) {
            if (process.stationId() == null) throw new BusinessRuleException("ROUTE_CONFIGURATION_INVALID", "Process " + process.processCode() + " has no Station mapping");
            if (!process.stationId().equals(currentStation)) {
                if (currentStation != null) closedStations.add(currentStation);
                if (closedStations.contains(process.stationId())) throw new BusinessRuleException("REPEATED_STATION_NOT_ALLOWED", "Derived route returns to Station " + process.stationCode());
                currentStation = process.stationId(); current = new ArrayList<>();
                route.add(new RouteEntry(route.size() + 1, currentStation, process.stationCode(), current));
            }
            current.add(process);
        }
        return List.copyOf(route);
    }
    public record SelectedProcess(UUID processId, String processCode, int sequence, UUID stationId, String stationCode) {}
    public record RouteEntry(int sequence, UUID stationId, String stationCode, List<SelectedProcess> processes) {
        public RouteEntry { processes = Objects.requireNonNull(processes); }
    }
}
