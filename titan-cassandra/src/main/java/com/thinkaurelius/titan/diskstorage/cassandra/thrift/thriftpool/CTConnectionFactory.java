package com.thinkaurelius.titan.diskstorage.cassandra.thrift.thriftpool;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.cassandra.db.marshal.TimeUUIDType;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.commons.pool.KeyedPoolableObjectFactory;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterators;
import com.thinkaurelius.titan.diskstorage.PermanentStorageException;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.TemporaryStorageException;

/**
 * A factory compatible with Apache commons-pool for Cassandra Thrift API
 * connections.
 *
 * @author Dan LaRocque <dalaro@hopcount.org>
 */
public class CTConnectionFactory implements KeyedPoolableObjectFactory {
	
    private static final Logger log = LoggerFactory.getLogger(CTConnectionFactory.class);

    private AtomicReference<Config> cfgRef;    

    public CTConnectionFactory(String hostname, int port, int timeoutMS, int frameSize, int maxMessageSize) {
		this.cfgRef = new AtomicReference<Config>(new Config(hostname, port,
				timeoutMS, frameSize, maxMessageSize));
    }

    @Override
    public void activateObject(Object key, Object o) throws Exception {
        // Do nothing, as in passivateObject
    }

    @Override
    public void destroyObject(Object key, Object o) throws Exception {
        CTConnection conn = (CTConnection) o;

        TTransport t = conn.getTransport();

        if (t.isOpen())
            t.close();
    }

    @Override
    public Object makeObject(Object key) throws Exception {
        String keyspace = (String) key;

        CTConnection conn = makeRawConnection();
        Cassandra.Client client = conn.getClient();
        client.set_keyspace(keyspace);

        return conn;
    }

    /**
     * Create a Cassandra-Thrift connection, but do not attempt to
     * set a keyspace on the connection.
     *
     * @return A CTConnection ready to talk to a Cassandra cluster
     * @throws TTransportException on any Thrift transport failure
     */
    private CTConnection makeRawConnection() throws TTransportException {
    	Config cfg = cfgRef.get();
    	
        log.debug("Creating TSocket({}, {}, {})", new Object[]{cfg.hostname, cfg.port, cfg.timeoutMS});

        TTransport transport = new TFramedTransport(new TSocket(cfg.hostname, cfg.port, cfg.timeoutMS), cfg.frameSize);
        TBinaryProtocol protocol = new TBinaryProtocol(transport);

        protocol.setReadLength(cfg.maxMessageSize);

        Cassandra.Client client = new Cassandra.Client(protocol);

        transport.open();

        return new CTConnection(transport, client, cfg);
    }

    @Override
    public void passivateObject(Object key, Object o) throws Exception {
        // Do nothing, as in activateObject
    }

    /**
     * Returns true if the connection's config is still up-to-date.
     */
    @Override
    public boolean validateObject(Object key, Object o) {
        CTConnection conn = (CTConnection) o;
        
        return conn.getConfig().equals(cfgRef.get());
    }
    
    public Config getConfig() {
    	return cfgRef.get();
    }
    
    public void setConfig(Config newCfg) {
    	cfgRef.set(newCfg);
    }

