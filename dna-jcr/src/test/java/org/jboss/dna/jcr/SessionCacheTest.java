/*
 * JBoss DNA (http://www.jboss.org/dna)
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * See the AUTHORS.txt file in the distribution for a full listing of 
 * individual contributors.
 *
 * Unless otherwise indicated, all code in JBoss DNA is licensed
 * to you under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 * 
 * JBoss DNA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.dna.jcr;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.jboss.dna.graph.IsNodeWithChildren.hasChildren;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.matchers.JUnitMatchers.hasItems;
import static org.mockito.Mockito.stub;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.jcr.InvalidItemStateException;
import javax.jcr.nodetype.NodeType;
import org.jboss.dna.common.statistic.Stopwatch;
import org.jboss.dna.common.util.StringUtil;
import org.jboss.dna.graph.ExecutionContext;
import org.jboss.dna.graph.Graph;
import org.jboss.dna.graph.Location;
import org.jboss.dna.graph.Node;
import org.jboss.dna.graph.connector.inmemory.InMemoryRepositorySource;
import org.jboss.dna.graph.property.Name;
import org.jboss.dna.graph.property.Path;
import org.jboss.dna.graph.property.PathNotFoundException;
import org.jboss.dna.graph.property.Property;
import org.jboss.dna.jcr.SessionCache.NodeEditor;
import org.jboss.dna.jcr.cache.Children;
import org.jboss.dna.jcr.cache.NodeInfo;
import org.jboss.dna.jcr.cache.PropertyInfo;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoAnnotations.Mock;
import org.xml.sax.SAXException;

/**
 * 
 */
public class SessionCacheTest {

    private ExecutionContext context;
    private RepositoryNodeTypeManager repoTypes;
    private JcrNodeTypeManager nodeTypes;
    private Stopwatch sw;
    private Graph store;
    private SessionCache cache;
    private Map<String, Graph> storesByName;
    private Map<String, SessionCache> cachesByName;

    @Mock
    private JcrSession session;

    @Before
    public void beforeEach() throws Exception {
        MockitoAnnotations.initMocks(this);
        sw = new Stopwatch();
        storesByName = new HashMap<String, Graph>();
        cachesByName = new HashMap<String, SessionCache>();

        context = new ExecutionContext();
        context.getNamespaceRegistry().register("vehix", "http://example.com/vehicles");

        stub(session.getExecutionContext()).toReturn(context);
        stub(session.namespaces()).toReturn(context.getNamespaceRegistry());

        // Load up all the node types ...
        JcrNodeTypeSource nodeTypeSource = null;
        nodeTypeSource = new JcrBuiltinNodeTypeSource(this.context, nodeTypeSource);
        nodeTypeSource = new DnaBuiltinNodeTypeSource(this.context, nodeTypeSource);
        nodeTypeSource = new VehixNodeTypeSource(context, nodeTypeSource);
        repoTypes = new RepositoryNodeTypeManager(context, nodeTypeSource);
        nodeTypes = new JcrNodeTypeManager(this.context, repoTypes);
        stub(session.nodeTypeManager()).toReturn(nodeTypes);

        // Now set up the graph and session cache ...
        store = getGraph("vehicles"); // imports the "/src/test/resources/vehicles.xml" file
        cache = getCache("vehicles");
    }

    /**
     * Define the node types for the "vehix" namespace.
     */
    public static class VehixNodeTypeSource extends AbstractJcrNodeTypeSource {
        private final List<JcrNodeType> primaryNodeTypes;
        private final List<JcrNodeType> mixinNodeTypes;

