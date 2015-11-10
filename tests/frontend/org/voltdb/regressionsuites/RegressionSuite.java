/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.regressionsuites;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import junit.framework.TestCase;

import org.apache.commons.lang3.StringUtils;
import org.voltdb.VoltDB;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.client.Client;
import org.voltdb.client.ClientAuthHashScheme;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientConfigForTest;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ConnectionUtil;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.common.Constants;
import org.voltdb.types.GeographyValue;
import org.voltdb.types.PointType;
import org.voltdb.types.VoltDecimalHelper;
import org.voltdb.utils.Encoder;

import com.google_voltpatches.common.net.HostAndPort;

/**
 * Base class for a set of JUnit tests that perform regression tests
 * on a running VoltDB server. It is assumed that all tests will access
 * a particular VoltDB server to do their work and check the output of
 * any procedures called. The main feature of this class is that the
 * backend instance of VoltDB is very flexible and the tests can be run
 * on multiple instances of VoltDB to test different VoltDB topologies.
 *
 */
public class RegressionSuite extends TestCase {

    protected static int m_verboseDiagnosticRowCap = 40;
    protected VoltServerConfig m_config;
    protected String m_username = "default";
    protected String m_password = "password";
    private final ArrayList<Client> m_clients = new ArrayList<Client>();
    private final ArrayList<SocketChannel> m_clientChannels = new ArrayList<SocketChannel>();
    protected final String m_methodName;

    /**
     * Trivial constructor that passes parameter on to superclass.
     * @param name The name of the method to run as a test. (JUnit magic)
     */
    public RegressionSuite(final String name) {
        super(name);
        m_methodName = name;
    }

    /**
     * JUnit special method called to setup the test. This instance will start
     * the VoltDB server using the VoltServerConfig instance provided.
     */
    @Override
    public void setUp() throws Exception {
        //New tests means a new server thread that hasn't done a restore
        m_config.setCallingMethodName(m_methodName);
        m_config.startUp(true);
    }

    /**
     * JUnit special method called to shutdown the test. This instance will
     * stop the VoltDB server using the VoltServerConfig instance provided.
     */
    @Override
    public void tearDown() throws Exception {
        m_config.shutDown();
        for (final Client c : m_clients) {
            c.close();
        }
        synchronized (m_clientChannels) {
            for (final SocketChannel sc : m_clientChannels) {
                try {
                    ConnectionUtil.closeConnection(sc);
                } catch (final IOException e) {
                    e.printStackTrace();
                }
            }
            m_clientChannels.clear();
        }
        m_clients.clear();
    }

    /**
     * @return Is the underlying instance of VoltDB running HSQL?
     */
    public boolean isHSQL() {
        return m_config.isHSQL();
    }

    /**
     * @return The number of logical partitions in this configuration
     */
    public int getLogicalPartitionCount() {
        return m_config.getLogicalPartitionCount();
    }

    /**
     * @return Is the underlying instance of VoltDB running Valgrind with the IPC client?
     */
    public boolean isValgrind() {
        return m_config.isValgrind();
    }

    public boolean isLocalCluster() {
        return m_config instanceof LocalCluster;
    }

    public boolean isDebug() {
        return m_config.isDebug();
    }

    /**
     * @return a reference to the associated VoltServerConfig
     */
    public final VoltServerConfig getServerConfig() {
        return m_config;
    }

    public Client getAdminClient() throws IOException {
        return getClient(1000 * 60 * 10, ClientAuthHashScheme.HASH_SHA256, true); // 10 minute default
    }

    public Client getClient() throws IOException {
        return getClient(1000 * 60 * 10, ClientAuthHashScheme.HASH_SHA256); // 10 minute default
    }

    public Client getClient(ClientAuthHashScheme scheme) throws IOException {
        return getClient(1000 * 60 * 10, scheme); // 10 minute default
    }

    public Client getClientToHostId(int hostId) throws IOException {
        return getClientToHostId(hostId, 1000 * 60 * 10); // 10 minute default
    }

    public Client getFullyConnectedClient() throws IOException {
        return getFullyConnectedClient(1000 * 60 * 10); // 10 minute default
    }

    /**
     * Get a VoltClient instance connected to the server driven by the
     * VoltServerConfig instance. Just pick from the list of listeners
     * randomly.
     *
     * Only uses the time
     *
     * @return A VoltClient instance connected to the server driven by the
     * VoltServerConfig instance.
     */
    public Client getClient(long timeout) throws IOException {
        return getClient(timeout, ClientAuthHashScheme.HASH_SHA256);
    }

