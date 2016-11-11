package io.hgraphdb.models;

import io.hgraphdb.*;
import io.hgraphdb.mutators.EdgeIndexRemover;
import io.hgraphdb.mutators.EdgeIndexWriter;
import io.hgraphdb.mutators.Mutator;
import io.hgraphdb.mutators.Mutators;
import io.hgraphdb.readers.EdgeIndexReader;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.BinaryPrefixComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.util.*;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.javatuples.Pair;
import org.javatuples.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

public class EdgeIndexModel extends BaseModel {

    public static final Logger LOGGER = LoggerFactory.getLogger(EdgeIndexModel.class);

    public EdgeIndexModel(HBaseGraph graph, Table table) {
        super(graph, table);
    }

    public void writeEdgeEndpoints(Edge edge) {
        Iterator<IndexMetadata> indices = graph.getIndices(
                OperationType.WRITE, IndexType.EDGE, edge.label(), ((HBaseEdge) edge).getPropertyKeys());
        EdgeIndexWriter indexWriter = new EdgeIndexWriter(graph, edge, indices);
        Mutator writer = new EdgeIndexWriter(graph, edge, Constants.CREATED_AT);
        Mutators.write(table, indexWriter, writer);
    }

    public void writeEdgeIndex(Edge edge, IndexMetadata index) {
        EdgeIndexWriter indexWriter = new EdgeIndexWriter(graph, edge, IteratorUtils.of(index));
        Mutators.write(table, indexWriter);
    }

    public void deleteEdgeEndpoints(Edge edge) {
        Iterator<IndexMetadata> indices = graph.getIndices(
                OperationType.WRITE, IndexType.EDGE, edge.label(), ((HBaseEdge) edge).getPropertyKeys());
        EdgeIndexRemover indexWriter = new EdgeIndexRemover(graph, edge, indices);
        Mutator writer = new EdgeIndexRemover(graph, edge, Constants.CREATED_AT, null);
        Mutators.write(table, writer, indexWriter);
    }

    public void deleteEdgeIndex(Edge edge, IndexMetadata.Key key, Long ts) {
        Mutator writer = new EdgeIndexRemover(graph, edge, key.propertyKey(), ts);
        Mutators.write(table, writer);
    }

    public Iterator<Edge> edges(HBaseVertex vertex, Direction direction, String... labels) {
        Tuple cacheKey = labels.length > 0
                ? new Pair<>(direction, Arrays.asList(labels)) : new Unit<>(direction);
        Iterator<Edge> edges = vertex.getEdgesFromCache(cacheKey);
        if (edges != null) {
            return edges;
        }
        Scan scan = getEdgesScan(vertex, direction, Constants.CREATED_AT, labels);
        return performEdgesScan(vertex, scan, cacheKey, edge -> true);
    }

    public Iterator<Edge> edges(HBaseVertex vertex, Direction direction, String label,
                                String key, Object value) {
        byte[] valueBytes = Serializer.serialize(value);
        Tuple cacheKey = new Quartet<>(direction, label, key, ByteBuffer.wrap(valueBytes));
        Iterator<Edge> edges = vertex.getEdgesFromCache(cacheKey);
        if (edges != null) {
            return edges;
        }
        final boolean useIndex = !key.equals(Constants.CREATED_AT)
                && graph.hasIndex(OperationType.READ, IndexType.EDGE, label, key);
        Scan scan = useIndex
                ? getEdgesScan(vertex, direction, label, key, value)
                : getEdgesScan(vertex, direction, Constants.CREATED_AT, label);
        return performEdgesScan(vertex, scan, cacheKey, edge -> {
            if (useIndex) return true;
            byte[] propValueBytes = Serializer.serialize(edge.getProperty(key));
            return Bytes.compareTo(propValueBytes, valueBytes) == 0;
        });
    }

    public Iterator<Edge> edges(HBaseVertex vertex, Direction direction, String label,
                                String key, Object inclusiveFromValue, Object exclusiveToValue) {
        byte[] fromBytes = Serializer.serialize(inclusiveFromValue);
        byte[] toBytes = Serializer.serialize(exclusiveToValue);
        Tuple cacheKey = new Quintet<>(direction, label, key, ByteBuffer.wrap(fromBytes), ByteBuffer.wrap(toBytes));
        Iterator<Edge> edges = vertex.getEdgesFromCache(cacheKey);
        if (edges != null) {
            return edges;
        }
        final boolean useIndex = !key.equals(Constants.CREATED_AT)
                && graph.hasIndex(OperationType.READ, IndexType.EDGE, label, key);
        Scan scan = useIndex
                ? getEdgesScan(vertex, direction, label, key, inclusiveFromValue, exclusiveToValue)
                : getEdgesScan(vertex, direction, Constants.CREATED_AT, label);
        return performEdgesScan(vertex, scan, cacheKey, edge -> {
            if (useIndex) return true;
            byte[] propValueBytes = Serializer.serialize(edge.getProperty(key));
            return Bytes.compareTo(propValueBytes, fromBytes) >= 0
                    && Bytes.compareTo(propValueBytes, toBytes) < 0;
        });
    }

