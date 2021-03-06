package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.TestUtils;
import com.conveyal.gtfs.util.FareDTO;
import com.conveyal.gtfs.util.FareRuleDTO;
import com.conveyal.gtfs.util.FeedInfoDTO;
import com.conveyal.gtfs.util.InvalidNamespaceException;
import com.conveyal.gtfs.util.RouteDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import static com.conveyal.gtfs.GTFS.createDataSource;
import static com.conveyal.gtfs.GTFS.makeSnapshot;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

public class JDBCTableWriterTest {

    private static final Logger LOG = LoggerFactory.getLogger(JDBCTableWriterTest.class);

    private static String testDBName;
    private static DataSource testDataSource;
    private static String testNamespace;
    private static final ObjectMapper mapper = new ObjectMapper();

    private static JdbcTableWriter createTestTableWriter (Table table) throws InvalidNamespaceException {
        return new JdbcTableWriter(table, testDataSource, testNamespace);
    }

    @BeforeClass
    public static void setUpClass() throws SQLException {
        // create a new database
        testDBName = TestUtils.generateNewDB();
        String dbConnectionUrl = String.format("jdbc:postgresql://localhost/%s", testDBName);
        testDataSource = createDataSource (dbConnectionUrl, null, null);
        LOG.info("creating feeds table because it isn't automatically generated unless you import a feed");
        Connection connection = testDataSource.getConnection();
        connection.createStatement()
            .execute("create table if not exists feeds (namespace varchar primary key, md5 varchar, " +
                "sha1 varchar, feed_id varchar, feed_version varchar, filename varchar, loaded_date timestamp, " +
                "snapshot_of varchar)");
        connection.commit();
        LOG.info("feeds table created");

        // create an empty snapshot to create a new namespace and all the tables
        FeedLoadResult result = makeSnapshot(null, testDataSource);
        testNamespace = result.uniqueIdentifier;
    }