    /**
     * Get a VoltClient instance connected to the server driven by the
     * VoltServerConfig instance. Just pick from the list of listeners
     * randomly.
     *
     * Only uses the time
     *
     * @return A VoltClient instance connected to the server driven by the
     * VoltServerConfig instance.
     */
    public Client getClient(long timeout, ClientAuthHashScheme scheme) throws IOException {
        return getClient(timeout, scheme, false);
    }

    public Client getClient(long timeout, ClientAuthHashScheme scheme, boolean useAdmin) throws IOException {
        final Random r = new Random();
        String listener = null;
        if (useAdmin) {
            listener = m_config.getAdminAddress(r.nextInt(m_config.getListenerCount()));
        } else {
            listener = m_config.getListenerAddress(r.nextInt(m_config.getListenerCount()));
        }
        ClientConfig config = new ClientConfigForTest(m_username, m_password, scheme);
        config.setConnectionResponseTimeout(timeout);
        config.setProcedureCallTimeout(timeout);
        final Client client = ClientFactory.createClient(config);
        // Use the port generated by LocalCluster if applicable
        try {
            client.createConnection(listener);
        }
        // retry once
        catch (ConnectException e) {
            if (useAdmin) {
                listener = m_config.getAdminAddress(r.nextInt(m_config.getListenerCount()));
            } else {
                listener = m_config.getListenerAddress(r.nextInt(m_config.getListenerCount()));
            }
            client.createConnection(listener);
        }
        m_clients.add(client);
        return client;
    }

    /**
     * Get a VoltClient instance connected to the server driven by the
     * VoltServerConfig instance. Just pick from the list of listeners
     * randomly.
     *
     * Only uses the time
     *
     * @return A VoltClient instance connected to the server driven by the
     * VoltServerConfig instance.
     */
    public Client getClientSha1(long timeout) throws IOException {
        final List<String> listeners = m_config.getListenerAddresses();
        final Random r = new Random();
        String listener = listeners.get(r.nextInt(listeners.size()));
        ClientConfig config = new ClientConfigForTest(m_username, m_password, ClientAuthHashScheme.HASH_SHA1);
        config.setConnectionResponseTimeout(timeout);
        config.setProcedureCallTimeout(timeout);
        final Client client = ClientFactory.createClient(config);
        // Use the port generated by LocalCluster if applicable
        try {
            client.createConnection(listener);
        }
        // retry once
        catch (ConnectException e) {
            listener = listeners.get(r.nextInt(listeners.size()));
            client.createConnection(listener);
        }
        m_clients.add(client);
        return client;
    }

    /**
     * Get a VoltClient instance connected to a specific server driven by the
     * VoltServerConfig instance. Find the server by the config's HostId.
     *
     * @return A VoltClient instance connected to the server driven by the
     * VoltServerConfig instance.
     */
    public Client getClientToHostId(int hostId, long timeout) throws IOException {
        final String listener = m_config.getListenerAddress(hostId);
        ClientConfig config = new ClientConfigForTest(m_username, m_password);
        config.setConnectionResponseTimeout(timeout);
        config.setProcedureCallTimeout(timeout);
        final Client client = ClientFactory.createClient(config);
        try {
            client.createConnection(listener);
        }
        // retry once
        catch (ConnectException e) {
            client.createConnection(listener);
        }
        m_clients.add(client);
        return client;
    }

    public Client getFullyConnectedClient(long timeout) throws IOException {
        final List<String> listeners = m_config.getListenerAddresses();
        final Random r = new Random();
        ClientConfig config = new ClientConfigForTest(m_username, m_password);
        config.setConnectionResponseTimeout(timeout);
        config.setProcedureCallTimeout(timeout);
        final Client client = ClientFactory.createClient(config);
        for (String listener : listeners) {
            // Use the port generated by LocalCluster if applicable
            try {
                client.createConnection(listener);
            }
            // retry once
            catch (ConnectException e) {
                listener = listeners.get(r.nextInt(listeners.size()));
                client.createConnection(listener);
            }
        }
        m_clients.add(client);
        return client;
    }

    /**
     * Release a client instance and any resources associated with it
     */
    public void releaseClient(Client c) throws IOException, InterruptedException {
        boolean removed = m_clients.remove(c);
        assert(removed);
        c.close();
    }

