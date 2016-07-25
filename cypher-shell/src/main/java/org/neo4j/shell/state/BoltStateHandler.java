package org.neo4j.shell.state;

import org.neo4j.driver.internal.logging.ConsoleLogging;
import org.neo4j.driver.v1.*;
import org.neo4j.shell.ConnectionConfig;
import org.neo4j.shell.Connector;
import org.neo4j.shell.TransactionHandler;
import org.neo4j.shell.commands.Disconnect;
import org.neo4j.shell.exception.CommandException;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Handles interactions with the driver
 */
public class BoltStateHandler implements TransactionHandler, Connector {
    protected Driver driver;
    protected Session session;
    protected Transaction tx = null;

    public BoltStateHandler() {
    }

    /**
     * Returns an appropriate runner, depending on the current transaction state.
     *
     * @return a statementrunner to execute cypher, or throws an exception if not connected
     */
    @Nonnull
    public StatementRunner getStatementRunner() throws CommandException {
        if (!isConnected()) {
            throw new CommandException("Not connected to Neo4j");
        }
        if (tx != null) {
            return tx;
        }
        return session;
    }

    @Override
    public void beginTransaction() throws CommandException {
        if (!isConnected()) {
            throw new CommandException("Not connected to Neo4j");
        }
        if (tx != null) {
            throw new CommandException("There is already an open transaction");
        }
        tx = session.beginTransaction();
    }

    @Override
    public void commitTransaction() throws CommandException {
        if (!isConnected()) {
            throw new CommandException("Not connected to Neo4j");
        }
        if (tx == null) {
            throw new CommandException("There is no open transaction to commit");
        }
        tx.success();
        tx.close();
        tx = null;
    }

    @Override
    public void rollbackTransaction() throws CommandException {
        if (!isConnected()) {
            throw new CommandException("Not connected to Neo4j");
        }
        if (tx == null) {
            throw new CommandException("There is no open transaction to rollback");
        }
        tx.failure();
        tx.close();
        tx = null;
    }

    @Override
    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    @Override
    public void connect(@Nonnull ConnectionConfig connectionConfig) throws CommandException {
        if (isConnected()) {
            throw new CommandException(String.format("Already connected. Call @|bold %s|@ first.",
                    Disconnect.COMMAND_NAME));
        }

        final AuthToken authToken;
        if (connectionConfig.username().isEmpty() && connectionConfig.password().isEmpty()) {
            authToken = null;
        } else if (!connectionConfig.username().isEmpty() && !connectionConfig.password().isEmpty()) {
            authToken = AuthTokens.basic(connectionConfig.username(), connectionConfig.password());
        } else if (connectionConfig.username().isEmpty()) {
            throw new CommandException("Specified password but no username");
        } else {
            throw new CommandException("Specified username but no password");
        }

        try {
            driver = getDriver(connectionConfig, authToken);
            session = driver.session();
            // Bug in Java driver forces us to runUntilEnd a statement to make it actually connect
            session.run("RETURN 1").consume();
        } catch (Throwable t) {
            silentDisconnect();
            throw t;
        }
    }

    /**
     * Get a driver to connect with
     * @param connectionConfig
     * @param authToken
     * @return
     */
    protected Driver getDriver(@Nonnull ConnectionConfig connectionConfig, AuthToken authToken) {
        return GraphDatabase.driver(connectionConfig.driverUrl(),
                authToken, Config.build().withLogging(new ConsoleLogging(Level.OFF)).toConfig());
    }

    @Override
    public void disconnect() throws CommandException {
        if (!isConnected()) {
            throw new CommandException("Not connected, nothing to disconnect from.");
        }
        silentDisconnect();
    }

    /**
     * Disconnect from Neo4j, clearing up any session resources, but don't give any output.
     */
    private void silentDisconnect() {
        try {
            if (session != null) {
                session.close();
            }
            if (driver != null) {
                driver.close();
            }
        } finally {
            session = null;
            driver = null;
        }
    }
}