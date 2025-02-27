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
package apoc.merge;

import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static org.junit.Assert.*;

import apoc.util.MapUtil;
import apoc.util.TestUtil;
import apoc.util.collection.Iterators;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.graphdb.*;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

public class MergeTest {

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule();

    @Before
    public void setUp() {
        TestUtil.registerProcedure(db, Merge.class);
    }

    @After
    public void teardown() {
        db.shutdown();
    }

    @Test
    public void testMergeNode() {
        testMergeNodeCommon(false);
    }

    @Test
    public void testMergeNodeWithStats() {
        testMergeNodeCommon(true);
    }

    // MERGE NODES
    private void testMergeNodeCommon(boolean isWithStats) {
        String procName = isWithStats ? "nodeWithStats" : "node";

        testCall(
                db,
                String.format("CALL apoc.merge.%s(['Person','Bastard'],{ssid:'123'}, {name:'John'})", procName),
                (row) -> {
                    Node node = (Node) row.get("node");
                    assertTrue(node.hasLabel(Label.label("Person")));
                    assertTrue(node.hasLabel(Label.label("Bastard")));
                    assertEquals("John", node.getProperty("name"));
                    assertEquals("123", node.getProperty("ssid"));

                    if (isWithStats) {
                        Map<String, Object> stats = (Map<String, Object>) row.get("stats");
                        assertEquals(2, stats.get("labelsAdded"));
                        assertEquals(1, stats.get("nodesCreated"));
                        assertEquals(2, stats.get("propertiesSet"));
                    }
                });
    }

    @Test
    public void testMergeNodeWithPreExisting() {
        db.executeTransactionally("CREATE (p:Person{ssid:'123', name:'Jim'})");
        testCall(db, "CALL apoc.merge.node(['Person'],{ssid:'123'}, {name:'John'}) YIELD node RETURN node", (row) -> {
            Node node = (Node) row.get("node");
            assertTrue(node.hasLabel(Label.label("Person")));
            assertEquals("Jim", node.getProperty("name"));
            assertEquals("123", node.getProperty("ssid"));
        });

        testResult(
                db,
                "match (p:Person) return count(*) as c",
                result -> assertEquals(1, (long) (Iterators.single(result.columnAs("c")))));
    }

    @Test
    public void testMergeWithNoLabel() {
        testCall(db, "CALL apoc.merge.node(null, {name:'John'}) YIELD node RETURN node", (row) -> {
            Node node = (Node) row.get("node");
            assertFalse(node.getLabels().iterator().hasNext());
            assertEquals("John", node.getProperty("name"));
        });

        testResult(
                db,
                "match (p) return count(*) as c",
                result -> assertEquals(1, (long) (Iterators.single(result.columnAs("c")))));
    }

    @Test
    public void testMergeNodeWithEmptyLabelList() {
        testCall(db, "CALL apoc.merge.node([], {name:'John'}) YIELD node RETURN node", (row) -> {
            Node node = (Node) row.get("node");
            assertFalse(node.getLabels().iterator().hasNext());
            assertEquals("John", node.getProperty("name"));
        });

        testResult(
                db,
                "match (p) return count(*) as c",
                result -> assertEquals(1, (long) (Iterators.single(result.columnAs("c")))));
    }

    @Test
    public void testMergeWithEmptyIdentityPropertiesShouldFail() {
        for (String idProps : new String[] {"null", "{}"}) {
            try {
                testCall(
                        db,
                        "CALL apoc.merge.node(['Person']," + idProps + ", {name:'John'}) YIELD node RETURN node",
                        row -> assertTrue(row.get("node") instanceof Node));
                fail();
            } catch (QueryExecutionException e) {
                assertTrue(e.getMessage().contains("you need to supply at least one identifying property for a merge"));
            }
        }
    }

