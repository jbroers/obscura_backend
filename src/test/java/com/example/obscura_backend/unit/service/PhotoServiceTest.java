package com.example.obscura_backend.unit.service;

import com.example.obscura_backend.service.PhotoService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class PhotoServiceTest {

    private PhotoService photoService;
    private final Path tempDir = Paths.get("build/test-uploads");

    @BeforeEach
    void setup() {
        photoService = new PhotoService();
        photoService.setUploadDirPath(tempDir.toString());
        photoService.init();
    }

    @AfterEach
    void cleanup() throws IOException {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
        }
    }

    @Test
    void savesValidImage() throws Exception {
        Path testImage = Paths.get("src/test/resources/test.jpg");
        assertTrue(Files.exists(testImage), "Test image ontbreekt");

        byte[] data = Files.readAllBytes(testImage);
        MockMultipartFile file = new MockMultipartFile("photo", "test.jpg", "image/jpeg", data);
        photoService.savePhoto(file);

        assertTrue(Files.exists(tempDir.resolve("test.jpg")));
    }

    @Test
    void throwsOnInvalidFile() {
        MockMultipartFile file = new MockMultipartFile("photo", "test.txt", "text/plain", "hello".getBytes());
        assertThrows(IllegalArgumentException.class, () -> photoService.savePhoto(file));
    }
}
