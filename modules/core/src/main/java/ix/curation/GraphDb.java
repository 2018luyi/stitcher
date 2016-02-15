package ix.curation;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

import java.util.logging.Logger;
import java.util.logging.Level;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.tooling.GlobalGraphOperations;

/**
 * wrapper around GraphDatabaseService instance
 */
public class GraphDb {
    static final Logger logger = Logger.getLogger(GraphDb.class.getName());

    static final Map<File, GraphDb> INSTANCES =
        new ConcurrentHashMap<File, GraphDb>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
                // do shutdown work here
                public void run () {
                    for (GraphDb gdb : INSTANCES.values()) {
                        gdb.graphDb().shutdown();
                        gdb.cache.shutdown();
                        logger.info
                            ("##### Shutting Down Graph Database: "
                             +gdb.dir+" ("+gdb.refs+") #####");
                    }
                }
            });
    }

    protected final File dir;
    protected final GraphDatabaseService gdb;
    protected final AtomicLong refs = new AtomicLong (1l);
    protected final CacheFactory cache;
    
    protected GraphDb (File dir) throws IOException {
        this.gdb = new GraphDatabaseFactory().newEmbeddedDatabase(dir);
        // this must be initialized after graph initialization
        this.cache = CacheFactory.getInstance(new File (dir, "_cache.ix"));
        this.dir = dir;
    }

    public GraphDatabaseService graphDb () { return gdb; }
    public CacheFactory getCache () { return cache; }
    public File getPath () { return dir; }
    public void shutdown () {
        if (refs.decrementAndGet() <= 1l) {
            INSTANCES.remove(dir);
            cache.shutdown();
            gdb.shutdown();
        }
    }

    public static GraphDb createTempDb () throws IOException {
        return createTempDb (null, null);
    }

    public static GraphDb createTempDb (String name) throws IOException {
        return createTempDb (name, null);
    }

    public static GraphDb createTempDb (File temp) throws IOException {
        return createTempDb (null, temp);
    }
    
    public static GraphDb createTempDb (String name, File temp)
        throws IOException {
        return createTempDb ("_ix"+(name != null ? name:""), ".db", temp);
    }
    
    public static GraphDb createTempDb (String prefix,
                                        String suffix, File temp)
        throws IOException {
        File junk = File.createTempFile(prefix, suffix, temp);
        File parent = temp == null ? junk.getParentFile() : temp;
        junk.delete();
        junk = new File (parent, junk.getName());
        junk.mkdirs();
        GraphDb graphDb = new GraphDb (junk);
        INSTANCES.put(junk, graphDb);
        return graphDb;
    }

    public static GraphDb getInstance (String dir) throws IOException {
        return getInstance (new File (dir));
    }
    
    public static synchronized GraphDb getInstance (File dir)
        throws IOException {
        GraphDb gdb = INSTANCES.get(dir);
        if (gdb == null) {
            INSTANCES.put(dir, gdb = new GraphDb (dir));
        }
        else {
            gdb.refs.incrementAndGet();
        }
        return gdb;
    }

    public static GraphDb getInstance (GraphDatabaseService gdb) {
        for (GraphDb db : INSTANCES.values())
            if (db.graphDb() == gdb)
                return db;
        return null;
    }
}
