package com.wander.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.wander.config.WanderProperties;

/**
 * {@link MailClient} over whatever SMTP relay the operator configured.
 *
 * <p>There is deliberately <b>no provider in this class</b>. Brevo, Gmail,
 * Resend and a Postfix on the next rack all speak SMTP, so the code needs a
 * host, a port and credentials and nothing else — no SDK, no API key handling,
 * no second code path to test. Changing provider is four lines in {@code .env}
 * and a restart. That is the same argument the map tiles and the geocoder are
 * settings rather than constants: which service an instance leans on is the
 * operator's decision, not this project's.
 *
 * <p>The bean exists whether or not mail is switched on, which is why
 * {@link JavaMailSender} arrives as an {@link ObjectProvider}: with
 * {@code spring.mail.host} unset there is no sender bean at all, and injecting
 * it directly would fail the context on every instance that is not sending mail
 * — which is most of them, since {@code wander.mail.enabled} defaults to false.
 * One always-present bean also means the tests have exactly one thing to
 * replace.
 */
@Component
public class SmtpMailClient implements MailClient {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailClient.class);

    private final WanderProperties properties;
    private final ObjectProvider<JavaMailSender> senders;

    public SmtpMailClient(WanderProperties properties, ObjectProvider<JavaMailSender> senders) {
        this.properties = properties;
        this.senders = senders;
    }

    /**
     * Switched on, addressed from somewhere, and with a relay actually
     * configured. All three, because any one of them missing produces the same
     * useless outcome — a "check your mail" page for a message that was never
     * sent — and the point of asking is to not offer the flow at all.
     */
    @Override
    public boolean enabled() {
        return properties.mail().enabled()
                && !properties.mail().from().isBlank()
                && senders.getIfAvailable() != null;
    }

    @Override
    public boolean send(String to, String subject, String body) {
        JavaMailSender sender = senders.getIfAvailable();
        if (!properties.mail().enabled() || sender == null || properties.mail().from().isBlank()) {
            // Not a warning: this is the ordinary state of an instance that does
            // not send mail, and a warning on every call would train somebody to
            // ignore the log.
            log.debug("Mail is not configured; not sending \"{}\"", subject);
            return false;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromHeader());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        try {
            sender.send(message);
            // The subject, never the recipient or the body: this log is read by
            // an operator debugging a relay, and a password reset link in a log
            // file is the thing the whole feature is built to avoid writing down.
            log.info("Sent \"{}\"", subject);
            return true;
        } catch (Exception ex) {
            // Swallowed on purpose — see MailClient. A relay outage costs a
            // message, not a 500 on somebody's browser, and the caller must not
            // be told the difference anyway.
            log.warn("Could not send \"{}\": {}", subject, ex.getMessage());
            return false;
        }
    }

    /**
     * {@code wander <noreply@example.com>} when a name is set, the bare address
     * otherwise. Quoted, because a display name containing a comma or a full stop
     * is not a legal unquoted atom and some relays reject the whole message
     * rather than tidying it up.
     */
    private String fromHeader() {
        String address = properties.mail().from();
        String name = properties.mail().fromName();
        return name.isBlank() ? address : "\"" + name.replace("\"", "") + "\" <" + address + ">";
    }
}
