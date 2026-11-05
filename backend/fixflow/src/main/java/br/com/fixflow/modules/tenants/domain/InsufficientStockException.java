package br.com.fixflow.modules.tenants.domain;
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException() { super("Estoque insuficiente."); }
}