    @SuppressWarnings("unchecked")
    private Iterator<Edge> performEdgesScan(HBaseVertex vertex, Scan scan, Tuple cacheKey,
                                            Predicate<HBaseEdge> filter) {
        List<Edge> cached = new ArrayList<>();
        final EdgeIndexReader parser = new EdgeIndexReader(graph);
        ResultScanner scanner;
        try {
            scanner = table.getScanner(scan);
            return IteratorUtils.<Result, Edge>flatMap(
                    IteratorUtils.concat(scanner.iterator(), IteratorUtils.of(Result.EMPTY_RESULT)),
                    result -> {
                        if (result == Result.EMPTY_RESULT) {
                            vertex.cacheEdges(cacheKey, cached);
                            return Collections.emptyIterator();
                        }
                        HBaseEdge edge = (HBaseEdge) parser.parse(result);
                        try {
                            if (!graph.isLazyLoading()) edge.load();
                            boolean passesFilter = filter.test(edge);
                            if (passesFilter) {
                                cached.add(edge);
                                return IteratorUtils.of(edge);
                            } else {
                                return Collections.emptyIterator();
                            }
                        } catch (final HBaseGraphNotFoundException e) {
                            e.getElement().removeStaleIndex();
                            return Collections.emptyIterator();
                        }
                    });
        } catch (IOException e) {
            throw new HBaseGraphException(e);
        }
    }

    public Iterator<Vertex> vertices(HBaseVertex vertex, Direction direction, String... labels) {
        return IteratorUtils.flatMap(edges(vertex, direction, labels), transformEdge(vertex));
    }

    public Iterator<Vertex> vertices(HBaseVertex vertex, Direction direction, String label,
                                     String edgeKey, Object edgeValue) {
        return IteratorUtils.flatMap(edges(vertex, direction, label, edgeKey, edgeValue), transformEdge(vertex));
    }

    public Iterator<Vertex> vertices(HBaseVertex vertex, Direction direction, String label,
                                     String edgeKey, Object inclusiveFromEdgeValue, Object exclusiveToEdgeValue) {
        return IteratorUtils.flatMap(edges(vertex, direction, label, edgeKey,
                inclusiveFromEdgeValue, exclusiveToEdgeValue), transformEdge(vertex));
    }

    private Function<Edge, Iterator<Vertex>> transformEdge(HBaseVertex vertex) {
        return edge -> {
            Object inVertexId = edge.inVertex().id();
            Object outVertexId = edge.outVertex().id();
            Object vertexId = vertex.id().equals(inVertexId) ? outVertexId : inVertexId;
            try {
                HBaseVertex v = (HBaseVertex) graph.findOrCreateVertex(vertexId);
                if (!graph.isLazyLoading()) v.load();
                return IteratorUtils.of(v);
            } catch (final HBaseGraphNotFoundException e) {
                e.getElement().removeStaleIndex();
                return Collections.emptyIterator();
            }
        };
    }

    private Scan getEdgesScan(Vertex vertex, Direction direction, String key, String... labels) {
        LOGGER.debug("Executing Scan, type: {}, id: {}", "key", vertex.id());

        Scan scan;
        if (direction == Direction.BOTH) {
            byte[] startRow = serializeForRead(vertex, null, null);
            byte[] prefix = serializeForRead(vertex, null, null);
            scan = new Scan(startRow);
            scan.setFilter(new PrefixFilter(prefix));
        } else {
            byte[] startRow = serializeForRead(vertex, direction, null);
            scan = new Scan(startRow);
            scan.setFilter(new PrefixFilter(startRow));
        }

        if (labels.length > 0) {
            applyEdgeLabelsRowFilter(scan, vertex, direction, key, labels);
        }
        return scan;
    }

