package com.gearhub.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** As fotos perdem localização, aparelho e textos escondidos, mas continuam abrindo e na orientação certa. */
public class ImageMetadataTest {

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static byte[] image(String format) throws IOException {
        BufferedImage picture = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        picture.setRGB(1, 1, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(picture, format, out);
        return out.toByteArray();
    }

    /** PNG de verdade com um bloco tEXt logo depois do cabeçalho. Usado também no teste de envio. */
    public static byte[] pngWithText(String value) throws IOException {
        byte[] png = image("png");
        byte[] data = ("Comment\0" + value).getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length).put("tEXt".getBytes(StandardCharsets.ISO_8859_1)).put(data);
        CRC32 crc = new CRC32();
        crc.update(chunk.array(), 4, 4 + data.length);
        chunk.putInt((int) crc.getValue());
        // Depois do IHDR (8 de assinatura + 25 do bloco)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, 33);
        out.writeBytes(chunk.array());
        out.write(png, 33, png.length - 33);
        return out.toByteArray();
    }

    /** EXIF como o de um celular: orientação 6 (girada) e um texto de GPS. */
    private static byte[] exifSegment() {
        byte[] gps = "GPSLatitude -23.5505 Samsung Galaxy".getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + gps.length).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x010F).putShort((short) 2).putInt(gps.length).putInt(8 + 2 + 2 * 12 + 4);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0);
        tiff.putInt(0);
        tiff.put(gps);
        byte[] header = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        int length = 2 + header.length + tiff.capacity();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(header);
        out.writeBytes(tiff.array());
        return out.toByteArray();
    }

    private static byte[] jpegWithExif() throws IOException {
        byte[] jpeg = image("jpg");
        byte[] xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>GPSAltitude</x:xmpmeta>");
        byte[] comment = segment(0xFE, "Tirada na casa do fulano");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.writeBytes(exifSegment());
        out.writeBytes(xmp);
        out.writeBytes(comment);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] segment(int marker, String content) {
        byte[] data = content.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        out.write((data.length + 2) >> 8);
        out.write((data.length + 2) & 0xFF);
        out.writeBytes(data);
        return out.toByteArray();
    }

    @Test
    void jpegPerdeGpsXmpEComentarioMasGuardaAOrientacao() throws IOException {
        byte[] original = jpegWithExif();
        assertThat(text(original)).contains("GPSLatitude", "GPSAltitude", "casa do fulano");

        byte[] clean = ImageMetadata.strip(original, "jpg");
        assertThat(text(clean)).doesNotContain("GPSLatitude", "Samsung", "GPSAltitude", "casa do fulano");
        // Continua abrindo, do mesmo tamanho
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(clean));
        assertThat(read.getWidth()).isEqualTo(4);
        assertThat(read.getHeight()).isEqualTo(3);
        // EXIF mínimo com a orientação 6, para a foto não aparecer deitada
        assertThat(text(clean)).contains("Exif\0\0MM");
        int at = text(clean).indexOf("Exif\0\0MM") + 6;
        assertThat(ByteBuffer.wrap(clean, at + 8 + 2 + 8, 2).getShort()).isEqualTo((short) 6);
    }

    @Test
    void jpegSemExifFicaIgual() throws IOException {
        byte[] jpeg = image("jpg");
        assertThat(ImageMetadata.strip(jpeg, "jpg")).isEqualTo(jpeg);
    }

    @Test
    void pngPerdeOsBlocosDeTexto() throws IOException {
        byte[] clean = ImageMetadata.strip(pngWithText("GPSLatitude=-23.55"), "png");
        assertThat(text(clean)).doesNotContain("GPSLatitude", "tEXt");
        assertThat(ImageIO.read(new ByteArrayInputStream(clean)).getWidth()).isEqualTo(4);
        assertThat(clean).isEqualTo(image("png"));
    }

    @Test
    void webpPerdeExifEXmpEAjustaOTamanho() {
        byte[] vp8x = {0x0C, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] exif = "GPSLatitude".getBytes(StandardCharsets.ISO_8859_1);
        byte[] image = {1, 2, 3, 4};
        ByteBuffer webp = ByteBuffer.allocate(12 + 8 + vp8x.length + 8 + exif.length + 1 + 8 + image.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        webp.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(webp.capacity() - 8).put("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        webp.put("VP8X".getBytes(StandardCharsets.ISO_8859_1)).putInt(vp8x.length).put(vp8x);
        webp.put("EXIF".getBytes(StandardCharsets.ISO_8859_1)).putInt(exif.length).put(exif).put((byte) 0);
        webp.put("VP8L".getBytes(StandardCharsets.ISO_8859_1)).putInt(image.length).put(image);

        byte[] clean = ImageMetadata.strip(webp.array(), "webp");
        assertThat(text(clean)).doesNotContain("GPSLatitude", "EXIF").contains("VP8X", "VP8L");
        ByteBuffer read = ByteBuffer.wrap(clean).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(read.getInt(4)).isEqualTo(clean.length - 8);
        // Bits de EXIF (0x08) e XMP (0x04) apagados no VP8X
        assertThat(clean[20] & 0x0C).isZero();
    }

    @Test
    void arquivoMalformadoERecusado() {
        assertThatThrownBy(() -> ImageMetadata.strip(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x12, 0x34, 0x56}, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageMetadata.strip(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1}, "png"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
