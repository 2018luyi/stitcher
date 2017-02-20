package ix.curation;

import java.util.*;
import java.io.*;
import java.net.URL;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.lang.reflect.Array;
import java.util.function.Consumer;
import java.util.stream.Stream;

import java.util.concurrent.Callable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Resource;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.index.RelationshipIndex;
import org.neo4j.index.lucene.LuceneTimeline;
import org.neo4j.index.lucene.TimelineIndex;

import ix.curation.graph.UnionFind;

// NOTE: methods and variables that begin with underscore "_" generally assume that a graph database transaction is already open!

public class EntityFactory implements Props {
    static final Logger logger = Logger.getLogger
        (EntityFactory.class.getName());

    static final double CLIQUE_WEIGHT = 0.7;
    static final int CLIQUE_MINSIZE = 2;

    static class DefaultGraphMetrics implements GraphMetrics {
        int entityCount;
        Map<EntityType, Integer> entityHistogram =
            new EnumMap<EntityType, Integer>(EntityType.class);
        Map<Integer, Integer> entitySizeDistribution =
            new TreeMap<Integer, Integer>();
        int stitchCount;
        Map<String, Integer> stitchHistogram =
            new TreeMap<String, Integer>();
        int connectedComponentCount;
        Map<Integer, Integer> connectedComponentHistogram =
            new TreeMap<Integer, Integer>();
        int singletonCount;

        DefaultGraphMetrics () {}

        public int getEntityCount () { return entityCount; }
        public Map<EntityType, Integer> getEntityHistogram () {
            return entityHistogram;
        }
        public Map<Integer, Integer> getEntitySizeDistribution () {
            return entitySizeDistribution;
        }
        public int getStitchCount () { return stitchCount; }
        public Map<String, Integer> getStitchHistogram () {
            return stitchHistogram;
        }
        public int getConnectedComponentCount () {
            return connectedComponentCount;
        }
        public Map<Integer, Integer> getConnectedComponentHistogram () {
            return connectedComponentHistogram;
        }
        public int getSingletonCount () { return singletonCount; }
    }

    class EntityIterator implements Iterator<Entity> {
        final Iterator<Node> iter;

        EntityIterator (Iterator<Node> iter) {
            this.iter = iter;
        }

        public boolean hasNext () {
            try (Transaction tx = gdb.beginTx()) {
                return iter.hasNext();
            }
        }
        
        public Entity next () {
            Node n = iter.next();
            return Entity.getEntity(n);
        }
        
        public void remove () {
            throw new UnsupportedOperationException ("remove not supported");
        }
    }

    static class ConnectedComponents implements Iterator<Entity[]> {
        int current;
        long[][] components;
        long[] singletons;
        final GraphDatabaseService gdb;
        
        ConnectedComponents (GraphDatabaseService gdb) {
            try (Transaction tx = gdb.beginTx()) {
                UnionFind eqv = new UnionFind ();
                List<Long> singletons = new ArrayList<Long>();

                gdb.findNodes(AuxNodeType.ENTITY).stream().forEach(node -> {
                        int edges = 0;
                        for (Relationship rel
                                 : node.getRelationships(Direction.BOTH,
                                                         Entity.KEYS)) {
                            eqv.union(rel.getStartNode().getId(),
                                      rel.getEndNode().getId());
                            ++edges;
                        }
                        
                        if (edges == 0) {
                            singletons.add(node.getId());
                        }
                    });
                
                this.singletons = new long[singletons.size()];
                for (int i = 0; i < this.singletons.length; ++i)
                    this.singletons[i] = singletons.get(i);
                components = eqv.components();
                tx.success();
            }
            this.gdb = gdb;
        }

        public long[][] components () {
            return components;
        }
        
        public long[] singletons () {
            return singletons;
        }

        public boolean hasNext () {
            boolean next = current < components.length;
            if (!next) {
                next = (current - components.length) < singletons.length;
            }
            return next;
        }
        
        public Entity[] next () {
            Entity[] comp;
            if (current < components.length) {
                long[] cc = components[current];
                
                comp = new Entity[cc.length];
                try (Transaction tx = gdb.beginTx()) {
                    for (int i = 0; i < cc.length; ++i) {
                        comp[i] = new Entity (gdb.getNodeById(cc[i]));
                    }
                    tx.success();
                }
            }
            else {
                comp = new Entity[1];
                try (Transaction tx = gdb.beginTx()) {
                    comp[0] = new Entity
                        (gdb.getNodeById
                         (singletons[current-components.length]));
                    tx.success();
                }
            }
            ++current;
            
            return comp;
        }
        
        public void remove () {
            throw new UnsupportedOperationException ("remove not supported");
        }
    }

    /*
     * a simple graph wrapper around a set of nodes
     */
    static class Graph {
        final BitSet[] adj;
        StitchKey key;
        GraphDatabaseService gdb;
        Node[] nodes;

        Graph (Component comp) {
            Entity[] entities = comp.entities();
            adj = new BitSet[entities.length];
            if (entities.length > 0) {
                gdb = entities[0].getGraphDb();
                try (Transaction tx = gdb.beginTx()) {
                    for (int i = 0; i < entities.length; ++i) {
                        Node n = entities[i]._node();
                        BitSet bs = new BitSet (entities.length);
                        for (Relationship rel :
                                 n.getRelationships(key, Direction.BOTH)) {
                            Node m = rel.getOtherNode(n);
                            long id = m.getId();
                            for (int j = 0; j < entities.length; ++j) 
                                if (i != j && entities[j].getId() == id) {
                                    bs.set(j);
                                    break;
                                }
                        }
                        adj[i] = bs;
                        nodes[i] = n;
                    }
                    tx.success();
                }
            }
        }
        
