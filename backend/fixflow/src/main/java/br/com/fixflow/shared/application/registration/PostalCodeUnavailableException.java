package br.com.fixflow.shared.application.registration;

public class PostalCodeUnavailableException extends RuntimeException {
    public PostalCodeUnavailableException() { super("Consulta de CEP indisponível."); }
}
