package com.example.obscura_backend.unit.service;

import com.example.obscura_backend.model.Photo;
import com.example.obscura_backend.repository.PhotoRepository;
import com.example.obscura_backend.service.PhotoService;
import com.example.obscura_backend.service.RawImageExtractionService;
import com.example.obscura_backend.service.ExifExtractionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PhotoServiceTest {

    @Mock
    private PhotoRepository photoRepository;

    @Mock
    private RawImageExtractionService rawImageExtractionService;

    @Mock
    private ExifExtractionService exifExtractionService;

    @InjectMocks
    private PhotoService photoService;

    private Path tempDir;

    @BeforeEach
    void setup() {
        tempDir = Paths.get("build/test-uploads");
        ReflectionTestUtils.setField(photoService, "uploadDirPath", tempDir.toString());
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
    void savesValidJpegImage() throws Exception {
        Path testImage = Paths.get("src/test/resources/test.jpg");
        assertTrue(Files.exists(testImage), "Test image ontbreekt");

        byte[] data = Files.readAllBytes(testImage);
        MockMultipartFile file = new MockMultipartFile("photo", "test.jpg", "image/jpeg", data);

        Photo mockPhoto = Photo.builder()
                .id(1L)
                .fileName("test.jpg")
                .filePath(tempDir.toString())
                .contentType("image/jpeg")
                .isRaw(false)
                .build();

        doNothing().when(exifExtractionService).extractExifData(any(), any(Photo.class));
        when(photoRepository.save(any(Photo.class))).thenReturn(mockPhoto);

        Photo result = photoService.savePhoto(file);

        assertNotNull(result);
        assertEquals("test.jpg", result.getFileName());
        assertEquals("image/jpeg", result.getContentType());
        assertFalse(result.getIsRaw());
        verify(photoRepository, times(1)).save(any(Photo.class));
        verify(exifExtractionService, times(1)).extractExifData(any(), any(Photo.class));
    }

    @Test
    void throwsOnInvalidFile() {
        MockMultipartFile file = new MockMultipartFile("photo", "test.txt", "text/plain", "hello".getBytes());
        assertThrows(IllegalArgumentException.class, () -> photoService.savePhoto(file));
    }

    @Test
    void throwsOnEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("photo", "empty.jpg", "image/jpeg", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> photoService.savePhoto(file));
        verify(photoRepository, never()).save(any(Photo.class));
    }

    @Test
    void throwsOnNullFile() {
        assertThrows(IllegalArgumentException.class, () -> photoService.savePhoto(null));
        verify(photoRepository, never()).save(any(Photo.class));
    }

    @Test
    void identifiesRawFormats() throws Exception {
        String[] rawExtensions = {"test.CR2", "test.nef", "test.ARW", "test.dng", "test.raf"};

        for (String filename : rawExtensions) {
            byte[] mockImageData = new byte[1024];
            Arrays.fill(mockImageData, (byte) 0xFF);

            MockMultipartFile file = new MockMultipartFile(
                    "photo",
                    filename,
                    "application/octet-stream",
                    mockImageData
            );

            Photo mockPhoto = Photo.builder()
                    .id(1L)
                    .fileName(filename)
                    .isRaw(true)
                    .build();

            when(rawImageExtractionService.extractRawPreview(any(MultipartFile.class)))
                    .thenReturn(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB));
            doNothing().when(exifExtractionService).extractExifData(any(), any(Photo.class));
            when(photoRepository.save(any(Photo.class))).thenReturn(mockPhoto);

            Photo result = photoService.savePhoto(file);
            assertNotNull(result, "Photo should be saved for " + filename);
            assertTrue(result.getIsRaw(), "File " + filename + " should be identified as RAW");

            reset(photoRepository, rawImageExtractionService, exifExtractionService);
        }
    }

    @Test
    void getAllPhotosReturnsAllPhotos() {
        Photo photo1 = Photo.builder().id(1L).fileName("photo1.jpg").build();
        Photo photo2 = Photo.builder().id(2L).fileName("photo2.jpg").build();

        List<Photo> mockPhotos = Arrays.asList(photo1, photo2);
        when(photoRepository.findAll()).thenReturn(mockPhotos);

        List<Photo> result = photoService.getAllPhotos();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("photo1.jpg", result.get(0).getFileName());
        assertEquals("photo2.jpg", result.get(1).getFileName());
        verify(photoRepository, times(1)).findAll();
    }

    @Test
    void getAllPhotosReturnsEmptyListWhenNoPhotos() {
        when(photoRepository.findAll()).thenReturn(Arrays.asList());

        List<Photo> result = photoService.getAllPhotos();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(photoRepository, times(1)).findAll();
    }
}
