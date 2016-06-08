package com.tradeshift.reaktive.datacenters;

import akka.NotUsed;
import akka.persistence.query.EventEnvelope;
import akka.stream.javadsl.Source;
import javaslang.collection.Set;

public interface EventRepository {
    /**
     * Returns a non-ending, real-time source to all events since a given offset. 
     * {@see akka.persistence.query.javadsl.EventsByTagQuery}
     */
    public Source<EventEnvelope,NotUsed> eventsByTag(String tag, long offset);
    
    /**
     * @return The names of any additional data centers that should get access to the 
     * persistence ID of the given event, after the event has been applied, or Seq.empty()
     * if the data centers should remain unchanged.
     */
    public Set<String> getDataCenterNames(EventEnvelope envelope);

    /**
     * Returns a finite source to all currently known events emitted by the given persistenceId.
     * {@see akka.persistence.query.javadsl.CurrentEventsByPersistenceIdQuery }
     */
    public Source<EventEnvelope,NotUsed> currentEventsByPersistenceId(String persistenceId);
}
