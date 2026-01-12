package com.example.obscura_backend.service.exif;

import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.GpsDirectory;
import com.example.obscura_backend.model.Photo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ExifExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(ExifExtractionService.class);

    public void extractExifData(Metadata metadata, Photo photo) {
        if (metadata == null) return;
        try { logMetadataSummary(metadata, "extract-start"); } catch (Exception ignored) {}
        logger.debug("Available metadata directories:");
        for (Directory directory : metadata.getDirectories()) {
            logger.debug("  - {}", directory.getName());
        }

        for (Directory directory : metadata.getDirectories()) {
            extractAllTagsByName(directory, metadata, photo);
        }

        try {
            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gps != null) {
                GeoLocation loc = gps.getGeoLocation();
                if (loc != null) {
                    if (photo.getGpsLatitude() == null) photo.setGpsLatitude(String.format("%.6f", loc.getLatitude()));
                    if (photo.getGpsLongitude() == null) photo.setGpsLongitude(String.format("%.6f", loc.getLongitude()));
                }
            }
        } catch (Exception ignored) {}

        if (photo.getDateTaken() == null) {
            for (Directory directory : metadata.getDirectories()) {
                for (Tag tag : directory.getTags()) {
                    try {
                        String name = tag.getTagName();
                        String desc = tag.getDescription();
                        if (desc == null || desc.trim().isEmpty()) continue;
                        String lname = name == null ? "" : name.toLowerCase();
                        if (lname.contains("date") || lname.contains("datetime") || lname.contains("time")) {
                            LocalDateTime parsed = parseExifDateTime(desc);
                            if (parsed != null) {
                                photo.setDateTaken(parsed);
                                logger.debug("Found date via broad scan: {} -> {}", name, parsed);
                                break;
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Broad date scan failed for tag {}: {}", tag.getTagName(), e.getMessage());
                    }
                }
                if (photo.getDateTaken() != null) break;
            }

            try {
                com.drew.metadata.xmp.XmpDirectory xmp = metadata.getFirstDirectoryOfType(com.drew.metadata.xmp.XmpDirectory.class);
                if (xmp != null) {
                    for (Tag tag : xmp.getTags()) {
                        try {
                            String name = tag.getTagName();
                            String desc = tag.getDescription();
                            if (desc == null || desc.trim().isEmpty()) continue;
                            String lname = name == null ? "" : name.toLowerCase();
                            if (lname.contains("date") || lname.contains("datetime") || lname.contains("create") || lname.contains("time")) {
                                LocalDateTime parsed = parseExifDateTime(desc);
                                if (parsed != null) {
                                    photo.setDateTaken(parsed);
                                    logger.debug("Found date in XMP tag {} -> {}", name, parsed);
                                    break;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (NoClassDefFoundError | Exception e) {
                logger.debug("XMP directory unavailable or parse failed: {}", e.getMessage());
            }
        }
    }

    public Map<String, String> collectMetadata(Metadata metadata) {
        Map<String, String> map = new LinkedHashMap<>();
        if (metadata == null) return map;

        for (Directory dir : metadata.getDirectories()) {
            String dname = dir.getName();
            for (Tag tag : dir.getTags()) {
                String tname = tag.getTagName();
                String desc = tag.getDescription();
                if ((desc == null || desc.trim().isEmpty())) {
                    try {
                        String s = null;
                        try { s = dir.getString(tag.getTagType()); } catch (Exception ignored) {}
                        if (s != null && !s.trim().isEmpty()) desc = s;
                        else {
                            Object raw = dir.getObject(tag.getTagType());
                            if (raw != null) desc = String.valueOf(raw);
                        }
                    } catch (Exception ignored) {}
                }
                String key = dname + ":" + (tname == null ? String.valueOf(tag.getTagType()) : tname);
                map.put(key, desc == null ? "" : desc);
            }

        }

        return map;
    }

    public Map<String, String> collectMetadataFromFile(MultipartFile file) {
        if (file == null) return new LinkedHashMap<>();
        try (InputStream is = file.getInputStream()) {
            Metadata m = com.drew.imaging.ImageMetadataReader.readMetadata(is);
            Map<String, String> base = collectMetadata(m);

            boolean looksLikeCr3 = false;
            try (InputStream is2 = file.getInputStream()) {
                byte[] header = new byte[4096];
                int r = is2.read(header);
                if (r > 0) {
                    String h = new String(header, 0, r, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase();
                    if (h.contains(".cr3") || h.contains("ftyp") || h.contains("cr3") || h.contains("craw") || h.contains("crx") || h.contains("prvw") || h.contains("ctmd")) {
                        looksLikeCr3 = true;
                    }
                }
            } catch (Exception ignored) {}

            String name = file.getOriginalFilename();
            if ((name != null && name.toLowerCase().endsWith(".cr3")) || looksLikeCr3) {
                logger.debug("collectMetadataFromFile: invoking CR3Parser for '{}' (looksLikeCr3={})", name, looksLikeCr3);
                try {
                    Map<String, String> cr3meta = CR3Parser.parse(file);
                    if (!cr3meta.isEmpty()) {
                        logger.debug("collectMetadataFromFile: CR3Parser returned {} entries", cr3meta.size());
                        cr3meta.forEach(base::putIfAbsent);

                        for (Map.Entry<String,String> e : cr3meta.entrySet()) {
                            String k = e.getKey();
                            String v = e.getValue();
                            if (k == null || v == null) continue;
                            String lk = k.toLowerCase();
                            String val = v.trim();
                            try {
                                if ((lk.startsWith("cmt:") || lk.startsWith("tiff:") || lk.startsWith("uuid_tiff:"))) {
                                    String tag = k.substring(k.indexOf(':') + 1).toLowerCase();
                                    if (tag.contains("make") && !base.containsKey("Make")) base.put("Make", val);
                                    if (tag.contains("model") && !base.containsKey("Model")) base.put("Model", val);
                                    if ((tag.contains("iso") || tag.contains("isospeed")) && !base.containsKey("ISO")) base.put("ISO", val);
                                    if ((tag.contains("f-number") || tag.contains("fnumber") || tag.contains("aperture")) && !base.containsKey("Aperture")) base.put("Aperture", val);
                                    if ((tag.contains("exposure") || tag.contains("exposuretime") || tag.contains("shutter")) && !base.containsKey("ShutterSpeed")) base.put("ShutterSpeed", val);
                                    if ((tag.contains("focal") || tag.contains("focallength")) && !base.containsKey("FocalLength")) base.put("FocalLength", val);
                                    if ((tag.contains("gps") || tag.contains("latitude")) && !base.containsKey("GPSLatitude")) base.put("GPSLatitude", val);
                                    if ((tag.contains("gps") || tag.contains("longitude")) && !base.containsKey("GPSLongitude")) base.put("GPSLongitude", val);
                                }
                            } catch (Exception ignored) {}
                        }

                        if (cr3meta.containsKey("CTMD:ISO") && !base.containsKey("ISO")) base.put("ISO", cr3meta.get("CTMD:ISO"));
                        if (cr3meta.containsKey("CTMD:Aperture") && !base.containsKey("Aperture")) base.put("Aperture", cr3meta.get("CTMD:Aperture"));
                        if (cr3meta.containsKey("CTMD:Shutter") && !base.containsKey("ShutterSpeed")) base.put("ShutterSpeed", cr3meta.get("CTMD:Shutter"));
                        if (cr3meta.containsKey("CTMD:FocalLength") && !base.containsKey("FocalLength")) base.put("FocalLength", cr3meta.get("CTMD:FocalLength"));
                        if (cr3meta.containsKey("PRVW:resolution") && !base.containsKey("Resolution")) base.put("Resolution", cr3meta.get("PRVW:resolution"));
                        if (cr3meta.containsKey("THMB:resolution") && !base.containsKey("Resolution")) base.put("Resolution", cr3meta.get("THMB:resolution"));
                        if (cr3meta.containsKey("CRAW:resolution") && !base.containsKey("Resolution")) base.put("Resolution", cr3meta.get("CRAW:resolution"));
                    } else {
                        logger.debug("collectMetadataFromFile: CR3Parser returned no entries for '{}'", name);
                    }
                } catch (Exception e) {
                    logger.debug("CR3Parser failed: {}", e.getMessage());
                }
            }

            return base;
        } catch (Exception e) {
            logger.debug("collectMetadataFromFile failed: {}", e.getMessage());
            try {
                Map<String, String> cr3meta = CR3Parser.parse(file);
                if (cr3meta != null && !cr3meta.isEmpty()) return cr3meta;
            } catch (Exception ignored) {}
            return new LinkedHashMap<>();
        }
    }

    private void extractAllTagsByName(Directory directory, Metadata metadata, Photo photo) {
        Integer tempWidth = null;
        Integer tempHeight = null;
        String exposureDescRaw = null;
        Object exposureRawObj = null;
        String shutterApexDesc = null;
        Object shutterApexRaw = null;

        for (Tag tag : directory.getTags()) {
            String tagName = tag.getTagName();
            String description = tag.getDescription();

            if (description == null || description.trim().isEmpty()) {
                try {
                    String s = null;
                    try { s = directory.getString(tag.getTagType()); } catch (Exception ignored) {}
                    if (s != null && !s.trim().isEmpty()) {
                        description = s;
                    } else {
                        Object raw = directory.getObject(tag.getTagType());
                        if (raw != null) {
                            if (raw.getClass().isArray()) {
                                try {
                                    Object[] arr = (Object[]) raw;
                                    description = java.util.Arrays.toString(arr);
                                } catch (ClassCastException e) {
                                    if (raw instanceof byte[]) description = java.util.Arrays.toString((byte[]) raw);
                                    else if (raw instanceof int[]) description = java.util.Arrays.toString((int[]) raw);
                                    else if (raw instanceof long[]) description = java.util.Arrays.toString((long[]) raw);
                                    else description = String.valueOf(raw);
                                }
                            } else {
                                description = String.valueOf(raw);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if ((description == null || description.trim().isEmpty()) && (tagName == null || tagName.trim().isEmpty())) {
                continue;
            }

            String lname = tagName == null ? "" : tagName.toLowerCase();
            String ldesc = description == null ? "" : description.toLowerCase();
            String combined = (lname + " " + ldesc).trim();

            try {
                if (photo.getResolution() == null && description != null) {
                    java.util.regex.Matcher resMatcher = java.util.regex.Pattern.compile("(\\d{2,5})[xX](\\d{2,5})").matcher(description);
                    if (resMatcher.find()) {
                        String w = resMatcher.group(1);
                        String h = resMatcher.group(2);
                        tempWidth = Integer.parseInt(w);
                        tempHeight = Integer.parseInt(h);
                        String resolution = tempWidth + "x" + tempHeight;
                        photo.setResolution(resolution.toLowerCase());
                        logger.debug("Parsed resolution from pattern: {}", resolution);
                    }
                }

                if (photo.getCameraMake() == null && (lname.contains("make") || combined.contains("manufacturer") || combined.contains("maker"))) {
                    photo.setCameraMake((description == null ? tagName : description).trim());
                }

                if (photo.getCameraModel() == null && (lname.contains("model") || combined.contains("camera model") || ldesc.contains("model")) && !lname.contains("lens")) {
                    photo.setCameraModel((description == null ? tagName : description).trim());
                }

                if (photo.getLensModel() == null && (lname.contains("lens") || ldesc.contains("ef-") || ldesc.contains("rf-") || ldesc.contains("lens model"))) {
                    photo.setLensModel((description == null ? tagName : description).trim());
                }

                if (photo.getIso() == null && (lname.contains("iso") || ldesc.matches(".*iso\\s*[:=]?\\s*\\d+.*"))) {
                    String digits = (description == null ? "" : description).replaceAll("[^0-9]", "");
                    if (!digits.isEmpty() && digits.length() <= 4) {
                        try { photo.setIso(Integer.parseInt(digits)); } catch (Exception ignored) {}
                    }
                }

                if (photo.getAperture() == null && (lname.contains("f-number") || lname.contains("aperture") || ldesc.startsWith("f/"))) {
                    photo.setAperture(formatAperture(description));
                }

                if ((lname.contains("exposure") || lname.contains("shutter") || ldesc.matches(".*\\d+/\\d+.*"))) {
                    if (exposureDescRaw == null) exposureDescRaw = (description == null ? tagName : description).trim();
                    try { Object raw = directory.getObject(tag.getTagType()); if (raw != null && exposureRawObj == null) exposureRawObj = raw; } catch (Exception ignored) {}
                }

                if (lname.contains("shutter speed") || tagName != null && tagName.toLowerCase().contains("shutter speed value")) {
                    if (shutterApexDesc == null) shutterApexDesc = (description == null ? tagName : description).trim();
                    try { Object raw = directory.getObject(tag.getTagType()); if (raw != null && shutterApexRaw == null) shutterApexRaw = raw; } catch (Exception ignored) {}
                }

                if (photo.getFocalLength() == null && (lname.contains("focal") || ldesc.contains("mm"))) {
                    photo.setFocalLength((description == null ? tagName : description).trim());
                }

                if (photo.getExposureCompensation() == null && (lname.contains("exposure compensation") || lname.contains("exposure bias"))) {
                    photo.setExposureCompensation((description == null ? tagName : description).trim());
                }

                if (photo.getWhiteBalance() == null && (lname.contains("white balance"))) {
                    photo.setWhiteBalance((description == null ? tagName : description).trim());
                }

                if (photo.getMeteringMode() == null && lname.contains("metering")) {
                    photo.setMeteringMode((description == null ? tagName : description).trim());
                }

                if (photo.getFlashMode() == null && lname.contains("flash")) {
                    photo.setFlashMode((description == null ? tagName : description).trim());
                }

                if ((tempWidth == null) && (lname.contains("image width") || lname.contains("exif image width"))) {
                    try {
                        String widthStr = (description == null ? "" : description).replaceAll("[^0-9]", "");
                        if (!widthStr.isEmpty()) tempWidth = Integer.parseInt(widthStr);
                    } catch (Exception ignored) {}
                }

                if ((tempHeight == null) && (lname.contains("image height") || lname.contains("exif image height"))) {
                    try {
                        String heightStr = (description == null ? "" : description).replaceAll("[^0-9]", "");
                        if (!heightStr.isEmpty()) tempHeight = Integer.parseInt(heightStr);
                    } catch (Exception ignored) {}
                }

                if (photo.getDateTaken() == null && (lname.contains("date") || lname.contains("time") || ldesc.matches(".*\\d{4}.*"))) {
                    LocalDateTime parsed = parseExifDateTime(description);
                    if (parsed != null) photo.setDateTaken(parsed);
                }

                if ((photo.getGpsLatitude() == null || photo.getGpsLongitude() == null) && (lname.contains("gps") || combined.contains("gps latitude") || combined.contains("gps longitude"))) {
                    extractGpsLatitude(directory, photo);
                    extractGpsLongitude(directory, photo);
                }

            } catch (Exception e) {
                logger.debug("Error extracting tag {}: {}", tagName, e.getMessage());
            }
        }

        if (tempWidth != null && tempHeight != null && photo.getResolution() == null) {
            String resolution = tempWidth + "x" + tempHeight;
            photo.setResolution(resolution);
            logger.debug("Set resolution: {}", resolution);
        }

        if (photo.getCameraMake() == null || photo.getCameraModel() == null || photo.getLensModel() == null
                || photo.getIso() == null || photo.getAperture() == null || photo.getShutterSpeed() == null
                || photo.getFocalLength() == null) {
            broadKeywordFieldScan(metadata, photo);
        }

        if (photo.getShutterSpeed() == null) {
            String formatted = null;
            try {
                if (exposureRawObj != null) {
                    formatted = parseExposureRawToString(exposureRawObj);
                }
                if ((formatted == null || formatted.isEmpty()) && shutterApexRaw != null) {
                    Double apexVal = parseNumericFromObject(shutterApexRaw);
                    if (apexVal != null) {
                        double sec = Math.pow(2.0, -apexVal);
                        formatted = formatExposureSeconds(sec);
                    }
                }
                if ((formatted == null || formatted.isEmpty()) && exposureDescRaw != null) {
                    formatted = parseExposureDescription(exposureDescRaw);
                }
            } catch (Exception e) {
                logger.debug("Post-process shutter parsing failed: {}", e.getMessage());
            }
            if (formatted != null && !formatted.isEmpty()) {
                photo.setShutterSpeed(formatted);
            }
        }
    }

    private String parseExposureRawToString(Object raw) {
        try {
            if (raw instanceof com.drew.lang.Rational) {
                double v = ((com.drew.lang.Rational) raw).doubleValue();
                return formatExposureSeconds(v);
            }
            if (raw instanceof com.drew.lang.Rational[]) {
                com.drew.lang.Rational[] arr = (com.drew.lang.Rational[]) raw;
                if (arr.length > 0) return formatExposureSeconds(arr[0].doubleValue());
            }
            if (raw instanceof Number) {
                double v = ((Number) raw).doubleValue();
                return formatExposureSeconds(v);
            }
            if (raw instanceof String) {
                String s = ((String) raw).trim();
                String parsed = parseExposureDescription(s);
                if (parsed != null && !parsed.isEmpty()) return parsed;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Double parseNumericFromObject(Object obj) {
        try {
            if (obj instanceof Number) return ((Number) obj).doubleValue();
            if (obj instanceof com.drew.lang.Rational) return ((com.drew.lang.Rational) obj).doubleValue();
            String s = String.valueOf(obj);
            s = s.replaceAll("[^0-9.\\-+eE]", " ").trim();
            if (s.isEmpty()) return null;
            String[] parts = s.split("\\s+");
            return Double.parseDouble(parts[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseExposureDescription(String desc) {
        if (desc == null) return null;
        desc = desc.trim();
        try {
            if (desc.matches("[\\-+]?[0-9]+\\.[0-9]+")) {
                double d = Double.parseDouble(desc);
                double sec = Math.pow(2.0, -d);
                return formatExposureSeconds(sec);
            }
        } catch (Exception ignored) {}

        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d+)\\/(\\d+)");
        java.util.regex.Matcher m = p.matcher(desc);
        if (m.find()) {
            try {
                double num = Double.parseDouble(m.group(1));
                double den = Double.parseDouble(m.group(2));
                if (den != 0) {
                    double sec = num / den;
                    return formatExposureSeconds(sec);
                }
            } catch (Exception ignored) {}
        }

        try {
            java.util.regex.Matcher md = java.util.regex.Pattern.compile("[0-9]*\\.?[0-9]+").matcher(desc);
            if (md.find()) {
                double v = Double.parseDouble(md.group());
                return formatExposureSeconds(v);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String formatExposureSeconds(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0) return null;
        if (seconds >= 1.0) {
            String s = String.format("%.2f s", seconds);
            if (s.endsWith(".00 s")) s = String.format("%.0f s", seconds);
            return s;
        } else {
            int denom = (int) Math.round(1.0 / seconds);
            if (denom <= 0) denom = 1;
            return "1/" + denom;
        }
    }

    private java.time.LocalDateTime parseExifDateTime(String desc) {
        if (desc == null) return null;
        String s = desc.trim();
        s = s.replaceAll("Z$", "").replaceAll("[+-]\\d{2}:?\\d{2}$", "").trim();

        String[] patterns = new String[]{"yyyy:MM:dd HH:mm:ss", "yyyy:MM:dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS"};
        for (String p : patterns) {
            try {
                java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(p);
                return java.time.LocalDateTime.parse(s, fmt);
            } catch (java.time.format.DateTimeParseException ignored) {
            }
        }

        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4}).(\\d{1,2}).(\\d{1,2}).*?(\\d{1,2}):?(\\d{2}):?(\\d{2})").matcher(s);
            if (m.find()) {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int d = Integer.parseInt(m.group(3));
                int hh = Integer.parseInt(m.group(4));
                int mm = Integer.parseInt(m.group(5));
                int ss = Integer.parseInt(m.group(6));
                return java.time.LocalDateTime.of(y, mo, d, hh, mm, ss);
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String formatAperture(String description) {
        if (description == null) return null;
        try {
            String s = description.trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]*\\.?[0-9]+)").matcher(s);
            if (m.find()) {
                String num = m.group(1);
                double v = Double.parseDouble(num);
                if (Math.abs(Math.round(v) - v) < 1e-9) return "f/" + String.format("%d", (int)Math.round(v));
                return "f/" + String.format("%.1f", v);
            }
        } catch (Exception ignored) {}
        return description;
    }

    private void extractGpsLatitude(Directory directory, Photo photo) {
        if (directory == null || photo == null) return;
        try {
            if (directory instanceof GpsDirectory) {
                GpsDirectory gps = (GpsDirectory) directory;
                GeoLocation loc = gps.getGeoLocation();
                if (loc != null) {
                    if (photo.getGpsLatitude() == null) photo.setGpsLatitude(String.format("%.6f", loc.getLatitude()));
                    if (photo.getGpsLongitude() == null) photo.setGpsLongitude(String.format("%.6f", loc.getLongitude()));
                    return;
                }
            }
        } catch (Exception ignored) {}

        for (Tag tag : directory.getTags()) {
            String tname = tag.getTagName();
            if (tname == null) continue;
            String lname = tname.toLowerCase();
            if (lname.contains("latitude") || lname.contains("lat")) {
                String desc = tag.getDescription();
                Double d = parseGpsCoord(desc);
                if (d != null && photo.getGpsLatitude() == null) photo.setGpsLatitude(String.format("%.6f", d));
            }
        }
    }

    private void extractGpsLongitude(Directory directory, Photo photo) {
        if (directory == null || photo == null) return;
        try {
            if (directory instanceof GpsDirectory) {
                GpsDirectory gps = (GpsDirectory) directory;
                GeoLocation loc = gps.getGeoLocation();
                if (loc != null) {
                    if (photo.getGpsLatitude() == null) photo.setGpsLatitude(String.format("%.6f", loc.getLatitude()));
                    if (photo.getGpsLongitude() == null) photo.setGpsLongitude(String.format("%.6f", loc.getLongitude()));
                    return;
                }
            }
        } catch (Exception ignored) {}

        for (Tag tag : directory.getTags()) {
            String tname = tag.getTagName();
            if (tname == null) continue;
            String lname = tname.toLowerCase();
            if (lname.contains("longitude") || lname.contains("lon") || lname.contains("long")) {
                String desc = tag.getDescription();
                Double d = parseGpsCoord(desc);
                if (d != null && photo.getGpsLongitude() == null) photo.setGpsLongitude(String.format("%.6f", d));
            }
        }
    }

    private Double parseGpsCoord(String desc) {
        if (desc == null) return null;
        String s = desc.trim();
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)[^\\d]+(\\d+)[^\\d]+([0-9]+(?:\\.[0-9]+)?)[^A-Za-z]*([NnSsEeWw])").matcher(s);
            if (m.find()) {
                double deg = Double.parseDouble(m.group(1));
                double min = Double.parseDouble(m.group(2));
                double sec = Double.parseDouble(m.group(3));
                String dir = m.group(4).toUpperCase();
                double val = deg + (min / 60.0) + (sec / 3600.0);
                if (dir.equals("S") || dir.equals("W")) val = -val;
                return val;
            }
        } catch (Exception ignored) {}

        try {
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("([-+]?[0-9]*\\.?[0-9]+)\\s*([NnSsEeWw])?").matcher(s);
            if (m2.find()) {
                double val = Double.parseDouble(m2.group(1));
                String dir = m2.groupCount() >= 2 ? m2.group(2) : null;
                if (dir != null) {
                    dir = dir.toUpperCase();
                    if (dir.equals("S") || dir.equals("W")) val = -Math.abs(val);
                    else val = Math.abs(val);
                }
                return val;
            }
        } catch (Exception ignored) {}

        return null;
    }

    private void broadKeywordFieldScan(Metadata metadata, Photo photo) {
        if (metadata == null || photo == null) return;
        try {
            Map<String, String> map = collectMetadata(metadata);
            populateFromMap(map, photo);
        } catch (Exception ignored) {}
    }

    public void logMetadataSummary(Metadata metadata, String context) {
        if (metadata == null) {
            logger.debug("[{}] No metadata available to summarize", context);
            return;
        }

        int dirCount = (int) java.util.stream.StreamSupport.stream(metadata.getDirectories().spliterator(), false).count();
        logger.debug("[{}] Metadata summary: {} directories", context, dirCount);
        int dirIndex = 0;
        for (Directory dir : metadata.getDirectories()) {
            dirIndex++;
            String dirName = dir.getName();
            logger.debug("[{}] Dir #{}: {} ({} tags)", context, dirIndex, dirName, dir.getTags().size());
            int shown = 0;
            for (Tag tag : dir.getTags()) {
                if (shown >= 20) {
                    logger.debug("[{}]   ... ({} more tags)", context, Math.max(0, dir.getTags().size() - shown));
                    break;
                }
                String tname = tag.getTagName();
                String desc = tag.getDescription();
                if (desc == null) desc = "";
                String shortDesc = desc.length() > 200 ? desc.substring(0, 200) + "..." : desc;
                logger.debug("[{}]   {}: {}", context, tname, shortDesc);
                shown++;
            }
        }
    }

    public void populateFromMap(java.util.Map<String, String> map, Photo photo) {
        if (map == null || photo == null) return;

        map.forEach((k, v) -> {
            if (k == null || v == null) return;
            String lk = k.toLowerCase();
            String val = v.trim();
            try {
                if (lk.contains("make") && photo.getCameraMake() == null) photo.setCameraMake(val);
                else if (lk.contains("model") && photo.getCameraModel() == null) photo.setCameraModel(val);
                else if ((lk.contains("lens") || lk.contains("lensmodel")) && photo.getLensModel() == null) photo.setLensModel(val);
                else if ((lk.contains("iso") || lk.contains("isospeed")) && photo.getIso() == null) {
                    String digits = val.replaceAll("[^0-9]", ""); if (!digits.isEmpty()) photo.setIso(Integer.parseInt(digits));
                } else if ((lk.contains("shutterspeed") || lk.contains("exposuretime") || lk.contains("exposure time") || lk.contains("exposure")) && photo.getShutterSpeed() == null) {
                    String formatted = parseExposureDescription(val);
                    photo.setShutterSpeed(formatted != null ? formatted : val);
                }
                else if ((lk.contains("aperture") || lk.contains("fnumber")) && photo.getAperture() == null) photo.setAperture(val);
                else if ((lk.contains("focallength") || lk.contains("focal")) && photo.getFocalLength() == null) photo.setFocalLength(val);
                else if ((lk.contains("exposure bias") || lk.contains("exposure bias value") || lk.contains("exposure compensation") || lk.contains("exposurecompensation")) && photo.getExposureCompensation() == null) {
                    photo.setExposureCompensation(val);
                }
                else if ((lk.contains("white balance") || lk.contains("whitebalance") || lk.contains("white_balance")) && photo.getWhiteBalance() == null) {
                    photo.setWhiteBalance(val);
                }
                else if ((lk.contains("metering") || lk.contains("metering mode")) && photo.getMeteringMode() == null) {
                    photo.setMeteringMode(val);
                }
                else if (lk.contains("flash") && photo.getFlashMode() == null) {
                    photo.setFlashMode(val);
                }
                else if (lk.contains("orientation") && photo.getOrientation() == null) {
                    photo.setOrientation(val);
                }
                else if ((lk.contains("imagesize") || lk.contains("resolution") || lk.contains("imagewidth")) && photo.getResolution() == null) {
                    String res = val.replaceAll("[^0-9xX]", ""); if (res.matches("\\d+[xX]\\d+")) photo.setResolution(res.toLowerCase());
                } else if ((lk.contains("createdate") || lk.contains("date/time") || lk.contains("datetime")) && photo.getDateTaken() == null) {
                    LocalDateTime parsed = parseExifDateTime(val);
                    if (parsed != null) photo.setDateTaken(parsed);
                }
            } catch (Exception e) {
                logger.debug("populateFromMap mapping failed for {}: {}", k, e.getMessage());
            }
        });
    }

    public Map<String, String> extractCommonFieldsFromMap(Map<String, String> metaMap) {
        Map<String, String> out = new LinkedHashMap<>();
        if (metaMap == null || metaMap.isEmpty()) return out;

        for (Map.Entry<String, String> e : metaMap.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase();
            String val = e.getValue() == null ? "" : e.getValue().trim();
            try {
                if (!out.containsKey("Make") && (key.contains("make") || key.contains("manufacturer") || val.toLowerCase().contains("canon") || val.toLowerCase().contains("nikon") || val.toLowerCase().contains("sony"))) {
                    out.put("Make", val);
                }
                if (!out.containsKey("Model") && key.contains("model")) out.put("Model", val);
                if (!out.containsKey("Lens") && (key.contains("lens") || key.contains("lensmodel"))) out.put("Lens", val);
                if (!out.containsKey("ISO") && (key.contains("iso") || key.contains("isospeed"))) out.put("ISO", val);
                if (!out.containsKey("Aperture") && (key.contains("f-number") || key.contains("aperture") || val.startsWith("f/"))) out.put("Aperture", val);
                if (!out.containsKey("ShutterSpeed") && (key.contains("shutter") || key.contains("exposuretime") || key.contains("exposure time"))) out.put("ShutterSpeed", val);
                if (!out.containsKey("FocalLength") && (key.contains("focal") || key.contains("focallength"))) out.put("FocalLength", val);
                if (!out.containsKey("Resolution") && (key.contains("resolution") || key.contains("imagesize") || key.contains("imagewidth") || key.contains("exif image"))) out.put("Resolution", val);
                if (!out.containsKey("DateTaken") && (key.contains("date") || key.contains("datetime") || key.contains("create"))) out.put("DateTaken", val);
                if (!out.containsKey("GPSLatitude") && (key.contains("gpslatitude") || key.contains("latitude"))) out.put("GPSLatitude", val);
                if (!out.containsKey("GPSLongitude") && (key.contains("gpslongitude") || key.contains("longitude"))) out.put("GPSLongitude", val);
                if (!out.containsKey("Orientation") && key.contains("orientation")) out.put("Orientation", val);
            } catch (Exception ignored) {}
        }

        return out;
    }
}