    private void applyEdgeLabelsRowFilter(Scan scan, Vertex vertex, Direction direction, String key, String... labels) {
        FilterList rowFilters = new FilterList(FilterList.Operator.MUST_PASS_ONE);
        for (String label : labels) {
            if (direction == Direction.BOTH) {
                applyEdgeLabelRowFilter(rowFilters, vertex, Direction.IN, label, key);
                applyEdgeLabelRowFilter(rowFilters, vertex, Direction.OUT, label, key);
            } else {
                applyEdgeLabelRowFilter(rowFilters, vertex, direction, label, key);
            }
        }

        if (scan.getFilter() != null) {
            FilterList filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL);
            filterList.addFilter(scan.getFilter());
            filterList.addFilter(rowFilters);
            scan.setFilter(filterList);
        } else {
            scan.setFilter(rowFilters);
        }
    }

    private void applyEdgeLabelRowFilter(FilterList filters, Vertex vertex, Direction direction, String label, String key) {
        RowFilter rowFilter = new RowFilter(CompareFilter.CompareOp.EQUAL,
                new BinaryPrefixComparator(serializeForRead(vertex, direction, label, key, null)));
        filters.addFilter(rowFilter);
    }

    private Scan getEdgesScan(Vertex vertex, Direction direction, String label, String key, Object value) {
        LOGGER.debug("Executing Scan, type: {}, id: {}", "key-value", vertex.id());

        byte[] startRow = serializeForRead(vertex, direction, label, key, value);
        Scan scan = new Scan(startRow);
        scan.setFilter(new PrefixFilter(startRow));
        return scan;
    }

    private Scan getEdgesScan(Vertex vertex, Direction direction, String label, String key,
                              Object fromInclusiveValue, Object toExclusiveValue) {
        LOGGER.debug("Executing Scan, type: {}, id: {}", "key-range", vertex.id());

        byte[] startRow = serializeForRead(vertex, direction, label, key, fromInclusiveValue);
        byte[] stopRow = serializeForRead(vertex, direction, label, key, toExclusiveValue);
        return new Scan(startRow, stopRow);
    }

    public byte[] serializeForRead(Vertex vertex, Direction direction, String label) {
        return serializeForRead(vertex, direction, label, Constants.CREATED_AT, null);
    }

    public byte[] serializeForRead(Vertex vertex, Direction direction, String label, String key, Object value) {
        PositionedByteRange buffer = new SimplePositionedMutableByteRange(4096);
        Serializer.serializeWithSalt(buffer, vertex.id());
        if (direction != null) {
            OrderedBytes.encodeInt8(buffer, direction == Direction.IN ? (byte) 1 : (byte) 0, Order.ASCENDING);
            if (label != null) {
                OrderedBytes.encodeString(buffer, label, Order.ASCENDING);
                if (key != null) {
                    OrderedBytes.encodeString(buffer, key, Order.ASCENDING);
                    if (value != null) {
                        Serializer.serialize(buffer, value);
                    }
                }
            }
        }
        buffer.setLength(buffer.getPosition());
        buffer.setPosition(0);
        byte[] bytes = new byte[buffer.getRemaining()];
        buffer.get(bytes);
        return bytes;
    }

    public byte[] serializeForWrite(Edge edge, Direction direction, String key) {
        Object inVertexId = edge.inVertex().id();
        Object outVertexId = edge.outVertex().id();
        PositionedByteRange buffer = new SimplePositionedMutableByteRange(4096);
        Serializer.serializeWithSalt(buffer, direction == Direction.IN ? inVertexId : outVertexId);
        OrderedBytes.encodeInt8(buffer, direction == Direction.IN ? (byte) 1 : (byte) 0, Order.ASCENDING);
        OrderedBytes.encodeString(buffer, edge.label(), Order.ASCENDING);
        OrderedBytes.encodeString(buffer, key, Order.ASCENDING);
        Serializer.serialize(buffer, key.equals(Constants.CREATED_AT) ? ((HBaseEdge) edge).createdAt() : edge.value(key));
        Serializer.serialize(buffer, direction == Direction.IN ? outVertexId : inVertexId);
        Serializer.serialize(buffer, edge.id());
        buffer.setLength(buffer.getPosition());
        buffer.setPosition(0);
        byte[] bytes = new byte[buffer.getRemaining()];
        buffer.get(bytes);
        return bytes;
    }

    public Edge deserialize(Result result) {
        byte[] bytes = result.getRow();
        PositionedByteRange buffer = new SimplePositionedByteRange(bytes);
        Object vertexId1 = Serializer.deserializeWithSalt(buffer);
        Direction direction = OrderedBytes.decodeInt8(buffer) == 1 ? Direction.IN : Direction.OUT;
        String label = OrderedBytes.decodeString(buffer);
        String key = OrderedBytes.decodeString(buffer);
        Object value = Serializer.deserialize(buffer);
        Object vertexId2 = Serializer.deserialize(buffer);
        Cell createdAttsCell = result.getColumnLatestCell(Constants.DEFAULT_FAMILY_BYTES, Constants.CREATED_AT_BYTES);
        Long createdAt = Serializer.deserialize(CellUtil.cloneValue(createdAttsCell));
        Object edgeId = Serializer.deserialize(buffer);
        Map<String, Object> properties = new HashMap<>();
        properties.put(key, value);
        HBaseEdge newEdge;
        if (direction == Direction.IN) {
            newEdge = new HBaseEdge(graph, edgeId, label, createdAt, null, properties, false,
                    graph.findOrCreateVertex(vertexId1),
                    graph.findOrCreateVertex(vertexId2));
        } else {
            newEdge = new HBaseEdge(graph, edgeId, label, createdAt, null, properties, false,
                    graph.findOrCreateVertex(vertexId2),
                    graph.findOrCreateVertex(vertexId1));
        }
        HBaseEdge edge = (HBaseEdge) graph.findOrCreateEdge(edgeId);
        edge.copyFrom(newEdge);
        edge.setIndexKey(new IndexMetadata.Key(IndexType.EDGE, label, key));
        edge.setIndexTs(createdAttsCell.getTimestamp());
        return edge;
    }
}