        Graph (GraphDatabaseService gdb, StitchKey key, long[] nodes) {
            adj = new BitSet[nodes.length];
            try (Transaction tx = gdb.beginTx()) {
                this.nodes = new Node[nodes.length];
                for (int i = 0; i < nodes.length; ++i) {
                    Node n = gdb.getNodeById(nodes[i]);
                    BitSet bs = new BitSet (nodes.length);
                    for (Relationship rel :
                             n.getRelationships(key, Direction.BOTH)) {
                        Node m = rel.getOtherNode(n);
                        long id = m.getId();
                        for (int j = 0; j < nodes.length; ++j) 
                            if (i != j && nodes[j] == id) {
                                bs.set(j);
                                break;
                            }
                    }
                    adj[i] = bs;
                    this.nodes[i] = n;
                }
                tx.success();
            }
            this.gdb = gdb;
            this.key = key;
        }

        public BitSet maxclique (StitchKey key, Object value) {
            return null;
        }

        // construct a clique based on the given set of nodes
        
        public BitSet edges (int n) { return adj[n]; }
        public StitchKey key () { return key; }
        public int size () { return adj.length; }
    }

    static class ComponentImpl implements Component {
        Set<Long> nodes = new TreeSet<Long>();
        Entity[] entities;
        String id;
        GraphDatabaseService gdb;

        ComponentImpl () {
        }
        
        ComponentImpl (Node node) {
            instrument (node);
        }

        void instrument (Node node) {
            gdb = node.getGraphDatabase();
            try (Transaction tx = gdb.beginTx()) {
                if (!node.hasLabel(AuxNodeType.COMPONENT)
                    || !node.hasProperty(CNode.RANK))
                    throw new IllegalArgumentException
                        ("Not a valid component node: "+node.getId());
                traverse (node);

                Integer rank = (Integer)node.getProperty(CNode.RANK);
                if (rank != nodes.size()) {
                    logger.warning("Node #"+node.getId()
                                   +": Rank is "+rank+" but there are "
                                   +nodes.size()+" nodes in this component!");
                }
                
                entities = new Entity[nodes.size()];
                int i = 0;
                for (Long id : nodes)
                    entities[i++] = Entity._getEntity(gdb.getNodeById(id));
                
                tx.success();
            }
            id = Util.sha1(nodes).substring(0, 9);
        }

        void traverse (Node node) {
            nodes.add(node.getId());
            for (Relationship rel :
                     node.getRelationships(Direction.BOTH, Entity.KEYS)) {
                Node xn = rel.getOtherNode(node);
                if (!nodes.contains(xn.getId()))
                    traverse (xn);
            }
        }

        ComponentImpl (GraphDatabaseService gdb, long[] nodes) {
            try (Transaction tx = gdb.beginTx()) {
                entities = new Entity[nodes.length];
                for (int i = 0; i < nodes.length; ++i) {
                    entities[i] = Entity._getEntity(gdb.getNodeById(nodes[i]));
                    this.nodes.add(nodes[i]);
                }
                id = Util.sha1(this.nodes).substring(0, 9);
            }
            this.gdb = gdb;         
        }

        ComponentImpl (GraphDatabaseService gdb, Long... nodes) {
            try (Transaction tx = gdb.beginTx()) {
                entities = new Entity[nodes.length];
                for (int i = 0; i < nodes.length; ++i) {
                    entities[i] = Entity._getEntity(gdb.getNodeById(nodes[i]));
                    this.nodes.add(nodes[i]);
                }
                id = Util.sha1(this.nodes).substring(0, 9);
            }
            this.gdb = gdb;         
        }

        ComponentImpl (Component... comps) {
            Set<Entity> entities = new TreeSet<Entity>();
            for (Component c : comps)
                for (Entity e : c) {
                    if (gdb == null) {
                        // this assumes that all entities come from the same
                        // underlying graphdb instance!
                        gdb = e.getGraphDb();
                    }
                    
                    entities.add(e);
                    nodes.add(e.getId());
                }

            this.entities = entities.toArray(new Entity[0]);
            id = Util.sha1(nodes).substring(0, 9);
        }

        ComponentImpl (Entity... entities) {
            for (Entity e : entities) {
                if (gdb == null)
                    gdb = e.getGraphDb();
                nodes.add(e.getId());
            }
            this.entities = entities;
            id = Util.sha1(nodes).substring(0, 9);
        }

        Long[] _filterStitchedNodes (StitchKey key, Object value) {
            Set<Long> subset = new TreeSet<Long>();
            if (EntityFactory._filterStitchedNodes
                (gdb, subset, key, value) > 0) {
                subset.retainAll(nodes);
            }
            return subset.toArray(new Long[0]);
        }
        
        /*
         * unique set of values that span the given stitch key
         */
        public Object[] values (StitchKey key) {
            Set values = new HashSet ();
            for (Entity e : entities) {
                try (Transaction tx = e._node().getGraphDatabase().beginTx()) {
                    for (Relationship rel :
                             e._node().getRelationships(key, Direction.BOTH)) {
                        if (rel.hasProperty(VALUE))
                            values.add(rel.getProperty(VALUE));
                    }
                }
            }
            return values.toArray(new Object[0]);
        }
        
        public Component filter (StitchKey key, Object value) {
            try (Transaction tx = gdb.beginTx()) {
                return _filter (key, value);
            }
        }

        public Component _filter (StitchKey key, Object value) {
            return new ComponentImpl (gdb, _filterStitchedNodes (key, value));
        }
        
        public Iterator<Entity> iterator () {
            return Arrays.asList(entities).iterator();
        }
        
