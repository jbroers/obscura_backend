package com.example.obscura_backend.controller;

import com.example.obscura_backend.dto.PhotoStatisticsDto;
import com.example.obscura_backend.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Statistics", description = "Photo statistics endpoints")
public class StatisticsController {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsController.class);
    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping
    @Operation(summary = "Get photo statistics", description = "Retrieves aggregated statistics about all photos")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved statistics",
                content = @Content(mediaType = "application/json",
                        schema = @Schema(implementation = PhotoStatisticsDto.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<PhotoStatisticsDto> getPhotoStatistics() {
        try {
            logger.info("Fetching photo statistics");
            PhotoStatisticsDto statistics = statisticsService.getPhotoStatistics();
            logger.info("Successfully calculated statistics for {} photos", statistics.getTotalPhotos());
            return ResponseEntity.ok(statistics);
        } catch (Exception e) {
            logger.error("Error calculating statistics: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

