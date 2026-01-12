package com.example.obscura_backend.dto;

import java.util.List;

public class BatchUploadResultDto {
    private int totalFiles;
    private int successCount;
    private int failureCount;
    private int duplicateCount;
    private List<PhotoResponseDto> successful;
    private List<FileErrorDto> failed;
    private List<String> duplicates;

    public BatchUploadResultDto() {
    }

    public BatchUploadResultDto(int totalFiles, int successCount, int failureCount, int duplicateCount,
                                List<PhotoResponseDto> successful, List<FileErrorDto> failed, List<String> duplicates) {
        this.totalFiles = totalFiles;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.duplicateCount = duplicateCount;
        this.successful = successful;
        this.failed = failed;
        this.duplicates = duplicates;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public void setTotalFiles(int totalFiles) {
        this.totalFiles = totalFiles;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public void setDuplicateCount(int duplicateCount) {
        this.duplicateCount = duplicateCount;
    }

    public List<PhotoResponseDto> getSuccessful() {
        return successful;
    }

    public void setSuccessful(List<PhotoResponseDto> successful) {
        this.successful = successful;
    }

    public List<FileErrorDto> getFailed() {
        return failed;
    }

    public void setFailed(List<FileErrorDto> failed) {
        this.failed = failed;
    }

    public List<String> getDuplicates() {
        return duplicates;
    }

    public void setDuplicates(List<String> duplicates) {
        this.duplicates = duplicates;
    }

    public static class FileErrorDto {
        private String filename;
        private String error;
        private long size;

        public FileErrorDto() {
        }

        public FileErrorDto(String filename, String error, long size) {
            this.filename = filename;
            this.error = error;
            this.size = size;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }
}

