/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package apoc.export.graphml;

import apoc.ApocConfig;
import apoc.Pools;
import apoc.export.cypher.ExportFileManager;
import apoc.export.cypher.FileManagerFactory;
import apoc.export.util.CountingReader;
import apoc.export.util.ExportConfig;
import apoc.export.util.ExportUtils;
import apoc.export.util.NodesAndRelsSubGraph;
import apoc.export.util.ProgressReporter;
import apoc.result.ProgressInfo;
import apoc.util.FileUtils;
import apoc.util.Util;
import apoc.util.collection.Iterables;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.neo4j.cypher.export.CypherResultSubGraph;
import org.neo4j.cypher.export.DatabaseSubGraph;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.security.URLAccessChecker;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.NotThreadSafe;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.TerminationGuard;

/**
 * @author mh
 * @since 22.05.16
 */
public class ExportGraphML {
    @Context
    public GraphDatabaseService db;

    @Context
    public Transaction tx;

    @Context
    public ApocConfig apocConfig;

    @Context
    public Pools pools;

    @Context
    public TerminationGuard terminationGuard;

    @Context
    public URLAccessChecker urlAccessChecker;

    @Procedure(name = "apoc.import.graphml", mode = Mode.WRITE)
    @Description("Imports a graph from the provided GraphML file.")
    public Stream<ProgressInfo> file(
            @Name("urlOrBinaryFile") Object urlOrBinaryFile, @Name("config") Map<String, Object> config) {
        ProgressInfo result = Util.inThread(pools, () -> {
            ExportConfig exportConfig = new ExportConfig(config);
            String file = null;
            String source = "binary";
            if (urlOrBinaryFile instanceof String) {
                file = (String) urlOrBinaryFile;
                source = "file";
            }
            ProgressReporter reporter = new ProgressReporter(null, null, new ProgressInfo(file, source, "graphml"));
            XmlGraphMLReader graphMLReader = new XmlGraphMLReader(db)
                    .reporter(reporter)
                    .batchSize(exportConfig.getBatchSize())
                    .relType(exportConfig.defaultRelationshipType())
                    .source(exportConfig.getSource())
                    .target(exportConfig.getTarget())
                    .nodeLabels(exportConfig.readLabels());

            if (exportConfig.storeNodeIds()) graphMLReader.storeNodeIds();

            try (CountingReader reader =
                    FileUtils.readerFor(urlOrBinaryFile, exportConfig.getCompressionAlgo(), urlAccessChecker)) {
                graphMLReader.parseXML(reader, terminationGuard);
            }

            return reporter.getTotal();
        });
        return Stream.of(result);
    }

    @NotThreadSafe
    @Procedure("apoc.export.graphml.all")
    @Description("Exports the full database to the provided GraphML file.")
    public Stream<ProgressInfo> all(@Name("file") String fileName, @Name("config") Map<String, Object> config)
            throws Exception {

        String source = String.format("database: nodes(%d), rels(%d)", Util.nodeCount(tx), Util.relCount(tx));
        return exportGraphML(fileName, source, new DatabaseSubGraph(tx), new ExportConfig(config));
    }

    @Procedure("apoc.export.graphml.data")
    @Description("Exports the given `NODE` and `RELATIONSHIP` values to the provided GraphML file.")
    public Stream<ProgressInfo> data(
            @Name("nodes") List<Node> nodes,
            @Name("rels") List<Relationship> rels,
            @Name("file") String fileName,
            @Name("config") Map<String, Object> config)
            throws Exception {

        String source = String.format("data: nodes(%d), rels(%d)", nodes.size(), rels.size());
        return exportGraphML(fileName, source, new NodesAndRelsSubGraph(tx, nodes, rels), new ExportConfig(config));
    }

    @Procedure("apoc.export.graphml.graph")
    @Description("Exports the given graph to the provided GraphML file.")
    public Stream<ProgressInfo> graph(
            @Name("graph") Map<String, Object> graph,
            @Name("file") String fileName,
            @Name("config") Map<String, Object> config)
            throws Exception {

        Collection<Node> nodes = (Collection<Node>) graph.get("nodes");
        Collection<Relationship> rels = (Collection<Relationship>) graph.get("relationships");
        String source = String.format("graph: nodes(%d), rels(%d)", nodes.size(), rels.size());
        return exportGraphML(fileName, source, new NodesAndRelsSubGraph(tx, nodes, rels), new ExportConfig(config));
    }

    @NotThreadSafe
    @Procedure("apoc.export.graphml.query")
    @Description(
            "Exports the given `NODE` and `RELATIONSHIP` values from the Cypher statement to the provided GraphML file.")
    public Stream<ProgressInfo> query(
            @Name("statement") String query, @Name("file") String fileName, @Name("config") Map<String, Object> config)
            throws Exception {
        ExportConfig c = new ExportConfig(config);
        Result result = tx.execute(query);
        SubGraph graph = CypherResultSubGraph.from(tx, result, c.getRelsInBetween(), false);
        String source = String.format(
                "statement: nodes(%d), rels(%d)",
                Iterables.count(graph.getNodes()), Iterables.count(graph.getRelationships()));
        return exportGraphML(fileName, source, graph, c);
    }

    private Stream<ProgressInfo> exportGraphML(
            @Name("file") String fileName, String source, SubGraph graph, ExportConfig exportConfig) throws Exception {
        apocConfig.checkWriteAllowed(exportConfig, fileName);
        final String format = "graphml";
        ProgressReporter reporter = new ProgressReporter(null, null, new ProgressInfo(fileName, source, format));
        XmlGraphMLWriter exporter = new XmlGraphMLWriter();
        ExportFileManager cypherFileManager = FileManagerFactory.createFileManager(fileName, false, exportConfig);
        final PrintWriter graphMl = cypherFileManager.getPrintWriter(format);
        if (exportConfig.streamStatements()) {
            return ExportUtils.getProgressInfoStream(
                    db,
                    pools.getDefaultExecutorService(),
                    terminationGuard,
                    format,
                    exportConfig,
                    reporter,
                    cypherFileManager,
                    (reporterWithConsumer) -> {
                        try {
                            exporter.write(graph, graphMl, reporterWithConsumer, exportConfig);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        } else {
            exporter.write(graph, graphMl, reporter, exportConfig);
            closeWriter(graphMl);
            return reporter.stream();
        }
    }

    private void closeWriter(PrintWriter writer) {
        writer.flush();
        try {
            writer.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
