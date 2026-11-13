package br.com.fixflow.modules.tenants.application;

import br.com.fixflow.shared.application.registration.dto.RegistrationAddress;
import java.util.UUID;

public interface Onboarding {
    record Profile(String companyName,String document,String phone,RegistrationAddress address) { }
    record View(boolean profileCompleted,boolean completed,Profile profile,long customers,long equipment,UUID firstOrderId) { }
    View load();
    View save(Profile profile);
    View complete();
    byte[] exportCsv();
}
