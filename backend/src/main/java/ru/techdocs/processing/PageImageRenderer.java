package ru.techdocs.processing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Рендер страницы документа в PNG — для vision-моделей. */
@Service
public class PageImageRenderer {

    public byte[] renderPng(String filename, InputStream input, int pageNumber, int dpi) throws Exception {
        BufferedImage image;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            try (PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(input))) {
                if (pageNumber < 1 || pageNumber > pdf.getNumberOfPages()) {
                    throw new IllegalArgumentException("Страницы " + pageNumber + " нет в документе");
                }
                image = new PDFRenderer(pdf).renderImageWithDPI(pageNumber - 1, dpi, ImageType.RGB);
            }
        } else {
            image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("Не удалось прочитать изображение");
            }
        }
        image = capSize(image, 2000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Ограничение длинной стороны: слишком крупные картинки ломают маленькие vision-модели. */
    private BufferedImage capSize(BufferedImage image, int maxSide) {
        int width = image.getWidth();
        int height = image.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxSide) return image;
        double scale = (double) maxSide / longest;
        int newWidth = (int) Math.round(width * scale);
        int newHeight = (int) Math.round(height * scale);
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        var graphics = scaled.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(image, 0, 0, newWidth, newHeight, null);
        graphics.dispose();
        return scaled;
    }
}
