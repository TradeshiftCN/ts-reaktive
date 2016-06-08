package com.tradeshift.reaktive.datacenters;

import static com.tradeshift.reaktive.CompletableFutures.toJava;

import java.util.concurrent.CompletionStage;

import com.datastax.driver.core.Session;
import com.tradeshift.reaktive.cassandra.CassandraSession;

import akka.actor.ActorSystem;

public class VisibilityCassandraSession extends CassandraSession {

    public VisibilityCassandraSession(ActorSystem system, String metricsCategory) {
        super(system, metricsCategory, VisibilityCassandraSession::createKeyspaceAndTable);
    }
    
    private static CompletionStage<Void> createKeyspaceAndTable(Session s) {
        // TODO use NetworkTopologyStrategy and make configurable 
        return 
            toJava(s.executeAsync("CREATE KEYSPACE IF NOT EXISTS datacenters WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 } ")).thenCompose(rs -> 
            toJava(s.executeAsync("CREATE TABLE IF NOT EXISTS datacenters.meta (tag text PRIMARY KEY, lastEventOffset bigint)"))).thenCompose(rs -> 
            toJava(s.executeAsync("CREATE TABLE IF NOT EXISTS datacenters.visibility (persistenceid text PRIMARY KEY, remotedatacenters set<text>)"))).thenApply(rs -> null);
    }
}
