package br.com.fixflow.modules.identity.application.exception;

public class SessionRejectedException extends RuntimeException {
    public SessionRejectedException() { super("Não foi possível autenticar. Verifique as credenciais ou tente novamente mais tarde."); }
}
