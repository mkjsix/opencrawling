/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.event.ConsumerStartingEvent;

/** Tests real Kafka configuration and listener startup, without the database or embedding model. */
public class KafkaTopicInitializationProbe {
    private static final List<String> ALL_TOPICS = List.of(
            "opencrawling-documents", "opencrawling-chunks", "opencrawling-embedded");
    private static final List<Configuration> CONFIGURATIONS = List.of(
            new Configuration("runtime", org.opencrawling.runtime.config.KafkaConfig.class,
                    "opencrawling-embedded", ALL_TOPICS),
            new Configuration("embedding", org.opencrawling.embedding.config.KafkaConfig.class,
                    "opencrawling-chunks", ALL_TOPICS.subList(1, 3)));

    private record Configuration(String name, Class<?> type, String consumedTopic, List<String> topics) {}

    public static class ProbeListener {
        private final LinkedBlockingQueue<ConsumerRecord<String, Object>> received = new LinkedBlockingQueue<>();

        @KafkaListener(topics = "${probe.topic}")
        public void consume(ConsumerRecord<String, Object> record) {
            received.add(record);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "unavailable".equals(args[0])) {
            for (Configuration configuration : CONFIGURATIONS) {
                verifyUnavailable(configuration, args[1]);
            }
        } else if (args.length == 3 && Set.of("absent", "one-partition").contains(args[0])) {
            var configuration = CONFIGURATIONS.stream().filter(c -> c.name().equals(args[2]))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown configuration"));
            verifyDelivery(configuration, args[1], "one-partition".equals(args[0]));
        } else {
            throw new IllegalArgumentException(
                    "Usage: KafkaTopicInitializationProbe unavailable|absent|one-partition bootstrap [runtime|embedding]");
        }
    }

    private static AnnotationConfigApplicationContext context(Configuration configuration, String bootstrap,
            ProbeListener listener) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe", Map.of(
                "spring.kafka.bootstrap-servers", bootstrap,
                "spring.kafka.consumer.group-id", "topic-initialization-" + UUID.randomUUID(),
                "probe.topic", configuration.consumedTopic())));
        context.register(configuration.type());
        context.registerBean(ProbeListener.class, () -> listener);
        return context;
    }

    private static void verifyUnavailable(Configuration configuration, String bootstrap) {
        var consumerStarted = new AtomicBoolean();
        try (var context = context(configuration, bootstrap, new ProbeListener())) {
            context.addApplicationListener(event -> {
                if (event instanceof ConsumerStartingEvent) {
                    consumerStarted.set(true);
                }
            });
            // Shorten only the unavailable-broker wait; retain the application's failure policy.
            context.addBeanFactoryPostProcessor(factory -> factory.addBeanPostProcessor(new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof KafkaAdmin admin) {
                        admin.setOperationTimeout(2);
                        admin.setCloseTimeout(1);
                    }
                    return bean;
                }
            }));
            boolean startupFailed = false;
            try {
                context.refresh();
            } catch (IllegalStateException exception) {
                if (!"Could not configure topics".equals(exception.getMessage())) {
                    throw exception;
                }
                startupFailed = true;
            }
            if (!startupFailed || consumerStarted.get()) {
                throw new AssertionError(configuration.name()
                        + " must fail startup before starting a listener when the broker is unavailable");
            }
        }
        System.out.println("PASS " + configuration.name() + ": unavailable broker prevents listener startup");
    }

    private static void verifyDelivery(Configuration configuration, String bootstrap, boolean preexisting)
            throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
            if (admin.listTopics().names().get(20, TimeUnit.SECONDS).stream().anyMatch(ALL_TOPICS::contains)) {
                throw new AssertionError("Each delivery scenario requires a fresh broker");
            }
            if (preexisting) {
                var topics = configuration.topics().stream().map(name -> new NewTopic(name, 1, (short) 1)).toList();
                admin.createTopics(topics).all().get(20, TimeUnit.SECONDS);
                awaitPartitionCounts(admin, configuration, 1);
            }
            var listener = new ProbeListener();
            try (var context = context(configuration, bootstrap, listener)) {
                context.refresh();
                awaitPartitionCounts(admin, configuration, 3);
                publishAndConsume(context, listener, configuration);
            }
        }
        System.out.println("PASS " + configuration.name() + ": "
                + (preexisting ? "existing one-partition topics grown" : "absent topics created after broker recovery")
                + "; acknowledged records from partitions 0, 1, 2 consumed within 30 seconds");
    }

    private static void awaitPartitionCounts(Admin admin, Configuration configuration, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var descriptions = admin.describeTopics(configuration.topics()).allTopicNames()
                        .get(5, TimeUnit.SECONDS);
                if (descriptions.values().stream().allMatch(topic -> topic.partitions().size() == expected)) {
                    return;
                }
            } catch (ExecutionException exception) {
                if (!(exception.getCause() instanceof UnknownTopicOrPartitionException)) {
                    throw exception;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError(configuration.name() + " topics did not reach " + expected + " partitions");
    }

    @SuppressWarnings("unchecked")
    private static void publishAndConsume(AnnotationConfigApplicationContext context, ProbeListener listener,
            Configuration configuration) throws Exception {
        var template = (KafkaTemplate<String, Object>) context.getBean(KafkaTemplate.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        for (int partition = 0; partition < 3; partition++) {
            var result = template.send(configuration.consumedTopic(), partition, "key-" + partition,
                    "partition-" + partition).get(20, TimeUnit.SECONDS);
            if (result.getRecordMetadata().partition() != partition) {
                throw new AssertionError("Record acknowledged on an unexpected partition");
            }
        }
        Set<Integer> received = Set.of();
        while (received.size() != 3 && System.nanoTime() < deadline) {
            var record = listener.received.poll(1, TimeUnit.SECONDS);
            if (record != null) {
                if (!("partition-" + record.partition()).equals(record.value())) {
                    throw new AssertionError("Unexpected payload: " + record.value());
                }
                received = java.util.stream.Stream.concat(received.stream(), java.util.stream.Stream.of(record.partition()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
        }
        if (!received.equals(Set.of(0, 1, 2))) {
            throw new AssertionError(configuration.name() + " received partitions " + received
                    + "; expected 0, 1, 2 before the five-minute consumer metadata refresh");
        }
    }
}
