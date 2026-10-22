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

    private ApiExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new ApiExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
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
