package com.example.obscura_backend.service;

import com.example.obscura_backend.dto.PhotoStatisticsDto;
import com.example.obscura_backend.model.Photo;
import com.example.obscura_backend.repository.PhotoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StatisticsService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);
    private final PhotoRepository photoRepository;

    public StatisticsService(PhotoRepository photoRepository) {
        this.photoRepository = photoRepository;
    }

    public PhotoStatisticsDto getPhotoStatistics() {
        logger.info("Calculating photo statistics");

        List<Photo> allPhotos = photoRepository.findAll();

        if (allPhotos.isEmpty()) {
            return createEmptyStatistics();
        }

        PhotoStatisticsDto stats = PhotoStatisticsDto.builder()
                .totalPhotos(allPhotos.size())
                .totalRawPhotos(countRawPhotos(allPhotos))
                .totalJpegPhotos(countJpegPhotos(allPhotos))
                .totalStorageUsedMB(calculateTotalStorage(allPhotos))
                .mostUsedCamera(findMostUsedCamera(allPhotos))
                .cameraUsage(calculateCameraUsage(allPhotos))
                .mostUsedLens(findMostUsedLens(allPhotos))
                .lensUsage(calculateLensUsage(allPhotos))
                .mostUsedAperture(findMostUsedAperture(allPhotos))
                .apertureDistribution(calculateApertureDistribution(allPhotos))
                .mostUsedIso(findMostUsedIso(allPhotos))
                .isoDistribution(calculateIsoDistribution(allPhotos))
                .mostUsedFocalLength(findMostUsedFocalLength(allPhotos))
                .focalLengthDistribution(calculateFocalLengthDistribution(allPhotos))
                .photosWithGps(countPhotosWithGps(allPhotos))
                .photosWithoutGps(countPhotosWithoutGps(allPhotos))
                .averageLatitude(calculateAverageLatitude(allPhotos))
                .averageLongitude(calculateAverageLongitude(allPhotos))
                .uploadPeriod(calculateUploadPeriod(allPhotos))
                .photosLastWeek(countPhotosLastWeek(allPhotos))
                .photosLastMonth(countPhotosLastMonth(allPhotos))
                .mostActiveDay(findMostActiveDay(allPhotos))
                .insights(generateInsights(allPhotos))
                .recommendations(generateRecommendations(allPhotos))
                .build();

        logger.info("Statistics calculated: {} photos, {} cameras, {} lenses",
                stats.getTotalPhotos(),
                stats.getCameraUsage().size(),
                stats.getLensUsage().size());

        return stats;
    }

    private PhotoStatisticsDto createEmptyStatistics() {
        return PhotoStatisticsDto.builder()
                .totalPhotos(0)
                .totalRawPhotos(0)
                .totalJpegPhotos(0)
                .totalStorageUsedMB(0L)
                .cameraUsage(new HashMap<>())
                .lensUsage(new HashMap<>())
                .apertureDistribution(new HashMap<>())
                .isoDistribution(new HashMap<>())
                .focalLengthDistribution(new HashMap<>())
                .photosWithGps(0)
                .photosWithoutGps(0)
                .uploadPeriod("N/A")
                .photosLastWeek(0)
                .photosLastMonth(0)
                .insights(Arrays.asList("Nog geen foto's geüpload"))
                .recommendations(Arrays.asList("Upload je eerste foto om statistieken te zien"))
                .build();
    }

    private Integer countRawPhotos(List<Photo> photos) {
        return (int) photos.stream()
                .filter(p -> p.getIsRaw() != null && p.getIsRaw())
                .count();
    }

    private Integer countJpegPhotos(List<Photo> photos) {
        return (int) photos.stream()
                .filter(p -> p.getIsRaw() == null || !p.getIsRaw())
                .count();
    }

    private Long calculateTotalStorage(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getFileSize() != null)
                .mapToLong(Photo::getFileSize)
                .sum() / (1024 * 1024);
    }

    private String findMostUsedCamera(List<Photo> photos) {
        Map<String, Integer> cameraUsage = calculateCameraUsage(photos);
        return cameraUsage.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Onbekend");
    }

    private Map<String, Integer> calculateCameraUsage(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getCameraMake() != null && p.getCameraModel() != null)
                .collect(Collectors.groupingBy(
                        p -> p.getCameraMake() + " " + p.getCameraModel(),
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private String findMostUsedLens(List<Photo> photos) {
        Map<String, Integer> lensUsage = calculateLensUsage(photos);
        return lensUsage.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Onbekend");
    }

    private Map<String, Integer> calculateLensUsage(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getLensModel() != null && !p.getLensModel().isEmpty())
                .collect(Collectors.groupingBy(
                        Photo::getLensModel,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private String findMostUsedAperture(List<Photo> photos) {
        Map<String, Integer> apertureDistribution = calculateApertureDistribution(photos);
        return apertureDistribution.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Onbekend");
    }

    private Map<String, Integer> calculateApertureDistribution(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getAperture() != null && !p.getAperture().isEmpty())
                .collect(Collectors.groupingBy(
                        Photo::getAperture,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private String findMostUsedIso(List<Photo> photos) {
        Map<String, Integer> isoDistribution = calculateIsoDistribution(photos);
        return isoDistribution.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Onbekend");
    }

    private Map<String, Integer> calculateIsoDistribution(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getIso() != null)
                .collect(Collectors.groupingBy(
                        p -> p.getIso().toString(),
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private String findMostUsedFocalLength(List<Photo> photos) {
        Map<String, Integer> focalLengthDistribution = calculateFocalLengthDistribution(photos);
        return focalLengthDistribution.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Onbekend");
    }

    private Map<String, Integer> calculateFocalLengthDistribution(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getFocalLength() != null && !p.getFocalLength().isEmpty())
                .collect(Collectors.groupingBy(
                        Photo::getFocalLength,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private Integer countPhotosWithGps(List<Photo> photos) {
        return (int) photos.stream()
                .filter(p -> p.getGpsLatitude() != null && p.getGpsLongitude() != null)
                .count();
    }

    private Integer countPhotosWithoutGps(List<Photo> photos) {
        return (int) photos.stream()
                .filter(p -> p.getGpsLatitude() == null || p.getGpsLongitude() == null)
                .count();
    }

    private Double calculateAverageLatitude(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getGpsLatitude() != null)
                .mapToDouble(p -> {
                    try {
                        return Double.parseDouble(p.getGpsLatitude());
                    } catch (NumberFormatException e) {
                        return 0.0;
                    }
                })
                .average()
                .orElse(0.0);
    }

    private Double calculateAverageLongitude(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getGpsLongitude() != null)
                .mapToDouble(p -> {
                    try {
                        return Double.parseDouble(p.getGpsLongitude());
                    } catch (NumberFormatException e) {
                        return 0.0;
                    }
                })
                .average()
                .orElse(0.0);
    }

    private String calculateUploadPeriod(List<Photo> photos) {
        Optional<LocalDateTime> earliest = photos.stream()
                .filter(p -> p.getUploadedAt() != null)
                .map(Photo::getUploadedAt)
                .min(LocalDateTime::compareTo);

        Optional<LocalDateTime> latest = photos.stream()
                .filter(p -> p.getUploadedAt() != null)
                .map(Photo::getUploadedAt)
                .max(LocalDateTime::compareTo);

        if (earliest.isPresent() && latest.isPresent()) {
            long days = ChronoUnit.DAYS.between(earliest.get(), latest.get());
            if (days == 0) {
                return "Vandaag";
            } else if (days == 1) {
                return "1 dag";
            } else if (days < 30) {
                return days + " dagen";
            } else if (days < 365) {
                return (days / 30) + " maanden";
            } else {
                return (days / 365) + " jaar";
            }
        }
        return "N/A";
    }

    private Integer countPhotosLastWeek(List<Photo> photos) {
        LocalDateTime oneWeekAgo = LocalDateTime.now().minusWeeks(1);
        return (int) photos.stream()
                .filter(p -> p.getUploadedAt() != null && p.getUploadedAt().isAfter(oneWeekAgo))
                .count();
    }

    private Integer countPhotosLastMonth(List<Photo> photos) {
        LocalDateTime oneMonthAgo = LocalDateTime.now().minusMonths(1);
        return (int) photos.stream()
                .filter(p -> p.getUploadedAt() != null && p.getUploadedAt().isAfter(oneMonthAgo))
                .count();
    }

    private String findMostActiveDay(List<Photo> photos) {
        Map<DayOfWeek, Long> dayCount = photos.stream()
                .filter(p -> p.getUploadedAt() != null)
                .collect(Collectors.groupingBy(
                        p -> p.getUploadedAt().getDayOfWeek(),
                        Collectors.counting()
                ));

        return dayCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> translateDayOfWeek(e.getKey()))
                .orElse("Onbekend");
    }

    private String translateDayOfWeek(DayOfWeek day) {
        switch (day) {
            case MONDAY: return "Maandag";
            case TUESDAY: return "Dinsdag";
            case WEDNESDAY: return "Woensdag";
            case THURSDAY: return "Donderdag";
            case FRIDAY: return "Vrijdag";
            case SATURDAY: return "Zaterdag";
            case SUNDAY: return "Zondag";
            default: return day.toString();
        }
    }

    private List<String> generateInsights(List<Photo> photos) {
        List<String> insights = new ArrayList<>();

        String mostUsedCamera = findMostUsedCamera(photos);
        int cameraCount = calculateCameraUsage(photos).size();
        if (cameraCount > 1) {
            insights.add(String.format("Je gebruikt %d verschillende camera's, maar fotografeert het meest met %s", cameraCount, mostUsedCamera));
        } else if (cameraCount == 1) {
            insights.add(String.format("Je bent trouw aan je %s", mostUsedCamera));
        }

        Map<String, Integer> apertures = calculateApertureDistribution(photos);
        if (!apertures.isEmpty()) {
            String mostUsedAperture = findMostUsedAperture(photos);
            double avgAperture = calculateAverageApertureValue(photos);
            if (avgAperture < 2.8) {
                insights.add(String.format("Je fotografeert graag met een grote diafragma opening (%s is favoriet)", mostUsedAperture));
            } else if (avgAperture > 5.6) {
                insights.add(String.format("Je gebruikt vaak een gesloten diafragma (%s is favoriet)", mostUsedAperture));
            }
        }

        Map<String, Integer> isos = calculateIsoDistribution(photos);
        if (!isos.isEmpty()) {
            double avgIso = calculateAverageIso(photos);
            if (avgIso > 1600) {
                insights.add("Je fotografeert vaak in low-light situaties met hoge ISO waardes");
            } else if (avgIso < 400) {
                insights.add("Je foto's zijn gemaakt in goede lichtomstandigheden met lage ISO waardes");
            }
        }

        int gpsCount = countPhotosWithGps(photos);
        int totalCount = photos.size();
        double gpsPercentage = (gpsCount * 100.0) / totalCount;
        if (gpsPercentage > 50) {
            insights.add(String.format("%.0f%% van je foto's heeft GPS locatie data", gpsPercentage));
        } else if (gpsPercentage < 10 && gpsCount > 0) {
            insights.add("Weinig foto's met GPS data - overweeg geotagging in te schakelen op je camera");
        }

        int rawCount = countRawPhotos(photos);
        double rawPercentage = (rawCount * 100.0) / totalCount;
        if (rawPercentage > 80) {
            insights.add("Je schiet bijna altijd in RAW format");
        } else if (rawPercentage > 0 && rawPercentage < 20) {
            insights.add("Je gebruikt voornamelijk JPEG, maar experimenteert soms met RAW");
        }

        int lastWeekCount = countPhotosLastWeek(photos);
        int lastMonthCount = countPhotosLastMonth(photos);
        if (lastWeekCount > 20) {
            insights.add("Je bent deze week erg productief geweest met fotograferen!");
        } else if (lastMonthCount > 50) {
            insights.add("Je hebt deze maand veel gefotografeerd");
        }

        if (insights.isEmpty()) {
            insights.add("Begin met meer foto's uploaden voor gedetailleerde inzichten");
        }

        return insights;
    }

    private List<String> generateRecommendations(List<Photo> photos) {
        List<String> recommendations = new ArrayList<>();

        int lensCount = calculateLensUsage(photos).size();
        if (lensCount == 1 && photos.size() > 10) {
            recommendations.add("Experimenteer met verschillende lenzen om je creativiteit te vergroten");
        }

        int gpsCount = countPhotosWithGps(photos);
        if (gpsCount == 0 && photos.size() > 5) {
            recommendations.add("Schakel GPS in op je camera om locaties bij te houden voor toekomstige referentie");
        }

        int rawCount = countRawPhotos(photos);
        if (rawCount == 0 && photos.size() > 10) {
            recommendations.add("Overweeg om in RAW te fotograferen voor maximale bewerkingsmogelijkheden");
        }

        double avgIso = calculateAverageIso(photos);
        if (avgIso > 3200) {
            recommendations.add("Je gebruikt vaak hoge ISO waardes, een snellere lens of externe flits kan helpen ruis te verminderen");
        }

        Map<String, Integer> apertures = calculateApertureDistribution(photos);
        if (apertures.size() == 1 && photos.size() > 10) {
            recommendations.add("Experimenteer met verschillende diafragma's om verschillende effecten te bereiken");
        }

        Map<String, Integer> focalLengths = calculateFocalLengthDistribution(photos);
        if (focalLengths.size() == 1 && photos.size() > 10) {
            String focal = findMostUsedFocalLength(photos);
            if (focal.contains("50")) {
                recommendations.add("Je bent een 50mm fan! Probeer eens een wide-angle lens voor landschappen");
            } else if (focal.contains("35")) {
                recommendations.add("Je houdt van 35mm! Een telelens kan interessant zijn voor portretten");
            }
        }

        long storageMB = calculateTotalStorage(photos);
        if (storageMB > 1000) {
            recommendations.add(String.format("Je gebruikt al %d MB opslag - overweeg regelmatig oude foto's op te schonen", storageMB));
        }

        int lastWeekCount = countPhotosLastWeek(photos);
        if (lastWeekCount == 0 && photos.size() > 0) {
            recommendations.add("Je hebt deze week nog niet gefotografeerd");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Je doet het geweldig! Blijf foto's uploaden voor meer aanbevelingen");
        }

        return recommendations;
    }

    private double calculateAverageApertureValue(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getAperture() != null)
                .mapToDouble(p -> {
                    try {
                        String aperture = p.getAperture().toLowerCase()
                                .replace("f/", "")
                                .replace("ƒ/", "")
                                .trim();
                        return Double.parseDouble(aperture);
                    } catch (Exception e) {
                        return 0.0;
                    }
                })
                .filter(v -> v > 0)
                .average()
                .orElse(0.0);
    }

    private double calculateAverageIso(List<Photo> photos) {
        return photos.stream()
                .filter(p -> p.getIso() != null)
                .mapToInt(Photo::getIso)
                .average()
                .orElse(0.0);
    }
}