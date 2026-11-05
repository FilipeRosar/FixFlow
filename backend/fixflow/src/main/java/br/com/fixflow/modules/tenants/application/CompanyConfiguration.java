package br.com.fixflow.modules.tenants.application;
import br.com.fixflow.modules.tenants.domain.CompanySettings;
public interface CompanyConfiguration {
    CompanySettings load();
    CompanySettings save(CompanySettings settings);
}
