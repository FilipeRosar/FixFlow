package br.com.fixflow.modules.quotes.application;

import java.time.Instant;

public interface PublicQuoteDecisions {
    record Receipt(String decision, String workOrderStatus, int quoteVersion, Instant decidedAt, String contentHash) { }
    record Outcome(int status, String code, Receipt receipt) { }
    Outcome decide(String tokenHash, String decision, String contentHash, String reason, String keyHash, String ip, String userAgent);
}
