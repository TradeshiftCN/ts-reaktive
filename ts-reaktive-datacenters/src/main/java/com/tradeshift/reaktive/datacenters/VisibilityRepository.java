package com.tradeshift.reaktive.datacenters;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;

import javaslang.collection.HashSet;
import javaslang.collection.Set;

/**
 * Stores which persistenceIds should be visible in other data centers (in addition to the current one)
 * 
 * TODO add some caching to this class, but only considering that the thing may be clustered later. 
 */
public class VisibilityRepository {
    private final VisibilityCassandraSession session;
    private CompletionStage<PreparedStatement> getEventOffsetStmt;
    private CompletionStage<PreparedStatement> setEventOffsetStmt;
    private CompletionStage<PreparedStatement> getVisibilityStmt;
    private CompletionStage<PreparedStatement> addVisibilityStmt;
    
    public VisibilityRepository(VisibilityCassandraSession session) {
        this.session = session;
        getEventOffsetStmt = session.prepare("SELECT lastEventOffset FROM datacenters.meta WHERE tag = ?");
        setEventOffsetStmt = session.prepare("INSERT INTO datacenters.meta (tag, lastEventOffset) VALUES (?, ?)");
        getVisibilityStmt = session.prepare("SELECT remotedatacenters FROM datacenters.visibility WHERE persistenceid = ?");
        addVisibilityStmt = session.prepare("UPDATE datacenters.visibility SET remotedatacenters = remotedatacenters + ? WHERE persistenceid = ?"); 
    }

    public long getLastEventOffset(String tag) {
        return await(getEventOffsetStmt
            .thenCompose(stmt -> session.select(stmt.bind(tag)))
            .thenApply(rs -> {
                final Row row = rs.one();
                if (row == null) {
                    return -1l;
                } else {
                    return row.getLong("tag");
                }
            }));
    }
    
    public void setLastEventOffset(String tag, long offset) {
        await(setEventOffsetStmt
            .thenCompose(stmt -> session.executeWrite(stmt.bind(tag, offset))));
    }

    public boolean isVisibleTo(DataCenter target, String persistenceId) {
        return getVisibility(persistenceId).contains(target.getName());
    }

    /**
     * Returns the data center names to which the given persistenceId is currently visible
     */
    public Set<String> getVisibility(String persistenceId) {
        return await(getVisibilityStmt
            .thenCompose(stmt -> session.select(stmt.bind(persistenceId)))
            .thenApply(rs -> {
                final Row row = rs.one();
                if (row == null) {
                    return HashSet.empty();
                } else {
                    return HashSet.ofAll(row.getSet("remotedatacenters", String.class));
                }
            }));
    }

    public void makeVisibleTo(DataCenter target, String persistenceId) {
        await(addVisibilityStmt
            .thenCompose(stmt -> session.executeWrite(stmt.bind(target.getName(), persistenceId))));
    }
    
    private <T> T await(CompletionStage<T> f) {
        try {
            return f.toCompletableFuture().get(1, TimeUnit.SECONDS); // TODO configure
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new IllegalStateException(e);
        } 
    }
}
