package com.wander.mail;

/**
 * The one place this application talks to an SMTP relay.
 *
 * It is the same shape as {@code GeocoderClient}, {@code EnrichmentClient} and
 * {@code WeatherClient}, and for the same two reasons: it is the seam the tests
 * replace with {@code @MockitoBean}, which keeps the suite off the network and
 * out of somebody's inbox, and it is the boundary a second delivery method
 * would sit behind if one ever arrived.
 *
 * <p><b>Plain text, deliberately.</b> There is no template engine in this
 * project and this is not the feature that should introduce one. A password
 * reset is three sentences and a URL; an HTML version would be a second copy of
 * the same words to keep in step, and multipart mail is more of a deliverability
 * risk than a plain body is a presentation problem.
 *
 * <p><b>Nothing here throws upward.</b> Same rule as enrichment: a relay having
 * a bad afternoon must not turn into a 500 on a page whose job is to say "check
 * your mail". The caller cannot do anything useful with the failure either —
 * telling an anonymous requester that delivery failed would leak whether the
 * address existed at all. So a send that fails is logged and swallowed, and
 * {@link #send} answers whether it worked for the benefit of tests and metrics
 * rather than of the request.
 */
public interface MailClient {

    /**
     * Whether this instance can send at all — {@code wander.mail.enabled} plus a
     * configured relay. Read by the endpoint that would otherwise offer a
     * "forgot password" flow with no way to finish it, and published on
     * {@code /api/config/sign-in} so the login page knows whether to draw the
     * link.
     */
    boolean enabled();

    /**
     * @return true if the relay accepted it. False means logged and swallowed —
     *         never an exception to the caller.
     */
    boolean send(String to, String subject, String body);
}
