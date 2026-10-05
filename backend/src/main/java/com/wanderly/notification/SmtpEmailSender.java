package com.wanderly.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Real email over SMTP. Locally that's Mailpit (inbox UI at http://localhost:8025); in production
 * point MAIL_HOST/MAIL_USERNAME/MAIL_PASSWORD at Amazon SES, Gmail, SendGrid, etc.
 */
@Component
@ConditionalOnProperty(name = "wanderly.mail.enabled", havingValue = "true", matchIfMissing = true)
public class SmtpEmailSender implements NotificationSender {

    private final JavaMailSender mail;
    private final String from;

    public SmtpEmailSender(JavaMailSender mail, @Value("${wanderly.mail.from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        send(toEmail, subject, body, null);
    }

    @Override
    public void send(String toEmail, String subject, String body, String messageId) {
        MimeMessage message = mail.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body, false);
            if (messageId != null) {
                message.setHeader("Message-ID", messageId);   // JavaMailSenderImpl preserves an explicit Message-ID
            }
        } catch (MessagingException e) {
            throw new MailPreparationException("Could not build email to " + toEmail, e);
        }
        mail.send(message);
    }
}
