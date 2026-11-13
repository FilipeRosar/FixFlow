package br.com.fixflow.modules.workorders;

import br.com.fixflow.modules.workorders.domain.WorkOrder;
import br.com.fixflow.modules.workorders.infrastructure.WorkOrderPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkOrderPdfTest {
    private WorkOrder order(String issue, String terms) {
        return new WorkOrder(UUID.randomUUID(), 1042, UUID.randomUUID(), UUID.randomUUID(), "ENTREGUE",
                issue, List.of("Carregador original", "Capa de proteção"), "Riscos leves na tampa. Tela sem trincas.",
                Instant.parse("2026-11-13T13:30:00Z"), 1, "Mariana Oliveira Santos", "Notebook Dell Inspiron 15", "DL-2026-001482",
                null, null, null, null, Instant.parse("2026-11-16T18:00:00Z"), 90, LocalDate.of(2027, 2, 14),
                true, false, null, "Carlos Almeida", terms, "PAID");
    }
    private WorkOrderPdf.Company company(String terms) {
        return new WorkOrderPdf.Company("Oficina Modelo • Assistência Técnica", "00.000.000/0001-00",
                "Rua das Acácias, 120 — Centro, São Paulo/SP", "(11) 0000-0000", terms, null);
    }
    @Test void rendersProfessionalEntryWithAllRecordedInformationOnOnePage() throws Exception {
        String terms = "O equipamento foi recebido para avaliação técnica. As condições de garantia e atendimento são as registradas pela assistência neste documento.";
        byte[] bytes = WorkOrderPdf.render(order("Equipamento não liga. Cliente informa que o indicador de carga permanece apagado.", terms), company(terms), null, false);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertEquals(1, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            for (String expected : List.of("TERMO DE ENTRADA", "1042", "IDENTIFICAÇÃO DO ATENDIMENTO", "Mariana Oliveira Santos",
                    "Notebook Dell Inspiron 15", "DL-2026-001482", "13/11/2026 10:30", "Carregador original", "Tela sem trincas.",
                    "TERMOS DE GARANTIA", "CIÊNCIA E ASSINATURAS", "Assinatura do cliente", "Assinatura da empresa", "Página 1 de 1")) {
                assertTrue(text.contains(expected), expected);
            }
            assertFalse(text.contains("ENTREGA E GARANTIA ADICIONAL"));
            assertEquals("TERMO DE ENTRADA — OS nº 1042", pdf.getDocumentInformation().getTitle());
        }
        Path preview = Path.of("target", "pdf-preview", "termo-entrada.pdf");
        Files.createDirectories(preview.getParent());
        Files.write(preview, bytes);
    }
    @Test void paginatesLongTextWithoutLosingTermsOrOverflowingThePage() throws Exception {
        String terms = "Condições registradas pela assistência. ".repeat(240) + "FIM DOS TERMOS";
        byte[] bytes = WorkOrderPdf.render(order("IDENTIFICADOR".repeat(80), terms), company("TERMOS ALTERADOS"), null, true);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertTrue(pdf.getNumberOfPages() > 1);
            var stripper = new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition position) {
                    assertTrue(position.getXDirAdj() >= 45, "Left margin");
                    assertTrue(position.getXDirAdj() + position.getWidthDirAdj() <= 551, "Right margin");
                    assertTrue(position.getYDirAdj() < 820, "Bottom margin");
                    super.processTextPosition(position);
                }
            };
            String text = stripper.getText(pdf);
            assertTrue(text.contains("FIM DOS TERMOS"));
            assertTrue(text.contains("TERMO DE ENTREGA"));
            assertTrue(text.contains("Carlos Almeida"));
            assertTrue(text.contains("90 dias. Vencimento: 14/02/2027"));
            assertFalse(text.contains("TERMOS ALTERADOS"));
            stripper.setStartPage(pdf.getNumberOfPages());
            String last = stripper.getText(pdf);
            assertTrue(last.contains("Assinatura do cliente"));
            assertTrue(last.contains("Assinatura da empresa"));
            assertTrue(last.contains("Página " + pdf.getNumberOfPages() + " de " + pdf.getNumberOfPages()));
        }
    }
    @Test void supportsLogoAndMissingOptionalInformation() throws Exception {
        var image = new BufferedImage(180, 90, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] bytes = WorkOrderPdf.render(order("Não liga", null), new WorkOrderPdf.Company("Oficina Modelo", null, null, null, null, null), output.toByteArray(), false);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertTrue(pdf.getPage(0).getResources().getXObjectNames().iterator().hasNext());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Não informado"));
            assertTrue(text.contains("Nenhum termo registrado."));
        }
    }
}
