package com.tradeshift.reaktive.datacenters;

import static com.tradeshift.reaktive.testkit.Eventually.eventuallyDo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.forgerock.cuppa.Cuppa.describe;
import static org.forgerock.cuppa.Cuppa.it;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.forgerock.cuppa.junit.CuppaRunner;
import org.junit.runner.RunWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.Done;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.persistence.query.EventEnvelope;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import javaslang.collection.HashMap;
import javaslang.collection.HashSet;
import javaslang.collection.Set;

@RunWith(CuppaRunner.class)
public class DataCenterForwarderSpec {
    public static final Config config = ConfigFactory.defaultReference().resolve();
    public static final ActorSystem system = ActorSystem.create("DataCenterForwarderSpec", config);
    public static final Materializer materializer = ActorMaterializer.create(system);
    
    private class TestDataCenter implements DataCenter {
        private final String name;
        private final ConcurrentLinkedQueue<EventEnvelope> events = new ConcurrentLinkedQueue<>();
        
        public TestDataCenter(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Sink<EventEnvelope, CompletionStage<Done>> sink(Consumer<Long> onDelivered) {
            return Flow.<EventEnvelope>create()
                .map(event -> {
                    events.add(event);
                    onDelivered.accept(event.offset());
                    return event;
                }).toMat(Sink.ignore(), (m1,m2) -> m2);
        }
    }
    
{
    describe("DataCenterForwarder", () -> {
        it("should forward events to data centers as indicated", () -> {
            TestDataCenter remote1 = new TestDataCenter("remote1"); 
            TestDataCenter remote2 = new TestDataCenter("remote2");
            
            DataCenterRepository dataRepo = mock(DataCenterRepository.class);
            when(dataRepo.getLocalName()).thenReturn("local");
            when(dataRepo.getRemotes()).thenReturn(HashMap.of(remote1.getName(), (DataCenter)remote1).put(remote2.getName(), remote2));
            
            VisibilityRepository visibilityRepo = mock(VisibilityRepository.class);
            AtomicReference<Set<String>> visibility = new AtomicReference<>(HashSet.empty()); 
            doAnswer(inv -> visibility.get()).when(visibilityRepo).getVisibility("doc1");
            doAnswer(inv -> visibility.updateAndGet(v -> v.add("remote1"))).when(visibilityRepo).makeVisibleTo(remote1, "doc1");
            doAnswer(inv -> visibility.updateAndGet(v -> v.add("remote2"))).when(visibilityRepo).makeVisibleTo(remote2, "doc1");
            
            EventRepository eventRepo = mock(EventRepository.class);
            EventEnvelope event1 = EventEnvelope.apply(1, "doc1", 1, "event1.1");
            EventEnvelope event2 = EventEnvelope.apply(2, "doc1", 1, "event1.2");
            when(eventRepo.eventsByTag("events", 0)).thenReturn(Source.from(Arrays.asList(event1, event2)));
            when(eventRepo.getDataCenterNames(event1)).thenReturn(HashSet.of(remote1.getName()));
            when(eventRepo.getDataCenterNames(event2)).thenReturn(HashSet.of(remote2.getName()));
            when(eventRepo.currentEventsByPersistenceId("doc1")).thenReturn(Source.from(Arrays.asList(event1, event2)));
            
            system.actorOf(Props.create(DataCenterForwarder.class, () -> new DataCenterForwarder(materializer, dataRepo, visibilityRepo, eventRepo, "events")));
            
            eventuallyDo(() -> {
                assertThat(remote1.events).contains(event1, event2);
                assertThat(remote2.events).contains(event1, event2);
                // TODO verify on lastEventOffset
            });
        });
    });
}}
