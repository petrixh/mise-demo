package com.example.mise.domain.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    List<ConversationMessage> findAllByOrderByCreatedAtAscIdAsc();

    Optional<ConversationMessage> findByMessageId(String messageId);

    /**
     * Returns the last {@code n} messages in reverse-chronological order (most recent first).
     * Callers must reverse the result to get chronological order.
     */
    @Query("SELECT m FROM ConversationMessage m ORDER BY m.createdAt DESC, m.id DESC")
    List<ConversationMessage> findLastN(Pageable pageable);
}
