package br.com.fixflow.modules.tenants.infrastructure;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Arrays;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class CompanyCsvTest {
    @Test void exportsAccentsQuotesDelimitersAndNewlinesForSpreadsheets() {
        String csv=new String(CompanyCsv.render(List.of("name","amount","active","notes"),List.of(
            Arrays.asList("João; \"Silva\"",new BigDecimal("69.90"),true,"Linha 1\nLinha 2"))),StandardCharsets.UTF_8);
        assertEquals("\ufeff\"Nome\";\"Valor\";\"Ativo\";\"Observações\"\r\n\"João; \"\"Silva\"\"\";\"69,90\";\"Sim\";\"Linha 1\nLinha 2\"\r\n",csv);
    }
    @Test void neutralizesTextFormulasAndPreservesNegativeNumbers() {
        String csv=new String(CompanyCsv.render(List.of("name","amount"),List.of(
            List.of("=HYPERLINK(\"https://example.invalid\")",-10), List.of(" +1",0),List.of("@SUM(1)",0))),StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"'=HYPERLINK"));
        assertTrue(csv.contains("\"' +1\""));
        assertTrue(csv.contains("\"'@SUM(1)\""));
        assertTrue(csv.contains(";\"-10\""));
    }
    @Test void exportsHeadersEvenWithoutRows() {
        assertEquals("\ufeff\"Nome\"\r\n",new String(CompanyCsv.render(List.of("name"),List.of()),StandardCharsets.UTF_8));
    }
}
