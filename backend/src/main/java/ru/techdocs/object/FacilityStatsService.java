package ru.techdocs.object;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.techdocs.document.Document;
import ru.techdocs.document.DocumentRepository;
import ru.techdocs.engineeringsystem.EngineeringSystem;
import ru.techdocs.engineeringsystem.EngineeringSystemRepository;
import ru.techdocs.equipment.EquipmentRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FacilityStatsService {

    private final DocumentRepository documentRepository;
    private final EngineeringSystemRepository systemRepository;
    private final EquipmentRepository equipmentRepository;

    public record FacilityWithStats(Long id, String name, String address, String description,
                                    String status, Instant createdAt,
                                    long documentCount, long systemCount,
                                    long equipmentCount, BigDecimal equipmentUnits,
                                    long errorCount) {}

    public record SystemWithStats(Long id, String name, String code, String description,
                                  long documentCount) {}

    public List<FacilityWithStats> withStats(List<Facility> facilities) {
        return facilities.stream().map(f -> new FacilityWithStats(
                f.getId(), f.getName(), f.getAddress(), f.getDescription(),
                f.getStatus(), f.getCreatedAt(),
                documentRepository.countByFacilityId(f.getId()),
                systemRepository.findByFacilityIdOrderById(f.getId()).size(),
                equipmentRepository.countByFacilityId(f.getId()),
                equipmentRepository.sumQuantityByFacility(f.getId()),
                documentRepository.countByFacilityIdAndStatus(f.getId(), Document.STATUS_ERROR)
        )).toList();
    }

    public List<SystemWithStats> systemsWithStats(Long facilityId) {
        List<EngineeringSystem> systems = systemRepository.findByFacilityIdOrderById(facilityId);
        return systems.stream().map(s -> new SystemWithStats(
                s.getId(), s.getName(), s.getCode(), s.getDescription(),
                documentRepository.countByEngineeringSystemId(s.getId())
        )).toList();
    }
}
