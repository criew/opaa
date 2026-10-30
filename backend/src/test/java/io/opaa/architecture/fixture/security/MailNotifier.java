package io.opaa.architecture.fixture.security;

import java.util.function.Consumer;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Hands a mail to an SMTP server, through a method reference. */
public class MailNotifier {

  public Consumer<SimpleMailMessage> sender(JavaMailSender mailSender) {
    return mailSender::send;
  }
}