        public String getId () { return id; }
        public Entity[] entities () { return entities; }
        public int size () { return nodes.size(); }
        public Set<Long> nodeSet () { return nodes; }
        public int hashCode () { return nodes.hashCode(); }
        public boolean equals (Object obj) {
            if (obj instanceof ComponentImpl) {
                return nodes.equals(((ComponentImpl)obj).nodes);
            }
            return false;
        }

        public Map<Object, Integer> stats (StitchKey key) {
            Map<Object, Integer> stats = new HashMap<>();
            try (Transaction tx = gdb.beginTx()) {
                Set<Long> seen = new HashSet<>();
                for (int i = 0; i < entities.length; ++i) {
                    Node n = entities[i]._node();
                    for (Relationship rel :
                             n.getRelationships(Direction.BOTH, key)) {
                        if (!seen.contains(rel.getId())) {
                            Node xn = rel.getOtherNode(n);
                            Object val = rel.getProperty(VALUE, null);
                            if (val != null) {
                                Integer c = stats.get(val);
                                stats.put(val, c == null ? 1 : (c+1));
                            }
                            seen.add(rel.getId());
                        }
                    }
                }
            }
            
            return stats;
        }

        @Override
        public void cliques (CliqueVisitor visitor, StitchKey... keys) {
            try (Transaction tx = gdb.beginTx()) {
                CliqueEnumeration clique = new CliqueEnumeration
                    (gdb, keys == null || keys.length == 0
                     ? Entity.KEYS : keys);
                clique.enumerate(nodes (), visitor);
            }
        }

        void dfs (Set<Long> nodes, Set<Relationship> edges,
                  Node n, StitchKey key, Object value) {
            nodes.add(n.getId());
            for (Relationship rel : n.getRelationships(Direction.BOTH)) {
                if (rel.isType(key)
                    && value.equals(rel.getProperty(VALUE, null)))
                    edges.add(rel);
                Node xn = rel.getOtherNode(n);
                if (!nodes.contains(xn.getId()))
                    dfs (nodes, edges, xn, key, value); 
            }
        }
        
        Set<Long> getNodes (StitchKey key, Object value) {
            Set<Long> nodes = new TreeSet<>();
            Set<Relationship> edges = new HashSet<>();
            try (Transaction tx = gdb.beginTx()) {
                dfs (nodes, edges, entities[0]._node(), key, value);
                nodes.clear();
                for (Relationship rel : edges) {
                    nodes.add(rel.getStartNode().getId());
                    nodes.add(rel.getEndNode().getId());
                }
            }
            return nodes;
        }
        
        @Override
        public void cliques (StitchKey key,
                             Object value, CliqueVisitor visitor) {
            try (Transaction tx = gdb.beginTx()) {
                Set<Long> nodes = getNodes (key, value);
                if (!nodes.isEmpty()) {
                    CliqueEnumeration clique = new CliqueEnumeration (gdb, key);
                    clique.enumerate(Util.toPrimitive
                                     (nodes.toArray(new Long[0])), visitor);
                }
            }
        }

        protected List<Entity> ov (Component comp) {
            List<Entity> ov = new ArrayList<Entity>();
            for (Entity e : comp) {
                long id = e.getId();
                if (nodes.contains(id))
                    ov.add(e);
            }
            return ov;
        }

        @Override
        public Component add (Component comp) {
            return new ComponentImpl (this, comp);
        }

        @Override
        public Component and (Component comp) {
            List<Entity> ov = ov (comp);
            return ov.isEmpty() ? null
                : new ComponentImpl (ov.toArray(new Entity[0]));
        }

        @Override
        public Component xor (Component comp) {
            List<Entity> ov = new ArrayList<Entity>();
            for (Entity e : comp) {
                long id = e.getId();
                if (!nodes.contains(id))
                    ov.add(e);
            }
            
            for (Entity e : this) {
                long id = e.getId();
                if (!comp.nodeSet().contains(id))
                    ov.add(e);
            }
            
            return ov.isEmpty()
                ? null : new ComponentImpl (ov.toArray(new Entity[0]));
        }

        public String toString () {
            return getClass().getName()+"{id="+id+",size="
                +nodes.size()+",nodes="+nodes+"}";
        }
    }

    static class ComponentLazy extends ComponentImpl {
        final Node seed;
        final Integer rank;
        AtomicBoolean inited = new AtomicBoolean (false);
        
        ComponentLazy (Node node) {
            try (Transaction tx = node.getGraphDatabase().beginTx()) {
                if (!node.hasLabel(AuxNodeType.COMPONENT))
                    throw new IllegalArgumentException
                        ("Not a valid component node: "+node.getId());
                
                rank = (Integer)node.getProperty(CNode.RANK);
                if (rank == null)
                    throw new IllegalArgumentException
                        ("Node does not contain rank");
                
                seed = node;
                tx.success();
            }
        }

        void init () {
            if (!inited.get()) {
                instrument (seed);
                inited.set(true);
            }
        }

        @Override public int size () {
            init ();
            return rank;
        }
        @Override public Set<Long> nodeSet () {
            init ();
            return super.nodeSet();
        }
        @Override public Entity[] entities () {
            init ();
            return super.entities();
        }
        @Override public Iterator<Entity> iterator () {
            init ();
            return super.iterator();
        }
        @Override public Double score () {
            return rank.doubleValue();
        }
    }

    static class CliqueImpl extends ComponentImpl implements Clique {
        final EnumMap<StitchKey, Object> values =
            new EnumMap (StitchKey.class);

        CliqueImpl (Component... comps) {
            super (comps);
        }

