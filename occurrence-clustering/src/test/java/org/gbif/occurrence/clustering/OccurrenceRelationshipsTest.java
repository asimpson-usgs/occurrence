package org.gbif.occurrence.clustering;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.junit.Test;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;

import static org.gbif.occurrence.clustering.RelationshipAssertion.FEATURE_ASSERTION.*;
import static org.junit.Assert.*;

/**
 * Tests for relationship assertion.
 */
public class OccurrenceRelationshipsTest {

  // Schema mirrors the production occurrence HDFS view of GBIF
  private static final StructType schema = new StructType(
    new StructField[] {
      DataTypes.createStructField("gbifId", DataTypes.LongType, true),
      DataTypes.createStructField("datasetKey", DataTypes.StringType, true),
      DataTypes.createStructField("basisOfRecord", DataTypes.StringType, true),
      DataTypes.createStructField("publishingoOrgKey", DataTypes.StringType, true),
      DataTypes.createStructField("datasetName", DataTypes.StringType, true),
      DataTypes.createStructField("publisher", DataTypes.StringType, true),
      DataTypes.createStructField("kingdomKey", DataTypes.IntegerType, true),
      DataTypes.createStructField("phylumKey", DataTypes.IntegerType, true),
      DataTypes.createStructField("classKey", DataTypes.IntegerType, true),
      DataTypes.createStructField("orderKey", DataTypes.IntegerType, true),
      DataTypes.createStructField("familyKey", DataTypes.IntegerType, true),
      DataTypes.createStructField("genusKey", DataTypes.IntegerType, true),
      DataTypes.createStructField("speciesKey", DataTypes.IntegerType, true),
      DataTypes.createStructField("acceptedTaxonKey", DataTypes.IntegerType, true),
      DataTypes.createStructField("taxonKey", DataTypes.IntegerType, true),
      DataTypes.createStructField("scientificName", DataTypes.StringType, true),
      DataTypes.createStructField("acceptedScientificName", DataTypes.StringType, true),
      DataTypes.createStructField("kingdom", DataTypes.StringType, true),
      DataTypes.createStructField("phylum", DataTypes.StringType, true),
      DataTypes.createStructField("order", DataTypes.StringType, true),
      DataTypes.createStructField("family", DataTypes.StringType, true),
      DataTypes.createStructField("genus", DataTypes.StringType, true),
      DataTypes.createStructField("species", DataTypes.StringType, true),
      DataTypes.createStructField("genericName", DataTypes.StringType, true),
      DataTypes.createStructField("specificEpithet", DataTypes.StringType, true),
      DataTypes.createStructField("taxonRank", DataTypes.StringType, true),
      DataTypes.createStructField("typeStatus", DataTypes.StringType, true),
      DataTypes.createStructField("preparations", DataTypes.StringType, true),
      DataTypes.createStructField("decimalLatitude", DataTypes.DoubleType, true),
      DataTypes.createStructField("decimalLongitude", DataTypes.DoubleType, true),
      DataTypes.createStructField("countryCode", DataTypes.StringType, true),
      DataTypes.createStructField("year", DataTypes.IntegerType, true),
      DataTypes.createStructField("month", DataTypes.IntegerType, true),
      DataTypes.createStructField("day", DataTypes.IntegerType, true),
      DataTypes.createStructField("eventDate", DataTypes.StringType, true),
      DataTypes.createStructField("recordNumber", DataTypes.StringType, true),
      DataTypes.createStructField("fieldNumber", DataTypes.StringType, true),
      DataTypes.createStructField("occurrenceID", DataTypes.StringType, true),
      DataTypes.createStructField("otherCatalogNumbers", DataTypes.StringType, true),
      DataTypes.createStructField("institutionCode", DataTypes.StringType, true),
      DataTypes.createStructField("collectionCode", DataTypes.StringType, true),
      DataTypes.createStructField("catalogNumber", DataTypes.StringType, true),
      DataTypes.createStructField("recordedBy", DataTypes.StringType, true),
      DataTypes.createStructField("recordedByID", DataTypes.StringType, true),
      DataTypes.createStructField("multi", DataTypes.StringType, true)
    }
  );

