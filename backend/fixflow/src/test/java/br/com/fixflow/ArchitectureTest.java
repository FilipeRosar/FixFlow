package br.com.fixflow;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;


@AnalyzeClasses(
        packages = "br.com.fixflow",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class ArchitectureTest {
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