        CliqueImpl (BitSet C, long[] gnodes, Set<StitchKey> keys,
                    GraphDatabaseService gdb) {
            entities = new Entity[C.cardinality()];
            try (Transaction tx = gdb.beginTx()) {
                Node[] nodes = new Node[C.cardinality()];
                for (int i = C.nextSetBit(0), j = 0;
                     i >= 0; i = C.nextSetBit(i+1)) {
                    nodes[j] = gdb.getNodeById(gnodes[i]);
                    entities[j] = Entity._getEntity(nodes[j]);
                    this.nodes.add(gnodes[i]);
                    ++j;
                }

                for (StitchKey key : keys)
                    update (nodes, key);
                tx.success();
            }
            id = Util.sha1(nodes).substring(0, 9);
        }

        void update (Node[] nodes, StitchKey key) {
            final Map<Object, Integer> counts = new HashMap<Object, Integer>();
            for (int i = 0; i < nodes.length; ++i) {
                for (Relationship rel
                         : nodes[i].getRelationships(key, Direction.BOTH)) {
                    for (int j = i+1; j < nodes.length; ++j) {
                        if (nodes[j].equals(rel.getOtherNode(nodes[i]))) {
                            if (rel.hasProperty(VALUE)) {
                                Object val = rel.getProperty(VALUE);
                                Integer c = counts.get(val);
                                counts.put(val, c != null ? c+1:1);
                            }
                        }
                    }
                }
            }

            int total = nodes.length*(nodes.length-1)/2;
            Object value = null;            
            for (Map.Entry<Object, Integer> me : counts.entrySet()) {
                Integer c = me.getValue();
                if (c == total) {
                    value = me.getKey();
                    break;
                }
                else /*if (c > 1)*/ { // multiple values for this stitchkey
                    value = value == null
                        ? me.getKey() : Util.merge(value, me.getKey());
                }
            }

            if (value != null && value.getClass().isArray()) {
                // make sure it's sorted from most frequent to least
                Object[] sorted = new Object[Array.getLength(value)];
                for (int i = 0; i < sorted.length; ++i)
                    sorted[i] = Array.get(value, i);
                
                Arrays.sort(sorted, new Comparator () {
                        public int compare (Object o1, Object o2) {
                            Integer c1 = counts.get(o1);
                            Integer c2 = counts.get(o2);
                            return c2 - c1;
                        }
                    });
                
                for (int i = 0; i < sorted.length; ++i)
                    Array.set(value, i, sorted[i]);
            }

            values.put(key, value);
        }
        
        public Map<StitchKey, Object> values () { return values; }
        
        @Override
        public Clique add (Component clique) {
            CliqueImpl ci = null;           
            List<Entity> ov = ov (clique);
            if (!ov.isEmpty()) {
                ci = new CliqueImpl (this, clique);
                if (clique instanceof Clique) {
                    ci.values.putAll(((Clique)clique).values());
                }
                ci.values.putAll(values);
            }
            return ci;
        }

        @Override
        public Double score () {
            return Math.pow(size(), 1. - CLIQUE_WEIGHT)
                * Math.pow(values.size(), CLIQUE_WEIGHT);
        }
    }
    
    /*
     * using the standard Bron-Kerbosch enumeration algorithm
     */
    static class CliqueEnumeration {
        final GraphDatabaseService gdb;
        final Map<BitSet, EnumSet<StitchKey>> cliques =
            new HashMap<BitSet, EnumSet<StitchKey>>();
        final StitchKey[] keys;
        
        CliqueEnumeration (GraphDatabaseService gdb, StitchKey... keys) {
            this.gdb = gdb;
            this.keys = keys;
        }

        public boolean enumerate (long[] nodes, CliqueVisitor visitor) {
            cliques.clear();
            
            for (StitchKey key : keys)
                enumerate (key, nodes);
            
            for (Map.Entry<BitSet, EnumSet<StitchKey>> me
                     : cliques.entrySet()) {
                
                Clique clique = new CliqueImpl
                    (me.getKey(), nodes, me.getValue(), gdb);
                
                // filter out any clique that has multiple values for
                //   a particular stitch key
                Map<StitchKey, Object> values = clique.values();
                EnumSet<StitchKey> keys = EnumSet.noneOf(StitchKey.class);
                for (Map.Entry<StitchKey, Object> e : values.entrySet()) {
                    if (e.getValue().getClass().isArray())
                        keys.add(e.getKey());
                }
                
                for (StitchKey k : keys)
                    values.remove(k);
                
                if (!values.isEmpty() && !visitor.clique(clique))
                    return false;
            }
            
            return true;
        }
        
        void enumerate (StitchKey key, long[] nodes) {
            BitSet P = new BitSet (nodes.length);
            for (int i = 0; i < nodes.length; ++i) {
                P.set(i);
            }
            BitSet C = new BitSet (nodes.length);
            BitSet S = new BitSet (nodes.length);
            Graph G = new Graph (gdb, key, nodes);

            /*
            { Set<Long> g = new TreeSet<Long>();
                for (int i = 0; i < nodes.length; ++i)
                    g.add(nodes[i]);
                logger.info("Clique enumeration "+key+" G="+g+"...");
            }
            */
            
            bronKerbosch (G, C, P, S);
        }