    @Test
    public void canCreateUpdateAndDeleteFeedInfoEntities() throws IOException, SQLException, InvalidNamespaceException {
        // Store Table and Class values for use in test.
        final Table feedInfoTable = Table.FEED_INFO;
        final Class<FeedInfoDTO> feedInfoDTOClass = FeedInfoDTO.class;

        // create new object to be saved
        FeedInfoDTO feedInfoInput = new FeedInfoDTO();
        String publisherName = "test-publisher";
        feedInfoInput.feed_publisher_name = publisherName;
        feedInfoInput.feed_publisher_url = "example.com";
        feedInfoInput.feed_lang = "en";
        feedInfoInput.default_route_color = "1c8edb";
        feedInfoInput.default_route_type = "3";

        // convert object to json and save it
        JdbcTableWriter createTableWriter = createTestTableWriter(feedInfoTable);
        String createOutput = createTableWriter.create(mapper.writeValueAsString(feedInfoInput), true);
        LOG.info("create {} output:", feedInfoTable.name);
        LOG.info(createOutput);

        // parse output
        FeedInfoDTO createdFeedInfo = mapper.readValue(createOutput, feedInfoDTOClass);

        // make sure saved data matches expected data
        assertThat(createdFeedInfo.feed_publisher_name, equalTo(publisherName));

        // try to update record
        String updatedPublisherName = "test-publisher-updated";
        createdFeedInfo.feed_publisher_name = updatedPublisherName;

        // covert object to json and save it
        JdbcTableWriter updateTableWriter = createTestTableWriter(feedInfoTable);
        String updateOutput = updateTableWriter.update(
            createdFeedInfo.id,
            mapper.writeValueAsString(createdFeedInfo),
            true
        );
        LOG.info("update {} output:", feedInfoTable.name);
        LOG.info(updateOutput);

        FeedInfoDTO updatedFeedInfoDTO = mapper.readValue(updateOutput, feedInfoDTOClass);

        // make sure saved data matches expected data
        assertThat(updatedFeedInfoDTO.feed_publisher_name, equalTo(updatedPublisherName));

        // try to delete record
        JdbcTableWriter deleteTableWriter = createTestTableWriter(feedInfoTable);
        int deleteOutput = deleteTableWriter.delete(
            createdFeedInfo.id,
            true
        );
        LOG.info("deleted {} records from {}", deleteOutput, feedInfoTable.name);

        // make sure record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(String.format(
            "select * from %s.%s where id=%d",
            testNamespace,
            feedInfoTable.name,
            createdFeedInfo.id
        ));
    }

    @Test
    public void canPreventSQLInjection() throws IOException, SQLException, InvalidNamespaceException {
        // create new object to be saved
        FeedInfoDTO feedInfoInput = new FeedInfoDTO();
        String publisherName = "' OR 1 = 1; SELECT '1";
        feedInfoInput.feed_publisher_name = publisherName;
        feedInfoInput.feed_publisher_url = "example.com";
        feedInfoInput.feed_lang = "en";
        feedInfoInput.default_route_color = "1c8edb";
        feedInfoInput.default_route_type = "3";

        // convert object to json and save it
        JdbcTableWriter createTableWriter = createTestTableWriter(Table.FEED_INFO);
        String createOutput = createTableWriter.create(mapper.writeValueAsString(feedInfoInput), true);
        LOG.info("create output:");
        LOG.info(createOutput);

        // parse output
        FeedInfoDTO createdFeedInfo = mapper.readValue(createOutput, FeedInfoDTO.class);

        // make sure saved data matches expected data
        assertThat(createdFeedInfo.feed_publisher_name, equalTo(publisherName));
    }

    @Test
    public void canCreateUpdateAndDeleteFares() throws IOException, SQLException, InvalidNamespaceException {
        // Store Table and Class values for use in test.
        final Table fareTable = Table.FARE_ATTRIBUTES;
        final Class<FareDTO> fareDTOClass = FareDTO.class;

        // create new object to be saved
        FareDTO fareInput = new FareDTO();
        String fareId = "2A";
        fareInput.fare_id = fareId;
        fareInput.currency_type = "USD";
        fareInput.price = 2.50;
        fareInput.agency_id = "RTA";
        fareInput.payment_method = 0;
        // Empty value should be permitted for transfers and transfer_duration
        fareInput.transfers = null;
        fareInput.transfer_duration = null;
        FareRuleDTO fareRuleInput = new FareRuleDTO();
        // Fare ID should be assigned to "child entity" by editor automatically.
        fareRuleInput.fare_id = null;
        fareRuleInput.route_id = null;
        // FIXME There is currently no check for valid zone_id values in contains_id, origin_id, and destination_id.
        fareRuleInput.contains_id = "any";
        fareRuleInput.origin_id = "value";
        fareRuleInput.destination_id = "permitted";
        fareInput.fare_rules = new FareRuleDTO[]{fareRuleInput};

        // convert object to json and save it
        JdbcTableWriter createTableWriter = createTestTableWriter(fareTable);
        String createOutput = createTableWriter.create(mapper.writeValueAsString(fareInput), true);
        LOG.info("create {} output:", fareTable.name);
        LOG.info(createOutput);

        // parse output
        FareDTO createdFare = mapper.readValue(createOutput, fareDTOClass);

        // make sure saved data matches expected data
        assertThat(createdFare.fare_id, equalTo(fareId));
        assertThat(createdFare.fare_rules[0].fare_id, equalTo(fareId));

        // try to update record
        String updatedFareId = "3B";
        createdFare.fare_id = updatedFareId;

        // covert object to json and save it
        JdbcTableWriter updateTableWriter = createTestTableWriter(fareTable);
        String updateOutput = updateTableWriter.update(
                createdFare.id,
                mapper.writeValueAsString(createdFare),
                true
        );
        LOG.info("update {} output:", fareTable.name);
        LOG.info(updateOutput);

        FareDTO updatedFareDTO = mapper.readValue(updateOutput, fareDTOClass);

        // make sure saved data matches expected data
        assertThat(updatedFareDTO.fare_id, equalTo(updatedFareId));
        assertThat(updatedFareDTO.fare_rules[0].fare_id, equalTo(updatedFareId));

        // try to delete record
        JdbcTableWriter deleteTableWriter = createTestTableWriter(fareTable);
        int deleteOutput = deleteTableWriter.delete(
                createdFare.id,
                true
        );
        LOG.info("deleted {} records from {}", deleteOutput, fareTable.name);

        // make sure fare_attributes record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(String.format(
                "select * from %s.%s where id=%d",
                testNamespace,
                fareTable.name,
                createdFare.id
        ));

        // make sure fare_rules record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(String.format(
                "select * from %s.%s where id=%d",
                testNamespace,
                Table.FARE_RULES.name,
                createdFare.fare_rules[0].id
        ));
    }

    private void assertThatSqlQueryYieldsZeroRows(String sql) throws SQLException {
        assertThatSqlQueryYieldsRowCount(sql, 0);
    }

    private void assertThatSqlQueryYieldsRowCount(String sql, int expectedRowCount) throws SQLException {
        LOG.info(sql);
        ResultSet resultSet = testDataSource.getConnection().prepareStatement(sql).executeQuery();
        assertThat(resultSet.getFetchSize(), equalTo(expectedRowCount));
    }

    @Test
    public void canCreateUpdateAndDeleteRoutes() throws IOException, SQLException, InvalidNamespaceException {
        // Store Table and Class values for use in test.
        final Table routeTable = Table.ROUTES;
        final Class<RouteDTO> routeDTOClass = RouteDTO.class;

        // create new object to be saved
        RouteDTO routeInput = new RouteDTO();
        String routeId = "500";
        routeInput.route_id = routeId;
        routeInput.agency_id = "RTA";
        // Empty value should be permitted for transfers and transfer_duration
        routeInput.route_short_name = "500";
        routeInput.route_long_name = "Hollingsworth";
        routeInput.route_type = 3;

        // convert object to json and save it
        JdbcTableWriter createTableWriter = createTestTableWriter(routeTable);
        String createOutput = createTableWriter.create(mapper.writeValueAsString(routeInput), true);
        LOG.info("create {} output:", routeTable.name);
        LOG.info(createOutput);

        // parse output
        RouteDTO createdRoute = mapper.readValue(createOutput, routeDTOClass);

        // make sure saved data matches expected data
        assertThat(createdRoute.route_id, equalTo(routeId));
        // TODO: Verify with a SQL query that the database now contains the created data (we may need to use the same
        //       db connection to do this successfully?)

        // try to update record
        String updatedRouteId = "600";
        createdRoute.route_id = updatedRouteId;

        // covert object to json and save it
        JdbcTableWriter updateTableWriter = createTestTableWriter(routeTable);
        String updateOutput = updateTableWriter.update(
                createdRoute.id,
                mapper.writeValueAsString(createdRoute),
                true
        );
        LOG.info("update {} output:", routeTable.name);
        LOG.info(updateOutput);

        RouteDTO updatedRouteDTO = mapper.readValue(updateOutput, routeDTOClass);

        // make sure saved data matches expected data
        assertThat(updatedRouteDTO.route_id, equalTo(updatedRouteId));
        // TODO: Verify with a SQL query that the database now contains the updated data (we may need to use the same
        //       db connection to do this successfully?)

        // try to delete record
        JdbcTableWriter deleteTableWriter = createTestTableWriter(routeTable);
        int deleteOutput = deleteTableWriter.delete(
                createdRoute.id,
                true
        );
        LOG.info("deleted {} records from {}", deleteOutput, routeTable.name);

        // make sure route record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(String.format(
                "select * from %s.%s where id=%d",
                testNamespace,
                routeTable.name,
                createdRoute.id
        ));
    }

    @AfterClass
    public static void tearDownClass() {
        TestUtils.dropDB(testDBName);
    }
}
