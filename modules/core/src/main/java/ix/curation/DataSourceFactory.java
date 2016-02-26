package ix.curation;

import java.util.*;
import java.io.*;
import java.net.URL;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.RelationshipIndex;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicRelationshipType;

public class DataSourceFactory implements Props {
    static final Logger logger = Logger.getLogger
        (DataSourceFactory.class.getName());

    protected final GraphDatabaseService gdb;
    protected final GraphDb graphDb;

    public static Set<DataSource> getDataSources (GraphDb graphDb) {
        return new DataSourceFactory(graphDb).datasources();
    }
    
    public DataSourceFactory (GraphDb graphDb) {
        this.graphDb = graphDb;
        gdb = graphDb.graphDb();
    }

    protected DataSource _register (String key, String name) {
        Index<Node> index = gdb.index().forNodes
            (DataSource.nodeIndexName());
        
        Node n = index.get(KEY, key).getSingle();
        if (n == null) {
            n = gdb.createNode(AuxNodeType.DATASOURCE);
            n.setProperty(NAME, name);
            n.setProperty(KEY, key);
            
            index.add(n, NAME, name);
            index.putIfAbsent(n, KEY, key);
            logger.info("** new data source registered: "
                        +key+" \""+name+"\"");
        }
        else {
            if (!name.equals(n.getProperty(NAME)))
                index.add(n, NAME, name);               
        }
        return DataSource._getDataSource(n);
    }

    public Set<DataSource> datasources () {
        try (Transaction tx = gdb.beginTx()) {
            Set<DataSource> sources = new TreeSet<DataSource>();
            for (Iterator<Node> it = gdb.findNodes(AuxNodeType.DATASOURCE);
                 it.hasNext(); ) {
                Node node = it.next();
                sources.add(DataSource._getDataSource(node));
            }
            return sources;
        }
    }

    public DataSource register (String source) {
        try (Transaction tx = gdb.beginTx()) {
            DataSource ds = _register (sourceKey (source), source);
            tx.success();
            return ds;
        }
    }

    public DataSource register (File file) throws IOException {
        Util.FileStats stats = Util.stats(file);
        String key = stats.sha1.substring(0, 9); // truncated sha1
        try (Transaction tx = gdb.beginTx()) {
            DataSource ds = _register (key, file.getName());
            ds.set(SHA1, stats.sha1);
            ds.set(SIZE, stats.size);
            ds.set(URI, file.getCanonicalFile().toURI().toString());
            tx.success();
            return ds;
        }
    }

    public DataSource register (URL url) throws IOException {
        try {
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                return register (new File (url.toURI()));
            }
            
            // otherwise we download the content of the url locally
            Util.FileStats stats = Util.fetchFile(url);
            String key = stats.sha1.substring(0, 9);
            try (Transaction tx = gdb.beginTx()) {
                DataSource ds = _register (key, url.toString());
                ds.set(URI, url.toURI().toString());
                ds.set(SHA1, stats.sha1);
                ds.set(SIZE, stats.size);
                tx.success();
                return ds;
            }
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Bogus URL: "+url, ex);
        }
        return null;
    }

    public DataSource getDataSourceByName (String source) {
        try (Transaction tx = gdb.beginTx()) {
            Index<Node> index = gdb.index().forNodes
                (DataSource.nodeIndexName());
            
            IndexHits<Node> hits = index.get(NAME, source);
            try {
                // simply return the first one
                DataSource ds = null;
                for (Node n : hits) {
                    ds = DataSource._getDataSource(n);
                    break;
                }
                tx.success();
                return ds;
            }
            finally {
                hits.close();
            }
        }
    }

    public DataSource getDataSourceByKey (String key) {
        try (Transaction tx = gdb.beginTx()) {
            Index<Node> index = gdb.index().forNodes
                (DataSource.nodeIndexName());

            DataSource ds = null;
            Node n = index.get(KEY, key).getSingle();
            if (n != null) {
                ds = DataSource._getDataSource(n);
            }
            tx.success();
            return ds;
        }
    }

    public DataSource getDataSource (String name) {
        DataSource ds = getDataSourceByKey (name);
        if (ds == null)
            ds = getDataSourceByName (name);
        return ds;
    }

    public static String sourceKey (String source) {
        return Util.sha1hex(source).substring(0, 9);
    }
}