    @Test
    public void testMergeNodeWithNullLabelsShouldFail() {
        try {
            testCall(
                    db,
                    "CALL apoc.merge.node([null], {name:'John'}) YIELD node RETURN node",
                    row -> assertTrue(row.get("node") instanceof Node));
            fail();
        } catch (QueryExecutionException e) {
            assertEquals(
                    e.getMessage(),
                    "Failed to invoke procedure `apoc.merge.node`: Caused by: java.lang.IllegalArgumentException: "
                            + "The list of label names may not contain any `NULL` or empty `STRING` values. If you wish to merge a `NODE` without a label, pass an empty list instead.");
        }
    }

    @Test
    public void testMergeNodeWithMixedLabelsContainingNullShouldFail() {
        try {
            testCall(
                    db,
                    "CALL apoc.merge.node(['Person', null], {name:'John'}) YIELD node RETURN node",
                    row -> assertTrue(row.get("node") instanceof Node));
            fail();
        } catch (QueryExecutionException e) {
            assertEquals(
                    e.getMessage(),
                    "Failed to invoke procedure `apoc.merge.node`: Caused by: java.lang.IllegalArgumentException: "
                            + "The list of label names may not contain any `NULL` or empty `STRING` values. If you wish to merge a `NODE` without a label, pass an empty list instead.");
        }
    }

    @Test
    public void testMergeNodeWithSingleEmptyLabelShouldFail() {
        try {
            testCall(
                    db,
                    "CALL apoc.merge.node([''], {name:'John'}) YIELD node RETURN node",
                    row -> assertTrue(row.get("node") instanceof Node));
            fail();
        } catch (QueryExecutionException e) {
            assertEquals(
                    e.getMessage(),
                    "Failed to invoke procedure `apoc.merge.node`: Caused by: java.lang.IllegalArgumentException: "
                            + "The list of label names may not contain any `NULL` or empty `STRING` values. If you wish to merge a `NODE` without a label, pass an empty list instead.");
        }
    }

    @Test
    public void testMergeNodeContainingMixedLabelsContainingEmptyStringShouldFail() {
        try {
            testCall(
                    db,
                    "CALL apoc.merge.node(['Person', ''], {name:'John'}) YIELD node RETURN node",
                    row -> assertTrue(row.get("node") instanceof Node));
            fail();
        } catch (QueryExecutionException e) {
            assertEquals(
                    e.getMessage(),
                    "Failed to invoke procedure `apoc.merge.node`: Caused by: java.lang.IllegalArgumentException: "
                            + "The list of label names may not contain any `NULL` or empty `STRING` values. If you wish to merge a `NODE` without a label, pass an empty list instead.");
        }
    }

    @Test
    public void testEscapeIdentityPropertiesWithSpecialCharactersShouldWork() {
        for (String key : new String[] {"normal", "i:d", "i-d", "i d"}) {
            Map<String, Object> identProps = MapUtil.map(key, "value");
            Map<String, Object> params = MapUtil.map("identProps", identProps);

            testCall(db, "CALL apoc.merge.node(['Person'], $identProps) YIELD node RETURN node", params, (row) -> {
                Node node = (Node) row.get("node");
                assertNotNull(node);
                assertTrue(node.hasProperty(key));
                assertEquals("value", node.getProperty(key));
            });
        }
    }

    @Test
    public void testLabelsWithSpecialCharactersShouldWork() {
        for (String label :
                new String[] {"Label with spaces", ":LabelWithColon", "label-with-dash", "LabelWithUmlautsÄÖÜ"}) {
            Map<String, Object> params = MapUtil.map("label", label);
            testCall(
                    db,
                    "CALL apoc.merge.node([$label],{id:1}, {name:'John'}) YIELD node RETURN node",
                    params,
                    row -> assertTrue(row.get("node") instanceof Node));
        }
    }

