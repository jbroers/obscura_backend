package com.example.obscura_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoResponseDto {
    private Long id;
    private String fileName;
    private String url;
    private String contentType;
    private Long fileSize;
    private String uploadedAt;
    private Boolean isRaw;
    private String metadata;
}