  @Test
  public void testSimpleAssertions() throws IOException {
    OccurrenceFeatures o1 = new OccurrenceFeatures(new RowBuilder()
      .with("occurrenceID", "1")
      .with("speciesKey", 1)
      .with("decimalLatitude", 44.0d)
      .with("decimalLongitude", 44.0d)
      .with("catalogNumber", "TIM1")
      .with("year", 1978)
      .with("month", 12)
      .with("day", 21)
      .buildWithSchema());

    OccurrenceFeatures o2 = new OccurrenceFeatures(new RowBuilder()
      .with("occurrenceID", "2")
      .with("speciesKey", 1)
      .with("decimalLatitude", 44.0d)
      .with("decimalLongitude", 44.0d)
      .with("catalogNumber", "//TIM1")
      .with("year", 1978)
      .with("month", 12)
      .with("day", 21)
      .buildWithSchema());

    RelationshipAssertion assertion = OccurrenceRelationships.generate(o1, o2);

    assertNotNull(assertion);
    assertTrue(assertion.justificationContains(SAME_ACCEPTED_SPECIES));
  }

  /**
   * Real data from records 2332470913, 2571156410 which should cluster.
   */
  @Test
  public void testCortinarius() throws IOException {
    OccurrenceFeatures o1 = new OccurrenceFeatures(new RowBuilder()
      .with("occurrenceID", "urn:catalog:O:F:304835")
      .with("recordNumber", "TEB 12-16")
      .with("speciesKey", 3348943)
      .with("decimalLatitude", 60.3302d)
      .with("decimalLongitude", 10.4647d)
      .with("catalogNumber", "304835")
      .with("year", 2016)
      .with("month", 6)
      .with("day", 11)
      .with("eventDate", "2016-06-11T00:00:00")
      .buildWithSchema());

    OccurrenceFeatures o2 = new OccurrenceFeatures(new RowBuilder()
      .with("occurrenceID", "urn:uuid:152ce614-69e1-4fbe-8f1c-3340d0a15491")
      .with("speciesKey", 3348943)
      .with("decimalLatitude", 60.330181d)
      .with("decimalLongitude", 10.464743d)
      .with("catalogNumber", "O-DFL-6644/2-D")
      .with("recordNumber", "TEB 12-16")
      .with("year", 2016)
      .with("month", 6)
      .with("day", 11)
      .with("eventDate", "2016-06-11T00:00:00")
      .buildWithSchema());

    RelationshipAssertion assertion = OccurrenceRelationships.generate(o1, o2);

    assertNotNull(assertion);
    assertTrue(assertion.justificationContains(SAME_ACCEPTED_SPECIES));
  }

  // Test even with nonsense a Holotype of the same name must be the same specimen (or worth investigating a data issue)
  @Test
  public void testHolotype() throws IOException {
    OccurrenceFeatures o1 = new OccurrenceFeatures(new RowBuilder()
      .with("taxonKey", 3350984)
      .with("decimalLatitude", 10d)
      .with("decimalLongitude", 10d)
      .with("countryCode", "DK")
      .with("typeStatus", "HoloType")
      .buildWithSchema());

    OccurrenceFeatures o2 = new OccurrenceFeatures(new RowBuilder()
      .with("taxonKey", 3350984)
      .with("decimalLatitude", 20d) // different
      .with("decimalLongitude", 20d) // different
      .with("countryCode", "NO") // different
      .with("typeStatus", "HoloType")
      .buildWithSchema());

    RelationshipAssertion assertion = OccurrenceRelationships.generate(o1, o2);
    assertNotNull(assertion);
    assertTrue(assertion.justificationContains(SAME_SPECIMEN));
  }

  // Test that two records with same collector, approximate location but a day apart match.
  // https://github.com/gbif/occurrence/issues/177
  @Test
  public void testDayApart() throws IOException {
    // real records where a trap set one evening and visited the next day is shared twice using different
    // days
    OccurrenceFeatures o1 = new OccurrenceFeatures(new RowBuilder()
      .with("gbifId", 49635968)
      .with("speciesKey", 1850114)
      .with("decimalLatitude", 	55.737d)
      .with("decimalLongitude", 12.538d)
      .with("year", 2004)
      .with("month", 8)
      .with("day", 1) // day trap set
      .with("countryCode", "DK")
      .with("recordedBy", "Donald Hobern")
      .buildWithSchema());

    OccurrenceFeatures o2 = new OccurrenceFeatures(new RowBuilder()
      .with("gbifId", 1227719129)
      .with("speciesKey", 1850114)
      .with("decimalLatitude", 	55.736932d) // different
      .with("decimalLongitude", 12.538104d)
      .with("year", 2004)
      .with("month", 8)
      .with("day", 2) // day collected
      .with("countryCode", "DK")
      .with("recordedBy", "Donald Hobern")
      .buildWithSchema());

    RelationshipAssertion assertion = OccurrenceRelationships.generate(o1, o2);
    assertNotNull(assertion);
    assertTrue(assertion.justificationContainsAll(APPROXIMATE_DATE, WITHIN_200m, SAME_COUNTRY, SAME_RECORDER_NAME));
  }

