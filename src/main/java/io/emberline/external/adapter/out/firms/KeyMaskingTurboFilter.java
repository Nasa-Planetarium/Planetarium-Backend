package io.emberline.external.adapter.out.firms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

/**
 * Log masking of registered secrets: a log call whose message, arguments or exception messages contain a secret is
 * dropped and written again with the secret replaced by {@code ****} and without the exception.
 */
final class KeyMaskingTurboFilter extends TurboFilter {

    private static final String FQCN = KeyMaskingTurboFilter.class.getName();
    private static final Set<String> SECRETS = new CopyOnWriteArraySet<>();
    private static volatile boolean installed;

    private KeyMaskingTurboFilter() {
    }

    static void register(String secret) {
        if (secret != null && secret.length() >= 4) {
            SECRETS.add(secret);
        }
        install();
    }

    private static synchronized void install() {
        if (!installed && LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            KeyMaskingTurboFilter filter = new KeyMaskingTurboFilter();
            filter.setName("emberline-secret-mask");
            filter.start();
            context.addTurboFilter(filter);
            installed = true;
        }
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (SECRETS.isEmpty() || format == null || level == null || !logger.isEnabledFor(level)) {
            return FilterReply.NEUTRAL;
        }
        String message = MessageFormatter.arrayFormat(format, params).getMessage();
        if (!containsSecret(message) && !throwableContainsSecret(t)) {
            return FilterReply.NEUTRAL;
        }
        String masked = message;
        for (String s : SECRETS) {
            masked = masked.replace(s, "****");
        }
        logger.log(marker, FQCN, Level.toLocationAwareLoggerInteger(level), masked, null, null);
        return FilterReply.DENY;
    }

    private static boolean containsSecret(String text) {
        return text != null && SECRETS.stream().anyMatch(text::contains);
    }

    private static boolean throwableContainsSecret(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (containsSecret(c.getMessage()) || containsSecret(c.toString())) {
                return true;
            }
        }
        return false;
    }
}