    /* This method was adapted from cassandra 0.7.5 cli/CliClient.java */
    public static void validateSchemaIsSettled(Cassandra.Client thriftClient,
                                               String currentVersionId) throws InterruptedException, StorageException {
        log.debug("Waiting for Cassandra schema propagation...");
        Map<String, List<String>> versions = null;

        final TimeUUIDType ti = TimeUUIDType.instance;

        final long start = System.currentTimeMillis();
        long lastTry = 0;
        final long limit = start + CTConnectionPool.SCHEMA_WAIT_MAX;
        final long minSleep = CTConnectionPool.SCHEMA_WAIT_INCREMENT;
        boolean inAgreement = false;
        outer:
        while (limit - System.currentTimeMillis() >= 0 && !inAgreement) {
            // Block for a little while if we're looping too fast
            final long now = System.currentTimeMillis();
            long sinceLast = now - lastTry;
            long willSleep = minSleep - sinceLast;
            if (0 < willSleep) {
                log.debug("Schema not yet propagated; " +
                        "rechecking in {} ms", willSleep);
                Thread.sleep(willSleep);
            }
            // Issue thrift query
            try {
                lastTry = System.currentTimeMillis();
                versions = thriftClient.describe_schema_versions(); // getting schema version for nodes of the ring
            } catch (Exception e) {
                throw new PermanentStorageException("Failed to fetch Cassandra Thrift schema versions: " +
                        ((e instanceof InvalidRequestException) ?
                                ((InvalidRequestException) e).getWhy() : e.getMessage()));
            }

            int nodeCount = 0;
            // Check schema version
            UUID benchmark = UUID.fromString(currentVersionId);
            ByteBuffer benchmarkBB = ti.decompose(benchmark);
            for (String version : versions.keySet()) {
                if (version.equals(StorageProxy.UNREACHABLE)) {
                    nodeCount += versions.get(version).size();
                    continue;
                }

                UUID uuid = UUID.fromString(version);
                ByteBuffer uuidBB = ti.decompose(uuid);
                if (-1 < ti.compare(uuidBB, benchmarkBB)) {
                    log.debug("Version {} equals or comes after required version {}", uuid, benchmark);
                    nodeCount += versions.get(version).size();
                    continue;
                }
                continue outer;
            }
            // I think there's a genuine bug in Cassandra that can cause the
            // result of describe_cluster_versions to converge before all migrations
            // have actually been applied
            // i'd like to debug that, but for now, just wait a sec
//            if (1 < nodeCount) 
//            	Thread.sleep(1000L);

            inAgreement = true;
        }

        if (null == versions) {
            throw new TemporaryStorageException("Couldn't contact Cassandra nodes before timeout");
        }

        if (versions.containsKey(StorageProxy.UNREACHABLE))
            log.warn("Warning: unreachable nodes: {}",
                    Joiner.on(", ").join(versions.get(StorageProxy.UNREACHABLE)));

        if (!inAgreement) {
            throw new TemporaryStorageException("The schema has not settled in " +
                    CTConnectionPool.SCHEMA_WAIT_MAX + " ms. Wanted version " +
                    currentVersionId + "; Versions are " + FBUtilities.toString(versions));
        } else {
            log.debug("Cassandra schema version {} propagated in about {} ms; Versions are {}",
                    new Object[]{currentVersionId, System.currentTimeMillis() - start, FBUtilities.toString(versions)});
        }
    }

    public static void waitForClusterSize(Cassandra.Client thriftClient,
                                          int minSize) throws InterruptedException, StorageException {
        log.debug("Checking Cassandra cluster size" +
                " (want at least {} nodes)...", minSize);
        Map<String, List<String>> versions = null;

        final long STARTUP_WAIT_MAX = 10000L;
        final long STARTUP_WAIT_INCREMENT = 100L;

        long start = System.currentTimeMillis();
        long lastTry = 0;
        long limit = start + STARTUP_WAIT_MAX;
        long minSleep = STARTUP_WAIT_INCREMENT;

        Integer curSize = null;

        while (limit - System.currentTimeMillis() >= 0) {
            // Block for a little while if we're looping too fast
            long sinceLast = System.currentTimeMillis() - lastTry;
            long willSleep = minSleep - sinceLast;
            if (0 < willSleep) {
//        		log.debug("Cassandra cluster size={} " +
//        				"(want {}); rechecking in {} ms",
//        				new Object[]{ curSize, minSize, willSleep });
                Thread.sleep(willSleep);
            }

            // Issue thrift query
            try {
                lastTry = System.currentTimeMillis();
                versions = thriftClient.describe_schema_versions();
                if (1 != versions.size())
                    continue;

                String version = Iterators.getOnlyElement(versions.keySet().iterator());
                curSize = versions.get(version).size();
                if (curSize >= minSize) {
                    log.debug("Cassandra cluster verified at size {} (schema version {}) in about {} ms",
                            new Object[]{curSize, version, System.currentTimeMillis() - start});
                    return;
                }
            } catch (Exception e) {
                throw new PermanentStorageException("Failed to fetch Cassandra Thrift schema versions: " +
                        ((e instanceof InvalidRequestException) ?
                                ((InvalidRequestException) e).getWhy() : e.getMessage()));
            }
        }
        throw new PermanentStorageException("Could not verify Cassandra cluster size");
    }
    
    public static class Config {
        private final String hostname;
        private final int port;
        private final int timeoutMS;
        private final int frameSize;
        private final int maxMessageSize;
        
		public Config(String hostname, int port, int timeoutMS, int frameSize,
				int maxMessageSize) {
			this.hostname = hostname;
			this.port = port;
			this.timeoutMS = timeoutMS;
			this.frameSize = frameSize;
			this.maxMessageSize = maxMessageSize;
		}

		public String getHostname() {
			return hostname;
		}

		public int getPort() {
			return port;
		}

		public int getTimeoutMS() {
			return timeoutMS;
		}

		public int getFrameSize() {
			return frameSize;
		}

		public int getMaxMessageSize() {
			return maxMessageSize;
		}
    }
}

