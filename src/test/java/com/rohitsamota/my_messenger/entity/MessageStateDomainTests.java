package com.rohitsamota.my_messenger.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.rohitsamota.my_messenger.enums.MessageStatus;

class MessageStateDomainTests {

    @Test
    void receiptTransitionsAreMonotonicAndReadImpliesDelivered() {
        MessageReceipt receipt = new MessageReceipt(10L, 20L);
        LocalDateTime readAt = LocalDateTime.of(2026, 10, 3, 12, 0);

        assertTrue(receipt.markRead(readAt));
        assertEquals(MessageStatus.READ, receipt.getStatus());
        assertEquals(readAt, receipt.getDeliveredAt());
        assertEquals(readAt, receipt.getReadAt());

        assertFalse(receipt.markDelivered(readAt.plusMinutes(1)));
        assertEquals(MessageStatus.READ, receipt.getStatus());
        assertEquals(readAt, receipt.getDeliveredAt());
    }

    @Test
    void receiptRejectsFailedAsRecipientState() {
        MessageReceipt receipt = new MessageReceipt(10L, 20L);

        assertThrows(
                IllegalArgumentException.class,
                () -> receipt.advanceStatus(MessageStatus.FAILED, LocalDateTime.now()));
    }

    @Test
    void participantWatermarksNeverMoveBackwards() {
        ConversationParticipant participant = new ConversationParticipant(1L, 2L);
        participant.incrementUnread();
        participant.incrementUnread();

        participant.recordDeliveredThrough(8L);
        participant.recordDeliveredThrough(7L);
        participant.recordReadThrough(8L, 0L);
        participant.recordReadThrough(6L, 0L);

        assertEquals(8L, participant.getLastDeliveredMessageId());
        assertEquals(8L, participant.getLastReadMessageId());
        assertEquals(0L, participant.getUnreadCount());
    }

    @Test
    void rejoinReactivatesTheUniqueParticipantWithoutDiscardingReceiptState() {
        ConversationParticipant participant = new ConversationParticipant(1L, 2L);
        participant.recordReadThrough(5L, 3L);
        participant.leave(LocalDateTime.of(2026, 10, 2, 12, 0));
        participant.hide(LocalDateTime.of(2026, 10, 2, 12, 0));

        participant.reactivate(LocalDateTime.of(2026, 10, 3, 12, 0));

        assertTrue(participant.isActive());
        assertFalse(participant.isHidden());
        assertEquals(5L, participant.getLastReadMessageId());
        assertEquals(3L, participant.getUnreadCount());
        assertNotNull(participant.getJoinedAt());
    }
}
