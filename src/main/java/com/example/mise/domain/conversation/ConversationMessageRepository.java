package com.example.mise.domain.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    List<ConversationMessage> findAllByOrderByCreatedAtAscIdAsc();

    Optional<ConversationMessage> findByMessageId(String messageId);
}
