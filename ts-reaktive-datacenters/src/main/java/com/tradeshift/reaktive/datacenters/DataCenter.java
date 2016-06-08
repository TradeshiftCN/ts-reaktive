package com.tradeshift.reaktive.datacenters;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import akka.Done;
import akka.persistence.query.EventEnvelope;
import akka.stream.javadsl.Sink;

/**
 * A remote data center to which events can be sent
 */
public interface DataCenter {
    /**
     * Returns the name of this data center
     */
    String getName();
    
    /**
     * Returns a Sink to which events can be sent, that should be visible in this data center.
     * 
     * The sink should regularly invoke [onDelivered] with the offset of a successfully
     * delivered event. Events are always sent to the sink in increasing offset.
     * 
     * In addition, the Sink should materialize into a CompletionStage that is completed when the sink
     * has successfully delivered the final event in the stream.
     */
    Sink<EventEnvelope,CompletionStage<Done>> sink(Consumer<Long> onDelivered);
}
