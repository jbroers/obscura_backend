package com.example.obscura_backend.service.exif;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public class CR3Parser {
    private static final Logger logger = LoggerFactory.getLogger(CR3Parser.class);

    public static Map<String, String> parse(MultipartFile file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null) return out;

        try (InputStream is = file.getInputStream(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
            byte[] data = baos.toByteArray();

            return parseBytesInternal(data, file.getOriginalFilename(), out);

        } catch (Exception e) {
            logger.debug("CR3Parser.parse failed: {}", e.getMessage());
        }

        return out;
    }

    public static Map<String, String> parseBytes(byte[] data, String name) {
        Map<String, String> out = new LinkedHashMap<>();
        if (data == null || data.length == 0) return out;
        return parseBytesInternal(data, name, out);
    }

    private static Map<String, String> parseBytesInternal(byte[] data, String fname, Map<String, String> out) {
        try {
            logger.debug("CR3Parser: parsing file '{}' size={} bytes", fname == null ? "<unknown>" : fname, data.length);

            try { parseBoxes(data, 0, data.length, out); } catch (Exception e) { logger.debug("parseBoxes failed: {}", e.getMessage()); }
            try { extractUuidBoxesAndParse(data, out); } catch (Exception e) { logger.debug("extractUuidBoxesAndParse failed: {}", e.getMessage()); }

            extractPrvwBoxes(data, out);
             extractThmbBoxes(data, out);
             extractCrawAndCmp1(data, out);

            extractCmtTiffBoxesAndParse(data, out);

            if (!out.containsKey("CTMD:ISO") && !out.containsKey("CTMD:Aperture") && !out.containsKey("CTMD:Shutter") && !out.containsKey("CTMD:FocalLength")) {
                 extractCtmdRecords(data, out);
                 scanForCtmdRecordsAnywhere(data, out);
             }

            if (!out.containsKey("CMT:Make") && !out.containsKey("TIFF:Make")) {
                 scanForTiffHeadersAnywhere(data, out);
             }

             if (!out.isEmpty()) {
                logger.debug("CR3Parser: discovered {} metadata entries", out.size());
             } else {
                logger.debug("CR3Parser: no metadata discovered");
             }

         } catch (Exception e) {
            logger.debug("CR3Parser.parse failed: {}", e.getMessage());
         }

         return out;
     }

     private static void parseBoxes(byte[] data, int start, int end, Map<String, String> out) {
         int p = start;
         while (p + 8 <= end) {
            int size = safeReadIntBE(data, p);
            String type = new String(new byte[]{data[p+4], data[p+5], data[p+6], data[p+7]});
            long headerSize = 8;
            long boxSize = size & 0xFFFFFFFFL;
            if (boxSize == 1) {
                if (p + 16 > end) break;
                boxSize = (((long) (data[p+8] & 0xFF) << 56) | ((long) (data[p+9] & 0xFF) << 48) | ((long) (data[p+10] & 0xFF) << 40) | ((long) (data[p+11] & 0xFF) << 32)
                        | ((long) (data[p+12] & 0xFF) << 24) | ((long) (data[p+13] & 0xFF) << 16) | ((long) (data[p+14] & 0xFF) << 8) | ((long) (data[p+15] & 0xFF)));
                 headerSize = 16;
             } else if (boxSize == 0) {
                boxSize = end - p;
            }

            if (boxSize < headerSize || p + boxSize > end) break;

            int payloadStart = (int) (p + headerSize);
            int payloadEnd = (int) (p + boxSize);

             if ("CTMD".equals(type) || (type.startsWith("CMT") || "uuid".equals(type))) {
                try { logger.debug("box {} @{} size={}", type, p, boxSize); } catch (Exception ignored) {}
            }

             if ("CTMD".equals(type)) {
                try {
                    int before = out.size();
                    parseCtmdBox(data, payloadStart, payloadEnd, out);
                    int after = out.size();
                    logger.debug("CR3Parser.parseBoxes: parsed CTMD at {} added {} fields", p, Math.max(0, after - before));
                } catch (Exception e) { logger.debug("parseCtmdBox failed: {}", e.getMessage()); }
            }

             if (isContainerBox(type) || "uuid".equals(type)) {
                try { parseBoxes(data, payloadStart, payloadEnd, out); } catch (Exception e) { logger.debug("parseBoxes recursion failed: {}", e.getMessage()); }
            }

             if (boxSize > Integer.MAX_VALUE) break;
            p = (int) (p + boxSize);
        }
    }

    private static boolean isContainerBox(String type) {
        return "moov".equals(type) || "trak".equals(type) || "mdia".equals(type) || "minf".equals(type) || "stbl".equals(type) || "edts".equals(type) || "udta".equals(type) || "meta".equals(type);
    }

    private static void parseCtmdBox(byte[] data, int start, int end, Map<String, String> out) {
        int p = start;
        int parsedRecords = 0;
        while (p + 12 <= end) {
            int recSize = safeReadIntLE(data, p);
            int recType = safeReadUnsignedShortLE(data, p + 4);
            if (recSize <= 12 || recSize > (end - p)) { p += 4; continue; }
            int payload = p + 12;
            try {
                if (recType == 5) {
                    int fnumNum = safeReadUnsignedShortLE(data, payload);
                    int fnumDen = safeReadUnsignedShortLE(data, payload + 2);
                    if (fnumDen > 0 && fnumNum > 0) out.putIfAbsent("CTMD:Aperture", String.format("f/%.1f", ((double) fnumNum) / fnumDen));
                    int expNum = safeReadUnsignedShortLE(data, payload + 4);
                    int expDen = safeReadUnsignedShortLE(data, payload + 6);
                    if (expNum > 0 && expDen > 0) out.putIfAbsent("CTMD:Shutter", (expNum % expDen == 0) ? String.valueOf(expNum/expDen) : expNum + "/" + expDen);
                    int iso = getIsoCandidate(data, payload);
                    if (isPlausibleIso(iso)) out.putIfAbsent("CTMD:ISO", String.valueOf(iso));
                    parsedRecords++;
                } else if (recType == 4) {
                    int fnum = safeReadUnsignedShortLE(data, payload);
                    int fden = safeReadUnsignedShortLE(data, payload + 2);
                    if (fden > 0 && fnum > 0) {
                        double fl = ((double) fnum) / fden;
                        if (fl >= 10.0) out.putIfAbsent("CTMD:FocalLength", String.format("%.1fmm", fl));
                        else logger.debug("CR3Parser.parseCtmdBox: ignoring small CTMD focal {}mm at {}", fl, p);
                    }
                    parsedRecords++;
                }
            } catch (Exception e) { logger.debug("parseCtmdBox record parse failed: {}", e.getMessage()); }

            p += recSize;
        }
        if (parsedRecords > 0) logger.debug("CR3Parser.parseCtmdBox: parsed {} CTMD records", parsedRecords);
    }

    private static int getIsoCandidate(byte[] data, int payloadOffset) {
        int[] tries = new int[]{8, 10, 12};
        for (int off : tries) {
            int v = safeReadIntLE(data, payloadOffset + off);
            if (isPlausibleIso(v)) return v;
        }
        int v = safeReadIntLE(data, payloadOffset + 16);
        return isPlausibleIso(v) ? v : -1;
    }

    private static boolean isPlausibleIso(int iso) {
        return iso > 50 && iso <= 51200;
    }

    private static void extractPrvwBoxes(byte[] data, Map<String, String> out) {
        int from = 0;
        byte[] sig = new byte[]{'P', 'R', 'V', 'W'};
        while (true) {
            int idx = indexOf(data, sig, from);
            if (idx < 0) break;
            int boxStart = idx - 4;
            if (boxStart < 0 || boxStart + 24 > data.length) { from = idx + 4; continue; }
            try {
                int width = readUnsignedShortBE(data, boxStart + 14);
                int height = readUnsignedShortBE(data, boxStart + 16);
                int jpegSize = readIntBE(data, boxStart + 20);
                out.put("PRVW:width", String.valueOf(width));
                out.put("PRVW:height", String.valueOf(height));
                out.put("PRVW:resolution", width + "x" + height);
                out.put("PRVW:jpegSize", String.valueOf(jpegSize));
                logger.debug("CR3Parser found PRVW: {}x{} (jpeg {} bytes)", width, height, jpegSize);
            } catch (Exception e) {
                logger.debug("Failed to parse PRVW at {}: {}", boxStart, e.getMessage());
            }
            from = idx + 4;
        }
    }

    private static void extractThmbBoxes(byte[] data, Map<String, String> out) {
        int from = 0;
        byte[] sig = new byte[]{'T','H','M','B'};
        while (true) {
            int idx = indexOf(data, sig, from);
            if (idx < 0) break;
            int boxStart = idx - 4;
            if (boxStart < 0 || boxStart + 28 > data.length) { from = idx + 4; continue; }
            try {
                int width = readUnsignedShortBE(data, boxStart + 12);
                int height = readUnsignedShortBE(data, boxStart + 14);
                int jpegSize = readIntBE(data, boxStart + 16);
                out.put("THMB:width", String.valueOf(width));
                out.put("THMB:height", String.valueOf(height));
                out.put("THMB:resolution", width + "x" + height);
                out.put("THMB:jpegSize", String.valueOf(jpegSize));
                logger.debug("CR3Parser found THMB: {}x{} (jpeg {} bytes)", width, height, jpegSize);
            } catch (Exception e) {
                logger.debug("Failed to parse THMB at {}: {}", boxStart, e.getMessage());
            }
            from = idx + 4;
        }
    }

    private static void extractCrawAndCmp1(byte[] data, Map<String, String> out) {
        int from = 0;
        byte[] sig = new byte[]{'C','M','P','1'};
        while (true) {
            int idx = indexOf(data, sig, from);
            if (idx < 0) break;
            int boxStart = idx - 4;
            if (boxStart < 0 || boxStart + 40 > data.length) { from = idx + 4; continue; }
            try {
                int width = readIntBE(data, boxStart + 16);
                int height = readIntBE(data, boxStart + 20);
                out.put("CMP1:width", String.valueOf(width));
                out.put("CMP1:height", String.valueOf(height));
                out.put("CMP1:resolution", width + "x" + height);
                logger.debug("CR3Parser found CMP1: {}x{}", width, height);
            } catch (Exception e) {
                logger.debug("Failed to parse CMP1 at {}: {}", boxStart, e.getMessage());
            }
            from = idx + 4;
        }

        from = 0;
        byte[] crawSig = new byte[]{'C','R','A','W'};
        while (true) {
            int idx = indexOf(data, crawSig, from);
            if (idx < 0) break;
            int boxStart = idx - 4;
            if (boxStart < 0 || boxStart + 36 > data.length) { from = idx + 4; continue; }
            try {
                int width = readUnsignedShortBE(data, boxStart + 32);
                int height = readUnsignedShortBE(data, boxStart + 34);
                out.put("CRAW:width", String.valueOf(width));
                out.put("CRAW:height", String.valueOf(height));
                out.put("CRAW:resolution", width + "x" + height);
                logger.debug("CR3Parser found CRAW: {}x{}", width, height);
            } catch (Exception e) {
                logger.debug("Failed to parse CRAW at {}: {}", boxStart, e.getMessage());
            }
            from = idx + 4;
        }
    }

    private static void extractCtmdRecords(byte[] data, Map<String, String> out) {
        int from = 0;
        byte[] sig = new byte[]{'C','T','M','D'};
        while (true) {
            int idx = indexOf(data, sig, from);
            if (idx < 0) break;
            int boxStart = idx - 4;
            if (boxStart < 0) { from = idx + 4; continue; }
            int boxSize = safeReadIntBE(data, boxStart);
            if (boxSize <= 0 || boxStart + boxSize > data.length) boxSize = Math.min(data.length - boxStart, 1024 * 1024);
            int boxEnd = boxStart + boxSize;

            int p = boxStart + 16;
            if (p < boxStart + 12) p = boxStart + 12;

            while (p + 12 <= boxEnd - 1) {
                int recSize = safeReadIntLE(data, p);
                int recType = safeReadUnsignedShortLE(data, p + 4);
                if (recSize <= 12 || recSize > (boxEnd - p)) {
                    p += 4;
                    continue;
                }
                if (p + recSize > boxEnd) break;

                int payloadOffset = p + 12;
                try {
                    if (recType == 5) {
                        int fnumNum = safeReadUnsignedShortLE(data, payloadOffset);
                        int fnumDen = safeReadUnsignedShortLE(data, payloadOffset + 2);
                        if (fnumDen > 0 && fnumNum > 0) {
                            double f = ((double) fnumNum) / ((double) fnumDen);
                            out.putIfAbsent("CTMD:Aperture", String.format("f/%.1f", f));
                            logger.debug("CR3Parser CTMD aperture parsed: f/{} over {} => f/{}", fnumNum, fnumDen, f);
                        }

                        int expNum = safeReadUnsignedShortLE(data, payloadOffset + 4);
                        int expDen = safeReadUnsignedShortLE(data, payloadOffset + 6);
                        if (expNum > 0 && expDen > 0) {
                            if (expNum % expDen == 0) out.putIfAbsent("CTMD:Shutter", String.valueOf(expNum / expDen));
                            else out.putIfAbsent("CTMD:Shutter", expNum + "/" + expDen);
                            logger.debug("CR3Parser CTMD shutter parsed: {}/{}", expNum, expDen);
                        }

                        int isoCandidate = safeReadIntLE(data, payloadOffset + 8);
                        if (isoCandidate > 0 && isoCandidate < 100000) {
                            out.putIfAbsent("CTMD:ISO", String.valueOf(isoCandidate));
                            logger.debug("CR3Parser CTMD ISO parsed: {}", isoCandidate);
                        }
                    } else if (recType == 4) {
                        int fnum = safeReadUnsignedShortLE(data, payloadOffset);
                        int fden = safeReadUnsignedShortLE(data, payloadOffset + 2);
                        if (fden > 0 && fnum > 0) {
                            double fl = ((double) fnum) / ((double) fden);
                            out.putIfAbsent("CTMD:FocalLength", String.format("%.1fmm", fl));
                            logger.debug("CR3Parser CTMD focal parsed: {} / {} => {}mm", fnum, fden, fl);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to parse CTMD record at {} type {}: {}", p, recType, e.getMessage());
                }

                p += Math.max(1, recSize);
            }

            from = idx + 4;
        }
    }

    private static void scanForCtmdRecordsAnywhere(byte[] data, Map<String, String> out) {
        logger.debug("CR3Parser: scanning file for CTMD-style records anywhere");
        int len = data.length;
        int p = 0;
        while (p + 12 <= len - 1) {
            int recSize = safeReadIntLE(data, p);
            int recType = safeReadUnsignedShortLE(data, p + 4);
            if (recSize <= 12 || recSize > (len - p)) { p++; continue; }
            if (p + recSize > len) { p++; continue; }

            if (recType == 4 || recType == 5 || recType == 7 || recType == 8 || recType == 9) {
                int payloadOffset = p + 12;
                try {
                    if (recType == 5) {
                        int fnumNum = safeReadUnsignedShortLE(data, payloadOffset);
                        int fnumDen = safeReadUnsignedShortLE(data, payloadOffset + 2);
                        if (fnumDen > 0 && fnumNum > 0) {
                            double f = ((double) fnumNum) / ((double) fnumDen);
                            out.putIfAbsent("CTMD:Aperture", String.format("f/%.1f", f));
                            logger.debug("CR3Parser (scan) CTMD aperture parsed at {}: f/{} over {} => f/{}", p, fnumNum, fnumDen, f);
                        }

                        int expNum = safeReadUnsignedShortLE(data, payloadOffset + 4);
                        int expDen = safeReadUnsignedShortLE(data, payloadOffset + 6);
                        if (expNum > 0 && expDen > 0) {
                            if (expNum % expDen == 0) out.putIfAbsent("CTMD:Shutter", String.valueOf(expNum / expDen));
                            else out.putIfAbsent("CTMD:Shutter", expNum + "/" + expDen);
                            logger.debug("CR3Parser (scan) CTMD shutter parsed at {}: {}/{}", p, expNum, expDen);
                        }

                        int isoCandidate = safeReadIntLE(data, payloadOffset + 8);
                        if (isoCandidate > 0 && isoCandidate < 100000) {
                            out.putIfAbsent("CTMD:ISO", String.valueOf(isoCandidate));
                            logger.debug("CR3Parser (scan) CTMD ISO parsed at {}: {}", p, isoCandidate);
                        }
                    } else if (recType == 4) {
                        int fnum = safeReadUnsignedShortLE(data, payloadOffset);
                        int fden = safeReadUnsignedShortLE(data, payloadOffset + 2);
                        if (fden > 0 && fnum > 0) {
                            double fl = ((double) fnum) / ((double) fden);
                            out.putIfAbsent("CTMD:FocalLength", String.format("%.1fmm", fl));
                            logger.debug("CR3Parser (scan) CTMD focal parsed at {}: {} / {} => {}mm", p, fnum, fden, fl);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("CR3Parser (scan) failed to parse at {}: {}", p, e.getMessage());
                }

                p += recSize;
            } else {
                p++;
            }
        }
    }

    private static void extractCmtTiffBoxesAndParse(byte[] data, Map<String, String> out) {
        byte[] sig = new byte[]{'C','M','T'};
        int from = 0;
        while (true) {
            int idx = indexOf(data, sig, from);
            if (idx < 0) break;
            if (idx + 3 >= data.length) break;
            char c = (char) data[idx + 3];
            if (!(Character.isDigit(c) || Character.isLetter(c))) { from = idx + 3; continue; }

            int boxStart = idx - 4;
            if (boxStart < 0) { from = idx + 3; continue; }

            int boxSize = safeReadIntBE(data, boxStart);
            if (boxSize <= 8 || boxStart + boxSize > data.length) {
                from = idx + 3; continue;
            }

            int payloadStart = boxStart + 8;
            int payloadEnd = boxStart + boxSize;
            int tiffOff = -1;
            for (int i = payloadStart; i + 4 <= payloadEnd; i++) {
                if (i + 4 <= payloadEnd && data[i] == 0x49 && data[i+1] == 0x49 && data[i+2] == 0x2A && data[i+3] == 0x00) { tiffOff = i; break; }
                if (i + 4 <= payloadEnd && data[i] == 0x4D && data[i+1] == 0x4D && data[i+2] == 0x00 && data[i+3] == 0x2A) { tiffOff = i; break; }
            }

            if (tiffOff > 0) {
                try {
                    int len = payloadEnd - tiffOff;
                    byte[] tiffBytes = new byte[len];
                    System.arraycopy(data, tiffOff, tiffBytes, 0, len);
                    Metadata md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(tiffBytes));
                    int tagCount = 0;
                    for (Directory dir : md.getDirectories()) {
                        for (Tag tag : dir.getTags()) {
                            String key = "CMT:" + tag.getTagName();
                            String val = tag.getDescription();
                            if (val != null && !val.isBlank()) out.putIfAbsent(key, val);
                            tagCount++;
                        }
                    }
                    logger.debug("CR3Parser extracted TIFF Exif from CMT at {} ({} bytes) with {} tags", tiffOff, len, tagCount);
                } catch (Exception e) {
                    logger.debug("Failed to parse TIFF inside CMT at {}: {}", boxStart, e.getMessage());
                }
            }

            from = idx + 3;
        }
    }

    private static void scanForTiffHeadersAnywhere(byte[] data, Map<String, String> out) {
        logger.debug("CR3Parser: scanning file for TIFF headers anywhere");
        int len = data.length;
        int p = 0;
        int found = 0;
        while (p + 4 <= len - 1) {
            if (data[p] == 0x49 && data[p+1] == 0x49 && data[p+2] == 0x2A && data[p+3] == 0x00 ||
                data[p] == 0x4D && data[p+1] == 0x4D && data[p+2] == 0x00 && data[p+3] == 0x2A) {
                int maxLen = Math.min(len - p, 1024 * 1024);
                try {
                    Metadata md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data, p, maxLen));
                    int tags = 0;
                    for (Directory dir : md.getDirectories()) {
                        for (Tag tag : dir.getTags()) {
                            String key = "TIFF:" + tag.getTagName();
                            String val = tag.getDescription();
                            if (val != null && !val.isBlank()) out.putIfAbsent(key, val);
                            tags++;
                        }
                    }
                    if (tags > 0) {
                        found++;
                        logger.debug("CR3Parser parsed TIFF header at {} -> {} tags", p, tags);
                    }
                } catch (Exception e) {
                    logger.debug("CR3Parser failed to parse TIFF at {}: {}", p, e.getMessage());
                }
                p += 4;
            } else {
                p++;
            }
            if (found >= 3) break;
        }
    }

    private static void extractUuidBoxesAndParse(byte[] data, Map<String, String> out) {
        byte[] uuidSig = new byte[]{'u','u','i','d'};
        int from = 0;
        while (true) {
            int idx = indexOf(data, uuidSig, from);
            if (idx < 0) break;
            int boxStart = idx - 4;
            if (boxStart < 0 || boxStart + 24 > data.length) { from = idx + 4; continue; }
            int boxSize = safeReadIntBE(data, boxStart);
            if (boxSize <= 24 || boxStart + boxSize > data.length) { from = idx + 4; continue; }
            int userTypeOffset = idx + 4;
            if (userTypeOffset + 16 > data.length) { from = idx + 4; continue; }
            byte[] userType = new byte[16];
            System.arraycopy(data, userTypeOffset, userType, 0, 16);
            StringBuilder sb = new StringBuilder();
            for (byte b : userType) sb.append(String.format("%02x", b));
            String uuidHex = sb.toString();
            logger.debug("CR3Parser: found uuid box at {} userType={} size={}", boxStart, uuidHex, boxSize);

            int payloadStart = boxStart + 24;
            int payloadEnd = boxStart + boxSize;
            try {
                int before = out.size();
                parseCtmdBox(data, payloadStart, payloadEnd, out);
                int after = out.size();
                if (after > before) logger.debug("CR3Parser: parsed CTMD from uuid {} added {} fields", uuidHex, after - before);

                int tiffOff = -1;
                for (int i = payloadStart; i + 4 <= payloadEnd; i++) {
                    if (data[i] == 0x49 && data[i+1] == 0x49 && data[i+2] == 0x2A && data[i+3] == 0x00) { tiffOff = i; break; }
                    if (data[i] == 0x4D && data[i+1] == 0x4D && data[i+2] == 0x00 && data[i+3] == 0x2A) { tiffOff = i; break; }
                }
                if (tiffOff > 0) {
                    int len = payloadEnd - tiffOff;
                    Metadata md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data, tiffOff, len));
                    int tagCount = 0;
                    for (Directory dir : md.getDirectories()) {
                        for (Tag tag : dir.getTags()) {
                            String key = "UUID_TIFF:" + tag.getTagName();
                            String val = tag.getDescription();
                            if (val != null && !val.isBlank()) out.putIfAbsent(key, val);
                            tagCount++;
                        }
                    }
                    logger.debug("CR3Parser: parsed TIFF inside uuid {} at {} -> {} tags", uuidHex, tiffOff, tagCount);
                }
            } catch (Exception e) {
                logger.debug("CR3Parser uuid payload parse failed for {}: {}", uuidHex, e.getMessage());
            }

             from = idx + 4;
         }
     }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer: for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readUnsignedShortBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int safeReadIntBE(byte[] b, int off) {
        try { return readIntBE(b, off); } catch (Exception e) { return -1; }
    }


    private static int safeReadIntLE(byte[] b, int off) {
        try { return ((b[off] & 0xFF)) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24); } catch (Exception e) { return -1; }
    }

    private static int safeReadUnsignedShortLE(byte[] b, int off) {
        try { return ((b[off] & 0xFF)) | ((b[off + 1] & 0xFF) << 8); } catch (Exception e) { return -1; }
    }
}
