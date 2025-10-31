package com.example.obscura_backend.dto;

public class PhotoResponseDto {
    private Long id;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String uploadedAt;
    private Boolean isRaw;
    private String metadata;

    public PhotoResponseDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(String uploadedAt) { this.uploadedAt = uploadedAt; }

    public Boolean getIsRaw() { return isRaw; }
    public void setIsRaw(Boolean isRaw) { this.isRaw = isRaw; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
