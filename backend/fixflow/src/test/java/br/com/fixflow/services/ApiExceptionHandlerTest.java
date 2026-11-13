package br.com.fixflow.services;

import br.com.fixflow.shared.web.exception.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


class ApiExceptionHandlerTest {

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.boot.test.context.TestComponent
    static class RejectedSubscription {
        @org.springframework.web.bind.annotation.PostMapping("/api/test/subscription")
        public void create() {
            throw new br.com.fixflow.modules.subscriptions.application.SubscriptionGateway.CreationRejected(
                    "O Mercado Pago exige comprador e recebedor no mesmo ambiente.");
        }
    }

    @Test
    void creationRejectionPreservesHttpStatusAndHelpfulDetail() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new RejectedSubscription()).setControllerAdvice(handler).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/test/subscription"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail")
                        .value("O Mercado Pago exige comprador e recebedor no mesmo ambiente."));
    }

    private ApiExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new ApiExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    @Test
    void planLimitReturnsUpgradeMessage() {
        var response = handler.handlePlanLimit(new br.com.fixflow.modules.tenants.domain.PlanLimitExceededException("usuários"));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("PLAN_LIMIT_REACHED", response.getBody().getProperties().get("code"));
        assertTrue(response.getBody().getDetail().contains("upgrade"));
    }

    @Test
    void shouldReturnInternalServerErrorAsProblemDetail() {
        var response = handler.handleUnexpected(
                new RuntimeException("Erro interno de teste"),
                request
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());

        ProblemDetail problem = response.getBody();

        assertEquals(500, problem.getStatus());
        assertEquals("Erro interno", problem.getTitle());
        assertEquals(
                "Ocorreu um erro interno ao processar a solicitação.",
                problem.getDetail()
        );
        assertEquals("/api/test", problem.getInstance().toString());
    }
}
