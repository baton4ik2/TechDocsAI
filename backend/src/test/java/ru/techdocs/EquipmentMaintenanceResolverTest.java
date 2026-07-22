package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.estimate.EquipmentMaintenanceResolver;
import ru.techdocs.pkm.PkmOperation;
import ru.techdocs.pkm.PkmOperationRepository;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EquipmentMaintenanceResolverTest {

    private final PkmOperationRepository pkm = mock(PkmOperationRepository.class);
    private final EquipmentMaintenanceResolver resolver = new EquipmentMaintenanceResolver(pkm);

    private PkmOperation op(String name, String periodicity, String perYear) {
        PkmOperation o = new PkmOperation();
        o.setOperationName(name);
        o.setPeriodicity(periodicity);
        o.setPeriodicityPerYear(new BigDecimal(perYear));
        return o;
    }

    @Test
    void prefersPkmOverRateName() {
        when(pkm.findBySystemTypeOrderByPosition("СКУД")).thenReturn(List.of(
                op("Проверка работоспособности", "Ежемесячно", "12"),
                op("Техническое обслуживание УКД", "Раз в год", "1")));

        var plan = resolver.resolve(new Equipment(), "СКУД", "ТО извещателя - полугодовое");

        // берётся операция ТО (не первая «проверка»), источник — ПКМ
        assertThat(plan.source()).isEqualTo(EquipmentMaintenanceResolver.SOURCE_PKM);
        assertThat(plan.perYear()).isEqualByComparingTo("1");
        assertThat(plan.note()).contains("Техническое обслуживание");
    }

    @Test
    void fallsBackToRateNameWhenNoPkm() {
        when(pkm.findBySystemTypeOrderByPosition(anyString())).thenReturn(List.of());

        var plan = resolver.resolve(new Equipment(), "СКУД", "ТО извещателя пожарного - полугодовое");

        assertThat(plan.source()).isEqualTo(EquipmentMaintenanceResolver.SOURCE_RATE);
        assertThat(plan.perYear()).isEqualByComparingTo("2");   // полугодовое
        assertThat(plan.periodicityText()).isEqualTo("Два раза в год");
    }

    @Test
    void noneWhenNothingResolvable() {
        when(pkm.findBySystemTypeOrderByPosition(anyString())).thenReturn(List.of());

        var plan = resolver.resolve(new Equipment(), null, "Расценка без периодичности");

        assertThat(plan.source()).isEqualTo(EquipmentMaintenanceResolver.SOURCE_NONE);
        assertThat(plan.perYear()).isNull();
    }
}
