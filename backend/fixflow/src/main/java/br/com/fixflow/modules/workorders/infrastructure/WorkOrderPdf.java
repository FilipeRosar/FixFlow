package br.com.fixflow.modules.workorders.infrastructure;

import br.com.fixflow.modules.workorders.domain.WorkOrder;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import java.io.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class WorkOrderPdf implements AutoCloseable {
    public record Company(String name, String document, String address, String phone, String terms, String logoKey) { }
    private static final float MARGIN = 46;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float BOTTOM = 64;
    private static final float[] INK = {0.12f, 0.17f, 0.24f};
    private static final float[] MUTED = {0.38f, 0.42f, 0.48f};
    private static final float[] RULE = {0.80f, 0.83f, 0.87f};
    private final PDDocument document = new PDDocument();
    private final PDType0Font font;
    private final String title;
    private final long number;
    private PDPageContentStream stream;
    private float y;

    private WorkOrderPdf(boolean delivery, long number) throws IOException {
        title = delivery ? "TERMO DE ENTREGA" : "TERMO DE ENTRADA";
        this.number = number;
        try (var input = WorkOrderPdf.class.getResourceAsStream("/fonts/NotoSans-Regular.ttf")) {
            if (input == null) throw new IOException("Fonte PDF indisponível");
            font = PDType0Font.load(document, input);
        }
        document.getDocumentInformation().setTitle(title + " — OS nº " + number);
        document.getDocumentInformation().setCreator("FixFlow");
        page();
    }
    private void page() throws IOException {
        if (stream != null) stream.close();
        var page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        y = PDRectangle.A4.getHeight() - MARGIN;
        if (document.getNumberOfPages() > 1) {
            draw(title + "  /  OS nº " + number, MARGIN, y, 9, MUTED);
            rule(y - 12);
            y -= 36;
        }
    }
    private void ensure(float height) throws IOException {
        if (y - height < BOTTOM) page();
    }
    private String printable(String value) throws IOException {
        var result = new StringBuilder();
        for (int cp : fallback(value).codePoints().toArray()) {
            if (cp == '\n') { result.append('\n'); continue; }
            if (Character.isISOControl(cp)) continue;
            String character = Character.isWhitespace(cp) ? " " : new String(Character.toChars(cp));
            try { font.encode(character); } catch (IOException | IllegalArgumentException exception) { character = "?"; }
            result.append(character);
        }
        return result.toString();
    }
    private float width(String text, float size) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }
    private List<String> wrap(String value, float size, float maxWidth) throws IOException {
        var lines = new ArrayList<String>();
        for (String paragraph : printable(value).split("\n", -1)) {
            String line = "";
            for (String word : paragraph.strip().split(" +")) {
                if (word.isEmpty()) continue;
                if (!line.isEmpty() && width(line + word, size) > maxWidth) {
                    lines.add(line);
                    line = "";
                }
                // Split long identifiers without dropping characters or overflowing the page.
                for (int cp : word.codePoints().toArray()) {
                    String character = new String(Character.toChars(cp));
                    if (!line.isEmpty() && width(line + character, size) > maxWidth) {
                        lines.add(line);
                        line = "";
                    }
                    if (line.isEmpty()) line = character;
                    else line += character;
                }
                line += " ";
            }
            lines.add(line.stripTrailing());
        }
        return lines;
    }
    private void draw(String text, float x, float baseline, float size, float[] color) throws IOException {
        stream.setNonStrokingColor(color[0], color[1], color[2]);
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, baseline);
        stream.showText(text);
        stream.endText();
    }
    private void rule(float baseline) throws IOException {
        stream.setStrokingColor(RULE[0], RULE[1], RULE[2]);
        stream.setLineWidth(0.6f);
        stream.moveTo(MARGIN, baseline);
        stream.lineTo(MARGIN + WIDTH, baseline);
        stream.stroke();
    }
    private void paragraph(String value, float size, float leading, float[] color) throws IOException {
        for (String line : wrap(value, size, WIDTH)) {
            ensure(leading);
            draw(line, MARGIN, y, size, color);
            y -= leading;
        }
    }
    private void field(String label, String value) throws IOException {
        ensure(44);
        draw(label.toUpperCase(java.util.Locale.ROOT), MARGIN, y, 7.5f, MUTED);
        y -= 15;
        paragraph(value, 10, 14, INK);
        y -= 9;
    }
    private void pair(String leftLabel, String leftValue, String rightLabel, String rightValue) throws IOException {
        float column = (WIDTH - 24) / 2;
        var left = wrap(leftValue, 10, column);
        var right = wrap(rightValue, 10, column);
        float height = 24 + Math.max(left.size(), right.size()) * 14;
        // Very long values use full-width fields, which can paginate safely.
        if (height > 180) {
            field(leftLabel, leftValue);
            field(rightLabel, rightValue);
            return;
        }
        ensure(height);
        draw(leftLabel.toUpperCase(java.util.Locale.ROOT), MARGIN, y, 7.5f, MUTED);
        draw(rightLabel.toUpperCase(java.util.Locale.ROOT), MARGIN + column + 24, y, 7.5f, MUTED);
        y -= 15;
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            if (i < left.size()) draw(left.get(i), MARGIN, y, 10, INK);
            if (i < right.size()) draw(right.get(i), MARGIN + column + 24, y, 10, INK);
            y -= 14;
        }
        y -= 9;
    }
    private void section(String index, String heading) throws IOException {
        ensure(70);
        stream.setNonStrokingColor(0.94f, 0.95f, 0.97f);
        stream.addRect(MARGIN, y - 21, WIDTH, 27);
        stream.fill();
        draw(index + "   " + heading, MARGIN + 10, y - 11, 9, INK);
        y -= 42;
    }
    private void header(Company company, byte[] logo) throws IOException {
        float textX = MARGIN;
        float textWidth = WIDTH;
        float top = y;
        if (logo != null) {
            var image = PDImageXObject.createFromByteArray(document, logo, "logo");
            float scale = Math.min(70f / image.getWidth(), 52f / image.getHeight());
            stream.drawImage(image, MARGIN, top - image.getHeight() * scale + 8,
                    image.getWidth() * scale, image.getHeight() * scale);
            textX += 88;
            textWidth -= 88;
        }
        for (String line : wrap(company.name(), 16, textWidth)) {
            ensure(22);
            draw(line, textX, y, 16, INK);
            y -= 22;
        }
        y -= 2;
        for (String line : wrap("CPF/CNPJ: " + fallback(company.document()) + "  |  Telefone: " + fallback(company.phone()), 8, textWidth)) {
            ensure(12);
            draw(line, textX, y, 8, MUTED);
            y -= 12;
        }
        for (String line : wrap("Endereço: " + fallback(company.address()), 8, textWidth)) {
            ensure(12);
            draw(line, textX, y, 8, MUTED);
            y -= 12;
        }
        y = Math.min(y, top - (logo == null ? 0 : 56)) - 12;
        rule(y);
        y -= 32;
        ensure(65);
        draw(title, MARGIN, y, 21, INK);
        y -= 22;
        draw("ORDEM DE SERVIÇO Nº " + number, MARGIN, y, 9, MUTED);
        y -= 30;
    }
    private void signatures(boolean delivery) throws IOException {
        ensure(170);
        section(delivery ? "05" : "04", "CIÊNCIA E ASSINATURAS");
        paragraph(delivery
                ? "Declaro o recebimento do equipamento identificado nesta ordem de serviço."
                : "Confirmo a entrada do equipamento para avaliação, com o defeito, estado físico e acessórios descritos acima.", 9, 13, INK);
        y -= 34;
        ensure(56);
        float column = (WIDTH - 30) / 2;
        stream.setStrokingColor(RULE[0], RULE[1], RULE[2]);
        stream.moveTo(MARGIN, y); stream.lineTo(MARGIN + column, y);
        stream.moveTo(MARGIN + column + 30, y); stream.lineTo(MARGIN + WIDTH, y);
        stream.stroke();
        draw("Assinatura do cliente", MARGIN, y - 14, 8, MUTED);
        draw("Assinatura da empresa", MARGIN + column + 30, y - 14, 8, MUTED);
        y -= 42;
        draw("Data: ____/____/________", MARGIN, y, 9, INK);
    }
    private void finish() throws IOException {
        stream.close();
        stream = null;
        int total = document.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            stream = new PDPageContentStream(document, document.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true);
            rule(45);
            draw("FixFlow  |  OS nº " + number, MARGIN, 29, 7, MUTED);
            String pagination = "Página " + (i + 1) + " de " + total;
            draw(pagination, MARGIN + WIDTH - width(pagination, 7), 29, 7, MUTED);
            stream.close();
            stream = null;
        }
    }
    private static String date(java.time.Instant value) {
        return value == null ? "Não informado" : DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of("America/Sao_Paulo")).format(value);
    }
    public static byte[] render(WorkOrder order, Company company, byte[] logo, boolean delivery) {
        try (var pdf = new WorkOrderPdf(delivery, order.number()); var output = new ByteArrayOutputStream()) {
            pdf.header(company, logo);
            pdf.section("01", "IDENTIFICAÇÃO DO ATENDIMENTO");
            pdf.pair("Cliente", order.customerName(), "Recebimento", date(order.openedAt()));
            pdf.pair("Equipamento", order.equipmentLabel(), "Número de série", order.serialNumber());
            pdf.section("02", "REGISTRO DE ENTRADA DO EQUIPAMENTO");
            pdf.field("Defeito relatado", order.reportedIssue());
            pdf.field("Estado físico na entrada", order.physicalCondition());
            pdf.field("Acessórios na entrada", order.receivedAccessories().isEmpty() ? "Nenhum registrado" : String.join(", ", order.receivedAccessories()));
            if (delivery) {
                pdf.section("03", "ENTREGA E GARANTIA ADICIONAL");
                pdf.pair("Entrega", date(order.deliveredAt()), "Responsável", order.deliveredByName());
                pdf.field("Prazo adicional de garantia", (order.warrantyDays() == null ? 0 : order.warrantyDays()) + " dias. Vencimento: " +
                        (order.warrantyUntil() == null ? "Sem prazo adicional registrado" : order.warrantyUntil().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
            }
            pdf.section(delivery ? "04" : "03", "TERMOS DE GARANTIA");
            String terms = delivery ? order.warrantyTerms() : company.terms();
            pdf.paragraph(terms == null || terms.isBlank() ? "Nenhum termo registrado." : terms, 9, 13, INK);
            pdf.y -= 16;
            pdf.signatures(delivery);
            pdf.finish();
            pdf.document.save(output);
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException("Não foi possível gerar o PDF da OS.", exception); }
    }
    private static String fallback(String value) { return value == null || value.isBlank() ? "Não informado" : value; }
    @Override public void close() throws IOException {
        try { if (stream != null) stream.close(); } finally { document.close(); }
    }
}
