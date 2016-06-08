package com.tradeshift.reaktive.datacenters;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.Done;
import akka.NotUsed;
import akka.actor.AbstractActor;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.cluster.singleton.ClusterSingletonManager;
import akka.cluster.singleton.ClusterSingletonManagerSettings;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.Backoff;
import akka.pattern.BackoffSupervisor;
import akka.persistence.query.EventEnvelope;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import javaslang.Tuple;
import javaslang.collection.Set;
import scala.concurrent.duration.Duration;

public class DataCenterForwarder extends AbstractActor {
    private static final Logger log = LoggerFactory.getLogger(DataCenterForwarder.class);
    
    public static void start(ActorSystem system, Materializer materializer, DataCenterRepository dataRepo, VisibilityRepository visibilityRepo, EventRepository eventRepo, String tag) {
        system.actorOf(ClusterSingletonManager.props(
            BackoffSupervisor.props(
                Backoff.onStop(
                    Props.create(DataCenterForwarder.class, () -> new DataCenterForwarder(materializer, dataRepo, visibilityRepo, eventRepo, tag)),
                    "f",
                    Duration.create(1, TimeUnit.SECONDS),
                    Duration.create(30, TimeUnit.SECONDS),
                    0.2)
            ),  
            Done.getInstance(), 
            ClusterSingletonManagerSettings.create(system)), "forwarder_" + tag);
    }
    
    private final DataCenterRepository dataRepo;
    private final VisibilityRepository visibilityRepo;
    private final EventRepository eventRepo;
    private final int parallelism = 8; // make configurable, number of documents to broadcast to new regions in parallel
    private final Materializer materializer;
    private final String tag;
    
    private final HashMap<String,Long> highestEventOffsetForDataCenter = new HashMap<>();
    private long updatingVisibilityOffset = -1;
    private int updatingVisibilityOffsetCount = 0;
    private long lastEventOffset;
    
    public DataCenterForwarder(Materializer materializer, DataCenterRepository dataRepo, VisibilityRepository visibilityRepo, EventRepository eventRepo, String tag) {
        this.materializer = materializer;
        this.dataRepo = dataRepo;
        this.visibilityRepo = visibilityRepo;
        this.eventRepo = eventRepo;
        this.tag = tag;
        dataRepo.getRemotes().keySet().forEach(name -> highestEventOffsetForDataCenter.put(name, -1l));

        // TODO consider rolling back the lastEventOffset "a bit" when resuming, to allow for clock drifts and other messiness.
        this.lastEventOffset = visibilityRepo.getLastEventOffset(tag);
        Source<EventEnvelope,NotUsed> src = eventRepo.eventsByTag(tag, lastEventOffset);
        for (DataCenter center: dataRepo.getRemotes().values()) {
            src = src.alsoTo(forwardTo(center));
        }
        src.alsoTo(stopOnError()).runWith(updateVisibility(), materializer);
        
        receive(ReceiveBuilder
            .match(UpdatingVisibility.class, msg -> {
                if (msg.offset > updatingVisibilityOffset) {
                    updatingVisibilityOffset = msg.offset;
                    updatingVisibilityOffsetCount = 1;
                } else if (msg.offset == updatingVisibilityOffset) {
                    updatingVisibilityOffsetCount++;
                }
            })
            .match(VisibilityUpdated.class, msg -> {
                if (msg.offset == updatingVisibilityOffset) {
                    if (updatingVisibilityOffsetCount >= 1) {
                        updatingVisibilityOffsetCount--;                        
                    }
                    updateLastEventOffset();
                }
            })
            .match(EventDelivered.class, msg -> {
                 if (msg.offset > highestEventOffsetForDataCenter.get(msg.dataCenter.getName())) {
                     highestEventOffsetForDataCenter.put(msg.dataCenter.getName(), msg.offset);
                     updateLastEventOffset();
                 }
            })
            .build()
        );
        
        log.debug("Started");
    }
    
    private void updateLastEventOffset() {
        long offset = -1;
        if (updatingVisibilityOffsetCount == 0) {
            offset = updatingVisibilityOffset;
        }
        for (String dc: highestEventOffsetForDataCenter.keySet()) {
            long dcOffset = highestEventOffsetForDataCenter.get(dc);
            if (dcOffset != -1 && dcOffset < offset) {
                offset = dcOffset;
            }
        }
        if (offset > lastEventOffset) {
            lastEventOffset = offset;
            visibilityRepo.setLastEventOffset(tag, lastEventOffset);
        }
    }
    
    private Sink<EventEnvelope,NotUsed> forwardTo(DataCenter target) {
        return Flow.<EventEnvelope>create()
            .filter(e -> visibilityRepo.isVisibleTo(target, e.persistenceId()))
            .to(target.sink(offset -> self().tell(new EventDelivered(target, offset), self())));
    }

    private Sink<EventEnvelope,NotUsed> updateVisibility() {
        return Flow.<EventEnvelope>create()
            .mapConcat(event -> {
                log.debug("Looking up visibility for {}", event.persistenceId());
                Set<String> newDataCenterNames = eventRepo.getDataCenterNames(event).removeAll(visibilityRepo.getVisibility(event.persistenceId()));
                for (String name: newDataCenterNames.removeAll(dataRepo.getRemotes().keySet())) {
                    log.warn("Encountered unknown data center {} in event {} from {}", name, event.sequenceNr(), event.persistenceId());
                }
                Set<DataCenter> dataCenters = newDataCenterNames.intersect(dataRepo.getRemotes().keySet()).map(dataRepo.getRemotes());
                for (DataCenter center: dataCenters) {
                    visibilityRepo.makeVisibleTo(center, event.persistenceId());
                }
                if (!dataCenters.isEmpty()) {
                    self().tell(new UpdatingVisibility(event.offset()), self());
                }
                return dataCenters.map(c -> Tuple.of(event, c));
            })
            .mapAsyncUnordered(parallelism, tuple -> {
                log.info("Replaying persistence ID {} into {}", tuple._1.persistenceId(), tuple._2.getName());
                return eventRepo.currentEventsByPersistenceId(tuple._1.persistenceId())
                                .alsoTo(stopOnError())
                                .runWith(tuple._2.sink(offset -> {}), materializer)
                                .thenApply(done -> tuple._1);
            })
            .alsoTo(stopOnError())
            .to(Sink.foreach(event -> self().tell(new VisibilityUpdated(event.offset()), self())));
    }
    
    private <T> Sink<T,NotUsed> stopOnError() {
        return Sink.<T>onComplete(done -> {
            if (done.isFailure()) {
                log.warn("Failure in stream, stopping actor: {}", done.failed().get());
                context().stop(self());
            } else {
                log.debug("A stream has stopped");
            }
        });
    }
    
    private static class UpdatingVisibility {
        private final long offset;

        public UpdatingVisibility(long offset) {
            this.offset = offset;
        }
    }
    
    private static class VisibilityUpdated {
        private final long offset;

        public VisibilityUpdated(long offset) {
            this.offset = offset;
        }
    }
    
    private static class EventDelivered {
        private final DataCenter dataCenter;
        private final long offset;
        
        public EventDelivered(DataCenter dataCenter, long offset) {
            this.dataCenter = dataCenter;
            this.offset = offset;
        }
    }
}