    // MERGE RELATIONSHIPS
    @Test
    public void testMergeRelationships() {
        db.executeTransactionally("create (:Person{name:'Foo'}), (:Person{name:'Bar'})");

        testCall(
                db,
                "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.relationship(s, 'KNOWS', {rid:123}, {since:'Thu'}, e) YIELD rel RETURN rel",
                (row) -> {
                    Relationship rel = (Relationship) row.get("rel");
                    assertEquals("KNOWS", rel.getType().name());
                    assertEquals(123L, rel.getProperty("rid"));
                    assertEquals("Thu", rel.getProperty("since"));
                });

        testCall(
                db,
                "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.relationship(s, 'KNOWS', {rid:123}, {since:'Fri'}, e) YIELD rel RETURN rel",
                (row) -> {
                    Relationship rel = (Relationship) row.get("rel");
                    assertEquals("KNOWS", rel.getType().name());
                    assertEquals(123L, rel.getProperty("rid"));
                    assertEquals("Thu", rel.getProperty("since"));
                });
        testCall(
                db,
                "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.relationship(s, 'OTHER', null, null, e) YIELD rel RETURN rel",
                (row) -> {
                    Relationship rel = (Relationship) row.get("rel");
                    assertEquals("OTHER", rel.getType().name());
                    assertTrue(rel.getAllProperties().isEmpty());
                });
    }

    @Test
    public void testRelationshipTypesWithSpecialCharactersShouldWork() {
        for (String relType : new String[] {"Reltype with space", ":ReltypeWithCOlon", "rel-type-with-dash"}) {
            Map<String, Object> params = MapUtil.map("relType", relType);
            testCall(
                    db,
                    "CREATE (a), (b) WITH a,b CALL apoc.merge.relationship(a, $relType, null, null, b) YIELD rel RETURN rel",
                    params,
                    row -> assertTrue(row.get("rel") instanceof Relationship));
        }
    }

    @Test
    public void testMergeRelWithNullRelTypeShouldFail() {
        try {
            testCall(
                    db,
                    "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e  CALL apoc.merge.relationship(s, null, null, null, e) YIELD rel RETURN rel",
                    row -> assertTrue(row.get("rel") instanceof Relationship));
            fail();
        } catch (QueryExecutionException e) {
            assertEquals(
                    e.getMessage(),
                    ("Failed to invoke procedure `apoc.merge.relationship`: Caused by: java.lang.IllegalArgumentException: "
                            + "It is not possible to merge a `RELATIONSHIP` without a `RELATIONSHIP` type."));
        }
    }

    @Test
    public void testMergeWithEmptyRelTypeShouldFail() {
        try {
            testCall(
                    db,
                    "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.relationship(s, '', null, null, e) YIELD rel RETURN rel",
                    row -> assertTrue(row.get("rel") instanceof Relationship));
            fail();
        } catch (QueryExecutionException e) {
            assertEquals(
                    e.getMessage(),
                    ("Failed to invoke procedure `apoc.merge.relationship`: Caused by: java.lang.IllegalArgumentException: "
                            + "It is not possible to merge a `RELATIONSHIP` without a `RELATIONSHIP` type."));
        }
    }

    // MERGE EAGER TESTS
    @Test
    public void testMergeEagerNode() {
        testMergeEagerCommon(false);
    }

    @Test
    public void testMergeEagerNodeWithStats() {
        testMergeEagerCommon(true);
    }

    private void testMergeEagerCommon(boolean isWithStats) {
        String procName = isWithStats ? "nodeWithStats" : "node";
        testCall(
                db,
                String.format("CALL apoc.merge.%s.eager(['Person','Bastard'],{ssid:'123'}, {name:'John'})", procName),
                (row) -> {
                    Node node = (Node) row.get("node");
                    assertTrue(node.hasLabel(Label.label("Person")));
                    assertTrue(node.hasLabel(Label.label("Bastard")));
                    assertEquals("John", node.getProperty("name"));
                    assertEquals("123", node.getProperty("ssid"));

                    if (isWithStats) {
                        final Map<String, Object> stats = (Map<String, Object>) row.get("stats");
                        assertEquals(2, stats.get("labelsAdded"));
                        assertEquals(1, stats.get("nodesCreated"));
                        assertEquals(2, stats.get("propertiesSet"));
                    }
                });
    }

