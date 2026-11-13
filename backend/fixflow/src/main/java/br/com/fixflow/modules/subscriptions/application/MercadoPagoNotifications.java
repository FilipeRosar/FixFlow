package br.com.fixflow.modules.subscriptions.application;

public interface MercadoPagoNotifications {
    void authenticateNotification(String signature, String requestId, String resourceId);
}