    /**
     * Get a SocketChannel that is an authenticated connection to a server driven by the
     * VoltServerConfig instance. Just pick from the list of listeners
     * randomly.
     *
     * @return A SocketChannel that is already authenticated with the server
     */
    public SocketChannel getClientChannel() throws IOException {
        return getClientChannel(false);
    }
    public SocketChannel getClientChannel(final boolean noTearDown) throws IOException {
        final List<String> listeners = m_config.getListenerAddresses();
        final Random r = new Random();
        final String listener = listeners.get(r.nextInt(listeners.size()));
        byte[] hashedPassword = ConnectionUtil.getHashedPassword(m_password);
        HostAndPort hNp = HostAndPort.fromString(listener);
        int port = Constants.DEFAULT_PORT;
        if (hNp.hasPort()) {
            port = hNp.getPort();
        }
        final SocketChannel channel = (SocketChannel)
            ConnectionUtil.getAuthenticatedConnection(
                    hNp.getHostText(), m_username, hashedPassword, port, null, ClientAuthHashScheme.getByUnencodedLength(hashedPassword.length))[0];
        channel.configureBlocking(true);
        if (!noTearDown) {
            synchronized (m_clientChannels) {
                m_clientChannels.add(channel);
            }
        }
        return channel;
    }

    /**
     * Protected method used by MultiConfigSuiteBuilder to set the VoltServerConfig
     * instance a particular test will run with.
     *
     * @param config An instance of VoltServerConfig to run tests with.
     */
    void setConfig(final VoltServerConfig config) {
        m_config = config;
    }


    @Override
    public String getName() {
        // munge the test name with the VoltServerConfig instance name
        return super.getName() + "-" + m_config.getName();
    }

    /**
     * Return appropriate port for hostId. Deal with LocalCluster providing non-default ports.
     * @param hostId zero-based host id
     * @return port number
     */
    public int port(int hostId) {
        return isLocalCluster() ? ((LocalCluster)m_config).port(hostId) : VoltDB.DEFAULT_PORT+hostId;
    }

    /**
     * Return appropriate admin port for hostId. Deal with LocalCluster providing non-default ports.
     * @param hostId zero-based host id
     * @return admin port number
     */
    public int adminPort(int hostId) {
        return isLocalCluster() ? ((LocalCluster)m_config).adminPort(hostId) : VoltDB.DEFAULT_ADMIN_PORT+hostId;
    }

    /**
     * Return appropriate internal port for hostId. Deal with LocalCluster providing non-default ports.
     * @param hostId zero-based host id
     * @return internal port number
     */
    public int internalPort(int hostId) {
        return isLocalCluster() ? ((LocalCluster)m_config).internalPort(hostId) : VoltDB.DEFAULT_INTERNAL_PORT+hostId;
    }

    static protected void validateTableOfLongs(Client c, String sql, long[][] expected)
            throws NoConnectionsException, IOException, ProcCallException {
        VoltTable vt = c.callProcedure("@AdHoc", sql).getResults()[0];
        validateTableOfLongs(sql, vt, expected);
    }

    static protected void validateTableOfScalarLongs(VoltTable vt, long[] expected) {
        assertNotNull(expected);
        assertEquals("Different number of rows! ", expected.length, vt.getRowCount());
        int len = expected.length;
        for (int i=0; i < len; i++) {
            validateRowOfLongs(vt, new long[] {expected[i]});
        }
    }

    static protected void validateTableOfScalarLongs(Client client, String sql, long[] expected)
            throws NoConnectionsException, IOException, ProcCallException {
        assertNotNull(expected);
        VoltTable vt = client.callProcedure("@AdHoc", sql).getResults()[0];
        validateTableOfScalarLongs(vt, expected);
    }

    private static void dumpExpectedLongs(long[][] expected) {
        System.out.println("row count:" + expected.length);
        for (long[] row : expected) {
            String prefix = "{ ";
            for (long value : row) {
                System.out.print(prefix + value);
                prefix = ", ";
            }
            System.out.println(" }");
        }
    }

    static private void validateTableOfLongs(String messagePrefix,
            VoltTable vt, long[][] expected) {
        assertNotNull(expected);
        if (expected.length != vt.getRowCount()) {
            if (vt.getRowCount() < m_verboseDiagnosticRowCap) {
                System.out.println("Diagnostic dump of unexpected result for " + messagePrefix + " : " + vt);
                System.out.println("VS. expected : ");
                dumpExpectedLongs(expected);
            }
            //* enable and set breakpoint to debug multiple row count mismatches and continue */ return;
        }
        assertEquals(messagePrefix + " returned wrong number of rows.  ",
                        expected.length, vt.getRowCount());
        int len = expected.length;
        for (int i=0; i < len; i++) {
            validateRowOfLongs(messagePrefix + " at row " + i + ", ", vt, expected[i]);
        }
    }

