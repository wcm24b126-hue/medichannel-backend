package com.medichannel.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.entity.ChatMessage;
import com.medichannel.repository.ChatMessageRepository;
import com.medichannel.security.PatientDataProtectionService;

/**
 * Ensures backward compatibility and data confidentiality by encrypting
 * legacy plaintext chat records with AES-256-GCM.
 *
 * Idempotent: runs at startup, identifies unencrypted records (missing the 'v1:' prefix),
 * encrypts them, and commits. Preserves message IDs, timestamps, sender IDs, and relationships.
 */
@Component
public class ChatMessageEncryptionMigrationRunner implements ApplicationRunner {

    private static final Logger logger =
            LoggerFactory.getLogger(ChatMessageEncryptionMigrationRunner.class);

    private final ChatMessageRepository chatMessageRepository;
    private final PatientDataProtectionService dataProtectionService;

    public ChatMessageEncryptionMigrationRunner(
            ChatMessageRepository chatMessageRepository,
            PatientDataProtectionService dataProtectionService
    ) {
        this.chatMessageRepository = chatMessageRepository;
        this.dataProtectionService = dataProtectionService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ChatMessage> unencrypted = chatMessageRepository.findAll().stream()
                .filter(m -> m.getContent() != null && !m.getContent().startsWith("v1:"))
                .toList();

        if (unencrypted.isEmpty()) {
            logger.info("Chat encryption audit: all consultation chat messages are encrypted under AES-256-GCM.");
            return;
        }

        logger.info("Migrating {} legacy plaintext consultation chat records to AES-256-GCM ciphertext...", unencrypted.size());

        int count = 0;
        for (ChatMessage msg : unencrypted) {
            String plaintext = msg.getContent();
            String ciphertext = dataProtectionService.encrypt(plaintext);
            msg.setContent(ciphertext);
            count++;
        }

        chatMessageRepository.saveAllAndFlush(unencrypted);

        logger.info("Chat encryption migration complete: successfully encrypted {} consultation chat messages.", count);
    }
}