        public VehixNodeTypeSource( ExecutionContext context,
                                    JcrNodeTypeSource predecessor ) {
            super(predecessor);
            this.primaryNodeTypes = new ArrayList<JcrNodeType>();
            this.mixinNodeTypes = new ArrayList<JcrNodeType>();
            Name carName = context.getValueFactories().getNameFactory().create("vehix:car");
            Name aircraftName = context.getValueFactories().getNameFactory().create("vehix:aircraft");
            JcrNodeType unstructured = findType(JcrNtLexicon.UNSTRUCTURED);

            // Add in the "vehix:car" node type (which extends "nt:unstructured") ...
            JcrNodeType car = new JcrNodeType(context, (RepositoryNodeTypeManager)null, carName,
                                              Arrays.asList(new NodeType[] {unstructured}), NO_PRIMARY_ITEM_NAME, NO_CHILD_NODES,
                                              NO_PROPERTIES, NOT_MIXIN, ORDERABLE_CHILD_NODES);

            // Add in the "vehix:aircraft" node type (which extends "nt:unstructured") ...
            JcrNodeType aircraft = new JcrNodeType(context, (RepositoryNodeTypeManager)null, aircraftName,
                                                   Arrays.asList(new NodeType[] {unstructured}), NO_PRIMARY_ITEM_NAME,
                                                   NO_CHILD_NODES, NO_PROPERTIES, NOT_MIXIN, ORDERABLE_CHILD_NODES);

            primaryNodeTypes.addAll(Arrays.asList(new JcrNodeType[] {car, aircraft,}));
        }

        /**
         * {@inheritDoc}
         * 
         * @see org.jboss.dna.jcr.AbstractJcrNodeTypeSource#getDeclaredMixinNodeTypes()
         */
        @Override
        public Collection<JcrNodeType> getDeclaredMixinNodeTypes() {
            return mixinNodeTypes;
        }

        /**
         * {@inheritDoc}
         * 
         * @see org.jboss.dna.jcr.AbstractJcrNodeTypeSource#getDeclaredPrimaryNodeTypes()
         */
        @Override
        public Collection<JcrNodeType> getDeclaredPrimaryNodeTypes() {
            return primaryNodeTypes;
        }

    }

    protected Graph createFrom( String repositoryName,
                                String workspaceName,
                                File file ) throws IOException, SAXException {
        InMemoryRepositorySource source = new InMemoryRepositorySource();
        source.setName(repositoryName);
        Graph graph = Graph.create(source, context);
        graph.createWorkspace().named(workspaceName);

        if (file != null) {
            graph.importXmlFrom(file).into("/");
        }
        return graph;
    }

    protected Graph getGraph( String name ) throws IOException, SAXException {
        Graph graph = storesByName.get(name);
        if (graph == null) {
            graph = createFrom(name, name + " workspace", new File("src/test/resources/" + name + ".xml"));
            storesByName.put(name, graph);
        }
        return graph;
    }

    protected SessionCache getCache( String name ) throws IOException, SAXException {
        SessionCache cache = cachesByName.get(name);
        if (cache == null) {
            cache = new SessionCache(session, name + " workspace", context, nodeTypes, getGraph(name));
            cachesByName.put(name, cache);
        }
        return cache;
    }

    protected Name name( String name ) {
        return context.getValueFactories().getNameFactory().create(name);
    }

    protected Path path( String path ) {
        return context.getValueFactories().getPathFactory().create(path);
    }

    protected Path.Segment segment( String segment ) {
        return context.getValueFactories().getPathFactory().createSegment(segment);
    }

    protected String pad( String name ) {
        return StringUtil.justifyLeft(name, 22, ' ');
    }

    protected void assertSameProperties( NodeInfo nodeInfo,
                                         Node dnaNode ) {
        UUID uuid = dnaNode.getLocation().getUuid();
        assertThat(nodeInfo.getUuid(), is(uuid));
        assertThat(nodeInfo.getOriginalLocation().getUuid(), is(uuid));
        Set<Name> propertyNames = nodeInfo.getPropertyNames();
        for (Name propertyName : propertyNames) {
            PropertyInfo info = nodeInfo.getProperty(propertyName);
            assertThat(info.getNodeUuid(), is(uuid));
            assertThat(info.getPropertyId().getNodeId(), is(uuid));
            assertThat(info.getPropertyId().getPropertyName(), is(propertyName));
            assertThat(info.getProperty().getName(), is(propertyName));
            Property actual = dnaNode.getProperty(propertyName);
            if (actual != null) {
                assertThat(info.getProperty().size(), is(actual.size()));
                assertThat(info.getProperty().getValuesAsArray(), is(actual.getValuesAsArray()));
            } else {
                assertThat(propertyName, is(JcrLexicon.PRIMARY_TYPE));
            }
        }
    }

