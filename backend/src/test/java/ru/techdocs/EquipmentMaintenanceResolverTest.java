package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.estimate.EquipmentMaintenanceResolver;
import ru.techdocs.pkm.PkmOperation;
import ru.techdocs.pkm.PkmOperationRepository;
import ru.techdocs.uniqueequipment.PlannedWork;
import ru.techdocs.uniqueequipment.PlannedWorkRepository;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EquipmentMaintenanceResolverTest {

    private final PkmOperationRepository pkm = mock(PkmOperationRepository.class);
    private final PlannedWorkRepository plannedWorks = mock(PlannedWorkRepository.class);
    private final EquipmentMaintenanceResolver resolver = new EquipmentMaintenanceResolver(pkm, plannedWorks);

    private PkmOperation pkmOp(String name, String periodicity, String perYear) {
        PkmOperation o = new PkmOperation();
        o.setOperationName(name);
        o.setPeriodicity(periodicity);
        o.setPeriodicityPerYear(new BigDecimal(perYear));
        return o;
    }

    private PlannedWork work(String type, String name, String periodicity, String perYear) {
        PlannedWork w = new PlannedWork();
        w.setWorkType(type);
        w.setName(name);
        w.setPeriodicity(periodicity);
        w.setPeriodicityPerYear(new BigDecimal(perYear));
        return w;
    }

    private Equipment equipment(Long uniqueId) {
        Equipment e = new Equipment();
        e.setUniqueEquipmentId(uniqueId);
        return e;
    }

    @Test
    void passportPlannedWorksTakePriorityOverPkm() {
        when(plannedWorks.findByUniqueEquipmentIdOrderByPosition(7L)).thenReturn(List.of(
                work("осмотр", "Технический осмотр", "Ежемесячно", "12"),
                work("ТО", "Техническое обслуживание", "Два раза в год", "2")));

        var ops = resolver.resolveOperations(equipment(7L), "СКУД");

        assertThat(ops).hasSize(2);
        assertThat(ops).allSatisfy(o ->
                assertThat(o.source()).isEqualTo(EquipmentMaintenanceResolver.SOURCE_PASSPORT));
        assertThat(ops.get(0).operationName()).isEqualTo("Технический осмотр");
        assertThat(ops.get(0).perYear()).isEqualByComparingTo("12");
        assertThat(ops.get(1).perYear()).isEqualByComparingTo("2");
        verifyNoInteractions(pkm);   // до ПКМ дело не дошло
    }

    @Test
    void fallsBackToPkmWhenNoPassportWorks() {
        when(plannedWorks.findByUniqueEquipmentIdOrderByPosition(anyLong())).thenReturn(List.of());
        when(pkm.findBySystemTypeOrderByPosition("СКУД")).thenReturn(List.of(
                pkmOp("Проверка работоспособности", "Ежемесячно", "12"),
                pkmOp("Техническое обслуживание УКД", "Раз в год", "1")));

        var ops = resolver.resolveOperations(equipment(7L), "СКУД");

        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).source()).isEqualTo(EquipmentMaintenanceResolver.SOURCE_PKM);
        assertThat(ops.get(0).operationName()).contains("Техническое обслуживание");
        assertThat(ops.get(0).perYear()).isEqualByComparingTo("1");
    }

    @Test
    void emptyWhenNoPassportAndNoPkm() {
        when(pkm.findBySystemTypeOrderByPosition(anyString())).thenReturn(List.of());
        assertThat(resolver.resolveOperations(equipment(null), "СКУД")).isEmpty();
        assertThat(resolver.resolveOperations(equipment(null), null)).isEmpty();
    }

    @Test
    void perYearFromRateNameAndLabel() {
        assertThat(resolver.perYearFromRate("ТО извещателя - полугодовое")).isEqualByComparingTo("2");
        assertThat(resolver.label(new BigDecimal("12"))).isEqualTo("Ежемесячно");
        assertThat(resolver.label(new BigDecimal("2"))).isEqualTo("Два раза в год");
    }
}