    public static void validateTableOfLongs(VoltTable vt, long[][] expected) {
        assertNotNull(expected);
        assertEquals("Wrong number of rows in table.  ",
                        expected.length, vt.getRowCount());
        int len = expected.length;
        for (int i=0; i < len; i++) {
            validateRowOfLongs("at row " + i + ", ", vt, expected[i]);
        }
    }

    static protected void validateRowOfLongs(String messagePrefix, VoltTable vt, long [] expected) {
        int len = expected.length;
        assertTrue(vt.advanceRow());
        for (int i=0; i < len; i++) {
            String message = messagePrefix + "at column " + i + ", ";

            long actual = -10000000;
            // ENG-4295: hsql bug: HSQLBackend sometimes returns wrong column type.
            try {
                actual = vt.getLong(i);
            } catch (IllegalArgumentException ex) {
                try {
                    actual = (long) vt.getDouble(i);
                } catch (IllegalArgumentException newEx) {
                    try {
                        actual = vt.getTimestampAsLong(i);
                    } catch (IllegalArgumentException exTm) {
                        try {
                            actual = vt.getDecimalAsBigDecimal(i).longValueExact();
                        } catch (IllegalArgumentException newerEx) {
                            newerEx.printStackTrace();
                            fail(message);
                        }
                    } catch (ArithmeticException newestEx) {
                        newestEx.printStackTrace();
                        fail(message);
                    }
                }
            }

            // Long.MIN_VALUE is like a NULL
            if (expected[i] != Long.MIN_VALUE) {
                assertEquals(message, expected[i], actual);
            } else {
                VoltType type = vt.getColumnType(i);
                assertEquals(message + "expected null: ", Long.parseLong(type.getNullValue().toString()), actual);
            }
        }
    }

    static protected void validateRowOfLongs(VoltTable vt, long [] expected) {
        validateRowOfLongs("", vt, expected);
    }

    static protected void validateTableColumnOfScalarLong(VoltTable vt, int col, long[] expected) {
        assertNotNull(expected);
        assertEquals(expected.length, vt.getRowCount());
        int len = expected.length;
        for (int i=0; i < len; i++) {
            assertTrue(vt.advanceRow());
            long actual = vt.getLong(col);

            if (expected[i] == Long.MIN_VALUE) {
                assertTrue(vt.wasNull());
                assertEquals(null, actual);
            } else {
                assertEquals(expected[i], actual);
            }
        }
    }

    static protected void validateTableColumnOfScalarVarchar(Client client, String sql, String[] expected)
            throws NoConnectionsException, IOException, ProcCallException {
        VoltTable vt = client.callProcedure("@AdHoc", sql).getResults()[0];
        validateTableColumnOfScalarVarchar(vt, 0, expected);
    }

    static protected void validateTableColumnOfScalarVarchar(VoltTable vt, String[] expected) {
        validateTableColumnOfScalarVarchar(vt, 0, expected);
    }

    static protected void validateTableColumnOfScalarVarchar(VoltTable vt, int col, String[] expected) {
        assertNotNull(expected);
        assertEquals(expected.length, vt.getRowCount());
        int len = expected.length;
        for (int i=0; i < len; i++) {
            assertTrue(vt.advanceRow());
            if (expected[i] == null) {
                String actual = vt.getString(col);
                assertTrue(vt.wasNull());
                assertEquals(null, actual);
            } else {
                assertEquals(expected[i], vt.getString(col));
            }
        }
    }


    static protected void validateTableColumnOfScalarVarbinary(Client client, String sql, String[] expected)
            throws NoConnectionsException, IOException, ProcCallException {
        VoltTable vt = client.callProcedure("@AdHoc", sql).getResults()[0];
        validateTableColumnOfScalarVarbinary(vt, 0, expected);
    }

    static private void validateTableColumnOfScalarVarbinary(VoltTable vt, int col, String[] expected) {
          assertNotNull(expected);
          assertEquals(expected.length, vt.getRowCount());
          int len = expected.length;
          for (int i=0; i < len; i++) {
              assertTrue(vt.advanceRow());
              byte[] actual = vt.getVarbinary(col);

              if (expected[i] == null) {
                  assertTrue(vt.wasNull());
                  assertEquals(null, actual);
              } else {
                  assertEquals(expected[i], Encoder.hexEncode(actual));
              }
          }
    }

