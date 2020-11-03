package org.gbif.occurrence.clustering;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertTrue;

/**
 * Unit tests for OccurrenceFeatures.
 *
 * These use a parquet file as input created using the following (CC0 data):
 * <pre>
 *   CREATE TABLE tim.test STORED AS parquet AS
 *   SELECT * FROM prod_h.occurrence
 *   WHERE datasetKey='50c9509d-22c7-4a22-a47d-8c48425ef4a7' AND recordedBy='Tim Robertson'
 * </pre>
 */
public class OccurrenceFeaturesTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private JavaSparkContext jsc;
  private SQLContext sqlContext;

  @Before
  public void setup() {
    SparkConf conf = new SparkConf()
      .setMaster("local[*]")
      .setAppName("test")
      .set("spark.ui.enabled", "false");
    jsc = new JavaSparkContext(conf);
    sqlContext = new SQLContext(jsc);
  }

  @After
  public void teardown() {
    jsc.stop();
  }

  /**
   * Test to ensure that JSON is generated correctly for nested JSON.
   */
  @Test
  public void testAsJsonWithMultimedia() throws IOException {
    Dataset<Row> data = sqlContext.read().parquet("src/test/resources/sample.parquet");
    Row first = data.first();

    // read and format the JSON from the first row
    String multimedia = first.getString(first.fieldIndex("ext_multimedia"));
    String formattedMultimedia = OBJECT_MAPPER.writeValueAsString(OBJECT_MAPPER.readTree(multimedia));

    OccurrenceFeatures features = new OccurrenceFeatures(first, null, "ext_multimedia");

    // ensure that the resulting JSON is not escaped
    assertTrue(features.asJson().contains(formattedMultimedia));
  }
}
