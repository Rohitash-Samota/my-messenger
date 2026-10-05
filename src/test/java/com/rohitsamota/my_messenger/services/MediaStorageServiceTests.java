package com.rohitsamota.my_messenger.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.entity.Conversion;
import com.rohitsamota.my_messenger.entity.Message;
import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.enums.ConversionType;
import com.rohitsamota.my_messenger.enums.MessageType;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.GroupMemberRepository;
import com.rohitsamota.my_messenger.repo.MessageRepoI;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@ExtendWith(MockitoExtension.class)
class MediaStorageServiceTests {
    @TempDir
    Path storagePath;

    @Mock
    private UserInfoRepository userRepository;
    @Mock
    private ConversionRepoI conversionRepository;
    @Mock
    private ConversationParticipantRepository participantRepository;
    @Mock
    private GroupMemberRepository groupMemberRepository;
    @Mock
    private MessageRepoI messageRepository;

    private MediaStorageService mediaStorageService;
    private User sender;
    private Conversion conversion;

    @BeforeEach
    void setUp() {
        mediaStorageService = new MediaStorageService(
                storagePath.toString(),
                1024,
                2048,
                1024,
                userRepository,
                conversionRepository,
                participantRepository,
                groupMemberRepository,
                messageRepository);
        sender = new User();
        sender.setId(1L);
        sender.setEmail("sender@example.com");
        conversion = new Conversion();
        conversion.setId(10L);
        conversion.setUserId(1L);
        conversion.setClientId(2L);
        conversion.setConversionType(ConversionType.INDIVIDUAL);
    }

    @Test
    void storesWithGeneratedFilenameAndServesOnlyThroughAuthorizedLookup() throws Exception {
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00, 0x01};
        authorizeSender();
        var response = mediaStorageService.store(
                sender.getEmail(),
                10L,
                MessageType.IMAGE,
                new MockMultipartFile("file", "../../avatar.jpg", "image/jpeg", jpeg));

        assertEquals("avatar.jpg", response.originalFilename());
        assertEquals("/v1/api/media/" + response.id(), response.url());
        assertFalse(Files.exists(storagePath.resolve("avatar.jpg")));
        assertEquals(response.id(), mediaStorageService.validateMessageReference(
                10L, MessageType.IMAGE, response.url()));
        ResponseStatusException wrongType = assertThrows(
                ResponseStatusException.class,
                () -> mediaStorageService.validateMessageReference(
                        10L, MessageType.VIDEO, response.url()));
        assertEquals(HttpStatus.BAD_REQUEST, wrongType.getStatusCode());
        var download = mediaStorageService.load(sender.getEmail(), response.id());
        assertArrayEquals(jpeg, download.resource().getInputStream().readAllBytes());
        assertEquals("image/jpeg", download.contentType());
    }

    @Test
    void deniesDownloadWhenEveryDatabaseReferenceIsDeleted() {
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        authorizeSender();
        var response = mediaStorageService.store(
                sender.getEmail(),
                10L,
                MessageType.IMAGE,
                new MockMultipartFile("file", "avatar.jpg", "image/jpeg", jpeg));
        Message firstDeletedReference = mediaReference(response.id());
        firstDeletedReference.softDelete(LocalDateTime.of(2026, 10, 5, 9, 0));
        Message secondDeletedReference = mediaReference(response.id());
        secondDeletedReference.softDelete(LocalDateTime.of(2026, 10, 5, 9, 1));
        when(messageRepository.findByConversionIdAndMediaId(10L, response.id()))
                .thenReturn(List.of(firstDeletedReference, secondDeletedReference));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> mediaStorageService.load(sender.getEmail(), response.id()));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void allowsSharedMediaWhileAnyDatabaseReferenceIsLive() throws Exception {
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        authorizeSender();
        var response = mediaStorageService.store(
                sender.getEmail(),
                10L,
                MessageType.IMAGE,
                new MockMultipartFile("file", "avatar.jpg", "image/jpeg", jpeg));
        Message deletedReference = mediaReference(response.id());
        deletedReference.softDelete(LocalDateTime.of(2026, 10, 5, 9, 0));
        Message liveReference = mediaReference(response.id());
        when(messageRepository.findByConversionIdAndMediaId(10L, response.id()))
                .thenReturn(List.of(deletedReference, liveReference));

        var download = mediaStorageService.load(sender.getEmail(), response.id());

        assertArrayEquals(jpeg, download.resource().getInputStream().readAllBytes());
    }

    @Test
    void rejectsAFileWhoseSignatureDoesNotMatchItsContentType() {
        authorizeSender();
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> mediaStorageService.store(
                        sender.getEmail(),
                        10L,
                        MessageType.IMAGE,
                        new MockMultipartFile(
                                "file", "fake.png", "image/png", "not-a-png".getBytes())));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatusCode());
    }

    @Test
    void hidesMediaFromUsersOutsideTheConversation() {
        authorizeSender();
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        var response = mediaStorageService.store(
                sender.getEmail(),
                10L,
                MessageType.IMAGE,
                new MockMultipartFile("file", "avatar.jpg", "image/jpeg", jpeg));
        when(participantRepository.existsByConversionIdAndUserIdAndDeletedAtIsNull(10L, 1L))
                .thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> mediaStorageService.load(sender.getEmail(), response.id()));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void rejectsRemoteOrNonCanonicalMediaUrlsForMessages() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> mediaStorageService.validateMessageReference(
                        10L, MessageType.IMAGE, "https://cdn.example.com/file.jpg"));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private void authorizeSender() {
        when(userRepository.findByEmailIgnoreCase(sender.getEmail()))
                .thenReturn(Optional.of(sender));
        when(conversionRepository.findById(10L)).thenReturn(Optional.of(conversion));
        when(participantRepository.existsByConversionIdAndUserIdAndDeletedAtIsNull(10L, 1L))
                .thenReturn(true);
    }

    private Message mediaReference(String mediaId) {
        Message message = new Message();
        message.setConversionId(10L);
        message.setContent("/v1/api/media/" + mediaId);
        message.setMediaId(mediaId);
        message.setMessageType(MessageType.IMAGE);
        return message;
    }
}