    protected void assertDoesExist( Graph graph,
                                    Location location ) {
        Node node = store.getNodeAt(location);
        assertThat(node, is(notNullValue()));
        assertThat(node.getLocation(), is(location));
    }

    protected void assertDoesNotExist( Graph graph,
                                       Location location ) {
        try {
            store.getNodeAt(location);
            fail("Shouldn't have found the node " + location);
        } catch (PathNotFoundException e) {
            // expected
        }
    }

    protected void assertDoesNotExist( Graph graph,
                                       Path path ) {
        try {
            store.getNodeAt(path);
            fail("Shouldn't have found the node " + path);
        } catch (PathNotFoundException e) {
            // expected
        }
    }

    protected void assertDoesNotExist( Graph graph,
                                       UUID uuid ) {
        try {
            store.getNodeAt(uuid);
            fail("Shouldn't have found the node " + uuid);
        } catch (PathNotFoundException e) {
            // expected
        }
    }

    protected void assertDoNotExist( Graph graph,
                                     List<Location> locations ) {
        for (Location location : locations)
            assertDoesNotExist(graph, location);
    }

    protected void assertDoesNotExist( SessionCache cache,
                                       UUID uuid ) throws InvalidItemStateException {
        assertThat(cache.findNodeInfoInCache(uuid), is(nullValue()));
    }

    protected void assertIsDeleted( SessionCache cache,
                                    UUID uuid ) {
        try {
            cache.findNodeInfoInCache(uuid);
            fail("Shouldn't have found the node " + uuid);
        } catch (InvalidItemStateException err) {
            // expected
        }
    }

    protected void assertDoesExist( SessionCache cache,
                                    UUID uuid ) throws InvalidItemStateException {
        NodeInfo info = cache.findNodeInfoInCache(uuid);
        assertThat(info, is(notNullValue()));
        assertThat(info.getUuid(), is(uuid));
    }

    @Test
    public void shouldCreateWithValidParameters() {
        assertThat(cache, is(notNullValue()));
    }

    @Test
    public void shouldFindNodeInfoForRootByUuid() throws Exception {
        Node root = store.getNodeAt("/");
        UUID rootUuid = root.getLocation().getUuid();
        NodeInfo rootInfo = cache.findNodeInfo(rootUuid);
        assertThat(rootInfo, is(notNullValue()));
        assertThat(rootInfo.getUuid(), is(rootUuid));
        assertThat(rootInfo.getDefinitionId().getNodeTypeName(), is(name("dna:root")));
        assertThat(rootInfo.getDefinitionId().getChildDefinitionName(), is(name("*")));
        assertThat(rootInfo.getPrimaryTypeName(), is(name("dna:root")));
        assertThat(rootInfo.getOriginalLocation().getPath().isRoot(), is(true));
        assertThat(rootInfo.getOriginalLocation().getUuid(), is(rootUuid));
        Set<Name> rootProperties = rootInfo.getPropertyNames();
        assertThat(rootProperties, hasItems(name("jcr:primaryType")));
        assertSameProperties(rootInfo, root);

        PropertyInfo info = rootInfo.getProperty(JcrLexicon.PRIMARY_TYPE);
        assertThat(info.getProperty().getFirstValue(), is((Object)name("dna:root")));
    }

