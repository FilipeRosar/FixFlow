package br.com.fixflow.shared.infrastructure.registration.usecase;

import br.com.fixflow.shared.application.registration.usecase.PostalCodeLookup;
import br.com.fixflow.shared.application.registration.PostalCodeUnavailableException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

@Service
public class ViaCepLookup implements PostalCodeLookup {
    private final RestClient client;

    @Autowired
    public ViaCepLookup() {
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl("https://viacep.com.br")
                .requestFactory(factory).build();
    }

    ViaCepLookup(RestClient client) { this.client = client; }

    @Override
    public Optional<Address> lookup(String postalCode) {
        if (postalCode == null || !postalCode.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("CEP inválido.");
        }
        try {
            var result = client.get().uri("/ws/{cep}/json/", postalCode)
                    .retrieve().body(ViaCepResponse.class);
            if (result == null) throw new PostalCodeUnavailableException();
            if (Boolean.TRUE.equals(result.erro())) return Optional.empty();
            if (!postalCode.equals(text(result.cep()).replace("-", ""))
                    || text(result.localidade()).isBlank() || !text(result.uf()).matches("[A-Z]{2}")) {
                throw new PostalCodeUnavailableException();
            }
            return Optional.of(new Address(text(result.logradouro()), text(result.bairro()),
                    text(result.localidade()), text(result.uf())));
        } catch (RestClientException exception) {
            throw new PostalCodeUnavailableException();
        }
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }

    record ViaCepResponse(String cep, String logradouro, String bairro, String localidade, String uf, Boolean erro) {}
}
