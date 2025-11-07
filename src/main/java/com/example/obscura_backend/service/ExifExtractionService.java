package com.example.obscura_backend.service;

import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.example.obscura_backend.model.Photo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ExifExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(ExifExtractionService.class);

    public void extractExifData(Metadata metadata, Photo photo) {
        logger.debug("Available metadata directories:");
        for (Directory directory : metadata.getDirectories()) {
            logger.debug("  - {}", directory.getName());
        }

        for (Directory directory : metadata.getDirectories()) {
            extractAllTagsByName(directory, photo);
        }
    }

    private void extractAllTagsByName(Directory directory, Photo photo) {
        Integer tempWidth = null;
        Integer tempHeight = null;

        for (Tag tag : directory.getTags()) {
            String tagName = tag.getTagName();
            String description = tag.getDescription();

            if (description == null || description.trim().isEmpty()) {
                continue;
            }

            try {
                switch (tagName) {
                    case "Make":
                        if (photo.getCameraMake() == null) {
                            photo.setCameraMake(description.trim());
                        }
                        break;
                    case "Model":
                        if (photo.getCameraModel() == null) {
                            photo.setCameraModel(description.trim());
                        }
                        break;
                    case "Lens Model":
                    case "Lens":
                        if (photo.getLensModel() == null) {
                            photo.setLensModel(description.trim());
                        }
                        break;
                    case "ISO Speed Ratings":
                    case "ISO Speed":
                    case "Photographic Sensitivity":
                        if (photo.getIso() == null) {
                            try {
                                photo.setIso(Integer.parseInt(description.trim()));
                            } catch (NumberFormatException e) {
                                logger.debug("Could not parse ISO: {}", description);
                            }
                        }
                        break;
                    case "F-Number":
                    case "Aperture Value":
                        if (photo.getAperture() == null) {
                            photo.setAperture(formatAperture(description));
                        }
                        break;
                    case "Exposure Time":
                    case "Shutter Speed Value":
                        if (photo.getShutterSpeed() == null) {
                            photo.setShutterSpeed(description.trim());
                        }
                        break;
                    case "Focal Length":
                        if (photo.getFocalLength() == null) {
                            photo.setFocalLength(description.trim());
                        }
                        break;
                    case "Exposure Compensation":
                    case "Exposure Bias Value":
                        if (photo.getExposureCompensation() == null) {
                            photo.setExposureCompensation(description.trim());
                        }
                        break;
                    case "White Balance":
                    case "White Balance Mode":
                        if (photo.getWhiteBalance() == null) {
                            photo.setWhiteBalance(description.trim());
                        }
                        break;
                    case "Metering Mode":
                        if (photo.getMeteringMode() == null) {
                            photo.setMeteringMode(description.trim());
                        }
                        break;
                    case "Flash":
                    case "Flash Mode":
                        if (photo.getFlashMode() == null) {
                            photo.setFlashMode(description.trim());
                        }
                        break;
                    case "Image Width":
                    case "Exif Image Width":
                        if (tempWidth == null) {
                            try {
                                String widthStr = description.replaceAll("[^0-9]", "");
                                if (!widthStr.isEmpty()) {
                                    tempWidth = Integer.parseInt(widthStr);
                                    logger.debug("Found image width: {}", tempWidth);
                                }
                            } catch (Exception e) {
                                logger.debug("Could not parse width: {}", description);
                            }
                        }
                        break;
                    case "Image Height":
                    case "Exif Image Height":
                        if (tempHeight == null) {
                            try {
                                String heightStr = description.replaceAll("[^0-9]", "");
                                if (!heightStr.isEmpty()) {
                                    tempHeight = Integer.parseInt(heightStr);
                                    logger.debug("Found image height: {}", tempHeight);
                                }
                            } catch (Exception e) {
                                logger.debug("Could not parse height: {}", description);
                            }
                        }
                        break;
                    case "Date/Time Original":
                    case "Date/Time Digitized":
                        if (photo.getDateTaken() == null) {
                            photo.setDateTaken(parseExifDateTime(description));
                        }
                        break;
                    case "Orientation":
                        if (photo.getOrientation() == null) {
                            photo.setOrientation(description.trim());
                        }
                        break;
                    case "GPS Latitude":
                        extractGpsLatitude(directory, photo);
                        break;
                    case "GPS Longitude":
                        extractGpsLongitude(directory, photo);
                        break;
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
    }

    private void extractGpsLatitude(Directory directory, Photo photo) {
        if (photo.getGpsLatitude() != null) return;

        try {
            String latStr = null;
            String latRef = null;

            for (Tag tag : directory.getTags()) {
                if (tag.getTagName().equals("GPS Latitude")) {
                    latStr = tag.getDescription();
                } else if (tag.getTagName().equals("GPS Latitude Ref")) {
                    latRef = tag.getDescription();
                }
            }

            if (latStr != null) {
                Double latitude = parseGpsCoordinate(latStr, latRef);
                if (latitude != null) {
                    photo.setGpsLatitude(String.format("%.6f", latitude));
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to extract GPS latitude: {}", e.getMessage());
        }
    }

    private void extractGpsLongitude(Directory directory, Photo photo) {
        if (photo.getGpsLongitude() != null) return;

        try {
            String lonStr = null;
            String lonRef = null;

            for (Tag tag : directory.getTags()) {
                if (tag.getTagName().equals("GPS Longitude")) {
                    lonStr = tag.getDescription();
                } else if (tag.getTagName().equals("GPS Longitude Ref")) {
                    lonRef = tag.getDescription();
                }
            }

            if (lonStr != null) {
                Double longitude = parseGpsCoordinate(lonStr, lonRef);
                if (longitude != null) {
                    photo.setGpsLongitude(String.format("%.6f", longitude));
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to extract GPS longitude: {}", e.getMessage());
        }
    }

    private LocalDateTime parseExifDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            dateTimeStr = dateTimeStr.trim();
            int firstColon = dateTimeStr.indexOf(':');
            if (firstColon > 0) {
                dateTimeStr = dateTimeStr.substring(0, firstColon) + "-" + dateTimeStr.substring(firstColon + 1);
                int secondColon = dateTimeStr.indexOf(':', firstColon);
                if (secondColon > 0) {
                    dateTimeStr = dateTimeStr.substring(0, secondColon) + "-" + dateTimeStr.substring(secondColon + 1);
                }
            }

            return LocalDateTime.parse(dateTimeStr,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            logger.debug("Could not parse date/time: {}", dateTimeStr);
            return null;
        }
    }

    private Double parseGpsCoordinate(String coordinate, String ref) {
        if (coordinate == null || coordinate.trim().isEmpty()) {
            return null;
        }

        try {
            String cleaned = coordinate.replace("°", " ")
                    .replace("'", " ")
                    .replace("\"", " ")
                    .replace(",", " ")
                    .trim();

            String[] parts = cleaned.split("\\s+");

            if (parts.length >= 3) {
                double degrees = Double.parseDouble(parts[0]);
                double minutes = Double.parseDouble(parts[1]);
                double seconds = Double.parseDouble(parts[2]);

                double decimal = degrees + (minutes / 60.0) + (seconds / 3600.0);

                if (ref != null && (ref.equalsIgnoreCase("S") || ref.equalsIgnoreCase("W"))) {
                    decimal = -decimal;
                }

                return decimal;
            } else if (parts.length == 1) {
                double decimal = Double.parseDouble(parts[0]);
                if (ref != null && (ref.equalsIgnoreCase("S") || ref.equalsIgnoreCase("W"))) {
                    decimal = -decimal;
                }
                return decimal;
            }
        } catch (Exception e) {
            logger.debug("Failed to parse GPS coordinate '{}' with ref '{}': {}", coordinate, ref, e.getMessage());
        }
        return null;
    }

    private String formatAperture(String aperture) {
        if (aperture == null || aperture.trim().isEmpty()) {
            return null;
        }

        aperture = aperture.trim();

        if (aperture.toLowerCase().startsWith("f/") || aperture.startsWith("ƒ/")) {
            return aperture;
        }

        try {
            String numeric = aperture.replaceAll("[^0-9./]", "");

            if (numeric.contains("/")) {
                String[] parts = numeric.split("/");
                if (parts.length == 2) {
                    double numerator = Double.parseDouble(parts[0]);
                    double denominator = Double.parseDouble(parts[1]);
                    double value = numerator / denominator;
                    return String.format("f/%.1f", value);
                }
            } else {
                double value = Double.parseDouble(numeric);
                return String.format("f/%.1f", value);
            }
        } catch (Exception e) {
            logger.debug("Could not format aperture '{}': {}", aperture, e.getMessage());
        }

        return aperture;
    }
}