    @Test
    public void shouldRepeatedlyFindRootNodeInfoByUuid() throws Exception {
        Node root = store.getNodeAt("/");
        UUID rootUuid = root.getLocation().getUuid();
        for (int i = 0; i != 20; ++i) {
            NodeInfo rootInfo = cache.findNodeInfo(rootUuid);
            assertThat(rootInfo, is(notNullValue()));
        }
    }

    @Test
    public void shouldRepeatedlyFindRootNodeInfoByPath() throws Exception {
        String sourceName = "cars";
        Graph store = getGraph(sourceName);
        SessionCache cache = getCache(sourceName);
        Node root = store.getNodeAt("/");
        UUID rootUuid = root.getLocation().getUuid();
        NodeInfo nodeInfo = cache.findNodeInfoForRoot();
        assertThat(nodeInfo.getUuid(), is(rootUuid));
        for (int i = 0; i != 20; ++i) {
            sw.start();
            assertThat(nodeInfo, is(sameInstance(cache.findNodeInfoForRoot())));
            sw.stop();
            assertThat(nodeInfo, is(sameInstance(cache.findNodeInfo(rootUuid))));
        }
        System.out.println(pad(sourceName) + " ==> " + sw.getSimpleStatistics());
    }

    @Test
    public void shouldRepeatedlyFindRootNodeInfoByUuidFromVehiclesSource() throws Exception {
        String sourceName = "vehicles";
        Graph store = getGraph(sourceName);
        SessionCache cache = getCache(sourceName);
        sw.start();
        Node root = store.getNodeAt("/");
        sw.stop();
        UUID rootUuid = root.getLocation().getUuid();
        for (int i = 0; i != 20; ++i) {
            sw.start();
            NodeInfo rootInfo = cache.findNodeInfo(rootUuid);
            sw.stop();
            assertThat(rootInfo, is(notNullValue()));
        }
        System.out.println(pad(sourceName) + " ==> " + sw.getSimpleStatistics());
    }

    @Test
    public void shouldRepeatedlyFindRootNodeInfoByUuidFromSourceWithPrimaryTypes() throws Exception {
        String sourceName = "repositoryForTckTests";
        Graph store = getGraph(sourceName);
        SessionCache cache = getCache(sourceName);
        sw.start();
        Node root = store.getNodeAt("/");
        sw.stop();
        UUID rootUuid = root.getLocation().getUuid();
        for (int i = 0; i != 20; ++i) {
            sw.start();
            NodeInfo rootInfo = cache.findNodeInfo(rootUuid);
            sw.stop();
            assertThat(rootInfo, is(notNullValue()));
        }
        System.out.println(pad(sourceName) + " ==> " + sw.getSimpleStatistics());
    }

    @Test
    public void shouldFindChildrenInNodeInfoForRoot() throws Exception {
        NodeInfo root = cache.findNodeInfoForRoot();
        Children children = root.getChildren();
        assertThat(children, is(notNullValue()));
        assertThat(children.size(), is(1));
        assertThat(children.getChild(segment("vehix:Vehicles")), is(notNullValue()));
    }

    @Test
    public void shouldFindNodeInfoForNonRootNodeByPath() throws Exception {
        String sourceName = "vehicles";
        Graph store = getGraph(sourceName);
        SessionCache cache = getCache(sourceName);
        // Get the root ...
        NodeInfo root = cache.findNodeInfoForRoot();

        // Now try to load a node that is well-below the root ...
        Path lr3Path = path("/vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Land Rover LR3");
        Node lr3Node = store.getNodeAt(lr3Path);
        sw.start();
        NodeInfo lr3 = cache.findNodeInfo(root.getUuid(), lr3Path);
        sw.stop();
        assertThat(lr3.getUuid(), is(lr3Node.getLocation().getUuid()));
        assertSameProperties(lr3, lr3Node);
        System.out.println(pad(sourceName) + " ==> " + sw.getSimpleStatistics());

        // Verify that this loaded all the intermediate nodes, by walking up ...
        NodeInfo info = lr3;
        while (true) {
            UUID parent = info.getParent();
            if (parent == null) {
                // then we should be at the root ...
                assertThat(info.getUuid(), is(root.getUuid()));
                break;
            }
            // Otherwise, we're not at the root, so we should find the parent ...
            info = cache.findNodeInfoInCache(parent);
            assertThat(info, is(notNullValue()));
        }
    }

