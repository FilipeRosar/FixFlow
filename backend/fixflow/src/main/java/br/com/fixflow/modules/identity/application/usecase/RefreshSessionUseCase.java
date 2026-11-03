package br.com.fixflow.modules.identity.application.usecase;

import br.com.fixflow.modules.identity.application.dto.SessionTokens;

public interface RefreshSessionUseCase {
    SessionTokens refresh(String refreshToken);
}
