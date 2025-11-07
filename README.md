# Obscura Backend

Spring Boot backend for Obscura Photography Management System.

## Features
- Photo upload (JPEG, RAW formats: ARW, CR2, NEF, DNG, etc.)
- EXIF metadata extraction
- Automatic image compression and optimization
- Photo statistics and insights

## Prerequisites
- Java 17+
- PostgreSQL 15+
- Gradle 8+

## Environment Variables

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/obscura
SPRING_DATASOURCE_USERNAME=username
SPRING_DATASOURCE_PASSWORD=password
DB_NAME=obscura
SPRING_PROFILES_ACTIVE=dev
PHOTO_UPLOAD_DIR=./uploads
BACKEND_URL=http://localhost:8080
```

## Local Development

### 1. Start PostgreSQL
```bash
docker run -d \
  --name obscura-db \
  -e POSTGRES_DB=obscura \
  -e POSTGRES_USER=username \
  -e POSTGRES_PASSWORD=password \
  -p 5432:5432 \
  postgres:15
```

### 2. Set environment variables
```bash
export BACKEND_URL=http://localhost:8080
export PHOTO_UPLOAD_DIR=./uploads
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/obscura
export SPRING_DATASOURCE_USERNAME=username
export SPRING_DATASOURCE_PASSWORD=password
```

For Windows PowerShell:
```powershell
$env:BACKEND_URL="http://localhost:8080"
$env:PHOTO_UPLOAD_DIR="./uploads"
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/obscura"
$env:SPRING_DATASOURCE_USERNAME="username"
$env:SPRING_DATASOURCE_PASSWORD="password"
```

Or use IntelliJ Run Configuration environment variables.

### 3. Run the application
```bash
./gradlew bootRun
```

The API will be available at `http://localhost:8080`

## Docker Deployment

### Update docker-compose.yml

Add `BACKEND_URL` to backend service:

```yaml
backend:
  environment:
    SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE}
    SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL}
    SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD}
    SPRING_DATASOURCE_USERNAME: ${SPRING_DATASOURCE_USERNAME}
    PHOTO_UPLOAD_DIR: ${PHOTO_UPLOAD_DIR}
    BACKEND_URL: ${BACKEND_URL}
```

### Build and run
```bash
docker-compose up --build
```

## API Endpoints

### Photo Upload
```
POST /photos/upload
Content-Type: multipart/form-data
Field: file (JPEG, ARW, CR2, NEF, DNG, etc.)
```

### Get All Photos
```
GET /photos
Returns: Array of PhotoMetadataDto with EXIF data and URLs
```

### Get Statistics
```
GET /statistics
Returns: PhotoStatisticsDto with insights and recommendations
```

## Supported File Formats

### Standard Formats
- JPEG/JPG
- PNG
- TIFF

### RAW Formats
- Sony ARW
- Canon CR2, CR3
- Nikon NEF
- Adobe DNG
- Olympus ORF
- Fujifilm RAF
- Panasonic RW2

## Architecture

See documentation in `docs/`:
- `photo-upload-sequence-diagram.puml` - Upload flow visualization
- `statistics-service-sequence-diagram.puml` - Statistics flow

## Testing

```bash
./gradlew test

./gradlew test jacocoTestReport
```

## License
TBD
