package com.thermogrid.simulator.config;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;

/**
 * Last step of a lossless consumer (plan 8.6): after the final failed attempt the message is republished to
 * {@code thermogrid.dlx} with its failure history in headers and the publish is confirmed by the broker. If that
 * republish fails, the message is rejected without requeue instead, so the queue's own dead-letter arguments still
 * move it to {@code thermogrid.dlq}. A failure to dead-letter therefore never silently drops a message.
 *
 * <p>Headers added: {@code x-exception-class}, {@code x-exception-message}, {@code x-exception-stacktrace},
 * {@code x-retry-attempts}, {@code x-original-queue}, {@code x-original-exchange}, {@code x-original-routing-key},
 * {@code x-failed-at}.
 */
public class DeadLetterRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);
    private static final int MAX_STACK = 4000;
    private static final int MAX_MESSAGE = 500;

    private final RabbitOperations rabbit;
    private final String deadLetterExchange;
    private final int attempts;
    private final long confirmTimeoutMs;
    private final BinaryExceptionClassifier classifier;
    private final AtomicLong deadLettered = new AtomicLong();

    @SafeVarargs
    public DeadLetterRecoverer(RabbitOperations rabbit, String deadLetterExchange, int attempts,
                               long confirmTimeoutMs,
                               Class<? extends Throwable>... permanent) {
        this.classifier = classifier(permanent);
        this.rabbit = rabbit;
        this.deadLetterExchange = deadLetterExchange;
        this.attempts = attempts;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        MessageProperties p = message.getMessageProperties();
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        Map<String, Object> headers = new HashMap<>(p.getHeaders());
        headers.put("x-exception-class", root.getClass().getName());
        headers.put("x-exception-message", truncate(String.valueOf(root.getMessage()), MAX_MESSAGE));
        headers.put("x-exception-stacktrace", truncate(stack(cause), MAX_STACK));
        headers.put("x-retry-attempts", retriesUsed(cause));
        headers.put("x-original-queue", p.getConsumerQueue());
        headers.put("x-original-exchange", p.getReceivedExchange());
        headers.put("x-original-routing-key", p.getReceivedRoutingKey());
        headers.put("x-failed-at", Instant.now().toString());

        MessageProperties out = new MessageProperties();
        out.setContentType(p.getContentType());
        out.setContentEncoding(p.getContentEncoding());
        out.setMessageId(p.getMessageId());
        out.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        headers.forEach(out::setHeader);
        Message copy = new Message(message.getBody(), out);
        String key = p.getReceivedRoutingKey() == null ? "unknown" : p.getReceivedRoutingKey();
        try {
            rabbit.invoke(ops -> {
                ops.send(deadLetterExchange, key, copy, new CorrelationData(String.valueOf(p.getMessageId())));
                ops.waitForConfirmsOrDie(confirmTimeoutMs);
                return null;
            });
            deadLettered.incrementAndGet();
            log.error("Message {} ({}) from {} dead-lettered to {}: {}", p.getMessageId(), key,
                    p.getConsumerQueue(), deadLetterExchange, root.toString());
        } catch (RuntimeException e) {
            log.error("Could not republish message {} to {} ({}); rejecting it so the queue's dead-letter arguments "
                    + "move it", p.getMessageId(), deadLetterExchange, e.toString());
            throw new AmqpRejectAndDontRequeueException("Dead-letter republish failed", cause);
        }
    }

    public long deadLettered() {
        return deadLettered.get();
    }

    private Object retriesUsed(Throwable cause) {
        // Permanent errors are not retried: the first attempt was the only one.
        return classifier.classify(cause) ? attempts : 1;
    }

    /** Builds the bounded retry template: {@code attempts} tries, backoff 1 s, 2 s, 4 s, permanent errors not retried. */
    @SafeVarargs
    public static RetryTemplate retryTemplate(int attempts, long initialMs, double multiplier, long maxMs,
                                              Sleeper sleeper, Class<? extends Throwable>... permanent) {
        SimpleRetryPolicy policy = new SimpleRetryPolicy(attempts, classes(permanent), true, true);
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(initialMs);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxMs);
        if (sleeper != null) {
            backOff.setSleeper(sleeper);
        }
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(policy);
        template.setBackOffPolicy(backOff);
        return template;
    }

    private static Map<Class<? extends Throwable>, Boolean> classes(Class<? extends Throwable>[] permanent) {
        Map<Class<? extends Throwable>, Boolean> classified = new HashMap<>();
        for (Class<? extends Throwable> c : FATAL) {
            classified.put(c, false);
        }
        for (Class<? extends Throwable> c : permanent) {
            classified.put(c, false);
        }
        return classified;
    }

    /** true = retryable, false = permanent. */
    public static BinaryExceptionClassifier classifier(Class<? extends Throwable>[] permanent) {
        return new BinaryExceptionClassifier(classes(permanent), true, true);
    }

    /** Errors that retrying cannot fix: undeserialisable or wrongly shaped messages (plan 8.6). */
    @SuppressWarnings("unchecked")
    static final Class<? extends Throwable>[] FATAL = new Class[] {
            org.springframework.amqp.support.converter.MessageConversionException.class,
            org.springframework.messaging.converter.MessageConversionException.class,
            org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException.class,
            org.springframework.messaging.handler.annotation.support.MethodArgumentTypeMismatchException.class,
            com.fasterxml.jackson.core.JsonProcessingException.class,
            ClassCastException.class,
            IllegalArgumentException.class
    };

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