    @Test
    public void shouldFindInfoForNodeUsingRelativePathFromRoot() throws Exception {
        String sourceName = "vehicles";
        Graph store = getGraph(sourceName);
        SessionCache cache = getCache(sourceName);

        // Verify that the node does exist in the source ...
        Path lr3AbsolutePath = path("/vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Land Rover LR3");
        Node lr3Node = store.getNodeAt(lr3AbsolutePath);

        // Get the root ...
        NodeInfo root = cache.findNodeInfoForRoot();

        // Now try to load a node that is well-below the root ...
        Path lr3Path = path("vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Land Rover LR3");
        assertThat(lr3Path.isAbsolute(), is(false));
        NodeInfo lr3 = cache.findNodeInfo(root.getUuid(), lr3Path);
        assertThat(lr3.getUuid(), is(lr3Node.getLocation().getUuid()));
        assertSameProperties(lr3, lr3Node);

        Path recoveredPath = cache.getPathFor(lr3);
        assertThat(recoveredPath, is(lr3AbsolutePath));
    }

    @Test
    public void shouldFindInfoForNodeUsingRelativePathFromNonRoot() throws Exception {
        // Verify that the node does exist in the source ...
        Path carsAbsolutePath = path("/vehix:Vehicles/vehix:Cars");
        Node carsNode = store.getNodeAt(carsAbsolutePath);
        Path lr3AbsolutePath = path("/vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Land Rover LR3");
        Node lr3Node = store.getNodeAt(lr3AbsolutePath);
        Path b787AbsolutePath = path("/vehix:Vehicles/vehix:Aircraft/vehix:Commercial/vehix:Boeing 787");
        Node b787Node = store.getNodeAt(b787AbsolutePath);

        // Get the root ...
        NodeInfo root = cache.findNodeInfoForRoot();

        // Now try to load the cars node ...
        Path carsPath = path("vehix:Vehicles/vehix:Cars");
        assertThat(carsPath.isAbsolute(), is(false));
        NodeInfo cars = cache.findNodeInfo(root.getUuid(), carsPath);
        assertThat(cars.getUuid(), is(carsNode.getLocation().getUuid()));
        assertSameProperties(cars, carsNode);

        // Now try to find the LR3 node relative to the car ...
        Path lr3Path = path("vehix:Utility/vehix:Land Rover LR3");
        assertThat(lr3Path.isAbsolute(), is(false));
        NodeInfo lr3 = cache.findNodeInfo(cars.getUuid(), lr3Path);
        assertThat(lr3.getUuid(), is(lr3Node.getLocation().getUuid()));
        assertSameProperties(lr3, lr3Node);

        // Now try to find the "Boeing 787" node relative to the LR3 node ...
        Path b787Path = path("../../../vehix:Aircraft/vehix:Commercial/vehix:Boeing 787");
        assertThat(b787Path.isAbsolute(), is(false));
        assertThat(b787Path.isNormalized(), is(true));
        NodeInfo b787 = cache.findNodeInfo(lr3.getUuid(), b787Path);
        assertThat(b787.getUuid(), is(b787Node.getLocation().getUuid()));
        assertSameProperties(b787, b787Node);

        assertThat(cache.getPathFor(cars), is(carsAbsolutePath));
        assertThat(cache.getPathFor(lr3), is(lr3AbsolutePath));
        assertThat(cache.getPathFor(b787), is(b787AbsolutePath));
    }

