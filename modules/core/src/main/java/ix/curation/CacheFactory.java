package ix.curation;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import java.util.logging.Logger;
import java.util.logging.Level;

import net.sf.ehcache.Status;
import net.sf.ehcache.Cache;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.CacheEntry;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.writer.CacheWriter;
import net.sf.ehcache.writer.AbstractCacheWriter;
import net.sf.ehcache.loader.CacheLoader;
import net.sf.ehcache.constructs.blocking.CacheEntryFactory;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;

import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.search.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.util.Version;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Document;

public class CacheFactory
    extends AbstractCacheWriter implements CacheEntryFactory {
    
    static private final Logger logger =
        Logger.getLogger(CacheFactory.class.getName());

    static final String KEY = "__key";
    static final String VALUE = "__value";

    static final Map<File, CacheFactory> CACHES =
        new ConcurrentHashMap<File, CacheFactory>();

    /*
    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
                // do shutdown work here
                public void run () {
                    for (CacheFactory cf : CACHES.values()) {
                        cf.cache.getCacheManager()
                            .removeCache(cf.cache.getName());
                        logger.info
                            ("##### Shutting down cache: "
                             +cf.cache.getName()+" ("+cf.refs+") #####");
                    }
                }
            });
    }
    */
    
    protected final File dir;
    protected final Ehcache cache;
    protected final AtomicLong refs = new AtomicLong (1l);
    protected IndexWriter indexWriter;
    
    private CacheFactory (File dir) throws IOException {        
        dir.mkdirs();
        if (!dir.isDirectory())
            throw new IllegalArgumentException (dir+" is not a directory!");
        
        this.dir = dir;
        String name = getCacheName ();
        
        CacheManager manager = CacheManager.getInstance();
        /*
        CacheConfiguration config = new CacheConfiguration ();
        config.eternal(true)
            .name(name)
            .timeToIdleSeconds(3600l)
            .timeToLiveSeconds(3600l)
            .timeoutMillis(5000l)
            .setupFor(manager);
        */
        cache = new SelfPopulatingCache
            (manager.addCacheIfAbsent(name), this);
        cache.registerCacheWriter(this);        
    }

    protected String getCacheName () throws IOException {
        return dir.getCanonicalFile().toURI().toString();
    }
    public File getCachePath () { return dir; }

    public static CacheFactory getInstance (String dir) throws IOException {
        return getInstance (new File (dir));
    }
    
    public static synchronized CacheFactory getInstance (File dir)
        throws IOException {
        CacheFactory cache = CACHES.get(dir);
        if (cache == null) {
            CACHES.put(dir, cache = new CacheFactory (dir));
        }
        else {
            cache.refs.incrementAndGet();
        }
        return cache;
    }

    public <T> T getOrElse (Object key, Callable<T> callable) {
        Element elm = cache.get(key);
        if (elm == null || elm.getObjectValue() == null) {
            try {
                T value = callable.call();
                if (value != null) {
                    cache.putWithWriter(elm = new Element (key, value));
                }
            }
            catch (Exception ex) {
                logger.log(Level.SEVERE, "Can't cache element", ex);
            }
        }
        return elm != null ? (T)elm.getObjectValue() : null;
    }

    public void put (Object key, Object value) {
        cache.putWithWriter(new Element (key, value));
    }
    
    public Object get (Object key) {
        Element elm = cache.get(key);
        return elm != null ? elm.getObjectValue() : null;
    }

    public void remove (Object key) {
        cache.removeWithWriter(key);
    }

    public int size () {
        try {
            return indexWriter.numDocs();
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Can't access IndexWriter", ex);
        }
        return -1;
    }

    public void shutdown () {
        if (refs.decrementAndGet() <= 1l) {
            CacheManager manager = cache.getCacheManager();
            manager.removeCache(cache.getName());
            CACHES.remove(dir);
        }
    }

    static String getKey (Serializable key) throws IOException {
        return Util.sha1hex(Util.serialize(key));
    }

    static Object getValue (Document doc) throws Exception {
        byte[] data = doc.getBinaryValue(VALUE);
        ObjectInputStream ois = new ObjectInputStream
            (new ByteArrayInputStream (data));
        return ois.readObject();
    }
    
    /**
     * CacheEntryFactory interface
     */
    public Object createEntry (Object key) throws Exception {
        if (!(key instanceof Serializable)) {
            throw new IllegalArgumentException
                ("Cache key "+key+" is not serliazable!");
        }
        
        String id = getKey ((Serializable)key);
        IndexSearcher searcher = new IndexSearcher
            (IndexReader.open(indexWriter, true));
        try {
            TermQuery query = new TermQuery (new Term (KEY, id));           
            TopDocs hits = searcher.search(query, 1);
            //logger.info("retrieving cache for "+id+"..."+(hits.totalHits > 0));
            Element elm = null;
            if (hits.totalHits > 0) {
                Document doc = searcher.doc(hits.scoreDocs[0].doc);
                elm = new Element (key, getValue (doc));
                cache.put(elm);
            }
            return elm;
        }
        finally {
            searcher.getIndexReader().close();
            searcher.close();
        }
    }
    
    /**
     * CacheWriter interface
     */
    static IndexWriter initIndex (File path) throws IOException {
        Directory dir = new NIOFSDirectory (path);
        Map<String, Analyzer> fields = new HashMap<String, Analyzer>();
        fields.put(KEY, new KeywordAnalyzer ());
        PerFieldAnalyzerWrapper analyzer = new PerFieldAnalyzerWrapper
            (new StandardAnalyzer (Version.LUCENE_CURRENT), fields);
        
        IndexWriterConfig config = new IndexWriterConfig
            (Version.LUCENE_CURRENT, analyzer);
        IndexWriter indexWriter = new IndexWriter (dir, config);
        logger.info("##### initializing cache: "+path
                    +"; "+indexWriter.numDocs()+" entries! ######");
        return indexWriter;
    }

    @Override
    public void init () {
        try {
            indexWriter = initIndex (dir);
        }
        catch (IOException ex) {
            logger.log(Level.SEVERE, "Can't initialize lucene for "+dir, ex);
        }
    }
    
    @Override
    public void dispose () {
        try {
            logger.info("#### closing cache writer "+cache.getName()
                        +"; "+indexWriter.numDocs()+" entries #####");
            indexWriter.close();
        }
        catch (IOException ex) {
            logger.log(Level.SEVERE, "Can't close lucene index!", ex);
        }
    }
    
    @Override
    public void delete (CacheEntry entry) {
        Object key = entry.getKey();
        if (!(key instanceof Serializable))
            return;

        try {
            String id = getKey ((Serializable)key);         
            Term term = new Term (KEY, id);
            indexWriter.deleteDocuments(term);
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Deleting cache "
                       +key+" from persistence!", ex);
        }
    }
    
    @Override
    public void write (Element elm) {
        Serializable key = elm.getKey();
        if (key != null) {
            //logger.info("Persisting cache key="+key+" value="+value);
            try {
                Document doc = new Document ();
                String id = getKey (key);
                Field f = new Field (KEY, true, id, Field.Store.NO,
                                     Field.Index.NOT_ANALYZED,
                                     Field.TermVector.NO);
                doc.add(f);
                doc.add(new Field
                        (VALUE, Util.serialize(elm.getObjectValue())));
                indexWriter.addDocument(doc);
            }
            catch (Exception ex) {
                logger.log(Level.SEVERE, "Can't write cache element: key="
                           +key+" value="+elm.getObjectValue(), ex);
            }
        }
        else {
            logger.warning("Key "+elm.getObjectKey()+" isn't serializable!");
        }
    }

    @Override
    public void deleteAll (Collection<CacheEntry> entries) {
        for (CacheEntry e : entries)
            delete (e);
    }
    
    @Override
    public void writeAll (Collection<Element> entries) {
        for (Element elm : entries)
            write (elm);
    }

    public static void main (String[] argv) throws Exception {
        if (argv.length == 0) {
            System.err.println("Usage: "+CacheFactory.class.getName()
                               +" DIR [MAX=1000]");
            System.exit(1);
        }

        CacheFactory cache = CacheFactory.getInstance(argv[0]);
        logger.info("## cache "+argv[0]+" has "+cache.size()+" entries!");
        
        cache.shutdown();
    }
}
