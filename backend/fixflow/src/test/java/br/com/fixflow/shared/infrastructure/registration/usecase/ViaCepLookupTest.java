package br.com.fixflow.shared.infrastructure.registration.usecase;

import br.com.fixflow.shared.application.registration.PostalCodeUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ViaCepLookupTest {
    private MockRestServiceServer server;
    private ViaCepLookup lookup;
    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("https://viacep.com.br");
        server = MockRestServiceServer.bindTo(builder).build();
        lookup = new ViaCepLookup(builder.build());
    }
    @Test
    void mapsAddressAndAllowsCityLevelPostalCodes() {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withSuccess("""
                    {"cep":"01001-000","logradouro":"Praça da Sé","bairro":"Sé","localidade":"São Paulo","uf":"SP","ibge":"3550308"}
                    """, MediaType.APPLICATION_JSON));
        var address = lookup.lookup("01001000").orElseThrow();
        assertEquals("Praça da Sé", address.street());
        assertEquals("São Paulo", address.city());
        server.verify();
    }
    @Test
    void returnsEmptyForUnknownPostalCode() {
        server.expect(requestTo("https://viacep.com.br/ws/99999999/json/"))
                .andRespond(withSuccess("{\"erro\":true}", MediaType.APPLICATION_JSON));
        assertTrue(lookup.lookup("99999999").isEmpty());
        server.verify();
    }
    @Test
    void rejectsInvalidInputWithoutCallingProvider() {
        assertThrows(IllegalArgumentException.class, () -> lookup.lookup("123"));
        server.verify();
    }
    @Test
    void reportsUnavailableProvider() {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withServerError());
        assertThrows(PostalCodeUnavailableException.class, () -> lookup.lookup("01001000"));
        server.verify();
    }
    @Test
    void rejectsMalformedResponse() {
        server.expect(requestTo("https://viacep.com.br/ws/01001000/json/"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(PostalCodeUnavailableException.class, () -> lookup.lookup("01001000"));
        server.verify();
    }
}
