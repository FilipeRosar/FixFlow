package br.com.fixflow.shared.files;

import br.com.fixflow.shared.infrastructure.files.AttachmentValidator;
import br.com.fixflow.shared.application.files.AttachmentValidation;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentValidatorT12Test {
    final AttachmentValidator validator = new AttachmentValidator();
    static byte[] image(String format) throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }
    void rejected(String name, String type, byte[] bytes, int status) {
        assertEquals(status, assertThrows(ResponseStatusException.class, () -> validator.validate(name, type, bytes)).getStatusCode().value());
    }
    @Test void t12RejectsExecutableWithFakeJpegExtensionAndMime() {
        rejected("foto.jpg", "image/jpeg", new byte[]{'M', 'Z', 0, 0, 0, 0}, 422);
        rejected("foto.png", "image/png", "<script>alert(1)</script>".getBytes(), 422);
    }
    @Test void extensionDeclaredMimeAndBytesMustAllAgree() throws Exception {
        byte[] png = image("png");
        rejected("foto.jpg", "image/jpeg", png, 422);
        rejected("foto.png", "image/jpeg", png, 422);
        rejected("foto.svg", "image/svg+xml", png, 422);
        rejected("foto.png", null, png, 422);
        rejected("foto.png", "image/png", Arrays.copyOf(png, 12), 422);
    }
    @Test void rejectsEmptyAndOversizedFilesAndAcceptsExactlyTenMiB() throws Exception {
        rejected("foto.png", "image/png", new byte[0], 422);
        rejected("foto.png", "image/png", new byte[AttachmentValidation.MAX_BYTES + 1], 413);
        var result = validator.validate("foto.png", "image/png", Arrays.copyOf(image("png"), AttachmentValidation.MAX_BYTES));
        assertTrue(result.content().length < 1024);
    }
    @Test void reencodesImagesStripsAppendedPayloadAndSanitizesOriginalNames() throws Exception {
        byte[] source = image("png");
        byte[] appended = Arrays.copyOf(source, source.length + 50);
        Arrays.fill(appended, source.length, appended.length, (byte) 'X');
        var result = validator.validate("../../foto\r\n.png", "image/png", appended);
        assertEquals("foto.png", result.originalName());
        assertEquals("image/png", result.contentType());
        assertNotEquals(result.sourceChecksum(), result.checksum());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(result.content())));
        assertEquals(AttachmentValidator.sha256(result.content()), result.checksum());
        assertEquals("image/jpeg", validator.validate("foto.JPEG", "image/jpeg", image("jpeg")).contentType());
    }
    @Test void acceptsWebpAndStoresOnlyReencodedPngPixels() throws Exception {
        try (var input = getClass().getResourceAsStream("/files/sample.webp")) {
            var result = validator.validate("foto.webp", "image/webp", input.readAllBytes());
            assertEquals("image/png", result.contentType());
            assertEquals("png", result.extension());
            assertEquals(2, ImageIO.read(new ByteArrayInputStream(result.content())).getWidth());
        }
    }
    @Test void parsesPdfAndRejectsScriptsEvenWhenStoredAsPdfObjects() throws Exception {
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            var output = new ByteArrayOutputStream(); document.save(output);
            assertEquals("application/pdf", validator.validate("laudo.pdf", "application/pdf", output.toByteArray()).contentType());
            document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('unsafe')"));
            output.reset(); document.save(output);
            rejected("laudo.pdf", "application/pdf", output.toByteArray(), 422);
        }
        rejected("laudo.pdf", "application/pdf", "%PDF-1.7\nnot a real document\n%%EOF".getBytes(), 422);
    }
    @Test void allowsOrdinaryPdfLinksButRejectsExecutableProtocols() throws Exception {
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            var action = new org.apache.pdfbox.cos.COSDictionary();
            action.setName(org.apache.pdfbox.cos.COSName.S, "URI");
            action.setString(org.apache.pdfbox.cos.COSName.URI, "https://example.com/manual");
            document.getDocumentCatalog().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("Link"), action);
            var output = new ByteArrayOutputStream(); document.save(output);
            assertEquals("application/pdf", validator.validate("manual.pdf", "application/pdf", output.toByteArray()).contentType());
            action.setString(org.apache.pdfbox.cos.COSName.URI, "javascript:app.alert(1)");
            output.reset(); document.save(output);
            rejected("manual.pdf", "application/pdf", output.toByteArray(), 422);
        }
    }

}
