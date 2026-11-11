package com.gearhub.users;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tira os metadados das fotos enviadas antes de guardar: EXIF (com a localização GPS, o aparelho e a data),
 * XMP, IPTC e comentários. Foto de celular costuma levar o lugar exato onde foi tirada, e qualquer pessoa
 * logada pode baixar o arquivo. Trabalha nos bytes, sem recomprimir: a imagem não perde qualidade.
 * Fica o necessário para exibir certo: perfil de cor e a orientação (senão a foto aparece deitada).
 */
public final class ImageMetadata {

    private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
    // PNG: blocos de texto, data e EXIF
    private static final Set<String> PNG_DROP = Set.of("tEXt", "zTXt", "iTXt", "eXIf", "tIME");
    // WebP: blocos de metadados; e os bits que avisam que eles existem, no VP8X
    private static final Set<String> WEBP_DROP = Set.of("EXIF", "XMP ");
    private static final int WEBP_FLAG_EXIF = 0x08;
    private static final int WEBP_FLAG_XMP = 0x04;

    private ImageMetadata() {
    }

    /** {@code extension}: jpg, png ou webp, como detectado pelos bytes. Arquivo malformado é recusado. */
    public static byte[] strip(byte[] image, String extension) {
        return switch (extension) {
            case "jpg" -> stripJpeg(image);
            case "png" -> stripPng(image);
            case "webp" -> stripWebp(image);
            default -> throw new IllegalArgumentException("Formato sem limpeza de metadados: " + extension);
        };
    }

    // ---------- JPEG ----------

    private static byte[] stripJpeg(byte[] in) {
        require(in.length > 4 && u8(in, 0) == 0xFF && u8(in, 1) == 0xD8);
        List<byte[]> kept = new ArrayList<>();
        Integer orientation = null;
        boolean jfifFirst = false;
        int i = 2;
        while (true) {
            require(i + 2 <= in.length && u8(in, i) == 0xFF);
            int marker = u8(in, i + 1);
            if (marker == 0xFF) {
                // Bytes de preenchimento entre segmentos
                i++;
                continue;
            }
            if (marker == 0xD9) {
                kept.add(slice(in, i, 2));
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                kept.add(slice(in, i, 2));
                i += 2;
                continue;
            }
            require(i + 4 <= in.length);
            int length = (u8(in, i + 2) << 8) | u8(in, i + 3);
            require(length >= 2 && i + 2 + length <= in.length);
            if (marker == 0xDA) {
                // Início da imagem em si: daqui até o fim é o conteúdo comprimido, copiado como está
                kept.add(slice(in, i, in.length - i));
                break;
            }
            boolean exif = marker == 0xE1 && startsWith(in, i + 4, EXIF_HEADER);
            if (exif && orientation == null) {
                orientation = jpegOrientation(in, i + 4 + EXIF_HEADER.length, length - 2 - EXIF_HEADER.length);
            }
            // Fica: APP0 (JFIF), APP2 (perfil de cor), APP14 (Adobe, cores) e os segmentos da imagem
            boolean metadata = marker == 0xE1 || (marker >= 0xE3 && marker <= 0xED) || marker == 0xEF || marker == 0xFE;
            if (!metadata) {
                if (kept.isEmpty() && marker == 0xE0) {
                    jfifFirst = true;
                }
                kept.add(slice(in, i, 2 + length));
            }
            i += 2 + length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(0xFF);
        out.write(0xD8);
        int start = 0;
        if (jfifFirst) {
            out.writeBytes(kept.getFirst());
            start = 1;
        }
        // Orientação volta num EXIF mínimo, só com ela
        if (orientation != null && orientation >= 2 && orientation <= 8) {
            out.writeBytes(orientationSegment(orientation));
        }
        for (int k = start; k < kept.size(); k++) {
            out.writeBytes(kept.get(k));
        }
        return out.toByteArray();
    }

    /** Lê a etiqueta Orientation (0x0112) do IFD0 do EXIF; nula se não houver. */
    private static Integer jpegOrientation(byte[] in, int tiff, int size) {
        if (size < 8 || tiff + size > in.length) {
            return null;
        }
        ByteOrder order;
        if (in[tiff] == 'I' && in[tiff + 1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (in[tiff] == 'M' && in[tiff + 1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(in, tiff, size).slice().order(order);
        long ifd = Integer.toUnsignedLong(buffer.getInt(4));
        if (ifd + 2 > size) {
            return null;
        }
        int entries = Short.toUnsignedInt(buffer.getShort((int) ifd));
        for (int e = 0; e < entries; e++) {
            int entry = (int) ifd + 2 + e * 12;
            if (entry + 12 > size) {
                return null;
            }
            if (Short.toUnsignedInt(buffer.getShort(entry)) == 0x0112) {
                return Short.toUnsignedInt(buffer.getShort(entry + 8));
            }
        }
        return null;
    }

    private static byte[] orientationSegment(int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        int length = 2 + EXIF_HEADER.length + tiff.capacity();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(EXIF_HEADER);
        out.writeBytes(tiff.array());
        return out.toByteArray();
    }

    // ---------- PNG ----------

    private static byte[] stripPng(byte[] in) {
        require(in.length > 8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, 8);
        int i = 8;
        while (i < in.length) {
            require(i + 12 <= in.length);
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(in, i, 4).getInt());
            require(length <= in.length - i - 12L);
            String type = new String(in, i + 4, 4, StandardCharsets.ISO_8859_1);
            int total = 12 + (int) length;
            if (!PNG_DROP.contains(type)) {
                out.write(in, i, total);
            }
            i += total;
            if ("IEND".equals(type)) {
                break;
            }
        }
        return out.toByteArray();
    }

    // ---------- WebP ----------

    private static byte[] stripWebp(byte[] in) {
        require(in.length >= 12 && startsWith(in, 0, "RIFF".getBytes(StandardCharsets.ISO_8859_1))
                && startsWith(in, 8, "WEBP".getBytes(StandardCharsets.ISO_8859_1)));
        ByteArrayOutputStream body = new ByteArrayOutputStream(in.length);
        int i = 12;
        while (i + 8 <= in.length) {
            String type = new String(in, i, 4, StandardCharsets.ISO_8859_1);
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(in, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            long padded = size + (size & 1);
            require(padded <= in.length - i - 8L);
            int total = 8 + (int) padded;
            if (!WEBP_DROP.contains(type)) {
                byte[] chunk = slice(in, i, total);
                if ("VP8X".equals(type) && size >= 1) {
                    chunk[8] = (byte) (chunk[8] & ~(WEBP_FLAG_EXIF | WEBP_FLAG_XMP));
                }
                body.writeBytes(chunk);
            }
            i += total;
        }
        byte[] chunks = body.toByteArray();
        ByteBuffer out = ByteBuffer.allocate(12 + chunks.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(4 + chunks.length)
                .put("WEBP".getBytes(StandardCharsets.ISO_8859_1)).put(chunks);
        return out.array();
    }

    // ---------- Apoio ----------

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Imagem malformada");
        }
    }

    private static int u8(byte[] in, int index) {
        return in[index] & 0xFF;
    }

    private static boolean startsWith(byte[] in, int offset, byte[] prefix) {
        if (offset + prefix.length > in.length) {
            return false;
        }
        for (int k = 0; k < prefix.length; k++) {
            if (in[offset + k] != prefix[k]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] slice(byte[] in, int offset, int length) {
        byte[] part = new byte[length];
        System.arraycopy(in, offset, part, 0, length);
        return part;
    }
}
