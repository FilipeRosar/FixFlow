package br.com.fixflow.shared.registration;

import br.com.fixflow.shared.application.registration.usecase.PostalCodeLookup;
import br.com.fixflow.shared.application.registration.PostalCodeUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class PostalCodeIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean PostalCodeLookup lookup;

    @Test
    void allowsLookupBeforeRegistrationWithoutAuthentication() throws Exception {
        when(lookup.lookup("01001000")).thenReturn(Optional.of(new PostalCodeLookup.Address("Praça da Sé", "Sé", "São Paulo", "SP")));
        mvc.perform(get("/api/enderecos/cep/01001000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.street").value("Praça da Sé"));
    }
    @Test
    void rejectsInvalidPostalCodeBeforeProviderCall() throws Exception {
        mvc.perform(get("/api/enderecos/cep/123")).andExpect(status().isBadRequest());
        verifyNoInteractions(lookup);
    }
    @Test
    void reportsNotFoundAndUnavailable() throws Exception {
        when(lookup.lookup("99999999")).thenReturn(Optional.empty());
        mvc.perform(get("/api/enderecos/cep/99999999")).andExpect(status().isNotFound());
        when(lookup.lookup("01001000")).thenThrow(new PostalCodeUnavailableException());
        mvc.perform(get("/api/enderecos/cep/01001000")).andExpect(status().isServiceUnavailable());
    }
}
