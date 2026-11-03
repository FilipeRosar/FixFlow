package br.com.fixflow.shared.application.registration;

public class InvalidRegistrationException extends RuntimeException {
    public InvalidRegistrationException() {
        super("Dados de cadastro inválidos. A senha deve ter de 10 a 72 caracteres e no máximo 72 bytes UTF-8.");
    }
}