    @Test
    public void testMergeEagerNodeWithOnCreate() {
        testCall(
                db,
                "CALL apoc.merge.node.eager(['Person','Bastard'],{ssid:'123'}, {name:'John'},{occupation:'juggler'}) YIELD node RETURN node",
                (row) -> {
                    Node node = (Node) row.get("node");
                    assertTrue(node.hasLabel(Label.label("Person")));
                    assertTrue(node.hasLabel(Label.label("Bastard")));
                    assertEquals("John", node.getProperty("name"));
                    assertEquals("123", node.getProperty("ssid"));
                    assertFalse(node.hasProperty("occupation"));
                });
    }

    @Test
    public void testMergeEagerNodeWithOnMatch() {
        db.executeTransactionally("CREATE (p:Person:Bastard {ssid:'123'})");
        testCall(
                db,
                "CALL apoc.merge.node.eager(['Person','Bastard'],{ssid:'123'}, {name:'John'}, {occupation:'juggler'}) YIELD node RETURN node",
                (row) -> {
                    Node node = (Node) row.get("node");
                    assertTrue(node.hasLabel(Label.label("Person")));
                    assertTrue(node.hasLabel(Label.label("Bastard")));
                    assertEquals("juggler", node.getProperty("occupation"));
                    assertEquals("123", node.getProperty("ssid"));
                    assertFalse(node.hasProperty("name"));
                });
    }

    @Test
    public void testMergeEagerNodesWithOnMatchCanMergeOnMultipleMatches() {
        db.executeTransactionally("UNWIND range(1,5) as index MERGE (:Person:`Bastard Man`{ssid:'123', index:index})");

        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "CALL apoc.merge.node.eager(['Person','Bastard Man'],{ssid:'123'}, {name:'John'}, {occupation:'juggler'}) YIELD node RETURN node");

