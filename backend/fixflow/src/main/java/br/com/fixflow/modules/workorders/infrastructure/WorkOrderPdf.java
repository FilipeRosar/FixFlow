package br.com.fixflow.modules.workorders.infrastructure;

import br.com.fixflow.modules.workorders.domain.WorkOrder;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import java.io.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class WorkOrderPdf implements AutoCloseable {
    public record Company(String name, String document, String address, String phone, String terms, String logoKey) { }
    private final PDDocument document = new PDDocument();
    private final PDType0Font font;
    private PDPageContentStream stream;
    private float y;
    private WorkOrderPdf() throws IOException {
        try (var input = WorkOrderPdf.class.getResourceAsStream("/fonts/NotoSans-Regular.ttf")) {
            if (input == null) throw new IOException("Fonte PDF indisponível");
            font = PDType0Font.load(document, input);
        }
        page();
    }
    private void page() throws IOException {
        if (stream != null) stream.close();
        var page = new PDPage(PDRectangle.A4); document.addPage(page);
        stream = new PDPageContentStream(document, page); y = 790;
    }
    private void line(String text) throws IOException {
        if (y < 55) page();
        stream.beginText(); stream.setFont(font, 11); stream.newLineAtOffset(48, y);
        stream.showText(text); stream.endText(); y -= 16;
    }
    private void text(String value) throws IOException {
        var line = new StringBuilder();
        for (int cp : fallback(value).codePoints().toArray()) {
            if (cp == '\n') { line(line.toString()); line.setLength(0); continue; }
            if (Character.isISOControl(cp)) continue;
            String character = Character.isWhitespace(cp) ? " " : new String(Character.toChars(cp));
            try { font.encode(character); } catch (IOException | IllegalArgumentException exception) { character = "?"; }
            if (font.getStringWidth(line.toString() + character) / 1000 * 11 > 499) {
                line(line.toString()); line.setLength(0);
            }
            line.append(character);
        }
        line(line.toString()); y -= 6;
    }
    private static String date(java.time.Instant value) {
        return value == null ? "Não informado" : DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of("America/Sao_Paulo")).format(value);
    }
    public static byte[] render(WorkOrder order, Company company, byte[] logo, boolean delivery) {
        try (var pdf = new WorkOrderPdf(); var output = new ByteArrayOutputStream()) {
            if (logo != null) {
                var image = PDImageXObject.createFromByteArray(pdf.document, logo, "logo");
                float scale = Math.min(140f / image.getWidth(), 60f / image.getHeight());
                pdf.stream.drawImage(image, 48, pdf.y - image.getHeight() * scale, image.getWidth() * scale, image.getHeight() * scale);
                pdf.y -= 76;
            }
            pdf.text(company.name());
            pdf.text("CPF/CNPJ: " + fallback(company.document()) + " | Telefone: " + fallback(company.phone()));
            pdf.text("Endereço: " + fallback(company.address()));
            pdf.text((delivery ? "TERMO DE ENTREGA" : "TERMO DE ENTRADA") + " — OS nº " + order.number());
            pdf.text("Recebimento: " + date(order.openedAt()));
            pdf.text("Cliente: " + fallback(order.customerName()));
            pdf.text("Equipamento: " + fallback(order.equipmentLabel()) + " | Série: " + fallback(order.serialNumber()));
            pdf.text("Defeito relatado: " + fallback(order.reportedIssue()));
            pdf.text("Estado físico na entrada: " + fallback(order.physicalCondition()));
            pdf.text("Acessórios na entrada: " + (order.receivedAccessories().isEmpty() ? "Nenhum registrado" : String.join(", ", order.receivedAccessories())));
            if (delivery) {
                pdf.text("Entrega: " + date(order.deliveredAt()) + " | Responsável: " + fallback(order.deliveredByName()));
                pdf.text("Prazo adicional de garantia: " + (order.warrantyDays() == null ? 0 : order.warrantyDays()) + " dias. Vencimento: " +
                        (order.warrantyUntil() == null ? "Sem prazo adicional registrado" : order.warrantyUntil().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
                pdf.text("Declaro o recebimento do equipamento identificado nesta ordem de serviço.");
            } else {
                pdf.text("Confirmo a entrada do equipamento para avaliação, com o defeito, estado físico e acessórios descritos acima.");
            }
            pdf.text("TERMOS DE GARANTIA");
            String terms = delivery ? order.warrantyTerms() : company.terms();
            pdf.text(terms == null || terms.isBlank() ? "Nenhum termo registrado." : terms);
            pdf.text("\nAssinatura do cliente: ______________________________________");
            pdf.text("Assinatura da empresa: _____________________________________");
            pdf.text("Data: ____/____/________");
            pdf.stream.close(); pdf.stream = null; pdf.document.save(output);
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("Não foi possível gerar o PDF da OS.", exception); }
    }
    private static String fallback(String value) { return value == null || value.isBlank() ? "Não informado" : value; }
    @Override public void close() throws IOException {
        try { if (stream != null) stream.close(); } finally { document.close(); }
    }
}
