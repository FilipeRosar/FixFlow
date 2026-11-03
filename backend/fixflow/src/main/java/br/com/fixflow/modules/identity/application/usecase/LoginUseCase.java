package br.com.fixflow.modules.identity.application.usecase;

import br.com.fixflow.modules.identity.application.dto.SessionTokens;
import java.util.UUID;

public interface LoginUseCase {
    SessionTokens login(String email, String password, UUID tenantId);
}