  // test 3 decimal place rounding example clusters
  @Test
  public void test3DP() throws IOException {
    // real records of Seigler & Miller
    OccurrenceFeatures o1 = new OccurrenceFeatures(new RowBuilder()
      .with("gbifId", 1675790844)
      .with("speciesKey", 3794925)
      .with("decimalLatitude", 		21.8656d)
      .with("decimalLongitude", -102.909d)
      .with("year", 2007)
      .with("month", 5)
      .with("day", 26)
      .with("recordedBy", "D. S. Seigler & J. T. Miller")
      .buildWithSchema());

    OccurrenceFeatures o2 = new OccurrenceFeatures(new RowBuilder()
      .with("gbifId", 2268858676l)
      .with("speciesKey", 3794925)
      .with("decimalLatitude", 		21.86558d)
      .with("decimalLongitude", -102.90929d)
      .with("year", 2007)
      .with("month", 5)
      .with("day", 26)
      .with("recordedBy", "David S. Seigler|J.T. Miller") // we should at some point detect this match
      .buildWithSchema());

    RelationshipAssertion assertion = OccurrenceRelationships.generate(o1, o2);
    assertNotNull(assertion);
    assertTrue(assertion.justificationContainsAll(SAME_DATE, WITHIN_200m, SAME_ACCEPTED_SPECIES));
  }

  @Test
  public void testNormaliseID() {
    assertEquals("ABC", OccurrenceRelationships.normalizeID(" A-/, B \\C"));
    // These are examples of collectors we could be able to organize in the future
    assertEquals("DAVIDSSEIGLERJTMILLER", OccurrenceRelationships.normalizeID("David S. Seigler|J.T. Miller"));
    assertEquals("DSSEIGLERJTMILLER", OccurrenceRelationships.normalizeID("D. S. Seigler & J. T. Miller"));
  }

  /**
   * Test to simply verify that a Spark dataset row operates the same as the isolated Row tests.
   */
  @Test
  public void testWithSpark() {
    SparkConf conf = new SparkConf()
      .setMaster("local[*]")
      .setAppName("test")
      .set("spark.ui.enabled", "false");

    JavaSparkContext jsc = new JavaSparkContext(conf);
    SQLContext sqlContext = new SQLContext(jsc);

    try {
      List<Row> rows = Arrays.asList(
        new RowBuilder()
          .with("occurrenceID", "1")
          .with("speciesKey", 1)
          .with("decimalLatitude", 44.0d)
          .with("decimalLongitude", 44.0d)
          .with("catalogNumber", "TIM1")
          .with("year", 1978)
          .with("month", 12)
          .with("day", 21).buildSchemaless(),
        new RowBuilder()
          .with("occurrenceID", "2")
          .with("speciesKey", 1)
          .with("decimalLatitude", 44.0d)
          .with("decimalLongitude", 44.0d)
          .with("catalogNumber", "//TIM1")
          .with("year", 1978)
          .with("month", 12)
          .with("day", 21)
          .buildSchemaless());
      final Dataset<Row> data = sqlContext.createDataFrame(rows, schema);
      List<Row> rowData = data.collectAsList();

      OccurrenceFeatures o1 = new OccurrenceFeatures(rowData.get(0));
      OccurrenceFeatures o2 = new OccurrenceFeatures(rowData.get(1));

      RelationshipAssertion assertion = OccurrenceRelationships.generate(o1, o2);

      assertNotNull(assertion);
      assertTrue(assertion.justificationContains(SAME_ACCEPTED_SPECIES));
    } finally {
      jsc.stop();
    }
  }



  /**
   * Utility builder of rows. Rows will adhere to the schema but may be constructed with or without.
   */
  private static class RowBuilder {
    private Object[] values = new Object[schema.fieldNames().length];

    private RowBuilder with(String field, Object value) {
      values[Arrays.asList(schema.fieldNames()).indexOf(field)] = value;
      return this;
    }

    private Row buildSchemaless() {
      return RowFactory.create(values);
    }

    private Row buildWithSchema() {
      return new GenericRowWithSchema(values, schema);
    }
  }
}
