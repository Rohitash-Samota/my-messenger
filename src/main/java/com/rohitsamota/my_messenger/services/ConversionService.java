package com.rohitsamota.my_messenger.services;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.ConversionCursorResponseDto;
import com.rohitsamota.my_messenger.dto.ConversionResponseDto;
import com.rohitsamota.my_messenger.entity.ConversationParticipant;
import com.rohitsamota.my_messenger.entity.Conversion;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service("conversionService")
public class ConversionService {
    private final ConversionRepoI conversionRepository;
    private final ConversationParticipantRepository participantRepository;
    private final UserInfoRepository userRepository;

    public ConversionService(
            ConversionRepoI conversionRepository,
            ConversationParticipantRepository participantRepository,
            UserInfoRepository userRepository) {
        this.conversionRepository = conversionRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ConversionCursorResponseDto listForUser(
            String email,
            String cursor,
            boolean archived,
            int limit) {
        Long userId = currentUserId(email);
        Cursor decodedCursor = decodeCursor(cursor);
        var pageRequest = PageRequest.of(0, limit + 1);
        List<Conversion> fetched = conversionRepository.findInboxPage(
                userId,
                archived,
                decodedCursor == null ? null : decodedCursor.pinned(),
                decodedCursor == null ? null : decodedCursor.activityAt(),
                decodedCursor == null ? null : decodedCursor.id(),
                pageRequest);

        boolean hasMore = fetched.size() > limit;
        List<Conversion> page = hasMore ? fetched.subList(0, limit) : fetched;
        Map<Long, ConversationParticipant> stateByConversion = participantStates(userId, page);
        List<ConversionResponseDto> items = page.stream()
                .map(conversion -> toResponse(
                        conversion,
                        requiredState(stateByConversion, conversion.getId())))
                .toList();
        String nextCursor = hasMore ? encodeCursor(
                page.get(page.size() - 1),
                requiredState(stateByConversion, page.get(page.size() - 1).getId())) : null;

        return new ConversionCursorResponseDto(items, nextCursor, hasMore);
    }

    @Transactional
    public ConversionResponseDto setPinned(String email, Long conversionId, boolean pinned) {
        Long userId = currentUserId(email);
        Conversion conversion = findVisibleConversion(conversionId, userId);
        ConversationParticipant participant = findActiveParticipant(conversionId, userId);
        participant.setPinned(pinned);
        syncLegacyOwnerFlags(conversion, participant, userId);
        return toResponse(conversion, participant);
    }

    @Transactional
    public ConversionResponseDto setArchived(String email, Long conversionId, boolean archived) {
        Long userId = currentUserId(email);
        Conversion conversion = findVisibleConversion(conversionId, userId);
        ConversationParticipant participant = findActiveParticipant(conversionId, userId);
        participant.setArchived(archived);
        syncLegacyOwnerFlags(conversion, participant, userId);
        return toResponse(conversion, participant);
    }

    private Conversion findVisibleConversion(Long conversionId, Long userId) {
        return conversionRepository.findVisibleById(conversionId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversion not found"));
    }

    private ConversationParticipant findActiveParticipant(Long conversionId, Long userId) {
        return participantRepository
                .findByConversionIdAndUserIdAndDeletedAtIsNull(conversionId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversion not found"));
    }

    private void syncLegacyOwnerFlags(
            Conversion conversion,
            ConversationParticipant participant,
            Long userId) {
        if (userId.equals(conversion.getUserId())) {
            conversion.setIsPin(participant.isPinned());
            conversion.setIsArchive(participant.isArchived());
        }
    }

    private Map<Long, ConversationParticipant> participantStates(
            Long userId,
            List<Conversion> conversions) {
        if (conversions.isEmpty()) {
            return Map.of();
        }
        List<Long> conversionIds = conversions.stream().map(Conversion::getId).toList();
        return participantRepository
                .findByUserIdAndConversionIdInAndDeletedAtIsNull(userId, conversionIds)
                .stream()
                .collect(Collectors.toMap(
                        ConversationParticipant::getConversionId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private ConversationParticipant requiredState(
            Map<Long, ConversationParticipant> stateByConversion,
            Long conversionId) {
        ConversationParticipant participant = stateByConversion.get(conversionId);
        if (participant == null) {
            throw new IllegalStateException("Missing conversation participant projection");
        }
        return participant;
    }

    private String encodeCursor(
            Conversion conversion,
            ConversationParticipant participant) {
        String cursorValue = (participant.isPinned() ? "1" : "0")
                + "|" + conversion.getLastActivityAt()
                + "|" + conversion.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursorValue.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(encodedCursor),
                    StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", -1);
            if (values.length != 3 || (!values[0].equals("0") && !values[0].equals("1"))) {
                throw new IllegalArgumentException("Invalid cursor contents");
            }
            LocalDateTime activityAt = LocalDateTime.parse(values[1]);
            long id = Long.parseLong(values[2]);
            if (id <= 0) {
                throw new IllegalArgumentException("Cursor ID must be positive");
            }
            return new Cursor(values[0].equals("1"), activityAt, id);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid conversion cursor", exception);
        }
    }

    private ConversionResponseDto toResponse(
            Conversion conversion,
            ConversationParticipant participant) {
        return new ConversionResponseDto(
                conversion.getId(),
                conversion.getClientId(),
                conversion.getConversionType(),
                participant.isPinned(),
                participant.isArchived(),
                participant.getUnreadCount(),
                conversion.getLastMessageId(),
                conversion.getLastActivityAt());
    }

    private Long currentUserId(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(user -> user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private record Cursor(boolean pinned, LocalDateTime activityAt, Long id) {
    }
}
