package br.com.fixflow.modules.identity.application.exception;
public class ResetRejectedException extends RuntimeException {
    public ResetRejectedException() { super("Link inválido ou expirado. Solicite um novo link de recuperação."); }
}
