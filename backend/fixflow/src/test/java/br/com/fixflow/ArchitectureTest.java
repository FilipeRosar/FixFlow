package br.com.fixflow;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;


@AnalyzeClasses(
        packages = "br.com.fixflow",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureTest {
    @ArchTest
    static final ArchRule only_scope_can_set_tenant_context = noClasses()
            .that().doNotHaveFullyQualifiedName("br.com.fixflow.shared.infrastructure.tenant.TenantScope")
            .should().callMethod(br.com.fixflow.shared.infrastructure.tenant.TenantContext.class,
                    "setTenantId", java.util.UUID.class);

    @ArchTest
    static final ArchRule only_authorized_executor_opens_tenant_scope = noClasses()
            .that().doNotHaveFullyQualifiedName("br.com.fixflow.shared.infrastructure.tenant.AuthorizedTenantExecutor")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("br.com.fixflow.shared.infrastructure.tenant.TenantScope");

    @ArchTest
    static final ArchRule web_cannot_access_tenant_internals = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule hexagonal_architecture =
            layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Domain").definedBy("..domain..")
                    .layer("Application").definedBy("..application..")
                    .layer("Infrastructure").definedBy("..infrastructure..")
                    .layer("Web").definedBy("..web..")
                    .layer("Framework").definedBy("org.springframework..")

                    .withOptionalLayers(true)

                    .whereLayer("Domain").mayNotBeAccessedByAnyLayer()
                    .whereLayer("Application").mayOnlyAccessLayers("Domain")
                    .whereLayer("Infrastructure").mayOnlyAccessLayers("Application", "Domain", "Framework")
                    .whereLayer("Web")
                    .mayOnlyAccessLayers("Application", "Domain", "Framework");

}