        boolean bronKerbosch (Graph G, BitSet C, BitSet P, BitSet S) {
            boolean done = false;
            if (P.isEmpty() && S.isEmpty()) {
                //logger.info("@Clique "+C);
                // only consider cliques that are of size >= CLIQUE_MINSIZE
                if (C.cardinality() >= CLIQUE_MINSIZE) {
                    BitSet c = (BitSet)C.clone();
                    
                    EnumSet<StitchKey> keys = cliques.get(c);
                    if (keys == null) {
                        cliques.put(c, EnumSet.of(G.key()));
                        //logger.info("Clique found.."+c);
                    }
                    else
                        keys.add(G.key());
                    
                    done = C.cardinality() == G.size();
                }
            }
            else {
                for (int u = P.nextSetBit(0); u >=0 && !done ;
                     u = P.nextSetBit(u+1)) {
                    P.clear(u);
                    BitSet PP = (BitSet)P.clone();
                    BitSet SS = (BitSet)S.clone();
                    PP.and(G.edges(u));
                    SS.and(G.edges(u));
                    C.set(u);
                    done = bronKerbosch (G, C, PP, SS);
                    C.clear(u);
                    S.set(u);
                }
            }
            
            return done;
        }
    }

    static class MaxCliqueEnum {
        final GraphDatabaseService gdb;
        final Map<BitSet, CliqueImpl> cliques =
            new HashMap<BitSet, CliqueImpl>();
        final StitchKey[] keys;
        
        MaxCliqueEnum (GraphDatabaseService gdb, StitchKey[] keys) {
            this.gdb = gdb;
            this.keys = keys;
        }

        public boolean enumerate (long[] nodes, CliqueVisitor visitor) {
            cliques.clear();
            
            /*
             * for each key, we first identify all unique values; then
             * for each key-value combination, we construct a subgraph
             * that spans only those key-value nodes whereas in 
             * CliqueEnumeration we need to enumerate over all maximal
             * cliques for all possible stitch key-value combinations.
             */
            ComponentImpl subgraph = new ComponentImpl (gdb, nodes);
            logger.info("** "+subgraph);
            for (StitchKey key : keys) {
                // find all unique values for this subgraph over the given
                // stitch key
                Object[] vals = subgraph.values(key);
                if (vals.length > 0) {
                    System.err.print(" ++  "+key+":");
                    for (int i = 0; i < vals.length; ++i) {
                        Component comp = subgraph.filter(key, vals[i]);
                        System.err.print(" "+vals[i]+"("+comp.size()+")");
                        if (comp.size() >= CLIQUE_MINSIZE) {
                            maxclique (comp, key, vals[i]);
                        }
                    }
                    System.err.println();
                }
            }
            
            for (Map.Entry<BitSet, CliqueImpl> me: cliques.entrySet()) {
            }
            
            return true;
        }

        void maxclique (Component comp, StitchKey key, Object value) {
            Graph G = new Graph (comp);
            BitSet C = G.maxclique(key, value);
            CliqueImpl clique = cliques.get(C);
            if (clique == null) {
                cliques.put(C, clique);
            }
            Object val = clique.values().get(key);
            clique.values()
                .put(key, val == null ? value : Util.merge(val, value));
        }
    }

    protected final GraphDb graphDb;
    protected final GraphDatabaseService gdb;
    protected final TimelineIndex<Node> timeline;
    
    public EntityFactory (String dir) throws IOException {
        this (GraphDb.getInstance(dir));
    }
    
    public EntityFactory (File dir) throws IOException {
        this (GraphDb.getInstance(dir));
    }

    public EntityFactory (GraphDatabaseService gdb) {
        this (GraphDb.getInstance(gdb));
    }
    
    public EntityFactory (GraphDb graphDb) {
        if (graphDb == null)
            throw new IllegalArgumentException ("GraphDb instance is null!");
        
        this.graphDb = graphDb;
        this.gdb = graphDb.graphDb();
        try (Transaction tx = gdb.beginTx()) {
            this.timeline = new LuceneTimeline
                (gdb, gdb.index().forNodes(CNode.NODE_TIMELINE));
            tx.success();
        }
    }

    public GraphDb getGraphDb () { return graphDb; }
    public CacheFactory getCache () { return graphDb.getCache(); }
    public void setCache (CacheFactory cache) {
        graphDb.setCache(cache);
    }
    public void setCache (String cache) throws IOException {
        graphDb.setCache(CacheFactory.getInstance(cache));
    }
    
    public long getLastUpdated () { return graphDb.getLastUpdated(); }
    
    public GraphMetrics calcGraphMetrics() {
        return calcGraphMetrics (gdb, AuxNodeType.ENTITY,
                                 Entity.TYPES, Entity.KEYS);
    }

    public GraphMetrics calcGraphMetrics (String label) {
        return calcGraphMetrics (Label.label(label));
    }
    
    public GraphMetrics calcGraphMetrics (Label label) {
        return calcGraphMetrics (gdb, label, Entity.TYPES, Entity.KEYS);
    }

    public static GraphMetrics calcGraphMetrics
        (GraphDatabaseService gdb) {
        return calcGraphMetrics
            (gdb, AuxNodeType.ENTITY, Entity.TYPES, Entity.KEYS);
    }

    public static GraphMetrics calcGraphMetrics
        (GraphDatabaseService gdb, EntityType[] types,
         RelationshipType[] keys) {
        return calcGraphMetrics (gdb, AuxNodeType.ENTITY, types, keys);
    }

    public static GraphMetrics calcGraphMetrics (Stream<Node> nodes) {
        return calcGraphMetrics (nodes, Entity.TYPES, Entity.KEYS);
    }
    
