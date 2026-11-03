package br.com.fixflow.shared.application.registration;

public class InvalidRegistrationException extends RuntimeException {
    public InvalidRegistrationException() {
        super("Confira os dados do cadastro. Use uma senha com pelo menos 8 caracteres, letra maiúscula, letra minúscula e número. Se a senha for muito longa, reduza o tamanho.");
    }
}