    static protected void validateTableColumnOfScalarFloat(Client client, String sql, double[] expected)
            throws NoConnectionsException, IOException, ProcCallException {
        VoltTable vt = client.callProcedure("@AdHoc", sql).getResults()[0];
        validateTableColumnOfScalarFloat(vt, 0, expected);
    }

    static protected void validateTableColumnOfScalarFloat(VoltTable vt, int col, double[] expected) {
          assertNotNull(expected);
          assertEquals(expected.length, vt.getRowCount());
          int len = expected.length;
          for (int i=0; i < len; i++) {
              assertTrue(vt.advanceRow());
              double actual = vt.getDouble(col);

              if (expected[i] == Double.MIN_VALUE) {
                  assertTrue(vt.wasNull());
                  assertEquals(null, actual);
              } else {
                  assertEquals(expected[i], actual, 0.00001);
              }
          }
    }

    private void validateRowOfDecimal(VoltTable vt, BigDecimal [] expected) {
        int len = expected.length;
        assertTrue(vt.advanceRow());
        for (int i=0; i < len; i++) {
            BigDecimal actual = null;
            try {
                actual = vt.getDecimalAsBigDecimal(i);
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
                fail();
            }
            if (expected[i] != null) {
                assertNotSame(null, actual);
                assertEquals(expected[i], actual);
            } else {
                if (isHSQL()) {
                    // We don't actually use this with
                    // HSQL.  So, just assert failure here.
                    fail("HSQL is not used to test the Volt DECIMAL type.");
                } else {
                    assertTrue(vt.wasNull());
                }
            }
        }
    }

    protected void validateTableOfDecimal(VoltTable vt, BigDecimal[][] expected) {
        assertNotNull(expected);
        assertEquals(expected.length, vt.getRowCount());
        int len = expected.length;
        for (int i=0; i < len; i++) {
            validateRowOfDecimal(vt, expected[i]);
        }
    }

    protected static int m_defaultScale = 12;
    protected static String m_roundingEnabledProperty = "BIGDECIMAL_ROUND";
    protected static String m_roundingModeProperty = "BIGDECIMAL_ROUND_POLICY";
    protected static String m_defaultRoundingEnablement = "true";
    protected static String m_defaultRoundingMode = "HALF_UP";

    protected static String getRoundingString(String label) {
        return String.format("%sRounding %senabled, mode is %s",
                             label == null ? (label + ": ") : "",
                             VoltDecimalHelper.isRoundingEnabled() ? "is " : "is *NOT* ",
                             VoltDecimalHelper.getRoundingMode().toString());
    }

    /*
     * This little helper function converts a string to
     * a decimal, and, maybe, rounds it to the Volt default scale
     * using the given mode.  If roundingEnabled is false, no
     * rounding is done.
     */
    protected static final BigDecimal roundDecimalValue(String decimalValueString,
                                                      boolean roundingEnabled,
                                                      RoundingMode mode) {
        BigDecimal bd = new BigDecimal(decimalValueString);
        if (!roundingEnabled) {
            return bd;
        }
        int precision = bd.precision();
        int scale = bd.scale();
        int lostScale = scale - m_defaultScale ;
        if (lostScale <= 0) {
            return bd;
        }
        int newPrecision = precision - lostScale;
        MathContext mc = new MathContext(newPrecision, mode);
        BigDecimal nbd = bd.round(mc);
        assertTrue(nbd.scale() <= m_defaultScale);
        if (nbd.scale() != m_defaultScale) {
            nbd = nbd.setScale(m_defaultScale);
        }
        assertEquals(getRoundingString("Decimal Scale setting failure"), m_defaultScale, nbd.scale());
        return nbd;
    }

    protected void validateTableOfDecimal(Client c, String sql, BigDecimal[][] expected)
            throws Exception, IOException, ProcCallException {
        assertNotNull(expected);
        VoltTable vt = c.callProcedure("@AdHoc", sql).getResults()[0];
        validateTableOfDecimal(vt, expected);
    }

    static protected void validateTableColumnOfScalarDecimal(Client client, String sql, BigDecimal[] expected)
            throws NoConnectionsException, IOException, ProcCallException {
        VoltTable vt = client.callProcedure("@AdHoc", sql).getResults()[0];
        validateTableColumnOfScalarDecimal(vt, 0, expected);
    }

