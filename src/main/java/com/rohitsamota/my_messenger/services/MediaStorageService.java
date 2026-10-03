package com.rohitsamota.my_messenger.services;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.MediaUploadResponseDto;
import com.rohitsamota.my_messenger.entity.Conversion;
import com.rohitsamota.my_messenger.enums.ConversionType;
import com.rohitsamota.my_messenger.enums.MessageType;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.GroupMemberRepository;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service
public class MediaStorageService {
    private static final int HEADER_BYTES = 32;
    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile(
            "^/v1/api/media/([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$");
    private static final Set<MessageType> SUPPORTED_TYPES = Set.of(
            MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO);
    private static final Map<MessageType, Set<String>> ALLOWED_CONTENT_TYPES = Map.of(
            MessageType.IMAGE, Set.of(
                    "image/jpeg", "image/png", "image/gif", "image/webp"),
            MessageType.VIDEO, Set.of(
                    "video/mp4", "video/webm", "video/quicktime"),
            MessageType.AUDIO, Set.of(
                    "audio/mpeg", "audio/ogg", "audio/wav", "audio/x-wav",
                    "audio/webm", "audio/mp4", "audio/x-m4a", "audio/aac"));
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("video/webm", "webm"),
            Map.entry("video/quicktime", "mov"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/ogg", "ogg"),
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/x-wav", "wav"),
            Map.entry("audio/webm", "webm"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/x-m4a", "m4a"),
            Map.entry("audio/aac", "aac"));

    private final Path filesDirectory;
    private final Path metadataDirectory;
    private final long maxImageBytes;
    private final long maxVideoBytes;
    private final long maxAudioBytes;
    private final UserInfoRepository userRepository;
    private final ConversionRepoI conversionRepository;
    private final ConversationParticipantRepository participantRepository;
    private final GroupMemberRepository groupMemberRepository;

    public MediaStorageService(
            @Value("${app.media.storage-path:./local-media}") String storagePath,
            @Value("${app.media.max-image-bytes:10485760}") long maxImageBytes,
            @Value("${app.media.max-video-bytes:104857600}") long maxVideoBytes,
            @Value("${app.media.max-audio-bytes:26214400}") long maxAudioBytes,
            UserInfoRepository userRepository,
            ConversionRepoI conversionRepository,
            ConversationParticipantRepository participantRepository,
            GroupMemberRepository groupMemberRepository) {
        Path root = Path.of(storagePath).toAbsolutePath().normalize();
        this.filesDirectory = root.resolve("files");
        this.metadataDirectory = root.resolve("metadata");
        this.maxImageBytes = requirePositive(maxImageBytes, "maxImageBytes");
        this.maxVideoBytes = requirePositive(maxVideoBytes, "maxVideoBytes");
        this.maxAudioBytes = requirePositive(maxAudioBytes, "maxAudioBytes");
        this.userRepository = userRepository;
        this.conversionRepository = conversionRepository;
        this.participantRepository = participantRepository;
        this.groupMemberRepository = groupMemberRepository;
    }

    @Transactional(readOnly = true)
    public MediaUploadResponseDto store(
            String email,
            Long conversationId,
            MessageType messageType,
            MultipartFile file) {
        Long userId = currentUserId(email);
        requireConversationMember(conversationId, userId);
        if (!SUPPORTED_TYPES.contains(messageType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "messageType must be IMAGE, VIDEO, or AUDIO");
        }
        if (file == null || file.isEmpty() || file.getSize() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Media file is required");
        }

        String contentType = normalizedContentType(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.get(messageType).contains(contentType)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Content type is not allowed for " + messageType.name());
        }
        long maximumBytes = maximumBytes(messageType);
        if (file.getSize() > maximumBytes) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Media file exceeds the configured size limit");
        }

        String id = UUID.randomUUID().toString();
        String extension = EXTENSIONS.get(contentType);
        String storedFilename = id + "." + extension;
        String originalFilename = safeOriginalFilename(file.getOriginalFilename(), extension);
        Instant createdAt = Instant.now();
        Path temporaryFile = null;
        Path finalFile = filesDirectory.resolve(storedFilename);
        Path temporaryMetadata = null;
        Path finalMetadata = metadataDirectory.resolve(id + ".properties");
        try {
            Files.createDirectories(filesDirectory);
            Files.createDirectories(metadataDirectory);
            temporaryFile = Files.createTempFile(filesDirectory, ".upload-", ".tmp");
            long copiedBytes;
            try (InputStream rawInput = file.getInputStream();
                    BufferedInputStream input = new BufferedInputStream(rawInput);
                    OutputStream output = Files.newOutputStream(
                            temporaryFile,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                input.mark(HEADER_BYTES + 1);
                byte[] header = input.readNBytes(HEADER_BYTES);
                input.reset();
                if (!matchesSignature(contentType, header)) {
                    throw new ResponseStatusException(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "File content does not match its declared content type");
                }
                copiedBytes = copyWithLimit(input, output, maximumBytes);
            }
            if (copiedBytes < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Media file is empty");
            }
            moveAtomically(temporaryFile, finalFile);
            temporaryFile = null;

            Properties metadata = new Properties();
            metadata.setProperty("id", id);
            metadata.setProperty("conversationId", conversationId.toString());
            metadata.setProperty("uploaderUserId", userId.toString());
            metadata.setProperty("messageType", messageType.name());
            metadata.setProperty("storedFilename", storedFilename);
            metadata.setProperty("originalFilename", originalFilename);
            metadata.setProperty("contentType", contentType);
            metadata.setProperty("size", Long.toString(copiedBytes));
            metadata.setProperty("createdAt", createdAt.toString());
            temporaryMetadata = Files.createTempFile(metadataDirectory, ".metadata-", ".tmp");
            try (OutputStream output = Files.newOutputStream(
                    temporaryMetadata,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                metadata.store(output, null);
            }
            moveAtomically(temporaryMetadata, finalMetadata);
            temporaryMetadata = null;

            return new MediaUploadResponseDto(
                    id,
                    conversationId,
                    messageType,
                    "/v1/api/media/" + id,
                    originalFilename,
                    contentType,
                    copiedBytes,
                    createdAt);
        } catch (ResponseStatusException exception) {
            deleteQuietly(temporaryFile);
            deleteQuietly(temporaryMetadata);
            deleteQuietly(finalFile);
            deleteQuietly(finalMetadata);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(temporaryFile);
            deleteQuietly(temporaryMetadata);
            deleteQuietly(finalFile);
            deleteQuietly(finalMetadata);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Media storage is unavailable",
                    exception);
        }
    }

    @Transactional(readOnly = true)
    public MediaDownload load(String email, String mediaId) {
        Long userId = currentUserId(email);
        String normalizedId = normalizedMediaId(mediaId);
        Properties metadata = readMetadata(normalizedId);
        Long conversationId = parseLong(metadata, "conversationId");
        requireConversationMember(conversationId, userId);

        Path file = storedFile(normalizedId, metadata);

        return new MediaDownload(
                new FileSystemResource(file),
                requiredMetadata(metadata, "originalFilename"),
                requiredMetadata(metadata, "contentType"),
                parseLong(metadata, "size"));
    }

    public void validateMessageReference(
            Long conversationId,
            MessageType messageType,
            String content) {
        if (!SUPPORTED_TYPES.contains(messageType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only IMAGE, VIDEO, and AUDIO may reference uploaded media");
        }
        Matcher matcher = MEDIA_URL_PATTERN.matcher(content == null ? "" : content);
        if (!matcher.matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Media messages must reference a local uploaded media URL");
        }

        String mediaId = normalizedMediaId(matcher.group(1));
        Properties metadata;
        try {
            metadata = readMetadata(mediaId);
            storedFile(mediaId, metadata);
        } catch (ResponseStatusException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Uploaded media does not exist",
                    exception);
        }
        if (!conversationId.equals(parseLong(metadata, "conversationId"))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Uploaded media belongs to a different conversation");
        }
        MessageType storedType;
        try {
            storedType = MessageType.valueOf(requiredMetadata(metadata, "messageType"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Stored media metadata is invalid", exception);
        }
        if (storedType != messageType) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Uploaded media type does not match the message type");
        }
    }

    private Properties readMetadata(String mediaId) {
        Path metadataFile = metadataDirectory.resolve(mediaId + ".properties").normalize();
        if (!metadataFile.startsWith(metadataDirectory) || !Files.isRegularFile(metadataFile)) {
            throw mediaNotFound();
        }
        Properties metadata = new Properties();
        try (InputStream input = Files.newInputStream(metadataFile)) {
            metadata.load(input);
            return metadata;
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Media storage is unavailable",
                    exception);
        }
    }

    private Path storedFile(String mediaId, Properties metadata) {
        String storedFilename = requiredMetadata(metadata, "storedFilename");
        if (!storedFilename.matches("[0-9a-f-]{36}\\.[a-z0-9]{2,5}")
                || !storedFilename.startsWith(mediaId + ".")) {
            throw new IllegalStateException("Stored media metadata contains an invalid filename");
        }
        Path file = filesDirectory.resolve(storedFilename).normalize();
        if (!file.startsWith(filesDirectory) || !Files.isRegularFile(file)) {
            throw mediaNotFound();
        }
        return file;
    }

    private void requireConversationMember(Long conversationId, Long userId) {
        Conversion conversion = conversionRepository.findById(conversationId)
                .filter(item -> item.getDeletedAt() == null)
                .orElseThrow(this::mediaNotFound);
        boolean member = conversion.getConversionType() == ConversionType.INDIVIDUAL
                ? userId.equals(conversion.getUserId()) || userId.equals(conversion.getClientId())
                : conversion.getConversionType() == ConversionType.GROUP
                        && groupMemberRepository.existsByGroupIdAndUserIdAndDeletedAtIsNull(
                                conversion.getClientId(), userId);
        if (!member || !participantRepository
                .existsByConversionIdAndUserIdAndDeletedAtIsNull(conversationId, userId)) {
            throw mediaNotFound();
        }
    }

    private Long currentUserId(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(user -> user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private String normalizedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "A content type is required");
        }
        return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private long maximumBytes(MessageType messageType) {
        return switch (messageType) {
            case IMAGE -> maxImageBytes;
            case VIDEO -> maxVideoBytes;
            case AUDIO -> maxAudioBytes;
            default -> throw new IllegalArgumentException("Unsupported media message type");
        };
    }

    private long copyWithLimit(InputStream input, OutputStream output, long maximumBytes)
            throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Media file exceeds the configured size limit");
            }
            output.write(buffer, 0, read);
        }
        return total;
    }

    private boolean matchesSignature(String contentType, byte[] header) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(header, 0xff, 0xd8, 0xff);
            case "image/png" -> startsWith(header, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "image/gif" -> asciiAt(header, 0, "GIF87a") || asciiAt(header, 0, "GIF89a");
            case "image/webp" -> asciiAt(header, 0, "RIFF") && asciiAt(header, 8, "WEBP");
            case "video/mp4", "video/quicktime", "audio/mp4", "audio/x-m4a" ->
                    asciiAt(header, 4, "ftyp");
            case "video/webm", "audio/webm" -> startsWith(header, 0x1a, 0x45, 0xdf, 0xa3);
            case "audio/ogg" -> asciiAt(header, 0, "OggS");
            case "audio/wav", "audio/x-wav" ->
                    asciiAt(header, 0, "RIFF") && asciiAt(header, 8, "WAVE");
            case "audio/mpeg" -> asciiAt(header, 0, "ID3")
                    || (header.length >= 2
                        && unsigned(header[0]) == 0xff
                        && (unsigned(header[1]) & 0xe0) == 0xe0);
            case "audio/aac" -> header.length >= 2
                    && unsigned(header[0]) == 0xff
                    && (unsigned(header[1]) & 0xf6) == 0xf0;
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (unsigned(bytes[index]) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean asciiAt(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (unsigned(bytes[offset + index]) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private String safeOriginalFilename(String originalFilename, String extension) {
        String value = originalFilename == null ? "" : originalFilename;
        value = value.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .strip();
        if (value.isBlank() || value.equals(".") || value.equals("..")) {
            value = "media." + extension;
        }
        return value.length() <= 180 ? value : value.substring(0, 180);
    }

    private String normalizedMediaId(String mediaId) {
        try {
            String normalized = UUID.fromString(mediaId).toString();
            if (!normalized.equals(mediaId.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return normalized;
        } catch (RuntimeException exception) {
            throw mediaNotFound();
        }
    }

    private String requiredMetadata(Properties metadata, String key) {
        String value = metadata.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Stored media metadata is incomplete");
        }
        return value;
    }

    private Long parseLong(Properties metadata, String key) {
        try {
            long value = Long.parseLong(requiredMetadata(metadata, key));
            if (value < 1) {
                throw new NumberFormatException("Value must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Stored media metadata is invalid", exception);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup after a rejected or failed upload.
        }
    }

    private long requirePositive(long value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private ResponseStatusException mediaNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found");
    }

    public record MediaDownload(
            Resource resource,
            String originalFilename,
            String contentType,
            long size) {
    }
}