    public static GraphMetrics calcGraphMetrics
        (Stream<Node> nodes, EntityType[] types, RelationshipType[] keys) {
        DefaultGraphMetrics metrics = new DefaultGraphMetrics ();
        nodes.forEach(node -> {
                for (EntityType t : types) {
                    if (node.hasLabel(t)) {
                        Integer c = metrics.entityHistogram.get(t);
                        metrics.entityHistogram.put(t, c != null ? c+1:1);
                    }
                }
                int nrel = 0;
                for (Relationship rel
                         : node.getRelationships(Direction.BOTH, keys)) {
                    Node xn = rel.getOtherNode(node);
                    // do we count self reference?
                    if (!xn.equals(node)) {
                        ++metrics.stitchCount;
                    }
                    String key = rel.getType().name();
                    Integer c = metrics.stitchHistogram.get(key);
                    metrics.stitchHistogram.put(key, c != null ? c+1:1);
                    ++nrel;
                }
                Integer c = metrics.entitySizeDistribution.get(nrel);
                metrics.entitySizeDistribution.put(nrel, c!=null ? c+1:1);
                
                if (node.hasLabel(AuxNodeType.COMPONENT)) {
                    Component comp = new ComponentImpl (node);
                    Integer cnt = metrics.connectedComponentHistogram.get
                        (comp.size());
                    metrics.connectedComponentHistogram.put
                        (comp.size(), cnt!= null ? cnt+1:1);
                    if (comp.size() == 1) {
                        ++metrics.singletonCount;
                    }
                    ++metrics.connectedComponentCount;
                    /*
                      if (comp.size() > 5)
                      logger.info("Component "+node.getId()
                      +" has "+comp.size()+" member(s)!");
                    */
                }
                
                ++metrics.entityCount;
            });
        
        // we're double counting, so now we correct the counts
        metrics.stitchCount /= 2;
        for (String k : metrics.stitchHistogram.keySet()) {
            metrics.stitchHistogram.put
                (k, metrics.stitchHistogram.get(k)/2);
        }
        
        return metrics;
    }
    
    public static GraphMetrics calcGraphMetrics
        (GraphDatabaseService gdb, Label label,
         EntityType[] types, RelationshipType[] keys) {

        GraphMetrics metrics = null;
        try (Transaction tx = gdb.beginTx()) {
            metrics = calcGraphMetrics
                (gdb.findNodes(label).stream(), types, keys);
            tx.success();
        }
        return metrics;
    }

    public static GraphMetrics calcGraphMetrics (Component component) {
        return calcGraphMetrics (component.stream().map(e -> e._node()));
    }

    static boolean hasLabel (Node node, Label... labels) {
        for (Label l : labels)
            if (node.hasLabel(l))
                return true;
        return false;
    }
    
    /*
     * return the top k stitched values for a given stitch key
     */
    public Map<Object, Integer> getStitchedValueDistribution (StitchKey key) {
        return getStitchedValueDistribution (key, (Label[])null);
    }
    
    public Map<Object, Integer> getStitchedValueDistribution
        (StitchKey key, String... labels) {
        Label[] l = null;
        if (labels != null && labels.length > 0) {
            l = new Label[labels.length];
            for (int i = 0; i < labels.length; ++i)
                l[i] = DynamicLabel.label(labels[i]);
        }
        return getStitchedValueDistribution (key, l);
    }
    
    public Map<Object, Integer> getStitchedValueDistribution
        (StitchKey key, Label... labels) {
        Map<Object, Integer> values = new HashMap<Object, Integer>();
        try (Transaction tx = gdb.beginTx()) {
            for (Relationship rel : gdb.getAllRelationships()) {
                if (rel.isType(key) && rel.hasProperty(Entity.VALUE)
                    && (labels == null
                        || (hasLabel (rel.getStartNode(), labels)
                            && hasLabel (rel.getEndNode(), labels)))) {
                        Object val = rel.getProperty(Entity.VALUE);
                        Integer c = values.get(val);
                        values.put(val, c!=null ? c+1:1);
                }
            }
            tx.success();
        }
        return values;
    }

    public int getStitchedValueCount (StitchKey key, Object value) {
        int count = 0;
        try (Transaction tx = gdb.beginTx()) {
            RelationshipIndex index = gdb.index().forRelationships
                (Entity.relationshipIndexName());
            IndexHits<Relationship> hits = index.get(key.name(), value);
            count = hits.size();
            hits.close();
            tx.success();
        }
        return count;
    }

    /*
     * globally delete the value for a particular stitch key
     */
    public void delete (StitchKey key, Object value) {
        try (Transaction tx = gdb.beginTx()) {
            RelationshipIndex index =
                gdb.index().forRelationships(Entity.relationshipIndexName());
            IndexHits<Relationship> hits = index.get(key.name(), value);
            try {
                for (Relationship rel : hits) {
                    CNode._delete(rel.getStartNode(), key.name(), value);
                    CNode._delete(rel.getEndNode(), key.name(), value);
                    index.remove(rel);
                    rel.delete();
                }
            }
            finally {
                hits.close();
            }
            tx.success();
        }
    }

    public int delete (DataSource source) {
        return delete (source.getKey());
    }
    
    /*
     * delete the entire data source; note that source can either be
     * the name or its key
     */
    public int delete (String source) {
        int count = 0;
        try (Transaction tx = gdb.beginTx()) {
            Index<Node> index = gdb.index().forNodes
                (DataSource.nodeIndexName());

            Node n = index.get(KEY, source).getSingle();
            if (n == null) {
                source = DataSourceFactory.sourceKey(source);
                n = index.get(KEY, source).getSingle();
            }
            
            if (n != null) {
                Label label = DynamicLabel.label(source);
                for (Iterator<Node> it = gdb.findNodes(label);
                     it.hasNext(); ) {
                    Node node = it.next();
                    Entity._getEntity(node).delete();
                    ++count;
                }
            }
            else {
                logger.warning("Can't find data source: "+source);
            }
            tx.success();
        }
        return count;
    }

    public Iterator<Entity[]> connectedComponents () {
        return new ConnectedComponents (gdb);
    }

    public Collection<Component> components () {
        List<Component> comps = new ArrayList<Component>();
        try (Transaction tx = gdb.beginTx()) {
            gdb.findNodes(AuxNodeType.COMPONENT).stream().forEach(node -> {
                    comps.add(new ComponentLazy (node));
                });
            tx.success();
        }
        
        return comps;
    }