    static protected void validateTableColumnOfScalarDecimal(VoltTable vt, int col, BigDecimal[] expected) {
        assertNotNull(expected);
        assertEquals(expected.length, vt.getRowCount());
        int len = expected.length;
        for (int i=0; i < len; i++) {
            assertTrue(vt.advanceRow());
            BigDecimal actual = vt.getDecimalAsBigDecimal(col);

            if (expected[i] == null) {
                assertTrue(vt.wasNull());
                assertEquals(null, actual);
            } else {
                BigDecimal rounded = expected[i].setScale(m_defaultScale, RoundingMode.valueOf(m_defaultRoundingMode));
                assertEquals(rounded, actual);
            }
        }
  }

    protected void assertTablesAreEqual(String prefix, VoltTable expectedRows, VoltTable actualRows) {
        assertEquals(prefix + "column count mismatch.  Expected: " + expectedRows.getColumnCount() + " actual: " + actualRows.getColumnCount(),
                expectedRows.getColumnCount(), actualRows.getColumnCount());

        int i = 0;
        while(expectedRows.advanceRow()) {
            assertTrue(prefix + "too few actual rows; expected more than " + (i + 1), actualRows.advanceRow());

            for (int j = 0; j < actualRows.getColumnCount(); j++) {
                String columnName = actualRows.getColumnName(j);
                String colPrefix = prefix + "row " + i + ": column: " + columnName + ": ";
                VoltType actualTy = actualRows.getColumnType(j);
                VoltType expectedTy = expectedRows.getColumnType(j);
                assertEquals(colPrefix + "type mismatch", expectedTy, actualTy);

                Object expectedObj = expectedRows.get(j,  expectedTy);
                Object actualObj = expectedRows.get(j,  actualTy);
                assertEquals(colPrefix + "values not equal: expected: " + expectedObj + ", actual: " + actualObj,
                        expectedObj, actualObj);
            }

            i++;
        }
        assertFalse(prefix + "too many actual rows; expected only " + i, actualRows.advanceRow());
    }

    public static void assertEquals(String msg, PointType expected, PointType actual) {
        assertEquals(msg + " latitude: ", expected.getLatitude(), actual.getLatitude(), 0.001);
        assertEquals(msg + " longitude: ", expected.getLongitude(), actual.getLongitude(), 0.001);
    }

    public static void assertEquals(PointType expected, PointType actual) {
        assertEquals("Points not equal: ", expected, actual);
    }

    public static void assertEquals(String msg, GeographyValue expected, GeographyValue actual) {
        if (expected == actual) {
            return;
        }

        // caller checks for null in the expected value
        assert (expected != null);

        if (actual == null) {
            fail(msg + " found null value when non-null expected");
        }

        List<List<PointType>> expectedLoops = expected.getLoops();
        List<List<PointType>> actualLoops = actual.getLoops();

        assertEquals(msg + "wrong number of loops, expected " + expectedLoops.size() + ", "
                + "got " + actualLoops.size(),
                expectedLoops.size(), actualLoops.size());

        int loopCtr = 0;
        Iterator<List<PointType>> expectedLoopIt = expectedLoops.iterator();
        for (List<PointType> actualLoop : actualLoops) {
            List<PointType> expectedLoop = expectedLoopIt.next();
            assertEquals(msg + loopCtr + "th loop should have " + expectedLoop.size()
                    + " vertices, but has " + actualLoop.size(),
                    expectedLoop.size(), actualLoop.size());

            int vertexCtr = 0;
            Iterator<PointType> expectedVertexIt = expectedLoop.iterator();
            for (PointType actualPt : actualLoop) {
                PointType expectedPt = expectedVertexIt.next();
                String prefix = msg + "at loop " + loopCtr + ", vertex " + vertexCtr;
                assertEquals(prefix, expectedPt, actualPt);
                ++vertexCtr;
            }

            ++loopCtr;
        }
    }

    public static void assertEquals(GeographyValue expected, GeographyValue actual) {
        assertEquals("Geographies not equal: ", expected, actual);
    }

