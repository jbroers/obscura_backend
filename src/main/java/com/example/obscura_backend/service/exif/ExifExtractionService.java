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

    private static final String FIELD_APERTURE = "Aperture";
    private static final String FIELD_SHUTTER_SPEED = "ShutterSpeed";
    private static final String FIELD_FOCAL_LENGTH = "FocalLength";
    private static final String FIELD_GPS_LATITUDE = "GPSLatitude";
    private static final String FIELD_GPS_LONGITUDE = "GPSLongitude";
    private static final String FIELD_MODEL = "Model";
    private static final String FIELD_RESOLUTION = "Resolution";
    private static final String FIELD_ISO = "ISO";

    private static final String KEYWORD_F_NUMBER = "f-number";
    private static final String KEYWORD_APERTURE = "aperture";
    private static final String KEYWORD_MODEL = "model";
    private static final String KEYWORD_EXPOSURE = "exposure";
    private static final String KEYWORD_EXPOSURE_TIME = "exposuretime";
    private static final String KEYWORD_SHUTTER = "shutter";
    private static final String KEYWORD_FOCAL = "focal";
    private static final String KEYWORD_FOCAL_LENGTH = "focallength";
    private static final String KEYWORD_ISO_SPEED = "isospeed";
    private static final String KEYWORD_LATITUDE = "latitude";
    private static final String KEYWORD_LONGITUDE = "longitude";
    private static final String KEYWORD_DATETIME = "datetime";

    private static final String DIGIT_PATTERN = "[^0-9]";

    public void extractExifData(Metadata metadata, Photo photo) {
        if (metadata == null) return;
        logMetadataSafely(metadata, "extract-start");
        logAvailableDirectories(metadata);

        for (Directory directory : metadata.getDirectories()) {
            extractAllTagsByName(directory, metadata, photo);
        }

        extractGpsLocation(metadata, photo);

        if (photo.getDateTaken() == null) {
            extractDateFromDirectories(metadata, photo);
            if (photo.getDateTaken() == null) {
                extractDateFromXmp(metadata, photo);
            }
        }
    }

    private void logMetadataSafely(Metadata metadata, String stage) {
        try {
            logMetadataSummary(metadata, stage);
        } catch (Exception ignored) {
        }
    }

    private void logAvailableDirectories(Metadata metadata) {
        logger.debug("Available metadata directories:");
        for (Directory directory : metadata.getDirectories()) {
            logger.debug("  - {}", directory.getName());
        }
    }

    private void extractGpsLocation(Metadata metadata, Photo photo) {
        try {
            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gps != null) {
                GeoLocation loc = gps.getGeoLocation();
                if (loc != null) {
                    if (photo.getGpsLatitude() == null) {
                        photo.setGpsLatitude(String.format("%.6f", loc.getLatitude()));
                    }
                    if (photo.getGpsLongitude() == null) {
                        photo.setGpsLongitude(String.format("%.6f", loc.getLongitude()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void extractDateFromDirectories(Metadata metadata, Photo photo) {
        for (Directory directory : metadata.getDirectories()) {
            if (tryExtractDateFromDirectoryTags(directory, photo)) {
                return;
            }
        }
    }

    private boolean tryExtractDateFromDirectoryTags(Directory directory, Photo photo) {
        for (Tag tag : directory.getTags()) {
            if (tryExtractDateFromTag(tag, photo, "broad scan")) {
                return true;
            }
        }
        return false;
    }

    private boolean tryExtractDateFromTag(Tag tag, Photo photo, String source) {
        try {
            String name = tag.getTagName();
            String desc = tag.getDescription();
            if (desc == null || desc.trim().isEmpty()) {
                return false;
            }
            
            String lname = name == null ? "" : name.toLowerCase();
            if (lname.contains("date") || lname.contains(KEYWORD_DATETIME) || lname.contains("time")) {
                LocalDateTime parsed = parseExifDateTime(desc);
                if (parsed != null) {
                    photo.setDateTaken(parsed);
                    logger.debug("Found date via {}: {} -> {}", source, name, parsed);
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("{} date scan failed for tag {}: {}", source, tag.getTagName(), e.getMessage());
        }
        return false;
    }

    private void extractDateFromXmp(Metadata metadata, Photo photo) {
        try {
            com.drew.metadata.xmp.XmpDirectory xmp = metadata.getFirstDirectoryOfType(
                com.drew.metadata.xmp.XmpDirectory.class);
            if (xmp != null) {
                tryExtractDateFromXmpTags(xmp, photo);
            }
        } catch (NoClassDefFoundError | Exception e) {
            logger.debug("XMP directory unavailable or parse failed: {}", e.getMessage());
        }
    }

    private void tryExtractDateFromXmpTags(com.drew.metadata.xmp.XmpDirectory xmp, Photo photo) {
        for (Tag tag : xmp.getTags()) {
            if (tryExtractDateFromXmpTag(tag, photo)) {
                return;
            }
        }
    }

    private boolean tryExtractDateFromXmpTag(Tag tag, Photo photo) {
        try {
            String name = tag.getTagName();
            String desc = tag.getDescription();
            if (desc == null || desc.trim().isEmpty()) {
                return false;
            }
            
            String lname = name == null ? "" : name.toLowerCase();
            if (lname.contains("date") || lname.contains(KEYWORD_DATETIME) || 
                lname.contains("create") || lname.contains("time")) {
                LocalDateTime parsed = parseExifDateTime(desc);
                if (parsed != null) {
                    photo.setDateTaken(parsed);
                    logger.debug("Found date in XMP tag {} -> {}", name, parsed);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public Map<String, String> collectMetadata(Metadata metadata) {
        Map<String, String> map = new LinkedHashMap<>();
        if (metadata == null) return map;

        for (Directory dir : metadata.getDirectories()) {
            String dname = dir.getName();
            for (Tag tag : dir.getTags()) {
                String tname = tag.getTagName();
                String desc = getTagDescription(dir, tag);
                String key = dname + ":" + (tname == null ? String.valueOf(tag.getTagType()) : tname);
                map.put(key, desc == null ? "" : desc);
            }
        }

        return map;
    }

    private String getTagDescription(Directory dir, Tag tag) {
        String desc = tag.getDescription();
        if (desc != null && !desc.trim().isEmpty()) {
            return desc;
        }

        try {
            String s = tryGetStringValue(dir, tag);
            if (s != null && !s.trim().isEmpty()) {
                return s;
            }
            
            Object raw = dir.getObject(tag.getTagType());
            if (raw != null) {
                return String.valueOf(raw);
            }
        } catch (Exception ignored) {
        }
        
        return desc;
    }

    private String tryGetStringValue(Directory dir, Tag tag) {
        try {
            return dir.getString(tag.getTagType());
        } catch (Exception ignored) {
            return null;
        }
    }

    public Map<String, String> collectMetadataFromFile(MultipartFile file) {
        if (file == null) return new LinkedHashMap<>();
        
        try (InputStream is = file.getInputStream()) {
            Metadata m = com.drew.imaging.ImageMetadataReader.readMetadata(is);
            Map<String, String> base = collectMetadata(m);

            boolean looksLikeCr3 = checkIfCr3File(file);
            String name = file.getOriginalFilename();
            
            if (shouldParseCr3(name, looksLikeCr3)) {
                enrichWithCr3Metadata(file, base, name, looksLikeCr3);
            }

            return base;
        } catch (Exception e) {
            logger.debug("collectMetadataFromFile failed: {}", e.getMessage());
            return tryGetCr3MetadataAsFallback(file);
        }
    }

    private boolean checkIfCr3File(MultipartFile file) {
        try (InputStream is2 = file.getInputStream()) {
            byte[] header = new byte[4096];
            int r = is2.read(header);
            if (r > 0) {
                String h = new String(header, 0, r, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase();
                return h.contains(".cr3") || h.contains("ftyp") || h.contains("cr3") || 
                       h.contains("craw") || h.contains("crx") || h.contains("prvw") || h.contains("ctmd");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean shouldParseCr3(String filename, boolean looksLikeCr3) {
        return (filename != null && filename.toLowerCase().endsWith(".cr3")) || looksLikeCr3;
    }

    private void enrichWithCr3Metadata(MultipartFile file, Map<String, String> base, String name, boolean looksLikeCr3) {
        logger.debug("collectMetadataFromFile: invoking CR3Parser for '{}' (looksLikeCr3={})", name, looksLikeCr3);
        
        try {
            Map<String, String> cr3meta = CR3Parser.parse(file);
            if (!cr3meta.isEmpty()) {
                logger.debug("collectMetadataFromFile: CR3Parser returned {} entries", cr3meta.size());
                cr3meta.forEach(base::putIfAbsent);
                
                processCr3MetadataEntries(cr3meta, base);
                addCr3SpecificFields(cr3meta, base);
            } else {
                logger.debug("collectMetadataFromFile: CR3Parser returned no entries for '{}'", name);
            }
        } catch (Exception e) {
            logger.debug("CR3Parser failed: {}", e.getMessage());
        }
    }

    private void processCr3MetadataEntries(Map<String, String> cr3meta, Map<String, String> base) {
        for (Map.Entry<String, String> e : cr3meta.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || v == null) continue;
            
            String lk = k.toLowerCase();
            String val = v.trim();
            
            tryMapCr3TagToStandardField(lk, k, val, base);
        }
    }

    private void tryMapCr3TagToStandardField(String lowerKey, String key, String val, Map<String, String> base) {
        try {
            if (lowerKey.startsWith("cmt:") || lowerKey.startsWith("tiff:") || lowerKey.startsWith("uuid_tiff:")) {
                String tag = key.substring(key.indexOf(':') + 1).toLowerCase();
                
                mapCr3TagValue(tag, val, base);
            }
        } catch (Exception ignored) {
        }
    }

    private void mapCr3TagValue(String tag, String val, Map<String, String> base) {
        if (tag.contains("make")) {
            base.putIfAbsent("Make", val);
        }
        if (tag.contains(KEYWORD_MODEL)) {
            base.putIfAbsent(FIELD_MODEL, val);
        }
        if (tag.contains("iso") || tag.contains(KEYWORD_ISO_SPEED)) {
            base.putIfAbsent(FIELD_ISO, val);
        }
        if (tag.contains(KEYWORD_F_NUMBER) || tag.contains("fnumber") || tag.contains(KEYWORD_APERTURE)) {
            base.putIfAbsent(FIELD_APERTURE, val);
        }
        if (tag.contains(KEYWORD_EXPOSURE) || tag.contains(KEYWORD_EXPOSURE_TIME) || tag.contains(KEYWORD_SHUTTER)) {
            base.putIfAbsent(FIELD_SHUTTER_SPEED, val);
        }
        if (tag.contains(KEYWORD_FOCAL) || tag.contains(KEYWORD_FOCAL_LENGTH)) {
            base.putIfAbsent(FIELD_FOCAL_LENGTH, val);
        }
        if (tag.contains("gps") || tag.contains(KEYWORD_LATITUDE)) {
            base.putIfAbsent(FIELD_GPS_LATITUDE, val);
        }
        if (tag.contains("gps") || tag.contains(KEYWORD_LONGITUDE)) {
            base.putIfAbsent(FIELD_GPS_LONGITUDE, val);
        }
    }

    private void addCr3SpecificFields(Map<String, String> cr3meta, Map<String, String> base) {
        if (cr3meta.containsKey("CTMD:ISO")) {
            base.putIfAbsent(FIELD_ISO, cr3meta.get("CTMD:ISO"));
        }
        if (cr3meta.containsKey("CTMD:Aperture")) {
            base.putIfAbsent(FIELD_APERTURE, cr3meta.get("CTMD:Aperture"));
        }
        if (cr3meta.containsKey("CTMD:Shutter")) {
            base.putIfAbsent(FIELD_SHUTTER_SPEED, cr3meta.get("CTMD:Shutter"));
        }
        if (cr3meta.containsKey("CTMD:FocalLength")) {
            base.putIfAbsent(FIELD_FOCAL_LENGTH, cr3meta.get("CTMD:FocalLength"));
        }
        if (cr3meta.containsKey("PRVW:resolution")) {
            base.putIfAbsent(FIELD_RESOLUTION, cr3meta.get("PRVW:resolution"));
        }
        if (cr3meta.containsKey("THMB:resolution")) {
            base.putIfAbsent(FIELD_RESOLUTION, cr3meta.get("THMB:resolution"));
        }
        if (cr3meta.containsKey("CRAW:resolution")) {
            base.putIfAbsent(FIELD_RESOLUTION, cr3meta.get("CRAW:resolution"));
        }
    }

    private Map<String, String> tryGetCr3MetadataAsFallback(MultipartFile file) {
        try {
            Map<String, String> cr3meta = CR3Parser.parse(file);
            if (cr3meta != null && !cr3meta.isEmpty()) {
                return cr3meta;
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<>();
    }

    private void extractAllTagsByName(Directory directory, Metadata metadata, Photo photo) {
        ExtractionContext context = new ExtractionContext();

        for (Tag tag : directory.getTags()) {
            String tagName = tag.getTagName();
            String description = getEnhancedDescription(directory, tag);

            if (shouldSkipTag(description, tagName)) {
                continue;
            }

            TagInfo tagInfo = new TagInfo(tagName, description);

            try {
                processResolutionTag(photo, tagInfo, context);
                processCameraInfoTags(photo, tagInfo);
                processExposureTags(directory, tag, tagInfo, context);
                processShutterApexTags(directory, tag, tagInfo, context);
                processLensAndSettingsTags(photo, tagInfo);
                processDimensionTags(tagInfo, context);
                processDateAndGpsTags(photo, directory, tagInfo);
            } catch (Exception e) {
                logger.debug("Error extracting tag {}: {}", tagName, e.getMessage());
            }
        }

        finalizePhotoData(metadata, photo, context);
    }

    private String getEnhancedDescription(Directory directory, Tag tag) {
        String description = tag.getDescription();
        if (description != null && !description.trim().isEmpty()) {
            return description;
        }

        try {
            String s = tryGetStringFromDirectory(directory, tag);
            if (s != null && !s.trim().isEmpty()) {
                return s;
            }

            Object raw = directory.getObject(tag.getTagType());
            if (raw != null) {
                return convertRawToDescription(raw);
            }
        } catch (Exception ignored) {
            // Failed to get enhanced description
        }

        return description;
    }

    private String tryGetStringFromDirectory(Directory directory, Tag tag) {
        try {
            return directory.getString(tag.getTagType());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String convertRawToDescription(Object raw) {
        if (raw.getClass().isArray()) {
            return convertArrayToString(raw);
        }
        return String.valueOf(raw);
    }

    private String convertArrayToString(Object raw) {
        try {
            Object[] arr = (Object[]) raw;
            return java.util.Arrays.toString(arr);
        } catch (ClassCastException e) {
            if (raw instanceof byte[] byteArray) {
                return java.util.Arrays.toString(byteArray);
            } else if (raw instanceof int[] intArray) {
                return java.util.Arrays.toString(intArray);
            } else if (raw instanceof long[] longArray) {
                return java.util.Arrays.toString(longArray);
            }
            return String.valueOf(raw);
        }
    }

    private boolean shouldSkipTag(String description, String tagName) {
        return (description == null || description.trim().isEmpty()) &&
               (tagName == null || tagName.trim().isEmpty());
    }

    private void processResolutionTag(Photo photo, TagInfo info, ExtractionContext context) {
        if (photo.getResolution() == null && info.description != null) {
            java.util.regex.Matcher resMatcher = java.util.regex.Pattern
                .compile("(\\d{2,5})[xX](\\d{2,5})")
                .matcher(info.description);
            if (resMatcher.find()) {
                context.tempWidth = Integer.parseInt(resMatcher.group(1));
                context.tempHeight = Integer.parseInt(resMatcher.group(2));
                String resolution = context.tempWidth + "x" + context.tempHeight;
                photo.setResolution(resolution.toLowerCase());
                logger.debug("Parsed resolution from pattern: {}", resolution);
            }
        }
    }

    private void  processCameraInfoTags(Photo photo, TagInfo info) {
        if (photo.getCameraMake() == null &&
            (info.lname.contains("make") || info.combined.contains("manufacturer") || info.combined.contains("maker"))) {
            photo.setCameraMake(info.getValueOrName());
        }

        if (photo.getCameraModel() == null &&
            (info.lname.contains(KEYWORD_MODEL) || info.combined.contains("camera model") || info.ldesc.contains(KEYWORD_MODEL)) &&
            !info.lname.contains("lens")) {
            photo.setCameraModel(info.getValueOrName());
        }

        if (photo.getLensModel() == null &&
            (info.lname.contains("lens") || info.ldesc.contains("ef-") || info.ldesc.contains("rf-") || info.ldesc.contains("lens model"))) {
            photo.setLensModel(info.getValueOrName());
        }

        if (photo.getIso() == null && (info.lname.contains("iso") || containsIsoPattern(info.ldesc))) {
            trySetIsoFromDescription(photo, info.description);
        }

        if (photo.getAperture() == null &&
            (info.lname.contains(KEYWORD_F_NUMBER) || info.lname.contains(KEYWORD_APERTURE) || info.ldesc.startsWith("f/"))) {
            photo.setAperture(formatAperture(info.description));
        }
    }

    private void trySetIsoFromDescription(Photo photo, String description) {
        String digits = (description == null ? "" : description).replaceAll(DIGIT_PATTERN, "");
        if (!digits.isEmpty() && digits.length() <= 4) {
            try {
                photo.setIso(Integer.parseInt(digits));
            } catch (Exception ignored) {
                // Failed to parse ISO
            }
        }
    }

    private void processExposureTags(Directory directory, Tag tag, TagInfo info, ExtractionContext context) {
        if (info.lname.contains(KEYWORD_EXPOSURE) || info.lname.contains(KEYWORD_SHUTTER) || info.ldesc.matches(".*\\d+/\\d+.*")) {
            if (context.exposureDescRaw == null) {
                context.exposureDescRaw = info.getValueOrName();
            }
            if (context.exposureRawObj == null) {
                context.exposureRawObj = tryGetRawObject(directory, tag);
            }
        }
    }

    private void processShutterApexTags(Directory directory, Tag tag, TagInfo info, ExtractionContext context) {
        if (info.lname.contains("shutter speed") ||
            (info.tagName != null && info.tagName.toLowerCase().contains("shutter speed value"))) {
            if (context.shutterApexDesc == null) {
                context.shutterApexDesc = info.getValueOrName();
            }
            if (context.shutterApexRaw == null) {
                context.shutterApexRaw = tryGetRawObject(directory, tag);
            }
        }
    }

    private Object tryGetRawObject(Directory directory, Tag tag) {
        try {
            return directory.getObject(tag.getTagType());
        } catch (Exception ignored) {
            return null;
        }
    }

    private void processLensAndSettingsTags(Photo photo, TagInfo info) {
        if (photo.getFocalLength() == null && (info.lname.contains(KEYWORD_FOCAL) || info.ldesc.contains("mm"))) {
            photo.setFocalLength(info.getValueOrName());
        }

        if (photo.getExposureCompensation() == null &&
            (info.lname.contains("exposure compensation") || info.lname.contains("exposure bias"))) {
            photo.setExposureCompensation(info.getValueOrName());
        }

        if (photo.getWhiteBalance() == null && info.lname.contains("white balance")) {
            photo.setWhiteBalance(info.getValueOrName());
        }

        if (photo.getMeteringMode() == null && info.lname.contains("metering")) {
            photo.setMeteringMode(info.getValueOrName());
        }

        if (photo.getFlashMode() == null && info.lname.contains("flash")) {
            photo.setFlashMode(info.getValueOrName());
        }
    }

    private void processDimensionTags(TagInfo info, ExtractionContext context) {
        if (context.tempWidth == null && (info.lname.contains("image width") || info.lname.contains("exif image width"))) {
            context.tempWidth = tryParseInteger(info.description);
        }

        if (context.tempHeight == null && (info.lname.contains("image height") || info.lname.contains("exif image height"))) {
            context.tempHeight = tryParseInteger(info.description);
        }
    }

    private Integer tryParseInteger(String description) {
        try {
            String digits = (description == null ? "" : description).replaceAll(DIGIT_PATTERN, "");
            if (!digits.isEmpty()) {
                return Integer.parseInt(digits);
            }
        } catch (Exception ignored) {
            // Failed to parse integer
        }
        return null;
    }

    private void processDateAndGpsTags(Photo photo, Directory directory, TagInfo info) {
        if (photo.getDateTaken() == null &&
            (info.lname.contains("date") || info.lname.contains("time") || info.ldesc.matches(".*\\d{4}.*"))) {
            LocalDateTime parsed = parseExifDateTime(info.description);
            if (parsed != null) {
                photo.setDateTaken(parsed);
            }
        }

        if ((photo.getGpsLatitude() == null || photo.getGpsLongitude() == null) &&
            (info.lname.contains("gps") || info.combined.contains("gps latitude") || info.combined.contains("gps longitude"))) {
            extractGpsLatitude(directory, photo);
            extractGpsLongitude(directory, photo);
        }
    }

    private void finalizePhotoData(Metadata metadata, Photo photo, ExtractionContext context) {
        if (context.tempWidth != null && context.tempHeight != null && photo.getResolution() == null) {
            String resolution = context.tempWidth + "x" + context.tempHeight;
            photo.setResolution(resolution);
            logger.debug("Set resolution: {}", resolution);
        }

        if (hasNullFields(photo)) {
            broadKeywordFieldScan(metadata, photo);
        }

        if (photo.getShutterSpeed() == null) {
            String formatted = calculateShutterSpeed(context.exposureDescRaw, context.exposureRawObj, context.shutterApexRaw);
            if (formatted != null && !formatted.isEmpty()) {
                photo.setShutterSpeed(formatted);
            }
        }
    }

    private boolean hasNullFields(Photo photo) {
        return photo.getCameraMake() == null || photo.getCameraModel() == null || photo.getLensModel() == null ||
               photo.getIso() == null || photo.getAperture() == null || photo.getShutterSpeed() == null ||
               photo.getFocalLength() == null;
    }

    private String calculateShutterSpeed(String exposureDescRaw, Object exposureRawObj, Object shutterApexRaw) {
        try {
            String formatted = null;

            if (exposureRawObj != null) {
                formatted = parseExposureRawToString(exposureRawObj);
            }

            if ((formatted == null || formatted.isEmpty()) && shutterApexRaw != null) {
                formatted = calculateFromApex(shutterApexRaw);
            }

            if ((formatted == null || formatted.isEmpty()) && exposureDescRaw != null) {
                formatted = parseExposureDescription(exposureDescRaw);
            }

            return formatted;
        } catch (Exception e) {
            logger.debug("Post-process shutter parsing failed: {}", e.getMessage());
            return null;
        }
    }

    private String calculateFromApex(Object shutterApexRaw) {
        Double apexVal = parseNumericFromObject(shutterApexRaw);
        if (apexVal != null) {
            double sec = Math.pow(2.0, -apexVal);
            return formatExposureSeconds(sec);
        }
        return null;
    }

    // Helper classes for data management
    private static class ExtractionContext {
        Integer tempWidth = null;
        Integer tempHeight = null;
        String exposureDescRaw = null;
        Object exposureRawObj = null;
        String shutterApexDesc = null;
        Object shutterApexRaw = null;
    }

    private static class TagInfo {
        final String tagName;
        final String description;
        final String lname;
        final String ldesc;
        final String combined;

        TagInfo(String tagName, String description) {
            this.tagName = tagName;
            this.description = description;
            this.lname = tagName == null ? "" : tagName.toLowerCase();
            this.ldesc = description == null ? "" : description.toLowerCase();
            this.combined = (lname + " " + ldesc).trim();
        }

        String getValueOrName() {
            return (description == null ? tagName : description).trim();
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
            if (lname.contains(KEYWORD_LATITUDE) || lname.contains("lat")) {
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
            if (lname.contains(KEYWORD_LONGITUDE) || lname.contains("lon") || lname.contains("long")) {
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
                else if (lk.contains(KEYWORD_MODEL) && photo.getCameraModel() == null) photo.setCameraModel(val);
                else if ((lk.contains("lens") || lk.contains("lensmodel")) && photo.getLensModel() == null) photo.setLensModel(val);
                else if ((lk.contains("iso") || lk.contains(KEYWORD_ISO_SPEED)) && photo.getIso() == null) {
                    String digits = val.replaceAll(DIGIT_PATTERN, ""); if (!digits.isEmpty()) photo.setIso(Integer.parseInt(digits));
                } else if ((lk.contains("shutterspeed") || lk.contains(KEYWORD_EXPOSURE_TIME) || lk.contains("exposure time") || lk.contains(KEYWORD_EXPOSURE)) && photo.getShutterSpeed() == null) {
                    String formatted = parseExposureDescription(val);
                    photo.setShutterSpeed(formatted != null ? formatted : val);
                }
                else if ((lk.contains(KEYWORD_APERTURE) || lk.contains("fnumber")) && photo.getAperture() == null) photo.setAperture(val);
                else if ((lk.contains(KEYWORD_FOCAL_LENGTH) || lk.contains(KEYWORD_FOCAL)) && photo.getFocalLength() == null) photo.setFocalLength(val);
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
                } else if ((lk.contains("createdate") || lk.contains("date/time") || lk.contains(KEYWORD_DATETIME)) && photo.getDateTaken() == null) {
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
                if (!out.containsKey(FIELD_MODEL) && key.contains(KEYWORD_MODEL)) out.put(FIELD_MODEL, val);
                if (!out.containsKey("Lens") && (key.contains("lens") || key.contains("lensmodel"))) out.put("Lens", val);
                if (!out.containsKey(FIELD_ISO) && (key.contains("iso") || key.contains(KEYWORD_ISO_SPEED))) out.put(FIELD_ISO, val);
                if (!out.containsKey(FIELD_APERTURE) && (key.contains(KEYWORD_F_NUMBER) || key.contains(KEYWORD_APERTURE) || val.startsWith("f/"))) out.put(FIELD_APERTURE, val);
                if (!out.containsKey(FIELD_SHUTTER_SPEED) && (key.contains(KEYWORD_SHUTTER) || key.contains(KEYWORD_EXPOSURE_TIME) || key.contains("exposure time"))) out.put(FIELD_SHUTTER_SPEED, val);
                if (!out.containsKey(FIELD_FOCAL_LENGTH) && (key.contains(KEYWORD_FOCAL) || key.contains(KEYWORD_FOCAL_LENGTH))) out.put(FIELD_FOCAL_LENGTH, val);
                if (!out.containsKey(FIELD_RESOLUTION) && (key.contains("resolution") || key.contains("imagesize") || key.contains("imagewidth") || key.contains("exif image"))) out.put(FIELD_RESOLUTION, val);
                if (!out.containsKey("DateTaken") && (key.contains("date") || key.contains(KEYWORD_DATETIME) || key.contains("create"))) out.put("DateTaken", val);
                if (!out.containsKey(FIELD_GPS_LATITUDE) && (key.contains("gpslatitude") || key.contains(KEYWORD_LATITUDE))) out.put(FIELD_GPS_LATITUDE, val);
                if (!out.containsKey(FIELD_GPS_LONGITUDE) && (key.contains("gpslongitude") || key.contains(KEYWORD_LONGITUDE))) out.put(FIELD_GPS_LONGITUDE, val);
                if (!out.containsKey("Orientation") && key.contains("orientation")) out.put("Orientation", val);
            } catch (Exception ignored) {}
        }

        return out;
    }

    private boolean containsIsoPattern(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        int isoIndex = text.indexOf("iso");
        if (isoIndex == -1) {
            return false;
        }

        int startPos = isoIndex + 3;
        if (startPos >= text.length()) {
            return false;
        }

        while (startPos < text.length() && Character.isWhitespace(text.charAt(startPos))) {
            startPos++;
        }

        if (startPos < text.length() && (text.charAt(startPos) == ':' || text.charAt(startPos) == '=')) {
            startPos++;
        }

        while (startPos < text.length() && Character.isWhitespace(text.charAt(startPos))) {
            startPos++;
        }

        return startPos < text.length() && Character.isDigit(text.charAt(startPos));
    }
}
