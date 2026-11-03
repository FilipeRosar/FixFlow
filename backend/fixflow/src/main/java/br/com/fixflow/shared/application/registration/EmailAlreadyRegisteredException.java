package br.com.fixflow.shared.application.registration;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException() {
        super("E-mail já cadastrado.");
    }
}