    @Test
    public void shouldFindJcrNodeUsingAbsolutePaths() throws Exception {
        // Verify that the node does exist in the source ...
        Path carsAbsolutePath = path("/vehix:Vehicles/vehix:Cars");
        Node carsNode = store.getNodeAt(carsAbsolutePath);
        Path lr3AbsolutePath = path("/vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Land Rover LR3");
        Node lr3Node = store.getNodeAt(lr3AbsolutePath);
        Path b787AbsolutePath = path("/vehix:Vehicles/vehix:Aircraft/vehix:Commercial/vehix:Boeing 787");
        Node b787Node = store.getNodeAt(b787AbsolutePath);

        // Get the root ...
        NodeInfo root = cache.findNodeInfoForRoot();

        // Now try to load the cars node ...
        Path carsPath = path("vehix:Vehicles/vehix:Cars");
        assertThat(carsPath.isAbsolute(), is(false));
        AbstractJcrNode carJcrNode = cache.findJcrNode(root.getUuid(), carsAbsolutePath);
        assertThat(carJcrNode.internalUuid(), is(carsNode.getLocation().getUuid()));
        NodeInfo cars = cache.findNodeInfo(root.getUuid(), carsPath);
        assertThat(cars.getUuid(), is(carsNode.getLocation().getUuid()));
        assertSameProperties(cars, carsNode);

        // Now try to find the LR3 node relative to the car ...
        Path lr3Path = path("vehix:Utility/vehix:Land Rover LR3");
        assertThat(lr3Path.isAbsolute(), is(false));
        AbstractJcrNode lr3JcrNode = cache.findJcrNode(root.getUuid(), lr3AbsolutePath);
        assertThat(lr3JcrNode.internalUuid(), is(lr3Node.getLocation().getUuid()));
        NodeInfo lr3 = cache.findNodeInfo(cars.getUuid(), lr3Path);
        assertThat(lr3.getUuid(), is(lr3Node.getLocation().getUuid()));
        assertSameProperties(lr3, lr3Node);

        // Now try to find the "Boeing 787" node relative to the LR3 node ...
        Path b787Path = path("../../../vehix:Aircraft/vehix:Commercial/vehix:Boeing 787");
        assertThat(b787Path.isAbsolute(), is(false));
        assertThat(b787Path.isNormalized(), is(true));
        AbstractJcrNode b787JcrNode = cache.findJcrNode(root.getUuid(), b787AbsolutePath);
        assertThat(b787JcrNode.internalUuid(), is(b787Node.getLocation().getUuid()));
        NodeInfo b787 = cache.findNodeInfo(lr3.getUuid(), b787Path);
        assertThat(b787.getUuid(), is(b787Node.getLocation().getUuid()));
        assertSameProperties(b787, b787Node);

        assertThat(cache.getPathFor(cars), is(carsAbsolutePath));
        assertThat(cache.getPathFor(lr3), is(lr3AbsolutePath));
        assertThat(cache.getPathFor(b787), is(b787AbsolutePath));
    }

    @Test
    public void shouldDeleteLeafNode() throws Exception {
        // Find the state of some Graph nodes we'll be using in the test ...
        Node utility = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Utility");
        Node lr2Node = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Land Rover LR2");
        Node lr3Node = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Land Rover LR3");
        int numChildrenOfUtility = utility.getChildren().size();
        assertThat(numChildrenOfUtility, is(4));
        assertThat(utility.getChildren(), hasChildren(segment("vehix:Land Rover LR2"),
                                                      segment("vehix:Land Rover LR3"),
                                                      segment("vehix:Hummer H3"),
                                                      segment("vehix:Ford F-150")));

        // Now get the editor for the 'vehix:Utility' node ...
        NodeEditor editor = cache.getEditorFor(utility.getLocation().getUuid());
        assertThat(editor, is(notNullValue()));

        // Destroy the LR3 node, which is a leaf ...
        editor.destroyChild(lr3Node.getLocation().getUuid());

        // Verify that the store has not yet been changed ...
        assertDoesExist(store, lr3Node.getLocation());

        // Save the session and verify that the node was deleted ...
        cache.save();
        Node utilityNode2 = store.getNodeAt(utility.getLocation());
        assertThat(utilityNode2.getChildren().size(), is(numChildrenOfUtility - 1));
        assertThat(utilityNode2.getChildren(), hasChildren(segment("vehix:Land Rover LR2"), // no LR3!
                                                           segment("vehix:Hummer H3"),
                                                           segment("vehix:Ford F-150")));
        // Should no longer find the LR3 node in the graph ...
        assertDoesNotExist(store, lr3Node.getLocation().getUuid());
        assertDoesExist(store, lr2Node.getLocation());
    }