            for (long index = 1; index <= 5; index++) {
                Node node = (Node) result.next().get("node");
                assertTrue(node.hasLabel(Label.label("Person")));
                assertTrue(node.hasLabel(Label.label("Bastard Man")));
                assertEquals("123", node.getProperty("ssid"));
                assertEquals(index, node.getProperty("index"));
                assertEquals("juggler", node.getProperty("occupation"));
                assertFalse(node.hasProperty("name"));
            }
        } catch (Exception e) {
            fail();
        }
    }

    @Test
    public void testMergeEagerRelationships() {
        testMergeRelsCommon(false);
    }

    @Test
    public void testMergeEagerRelationshipsWithStats() {
        testMergeRelsCommon(true);
    }

    private void testMergeRelsCommon(boolean isWithStats) {
        db.executeTransactionally("create (:Person{name:'Foo'}), (:Person{name:'Bar'})");

        String procName = isWithStats ? "relationshipWithStats" : "relationship";
        String returnClause = isWithStats ? "YIELD rel, stats RETURN rel, stats" : "YIELD rel RETURN rel";
        testCall(
                db,
                String.format(
                        "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.%s.eager(s, 'KNOWS', {rid:123}, {since:'Thu'}, e) %s",
                        procName, returnClause),
                (row) -> {
                    Relationship rel = (Relationship) row.get("rel");
                    assertEquals("KNOWS", rel.getType().name());
                    assertEquals(123L, rel.getProperty("rid"));
                    assertEquals("Thu", rel.getProperty("since"));

                    if (isWithStats) {
                        final Map<String, Object> stats = (Map<String, Object>) row.get("stats");
                        assertEquals(1, stats.get("relationshipsCreated"));
                        assertEquals(2, stats.get("propertiesSet"));
                    }
                });

        testCall(
                db,
                String.format(
                        "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.%s.eager(s, 'KNOWS', {rid:123}, {since:'Fri'}, e) %s",
                        procName, returnClause),
                (row) -> {
                    Relationship rel = (Relationship) row.get("rel");
                    assertEquals("KNOWS", rel.getType().name());
                    assertEquals(123L, rel.getProperty("rid"));
                    assertEquals("Thu", rel.getProperty("since"));

                    if (isWithStats) {
                        final Map<String, Object> stats = (Map<String, Object>) row.get("stats");
                        assertEquals(0, stats.get("relationshipsCreated"));
                        assertEquals(0, stats.get("propertiesSet"));
                    }
                });

        testCall(
                db,
                String.format(
                        "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.%s(s, 'OTHER', null, null, e) %s",
                        procName, returnClause),
                (row) -> {
                    Relationship rel = (Relationship) row.get("rel");
                    assertEquals("OTHER", rel.getType().name());
                    assertTrue(rel.getAllProperties().isEmpty());

                    if (isWithStats) {
                        final Map<String, Object> stats = (Map<String, Object>) row.get("stats");
                        assertEquals(1, stats.get("relationshipsCreated"));
                        assertEquals(0, stats.get("propertiesSet"));
                    }
                });
    }

    @Test
    public void testMergeEagerRelationshipsWithOnMatch() {
        db.executeTransactionally("create (:Person{name:'Foo'}), (:Person{name:'Bar'})");

        testCall(
                db,
                "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.relationship.eager(s, 'KNOWS', {rid:123}, {since:'Thu'}, e,{until:'Saturday'}) YIELD rel RETURN rel",
                (row) -> {
                    Relationship rel = (Relationship) row.get("rel");
                    assertEquals("KNOWS", rel.getType().name());
                    assertEquals(123L, rel.getProperty("rid"));
                    assertEquals("Thu", rel.getProperty("since"));
                    assertFalse(rel.hasProperty("until"));
                });

        testCall(
                db,
                "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.relationship.eager(s, 'KNOWS', {rid:123}, {}, e,{since:'Fri'}) YIELD rel RETURN rel",
                (row) -> {
                    Relationship rel = (Relationship) row.get("rel");
                    assertEquals("KNOWS", rel.getType().name());
                    assertEquals(123L, rel.getProperty("rid"));
                    assertEquals("Fri", rel.getProperty("since"));
                });
    }

    @Test
    public void testMergeEagerRelationshipsWithOnMatchCanMergeOnMultipleMatches() {
        db.executeTransactionally(
                "CREATE (foo:Person{name:'Foo'}), (bar:Person{name:'Bar'}) WITH foo, bar UNWIND range(1,3) as index CREATE (foo)-[:KNOWS {rid:123}]->(bar)");

        try (Transaction tx = db.beginTx()) {
            Result result = tx.execute(
                    "MERGE (s:Person{name:'Foo'}) MERGE (e:Person{name:'Bar'}) WITH s,e CALL apoc.merge.relationship.eager(s, 'KNOWS', {rid:123}, {}, e, {since:'Fri'}) YIELD rel RETURN rel");

            for (long index = 1; index <= 3; index++) {
                Relationship rel = (Relationship) result.next().get("rel");
                assertEquals("KNOWS", rel.getType().name());
                assertEquals(123L, rel.getProperty("rid"));
                assertEquals("Fri", rel.getProperty("since"));
            }
        } catch (Exception e) {
            fail();
        }
    }

    @Test
    public void testMergeEagerWithEmptyIdentityPropertiesShouldFail() {
        for (String idProps : new String[] {"null", "{}"}) {
            try {
                testCall(
                        db,
                        "CALL apoc.merge.node(['Person']," + idProps + ", {name:'John'}) YIELD node RETURN node",
                        row -> assertTrue(row.get("node") instanceof Node));
                fail();
            } catch (QueryExecutionException e) {
                assertTrue(e.getMessage().contains("you need to supply at least one identifying property for a merge"));
            }
        }
    }
}
