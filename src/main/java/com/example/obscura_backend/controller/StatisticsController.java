package com.example.obscura_backend.controller;

import com.example.obscura_backend.dto.PhotoStatisticsDto;
import com.example.obscura_backend.service.StatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/statistics")
@CrossOrigin
public class StatisticsController {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsController.class);
    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping
    public ResponseEntity<?> getPhotoStatistics() {
        try {
            logger.info("Fetching photo statistics");
            PhotoStatisticsDto statistics = statisticsService.getPhotoStatistics();
            logger.info("Successfully calculated statistics for {} photos", statistics.getTotalPhotos());
            return ResponseEntity.ok(statistics);
        } catch (Exception e) {
            logger.error("Error calculating statistics: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Failed to calculate statistics\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }
}