    @Test
    public void shouldDeleteNonLeafNode() throws Exception {
        // Find the state of some Graph nodes we'll be using in the test ...
        Node carsNode = store.getNodeAt("/vehix:Vehicles/vehix:Cars");
        Node utility = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Utility");
        Node hybrid = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Hybrid");
        Node luxury = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Luxury");
        Node sports = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Sports");
        Node lr3 = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Land Rover LR3");
        Node lr2 = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Land Rover LR2");
        Node f150 = store.getNodeAt("/vehix:Vehicles/vehix:Cars/vehix:Utility/vehix:Ford F-150");
        int numChildrenOfCars = carsNode.getChildren().size();
        assertThat(numChildrenOfCars, is(4));
        assertThat(carsNode.getChildren(), hasChildren(segment("vehix:Hybrid"),
                                                       segment("vehix:Sports"),
                                                       segment("vehix:Luxury"),
                                                       segment("vehix:Utility")));

        // Load the LR2 and Hummer nodes ...
        NodeInfo h3i = cache.findNodeInfo(utility.getLocation().getUuid(), path("vehix:Hummer H3"));
        NodeInfo lr2i = cache.findNodeInfo(utility.getLocation().getUuid(), path("vehix:Land Rover LR2"));
        assertThat(h3i, is(notNullValue()));
        assertThat(lr2i, is(notNullValue()));
        assertThat(lr2i.getUuid(), is(lr2.getLocation().getUuid()));

        // Now get the editor for the 'vehix:Cars' node ...
        NodeEditor editor = cache.getEditorFor(carsNode.getLocation().getUuid());
        assertThat(editor, is(notNullValue()));

        // Destroy the Utility node, which is NOT a leaf ...
        editor.destroyChild(utility.getLocation().getUuid());

        // Verify that the store has not yet been changed ...
        assertDoesExist(store, utility.getLocation());

        // ... but the utility node and its two loaded children have been marked for deletion ...
        assertIsDeleted(cache, utility.getLocation().getUuid());
        assertIsDeleted(cache, h3i.getUuid());
        assertIsDeleted(cache, lr2i.getUuid());

        // Save the session and verify that the Utility node and its children were deleted ...
        cache.save();
        Node carsNode2 = store.getNodeAt(carsNode.getLocation());
        assertThat(carsNode2.getChildren().size(), is(numChildrenOfCars - 1));
        assertThat(carsNode2.getChildren(),
                   hasChildren(segment("vehix:Hybrid"), segment("vehix:Sports"), segment("vehix:Luxury")));
        // Should no longer find the Utility node in the graph ...
        assertDoesNotExist(store, utility.getLocation());
        assertDoesNotExist(cache, utility.getLocation().getUuid());
        assertDoesNotExist(cache, h3i.getUuid());
        assertDoesNotExist(cache, lr2i.getUuid());
        assertDoesNotExist(cache, lr3.getLocation().getUuid());
        assertDoesNotExist(cache, f150.getLocation().getUuid());
        assertDoNotExist(store, utility.getChildren());
        assertDoesExist(store, hybrid.getLocation());
        assertDoesExist(store, luxury.getLocation());
        assertDoesExist(store, sports.getLocation());
    }

}