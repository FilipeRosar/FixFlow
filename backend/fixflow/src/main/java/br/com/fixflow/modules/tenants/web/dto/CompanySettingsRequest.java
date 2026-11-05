package br.com.fixflow.modules.tenants.web.dto;
import jakarta.validation.constraints.*;
public record CompanySettingsRequest(@Size(max = 700000) String logoDataUrl,
        @NotNull @Size(max = 10000) String warrantyTerms,
        @NotNull @Min(1) @Max(365) Integer quoteValidityDays, @NotNull Boolean allowNegativeStock) { }