    private static void assertContentOfRow(int row, Object[] expectedRow, VoltTable actualRow) {
        for (int i = 0; i < expectedRow.length; ++i) {
            String msg = "Row " + row + ", col " + i + ": ";
            Object expectedObj = expectedRow[i];
            if (expectedObj == null) {
                VoltType vt = actualRow.getColumnType(i);
                actualRow.get(i,  vt);
                assertTrue(msg, actualRow.wasNull());
            }
            else if (expectedObj instanceof PointType) {
                assertEquals(msg, (PointType)expectedObj, actualRow.getPoint(i));
            }
            else if (expectedObj instanceof GeographyValue) {
                assertEquals(msg, (GeographyValue)expectedObj, actualRow.getGeographyValue(i));
            }
            else if (expectedObj instanceof Long) {
                long val = ((Long)expectedObj).longValue();
                assertEquals(msg, val, actualRow.getLong(i));
            }
            else if (expectedObj instanceof Integer) {
                long val = ((Integer)expectedObj).longValue();
                assertEquals(msg, val, actualRow.getLong(i));
            }
            else if (expectedObj instanceof Double) {
                double expectedValue= (Double)expectedObj;
                double actualValue = actualRow.getDouble(i);
                // check if the row value was evaluated as null. Looking
                // at return is not reliable way to do so;
                // for null values, convert value into double min
                if (actualRow.wasNull()) {
                    actualValue = Double.MIN_VALUE;
                }
                assertEquals(msg, expectedValue, actualValue);
            }
            else if (expectedObj instanceof String) {
                String val = (String)expectedObj;
                assertEquals(msg, val, actualRow.getString(i));
            }
            else {
                fail("Unexpected type in expected row: " + expectedObj.getClass().getSimpleName());
            }
        }
    }

    /**
     * Accept expected table contents as 2-dimensional array of objects, to make it easy to write tests.
     * Currently only handles some data types.  Feel free to add more as needed.
     */
    public static void assertContentOfTable(Object[][] expectedTable, VoltTable actualTable) {
        for (int i = 0; i < expectedTable.length; ++i) {
            assertTrue("Fewer rows than expected: "
                    + "expected: " + expectedTable.length + ", "
                    + "actual: " + i,
                    actualTable.advanceRow());
            assertContentOfRow(i, expectedTable[i], actualTable);
        }
        assertFalse("More rows than expected: "
                + "expected " + expectedTable.length + ", "
                + "actual: " + actualTable.getRowCount(),
                actualTable.advanceRow());
    }

    static protected void verifyStmtFails(Client client, String stmt, String expectedPattern) throws IOException {
        verifyProcFails(client, expectedPattern, "@AdHoc", stmt);
    }

    static protected void verifyAdHocFails(Client client, String expectedPattern, Object... args) throws IOException {
        verifyProcFails(client, expectedPattern, "@AdHoc", args);
    }

    static protected void verifyProcFails(Client client, String expectedPattern, String storedProc, Object... args) throws IOException {

        String what;
        if (storedProc.compareTo("@AdHoc") == 0) {
            what = "the statement \"" + args[0] + "\"";
        }
        else {
            what = "the stored procedure \"" + storedProc + "\"";
        }

        try {
            client.callProcedure(storedProc, args);
        }
        catch (ProcCallException pce) {
            String msg = pce.getMessage();
            String diagnostic = "Expected " + what + " to throw an exception matching the pattern \"" +
                    expectedPattern + "\", but instead it threw an exception containing \"" + msg + "\".";
            Pattern pattern = Pattern.compile(expectedPattern, Pattern.MULTILINE);
            assertTrue(diagnostic, pattern.matcher(msg).find());
            return;
        }

        String diagnostic = "Expected " + what + " to throw an exception matching the pattern \"" +
                expectedPattern + "\", but instead it threw nothing.";
        fail(diagnostic);
    }


    // ALL OF THE VALIDATION SCHEMAS IN THIS TEST ARE BASED OFF OF
    // THE VOLTDB DOCS, RATHER THAN REUSING THE CODE THAT GENERATES THEM.
    // IN SOME MAGICAL FUTURE MAYBE THEY ALL CAN BE GENERATED FROM THE
    // SAME METADATA.
    static protected void validateSchema(VoltTable result, VoltTable expected)
    {
        assertEquals(expected.getColumnCount(), result.getColumnCount());
        for (int i = 0; i < result.getColumnCount(); i++) {
            assertEquals("Failed name column: " + i, expected.getColumnName(i), result.getColumnName(i));
            assertEquals("Failed type column: " + i, expected.getColumnType(i), result.getColumnType(i));
        }
    }

