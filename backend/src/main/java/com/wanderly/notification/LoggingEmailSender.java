package com.wanderly.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Used when MAIL_ENABLED=false: prints emails (including verification codes) to the log. */
@Component
@ConditionalOnProperty(name = "wanderly.mail.enabled", havingValue = "false")
public class LoggingEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info("[mock email] to={} subject=\"{}\"\n{}", toEmail, subject, body);
    }
}