    public void components (Consumer<Component> consumer) {
        try (Transaction tx = gdb.beginTx()) {
            gdb.findNodes(AuxNodeType.COMPONENT).stream().forEach(node -> {
                    consumer.accept(new ComponentLazy (node));
                });
        }
    }

    public Component component (long id) {
        try (Transaction tx = gdb.beginTx()) {
            Node node = gdb.getNodeById(id);
            if (!node.hasLabel(AuxNodeType.COMPONENT))
                node = CNode.getRoot(node);
            Component comp = new ComponentImpl (node);
            tx.success();
            
            return comp;
        }
    }

    public Entity[] entities (String label, int skip, int top) {
        return entities (DynamicLabel.label(label), skip, top);
    }
    
    public Entity[] entities (Label label, int skip, int top) {
        List<Entity> page = new ArrayList<Entity>();
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("skip", skip);
        params.put("top", top);

        //System.out.println("components: skip="+skip+" top="+top);
        try (Transaction tx = gdb.beginTx();
             Result result = gdb.execute
             ("match(n:`"+label+"`) return n skip {skip} limit {top}", params)
             ) {
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                //System.out.println("  rows: "+row);
                Node n = (Node)row.get("n");
                try {
                    page.add(Entity._getEntity(n));
                }
                catch (Exception ex) { // not an entity
                }
            }
            result.close();
            tx.success();
        }
        
