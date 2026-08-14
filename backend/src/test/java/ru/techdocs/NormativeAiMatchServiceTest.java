package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.ai.AiClient;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.normative.NormativeAiMatchService;
import ru.techdocs.normative.NormativeRate;
import ru.techdocs.normative.NormativeRateRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NormativeAiMatchServiceTest {

    private final NormativeRateRepository rates = mock(NormativeRateRepository.class);
    private final AiClient ai = mock(AiClient.class);
    private final NormativeAiMatchService service = new NormativeAiMatchService(rates, ai);

    private NormativeRate rate(String code, String name) {
        NormativeRate r = new NormativeRate();
        r.setCode(code);
        r.setName(name);
        return r;
    }

    @Test
    void withoutAiReturnsFullTextCandidates() {
        when(ai.hasMatchModel()).thenReturn(false);
        when(rates.search(anyString(), anyInt()))
                .thenReturn(List.of(rate("22-1", "ТО извещателя"), rate("22-2", "ТО оповещателя")));

        var result = service.match("извещатель");

        assertThat(result.aiUsed()).isFalse();
        assertThat(result.aiConfigured()).isFalse();   // ИИ выключен
        assertThat(result.matches()).isEmpty();
        assertThat(result.candidates()).hasSize(2);
        verify(ai, never()).completeMatch(anyString(), anyString());
    }

    @Test
    void aiConfiguredButFailedIsDistinguishable() {
        // ИИ настроен, но запрос упал (провайдер вернул null) — aiConfigured=true, aiUsed=false.
        // Черновик по этому признаку пометит строку «на проверку», а не подставит расценку.
        when(ai.hasMatchModel()).thenReturn(true);
        when(rates.search(anyString(), anyInt())).thenReturn(List.of(rate("22-1", "ТО")));
        when(ai.completeMatch(anyString(), anyString())).thenReturn(null);

        var result = service.match("извещатель");

        assertThat(result.aiUsed()).isFalse();
        assertThat(result.aiConfigured()).isTrue();
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void aiPicksMatchesAndDropsHallucinatedCodes() {
        when(ai.hasMatchModel()).thenReturn(true);
        when(rates.search(anyString(), anyInt()))
                .thenReturn(List.of(rate("22-1", "ТО извещателя"), rate("22-2", "ТО оповещателя")));
        // модель вернула один реальный шифр и один выдуманный
        when(ai.completeMatch(anyString(), anyString())).thenReturn("""
                Вот результат:
                [
                  {"code": "22-1", "reason": "прямое совпадение по извещателю"},
                  {"code": "99-НЕТ", "reason": "выдуманный"}
                ]
                """);

        var result = service.match("извещатель дымовой");

        assertThat(result.aiUsed()).isTrue();
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).rate().getCode()).isEqualTo("22-1");
        assertThat(result.matches().get(0).reason()).contains("извещателю");
    }

    @Test
    void emptyCatalogReturnsNothing() {
        when(rates.search(anyString(), anyInt())).thenReturn(List.of());

        var result = service.match("что угодно");

        assertThat(result.matches()).isEmpty();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.aiUsed()).isFalse();
        verify(ai, never()).completeMatch(anyString(), anyString());
    }

    @Test
    void malformedAiAnswerFallsBackToCandidates() {
        when(ai.hasMatchModel()).thenReturn(true);
        when(rates.search(anyString(), anyInt())).thenReturn(List.of(rate("22-1", "ТО")));
        when(ai.completeMatch(anyString(), anyString())).thenReturn("извините, не понял");

        var result = service.match("извещатель");

        assertThat(result.matches()).isEmpty();
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void fewShotExampleRateIsAddedToCandidatesAndPromptAndCanBeChosen() {
        when(ai.hasMatchModel()).thenReturn(true);
        // полнотекстовый поиск дал одну расценку, а в эталоне — другая (99-ET), которой в поиске нет
        when(rates.search(anyString(), anyInt())).thenReturn(List.of(rate("22-1", "ТО извещателя")));
        when(rates.findFirstByCodeOrderById("99-ET")).thenReturn(java.util.Optional.of(rate("99-ET", "ТО по эталону")));

        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(ai.completeMatch(anyString(), anyString()))
                .thenReturn("[{\"code\":\"99-ET\",\"reason\":\"как в эталоне\"}]");

        var result = service.match("считыватель",
                List.of(new NormativeAiMatchService.Example("Считыватель TS-CTR", "99-ET")));

        // расценка из эталона попала в кандидаты и была выбрана
        assertThat(result.candidates()).extracting(NormativeRate::getCode).contains("22-1", "99-ET");
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).rate().getCode()).isEqualTo("99-ET");

        verify(ai).completeMatch(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("Примеры из эталона").contains("99-ET");
    }

    @Test
    void blankQueryRejected() {
        assertThatThrownBy(() -> service.match("  ")).isInstanceOf(BadRequestException.class);
    }
}