    static protected void validStatisticsForTableLimit(Client client, String tableName, long limit) throws Exception {
        validStatisticsForTableLimitAndPercentage(client, tableName, limit, -1);
    }

    static protected void validStatisticsForTableLimitAndPercentage(Client client, String tableName, long limit, long percentage) throws Exception {
        long start = System.currentTimeMillis();
        while (true) {
            long lastLimit =-1, lastPercentage = -1;
            Thread.sleep(1000);
            if (System.currentTimeMillis() - start > 10000) {
                String percentageStr = "";
                if (percentage >= 0) {
                    percentageStr = ", last seen percentage: " + lastPercentage;
                }
                fail("Took too long or have wrong answers: last seen limit: " + lastLimit + percentageStr);
            }

            VoltTable[] results = client.callProcedure("@Statistics", "TABLE", 0).getResults();
            for (VoltTable t: results) { System.out.println(t.toString()); }
            if (results[0].getRowCount() == 0) continue;

            boolean foundTargetTuple = false;
            boolean limitExpected = false;
            boolean percentageExpected = percentage < 0 ? true: false;

            for (VoltTable vt: results) {
                while(vt.advanceRow()) {
                    String name = vt.getString("TABLE_NAME");
                    if (tableName.equals(name)) {
                        foundTargetTuple = true;
                        lastLimit = vt.getLong("TUPLE_LIMIT");
                        if (limit == lastLimit) {
                            limitExpected = true;
                        }
                        if (percentageExpected || percentage == (lastPercentage = vt.getLong("PERCENT_FULL")) ) {
                            percentageExpected = true;
                        }

                        if (limitExpected && percentageExpected) return;
                        break;
                    }
                }
                if (foundTargetTuple) break;
            }
        }
    }

    static protected void checkDeploymentPropertyValue(Client client, String key, String value)
            throws IOException, ProcCallException, InterruptedException {
        boolean found = false;

        VoltTable result = client.callProcedure("@SystemInformation", "DEPLOYMENT").getResults()[0];
        while (result.advanceRow()) {
            if (result.getString("PROPERTY").equalsIgnoreCase(key)) {
                found = true;
                assertEquals(value, result.getString("VALUE"));
                break;
            }
        }
        assertTrue(found);
    }

    static protected void checkQueryPlan(Client client, String query, String...patterns)
            throws NoConnectionsException, IOException, ProcCallException {
        VoltTable vt;
        assert(patterns.length >= 1);

        vt = client.callProcedure("@Explain", query).getResults()[0];
        String vtStr = vt.toString();

        for (String pattern : patterns) {
            if (! vtStr.contains(pattern)) {
                fail("The explain plan \n" + vtStr + "\n is expected to contain pattern: " + pattern);
            }
        }
    }

    /**
     * Utility function to run queries and dump results to stdout.
     * @param client
     * @param queries one or more query strings to send in a batch
     * @throws IOException
     * @throws NoConnectionsException
     * @throws ProcCallException
     */
    protected static void dumpQueryResults(Client client, String... queries)
            throws IOException, NoConnectionsException, ProcCallException {
        VoltTable vts[] = client.callProcedure("@AdHoc", StringUtils.join(queries, '\n')).getResults();
        int ii = 0;
        for (VoltTable vtn : vts) {
            System.out.println("DEBUG: result for " + queries[ii] + "\n" + vtn + "\n");
            ++ii;
        }
    }

    /**
     * Utility function to explain queries and dump results to stdout.
     * @param client
     * @param queries one or more query strings to send in a batch to @Explain.
     * @throws IOException
     * @throws NoConnectionsException
     * @throws ProcCallException
     */
    protected static void dumpQueryPlans(Client client, String... queries)
            throws IOException, NoConnectionsException, ProcCallException {
        VoltTable vts[] = client.callProcedure("@Explain", StringUtils.join(queries, '\n')).getResults();
        int ii = 0;
        for (VoltTable vtn : vts) {
            System.out.println("DEBUG: plan for " + queries[ii] + "\n" + vtn + "\n");
            ++ii;
        }
    }

    protected static void truncateTables(Client client, String[] tables) throws IOException, ProcCallException {
        for (String tb : tables) {
            truncateTables(client, tb);
        }
    }

    protected static void truncateTables(Client client, String tb) throws IOException, ProcCallException {
        client.callProcedure("@AdHoc", "Truncate table " + tb);
        validateTableOfScalarLongs(client, "select count(*) from " + tb, new long[]{0});
    }

}
