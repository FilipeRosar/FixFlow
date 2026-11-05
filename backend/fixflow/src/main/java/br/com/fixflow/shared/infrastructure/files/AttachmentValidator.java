package br.com.fixflow.shared.infrastructure.files;

import br.com.fixflow.shared.application.files.AttachmentValidation;
import org.apache.tika.Tika;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Component
public class AttachmentValidator implements AttachmentValidation {
    private final Tika tika = new Tika();
    private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp", "pdf", "application/pdf");
    private static final Set<String> ACTIVE_PDF_KEYS = Set.of("JS", "JavaScript", "EmbeddedFiles", "EF", "XFA", "RichMediaContent");
    @Override public Validated validate(String name, String declared, byte[] content) {
        if (content.length == 0) throw invalid("O arquivo está vazio.");
        if (content.length > MAX_BYTES) throw oversized();
        String safeName = name == null ? "" : name.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (safeName.isBlank() || safeName.length() > 180) throw invalid("Informe um nome de arquivo de até 180 caracteres.");
        String extension = safeName.substring(safeName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String expected = TYPES.get(extension);
        // No filename is passed to Tika: detection must depend on bytes, not the supplied extension.
        if (expected == null || !expected.equals(declared) || !expected.equals(tika.detect(content)))
            throw invalid("Extensão, MIME e conteúdo do arquivo devem corresponder a JPEG, PNG, WebP ou PDF.");
        try {
            byte[] sanitized;
            String storedType;
            String storedExtension;
            if (expected.equals("application/pdf")) {
                sanitized = pdf(content); storedType = expected; storedExtension = "pdf";
            } else {
                storedExtension = expected.equals("image/jpeg") ? "jpg" : "png";
                storedType = storedExtension.equals("jpg") ? "image/jpeg" : "image/png";
                sanitized = image(content, storedExtension);
            }
            if (sanitized.length > MAX_BYTES) throw oversized();
            return new Validated(safeName, storedExtension, storedType, sanitized, sha256(sanitized), sha256(content));
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid("Arquivo inválido, corrompido ou não suportado.");
        }
    }
    private byte[] image(byte[] source, String format) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("Imagem inválida.");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 20_000_000) throw invalid("A imagem deve ter no máximo 20 megapixels.");
                BufferedImage decoded = reader.read(0);
                // Copy pixels into a new image; no original EXIF, ICC, GPS or appended payload survives.
                var clean = new BufferedImage(width, height, format.equals("jpg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
                int[] pixels = new int[width];
                for (int y = 0; y < height; y++) {
                    decoded.getRGB(0, y, width, 1, pixels, 0, width);
                    clean.setRGB(0, y, width, 1, pixels, 0, width);
                }
                decoded.flush();
                var output = boundedOutput();
                try {
                    if (!ImageIO.write(clean, format, output)) throw invalid("Não foi possível processar a imagem.");
                    return output.toByteArray();
                } finally { clean.flush(); }
            } finally { reader.dispose(); }
        }
    }
    private byte[] pdf(byte[] source) throws IOException {
        try (var document = Loader.loadPDF(source)) {
            if (document.isEncrypted() || document.getNumberOfPages() < 1) throw invalid("PDF protegido ou sem páginas não é permitido.");
            var pending = new ArrayDeque<COSBase>();
            var visited = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
            pending.add(document.getDocumentCatalog().getCOSObject());
            while (!pending.isEmpty()) {
                COSBase node = pending.removeFirst();
                if (!visited.add(node)) continue;
                if (visited.size() > 100_000) throw invalid("PDF complexo demais para processamento.");
                if (node instanceof COSObject ref) {
                    if (ref.getObject() != null) pending.add(ref.getObject());
                } else if (node instanceof COSDictionary dictionary) {
                    for (String key : ACTIVE_PDF_KEYS) if (dictionary.containsKey(COSName.getPDFName(key))) throw invalid("PDF com scripts, ações automáticas ou arquivos incorporados não é permitido.");
                    COSName action = dictionary.getCOSName(COSName.S);
                    if (action != null && Set.of("JavaScript", "Launch", "SubmitForm", "ImportData", "GoToR", "GoToE", "Rendition", "Movie", "Sound").contains(action.getName()))
                        throw invalid("PDF com conteúdo ativo não é permitido.");
                    if (COSName.URI.equals(action)) {
                        String link = dictionary.getString(COSName.URI);
                        String scheme = link == null ? "" : java.net.URI.create(link.strip()).getScheme();
                        if (scheme == null || !Set.of("https", "http", "mailto").contains(scheme.toLowerCase(Locale.ROOT)))
                            throw invalid("PDF com protocolo de link inseguro não é permitido.");
                    }
                    for (COSBase value : dictionary.getValues()) if (value != null) pending.add(value);
                } else if (node instanceof COSArray array) {
                    for (COSBase value : array) if (value != null) pending.add(value);
                }
            }
            var output = boundedOutput();
            document.save(output);
            return output.toByteArray();
        }
    }
    private ByteArrayOutputStream boundedOutput() {
        return new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] value, int offset, int length) {
                if ((long) count + length > MAX_BYTES) throw oversized();
                super.write(value, offset, length);
            }
            @Override public synchronized void write(int value) {
                if (count >= MAX_BYTES) throw oversized();
                super.write(value);
            }
        };
    }
    public static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private ResponseStatusException invalid(String detail) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail); }
    private ResponseStatusException oversized() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O limite é de 10 MB por arquivo, inclusive após o processamento."); }
}
