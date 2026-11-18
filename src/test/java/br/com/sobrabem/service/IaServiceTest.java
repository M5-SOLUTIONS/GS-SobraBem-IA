package br.com.sobrabem.service;

import br.com.sobrabem.ai.IaIndisponivelException;
import br.com.sobrabem.ai.OrientacaoResultado;
import br.com.sobrabem.ai.OrientacaoService;
import br.com.sobrabem.domain.entity.Categoria;
import br.com.sobrabem.dto.request.OrientacaoRequest;
import br.com.sobrabem.dto.response.OrientacaoResponse;
import br.com.sobrabem.exception.CodigoErro;
import br.com.sobrabem.exception.NegocioException;
import br.com.sobrabem.repository.CategoriaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static br.com.sobrabem.Fixtures.categoria;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IaServiceTest {

    // o IaService recebe o Duration no construtor (não é mock), então ele é montado à mão.
    // 300 ms em vez dos 20 s de produção: o teste de timeout não precisa esperar tudo isso.
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    @Mock
    OrientacaoService orientacaoService;
    @Mock
    CategoriaRepository categorias;

    IaService service;

    private final Categoria paes = categoria(1L, 12);

    @BeforeEach
    void criarService() {
        service = new IaService(orientacaoService, categorias, TIMEOUT);
    }

    @AfterEach
    void encerrarService() {
        service.encerrar(); // fecha o executor de threads virtuais de cada teste
    }

    // ---------- categoria ----------

    @Test
    void categoriaInexistenteDa404SemChamarAIa() {
        when(categorias.findById(99L)).thenReturn(Optional.empty());

        NegocioException e = assertThrows(NegocioException.class,
                () -> service.gerar(new OrientacaoRequest("Pão francês", null, 99L)));

        assertThat(e.getCodigo()).isEqualTo(CodigoErro.NAO_ENCONTRADO);
        assertThat(e.getCodigo().getStatus().value()).isEqualTo(404);
        verifyNoInteractions(orientacaoService);
    }

    // ---------- caminho feliz ----------

    @Test
    void comBaseNoAcervoDevolveOrientacaoEFontes() {
        when(categorias.findById(1L)).thenReturn(Optional.of(paes));
        when(orientacaoService.gerar("Pão francês", "Do dia", paes))
                .thenReturn(new OrientacaoResultado("Guarde em saco de papel.", List.of("paes-bolos.md"), null));

        OrientacaoResponse r = service.gerar(new OrientacaoRequest("Pão francês", "Do dia", 1L));

        assertThat(r.orientacao()).isEqualTo("Guarde em saco de papel.");
        assertThat(r.fontes()).containsExactly("paes-bolos.md");
        assertThat(r.aviso()).isNull();
    }

    @Test
    void semBaseNoAcervoDevolveAvisoEmVezDeErro() {
        when(categorias.findById(1L)).thenReturn(Optional.of(paes));
        when(orientacaoService.gerar(any(), any(), any()))
                .thenReturn(new OrientacaoResultado(null, List.of(), "Sem orientação no acervo para este alimento."));

        OrientacaoResponse r = service.gerar(new OrientacaoRequest("Sushi", null, 1L));

        assertThat(r.orientacao()).isNull();
        assertThat(r.fontes()).isEmpty();
        assertThat(r.aviso()).isEqualTo("Sem orientação no acervo para este alimento.");
    }

    @Test
    void fontesNulasViramListaVazia() {
        when(categorias.findById(1L)).thenReturn(Optional.of(paes));
        when(orientacaoService.gerar(any(), any(), any()))
                .thenReturn(new OrientacaoResultado("Mantenha refrigerado.", null, null));

        OrientacaoResponse r = service.gerar(new OrientacaoRequest("Queijo minas", null, 1L));

        assertThat(r.fontes()).isNotNull().isEmpty();
    }

    @Test
    void enviaOTituloSemEspacosECategoriaCarregadaParaAIa() {
        when(categorias.findById(1L)).thenReturn(Optional.of(paes));
        when(orientacaoService.gerar(any(), any(), any()))
                .thenReturn(new OrientacaoResultado("ok", List.of(), null));

        service.gerar(new OrientacaoRequest("  Pão francês  ", "Do dia", 1L));

        verify(orientacaoService).gerar("Pão francês", "Do dia", paes);
    }

    // ---------- falhas → 503 ----------

    @Test
    void iaIndisponivelDaImplementacaoEPropagadaSemEmbrulho() {
        IaIndisponivelException falha = new IaIndisponivelException("Gemini fora do ar");
        when(categorias.findById(1L)).thenReturn(Optional.of(paes));
        when(orientacaoService.gerar(any(), any(), any())).thenThrow(falha);

        IaIndisponivelException e = assertThrows(IaIndisponivelException.class,
                () -> service.gerar(new OrientacaoRequest("Pão francês", null, 1L)));

        assertThat(e).isSameAs(falha);
    }

    @Test
    void qualquerOutroErroDaImplementacaoViraIaIndisponivel() {
        RuntimeException falha = new IllegalStateException("cota esgotada");
        when(categorias.findById(1L)).thenReturn(Optional.of(paes));
        when(orientacaoService.gerar(any(), any(), any())).thenThrow(falha);

        IaIndisponivelException e = assertThrows(IaIndisponivelException.class,
                () -> service.gerar(new OrientacaoRequest("Pão francês", null, 1L)));

        assertThat(e.getMessage()).isEqualTo("Falha ao gerar a orientação");
        assertThat(e.getCause()).isSameAs(falha);
    }

    // ---------- timeout ----------

    @Test
    void estourarOTimeoutDa503ECancelaATarefa() throws InterruptedException {
        CountDownLatch tarefaCancelada = new CountDownLatch(1);
        when(categorias.findById(1L)).thenReturn(Optional.of(paes));
        when(orientacaoService.gerar(any(), any(), any())).thenAnswer(inv -> {
            try {
                Thread.sleep(10_000); // Gemini "pendurado": bem além do timeout
            } catch (InterruptedException ie) {
                tarefaCancelada.countDown(); // o cancel(true) do IaService chegou aqui
                Thread.currentThread().interrupt();
            }
            return null;
        });

        IaIndisponivelException e = assertThrows(IaIndisponivelException.class,
                () -> service.gerar(new OrientacaoRequest("Pão francês", null, 1L)));

        assertThat(e.getMessage()).startsWith("A IA não respondeu em");
        assertThat(e.getCause()).isInstanceOf(TimeoutException.class);
        assertThat(tarefaCancelada.await(2, TimeUnit.SECONDS))
                .as("a thread da IA deve ser interrompida quando o timeout estoura")
                .isTrue();
    }

    @Test
    void respostaDentroDoTimeoutNaoEInterrompida() {
        when(categorias.findById(1L)).thenReturn(Optional.of(paes));
        when(orientacaoService.gerar(any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(50); // lenta, mas dentro dos 300 ms
            return new OrientacaoResultado("Resposta no prazo.", List.of("paes-bolos.md"), null);
        });

        OrientacaoResponse r = service.gerar(new OrientacaoRequest("Pão francês", null, 1L));

        assertThat(r.orientacao()).isEqualTo("Resposta no prazo.");
    }

    @Test
    void threadInterrompidaViraIaIndisponivelEPreservaAInterrupcao() {
        CountDownLatch liberar = new CountDownLatch(1);
        when(categorias.findById(1L)).thenReturn(Optional.of(paes));
        // lenient: a tarefa pode nem chegar a rodar antes do teste terminar
        lenient().when(orientacaoService.gerar(any(), any(), any())).thenAnswer(inv -> {
            liberar.await(5, TimeUnit.SECONDS); // segura a tarefa para o get() não achar resultado pronto
            return null;
        });

        Thread.currentThread().interrupt();
        try {
            IaIndisponivelException e = assertThrows(IaIndisponivelException.class,
                    () -> service.gerar(new OrientacaoRequest("Pão francês", null, 1L)));

            assertThat(e.getMessage()).isEqualTo("Geração interrompida");
            assertThat(e.getCause()).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // limpa o flag para não vazar para outros testes
            liberar.countDown();
        }
    }
}