        return page.toArray(new Entity[0]);
    }

    public Iterator<Entity> find (StitchKey key, Object value) {
        return find (key.name(), value);
    }

    public boolean cliqueEnumeration (CliqueVisitor visitor) {
        return cliqueEnumeration (Entity.KEYS, visitor);
    }

    public boolean cliqueEnumeration (String label, CliqueVisitor visitor) {
        return label != null ? 
            cliqueEnumeration (Entity.KEYS,
                               DynamicLabel.label(label), visitor)
            : cliqueEnumeration (Entity.KEYS, visitor);
    }
    
    public boolean cliqueEnumeration (StitchKey[] keys,
                                      String label, CliqueVisitor visitor) {
        if (keys == null || keys.length == 0)
            keys = Entity.KEYS;
        
        return label != null ?
            cliqueEnumeration (keys, DynamicLabel.label(label), visitor)
            : cliqueEnumeration (keys, visitor);
    }

    public boolean cliqueEnumeration (StitchKey[] keys,
                                      Label label, CliqueVisitor visitor) {
        try (Transaction tx = gdb.beginTx()) {
            List<Long> ids = new ArrayList<Long>();
            for (Iterator<Node> it = gdb.findNodes(label); it.hasNext(); ) {
                Node n = it.next();
                ids.add(n.getId());
            }
            long[] nodes = new long[ids.size()];
            int i = 0;
            for (Long id : ids) {
                nodes[i++] = id;
            }
            
            return cliqueEnumeration (keys, nodes, visitor);
        }
    }

    public boolean cliqueEnumeration (StitchKey[] keys, CliqueVisitor visitor) {
        /*
        ConnectedComponents cc = new ConnectedComponents (gdb);
        long[][] comps = cc.components();
        for (int i = 0; i < comps.length
                 && comps[i].length >= CLIQUE_MINSIZE; ++i) {
            if (!cliqueEnumeration (keys, comps[i], visitor))
                return false;
        }
        return true;
        */
        
        try (Transaction tx = gdb.beginTx()) {
            for (Iterator<Node> it = gdb.findNodes(AuxNodeType.COMPONENT);
                 it.hasNext();) {
                Node node = it.next();
                
                Integer rank = (Integer)node.getProperty(CNode.RANK);
                if (rank == null)
                    throw new RuntimeException ("Component node "+node.getId()
                                                +" has no rank!");
                if (rank >= CLIQUE_MINSIZE) {
                    Component comp = new ComponentImpl (node);
                    if (!cliqueEnumeration (keys, comp.nodes(), visitor))
                        return false;
                }
            }
        }
        
        return true;
    }

    public boolean cliqueEnumeration (long[] nodes, CliqueVisitor visitor) {
        return cliqueEnumeration (Entity.KEYS, nodes, visitor);
    }

    public boolean cliqueEnumeration
        (StitchKey[] keys, Entity[] entities, CliqueVisitor visitor) {
        long[] nodes = new long[entities.length];
        try (Transaction tx = gdb.beginTx()) {
            for (int i = 0; i < nodes.length; ++i)
                nodes[i] = entities[i].getId();
            
            return cliqueEnumeration (keys, nodes, visitor);
        }
    }
    
    public boolean cliqueEnumeration (Entity[] entities,
                                      CliqueVisitor visitor) {
        return cliqueEnumeration (Entity.KEYS, entities, visitor);
    }
    
    public boolean cliqueEnumeration (StitchKey[] keys,
                                      long[] nodes, CliqueVisitor visitor) {
        /*
        { EnumSet<StitchKey> set = EnumSet.noneOf(StitchKey.class);
            for (StitchKey k : keys) set.add(k);

            logger.info("enumerating cliques over "+set+" spanning "
                        +nodes.length+" nodes...");
        }
        */

        if (nodes.length >= CLIQUE_MINSIZE) {
            CliqueEnumeration clique = new CliqueEnumeration (gdb, keys); 
            // enumerate all cliques for this key
            return clique.enumerate(nodes, visitor);
        }
        return false;
    }

    public boolean cliqueEnumeration (StitchKey key, Object value,
                                      CliqueVisitor visitor) {
        Set<Long> matches = new HashSet<>();
        if (_filterStitchedNodes (gdb, matches, key, value) > 0) {
            CliqueEnumeration clique = new CliqueEnumeration (gdb, Entity.KEYS);
            return clique.enumerate
                (Util.toPrimitive(matches.toArray(new Long[0])), visitor);
        }
        return false;
    }

    static int _filterStitchedNodes (GraphDatabaseService gdb,
                                     Set<Long> matches,
                                     StitchKey key, Object value) {
        Map<String, Object> params = new HashMap<>();
        params.put("value", value);
        try (Transaction tx = gdb.beginTx();  Result result = gdb.execute
             ("match(n)-[e:`"+key
              +"`]-(m) where e.value = {value} "
              +"return id(n) as `n`, id(m) as `m`", params)) {
            int edges = 0;
            for (; result.hasNext(); ++edges) {
                Map<String, Object> row = result.next();
                matches.add((Long)row.get("n"));
                matches.add((Long)row.get("m"));
            }
            result.close();
            
            return edges;
        }
    }
    
    public Iterator<Entity> find (String key, Object value) {
        Iterator<Entity> iterator = null;
        try (Transaction tx = gdb.beginTx()) {
            Index<Node> index = gdb.index().forNodes(Entity.nodeIndexName());
            if (value.getClass().isArray()) {
                int len = Array.getLength(value);
                for (int i = 0; i < len; ++i) {
                    Object v = Array.get(value, i);
                    IndexHits<Node> hits = index.get(key, v);
                    // return on the first non-empty hits
                    if (hits.size() > 0) {
                        iterator = new EntityIterator (hits.iterator());
                        break;
                    }
                }
            }

            if (iterator == null)
                iterator = new EntityIterator
                    (index.get(key, value).iterator());
        }
        return iterator;
    }

    
    /**
     * iterate over entities of a particular data source
     */
    public Iterator<Entity> entities (DataSource source) {
        return entities (source.getKey());
    }

    public Iterator<Entity> entities (String label) {
        try (Transaction tx = gdb.beginTx()) {
            return new EntityIterator
                (gdb.findNodes(DynamicLabel.label(label)));
        }
    }

    public Entity[] entities (long[] ids) {
        try (Transaction tx = gdb.beginTx()) {
            return _entities (ids);
        }
    }

    public Entity[] _entities (long[] ids) {
        Entity[] entities = new Entity[ids.length];
        for (int i = 0; i < ids.length; ++i)
            entities[i] = _entity (ids[i]);
        return entities;
    }   

    public Entity entity (long id) {
        try (Transaction tx = gdb.beginTx()) {
            return _entity (id);
        }
    }

    public Entity _entity (long id) {
        return Entity._getEntity(gdb.getNodeById(id));  
    }
    
    /**
     * iterate over entities regardless of data source
     */
    public Iterator<Entity> entities () {
        try (Transaction tx = gdb.beginTx()) {
            return new EntityIterator (gdb.findNodes(AuxNodeType.ENTITY));
        }
    }
    
    public void shutdown () {
        graphDb.shutdown();
    }

    public void execute (Runnable r) {
        execute (r, true);
    }
    
    public void execute (Runnable r, boolean commit) {
        try (Transaction tx = gdb.beginTx()) {
            r.run();
            if (commit)
                tx.success();
        }
    }
 
    public <V> V execute (Callable<V> c) {
        return execute (c, true);
    }
    
    public <V> V execute (Callable<V> c, boolean commit) {
        V result = null;
        try (Transaction tx = gdb.beginTx()) {
            result = c.call();
            if (commit)
                tx.success();
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Can't execute callable", ex);
        }
        return result;  
    }

    public Entity createStitch (DataSource source, Component component) {
        try (Transaction tx = gdb.beginTx()) {
            Entity ent = _createStitch (source, component);
            tx.success();
            return ent;
        }
    }

    public Entity createStitch (DataSource source, long[] component) {
        try (Transaction tx = gdb.beginTx()) {
            Entity ent = _createStitch (source, component);
            tx.success();
            return ent;
        }       
    }
    
    public Entity _createStitch (DataSource source, long[] component) {
        return _createStitch (source, new ComponentImpl (gdb, component));
    }
    
    public Entity _createStitch (DataSource source, Component component) {
        Node node = gdb.createNode(AuxNodeType.STITCHED,
                                   DynamicLabel.label(source._getKey()));
        node.setProperty(SOURCE, source._getKey());
        node.setProperty(SCORE, component.score());

        Set<String> sources = new HashSet<String>();
        for (Entity e : component) {
            sources.add((String)e._node.getProperty(SOURCE));
        }
        for (String s : sources)
            node.addLabel(DynamicLabel.label(s));
        
        return Entity._getEntity(node);
    }

    // return the last k updated entities
    public Entity[] getLastUpdatedEntities (int k) {
        try (Transaction tx = gdb.beginTx()) {
            return _getLastUpdatedEntities (k);
        }
    }
    
    public Entity[] _getLastUpdatedEntities (int k) {
        IndexHits<Node> hits = timeline.getBetween
            (null, System.currentTimeMillis(), true);
        try {
            int max = Math.min(k, hits.size());
            Entity[] entities = new Entity[max];
            k = 0;
            for (Node n : hits) {
                try {
                    entities[k] = Entity._getEntity(n);
                    if (++k == entities.length)
                        break;
                }
                catch (IllegalArgumentException ex) {
                    // not an Entity node
                }
            }
            return entities;
        }
        finally {
            hits.close();
        }
    }

    public Entity getLastUpdatedEntity () {
        Entity[] ent = getLastUpdatedEntities (1);
        return ent != null && ent.length > 0 ? ent[0] : null;
    }
}
