package org.gbif.occurrence.cli.registry;

import org.gbif.api.model.common.paging.Pageable;
import org.gbif.api.model.common.paging.PagingRequest;
import org.gbif.api.model.common.paging.PagingResponse;
import org.gbif.api.model.registry.Dataset;
import org.gbif.api.model.registry.Endpoint;
import org.gbif.api.model.registry.Organization;
import org.gbif.api.service.registry.OrganizationService;
import org.gbif.api.util.comparators.EndpointPriorityComparator;
import org.gbif.api.vocabulary.EndpointType;
import org.gbif.common.messaging.AbstractMessageCallback;
import org.gbif.common.messaging.api.MessagePublisher;
import org.gbif.common.messaging.api.messages.*;
import org.gbif.occurrence.cli.registry.sync.OccurrenceScanMapper;
import org.gbif.occurrence.cli.registry.sync.RegistryBasedOccurrenceMutator;
import org.gbif.occurrence.cli.registry.sync.SyncCommon;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for any registry changes {@link RegistryChangeMessage}
 * of interest to occurrences: namely organization and dataset updates or deletions.
 * <p>
 * This was written at a time when we only looked at occurrence datasets, but without
 * planning, it is now the process that also triggers crawling for checklist datasets,
 * and metadata-only datasets.
 */
public class RegistryChangeListener extends AbstractMessageCallback<RegistryChangeMessage> {

  private static final Logger LOG = LoggerFactory.getLogger(RegistryChangeListener.class);
  private static final int PAGING_LIMIT = 20;
  private static final String HBASE_TIMEOUT = "600000";
  private static final String MR_MAP_MEMORY_MB = "1024";
  //approx. 85% of MR_MAP_MEMORY_MB
  private static final String MR_MAP_JAVA_OPTS = "-Xmx768m";
  private static final String MR_QUEUE_NAME = "crap";

  private static final Set<EndpointType> CRAWLABLE_ENDPOINT_TYPES = new ImmutableSet.Builder<EndpointType>().add(
    EndpointType.BIOCASE,
    EndpointType.DIGIR,
    EndpointType.DIGIR_MANIS,
    EndpointType.TAPIR,
    EndpointType.DWC_ARCHIVE,
    EndpointType.EML).build();

  // pipelines
  private static final String METADATA_INTERPRETATION = "METADATA";
  private static final String LOCATION_INTERPRETATION = "LOCATION";

  /*
    When an IPT publishes a new dataset we will get multiple messages from the registry informing us of the update
    (depending on the number of endpoints, contacts etc). We only want to send a single crawl message for one of those
    updates so we cache the dataset uuid for 5 seconds, which should be long enough to handle all of the registry
    updates.
    */
  private static final Cache<UUID, Object> RECENTLY_UPDATED_DATASETS =
    CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.SECONDS).initialCapacity(10).maximumSize(1000).build();
  // used as a value for the cache - we only care about the keys
  private static final Object EMPTY_VALUE = new Object();

  private final MessagePublisher messagePublisher;
  private final OrganizationService orgService;
  private RegistryBasedOccurrenceMutator occurrenceMutator;

  public RegistryChangeListener(MessagePublisher messagePublisher, OrganizationService orgService) {
    this.messagePublisher = messagePublisher;
    this.orgService = orgService;
    this.occurrenceMutator = new RegistryBasedOccurrenceMutator();
  }

  @Override
  public void handleMessage(RegistryChangeMessage message) {
    LOG.info("Handling registry [{}] msg for class [{}]", message.getChangeType(), message.getObjectClass());
    Class<?> clazz = message.getObjectClass();
    if ("Dataset".equals(clazz.getSimpleName())) {
      handleDataset(message.getChangeType(), (Dataset) message.getOldObject(), (Dataset) message.getNewObject());
    } else if ("Organization".equals(clazz.getSimpleName())) {
      handleOrganization(message.getChangeType(),
                         (Organization) message.getOldObject(),
                         (Organization) message.getNewObject());
    }
  }

  private void handleDataset(RegistryChangeMessage.ChangeType changeType, Dataset oldDataset, Dataset newDataset) {
    switch (changeType) {
      case DELETED:
        LOG.info("Sending delete for dataset [{}]", oldDataset.getKey());
        try {
          messagePublisher.send(new DeleteDatasetOccurrencesMessage(oldDataset.getKey(),
                                                                    OccurrenceDeletionReason.DATASET_MANUAL));
        } catch (IOException e) {
          LOG.warn("Could not send delete dataset message for key [{}]", oldDataset.getKey(), e);
        }
        break;
      case UPDATED:
        // if it has a crawlable endpoint and we haven't just sent a crawl msg, we need to crawl it no matter what changed
        if (shouldCrawl(newDataset)) {
          LOG.info("Sending crawl for updated dataset [{}]", newDataset.getKey());
          try {
            messagePublisher.send(new StartCrawlMessage(newDataset.getKey()));
          } catch (IOException e) {
            LOG.warn("Could not send start crawl message for dataset key [{}]", newDataset.getKey(), e);
          }
          // check if we should start a m/r job to update occurrence records
          // FIXME this can lead to issues if 100 datasets change organization in a batch update.
          if (occurrenceMutator.requiresUpdate(oldDataset, newDataset)) {
            Optional<String> changedMessage = occurrenceMutator.generateUpdateMessage(oldDataset, newDataset);

            // send message to pipelines
            sendUpdateMessageToPipelines(
                newDataset, Collections.singleton(METADATA_INTERPRETATION), changedMessage.orElse("Dataset changed in registry"));

            LOG.info("Starting m/r sync for dataset [{}], with reason {}", newDataset.getKey(), changedMessage);
            try {
              runMrSync(newDataset.getKey());
            } catch (Exception e) {
              LOG.warn("Failed to run RegistrySync m/r for dataset [{}]", newDataset.getKey(), e);
            }
          } else {
            LOG.debug("Owning orgs and license match for updated dataset [{}] - taking no action", newDataset.getKey());
          }
        } else {
          LOG.info("Ignoring update of dataset [{}] because either no crawlable endpoints or we just sent a crawl",
                   newDataset.getKey());
        }
        break;
      case CREATED:
        if (shouldCrawl(newDataset)) {
          LOG.info("Sending crawl for new dataset [{}]", newDataset.getKey());
          try {
            messagePublisher.send(new StartCrawlMessage(newDataset.getKey()));
          } catch (IOException e) {
            LOG.warn("Could not send start crawl message for dataset key [{}]", newDataset.getKey(), e);
          }
        } else {
          LOG.info("Ignoring creation of dataset [{}] because no crawlable endpoints or we just sent a crawl",
                   newDataset.getKey());
        }
        break;
    }
  }

  private static boolean shouldCrawl(Dataset dataset) {
    if (RECENTLY_UPDATED_DATASETS.getIfPresent(dataset.getKey()) == null && isCrawlable(dataset)) {
      RECENTLY_UPDATED_DATASETS.put(dataset.getKey(), EMPTY_VALUE);
      return true;
    }

    return false;
  }

  private static boolean isCrawlable(Dataset dataset) {
    for (Endpoint endpoint : dataset.getEndpoints()) {
      if (CRAWLABLE_ENDPOINT_TYPES.contains(endpoint.getType())) {
        return true;
      }
    }

    return false;
  }

  private void handleOrganization(
    RegistryChangeMessage.ChangeType changeType, Organization oldOrg, Organization newOrg
  ) {
    switch (changeType) {
      case UPDATED:
        if (!oldOrg.isEndorsementApproved() && newOrg.isEndorsementApproved()) {
          LOG.info("Starting crawl of all datasets for newly endorsed org [{}]", newOrg.getKey());
          DatasetVisitor visitor = dataset -> {
            try {
              messagePublisher.send(new StartCrawlMessage(dataset.getKey()));
            } catch (IOException e) {
              LOG.warn("Could not send start crawl message for newly endorsed dataset key [{}]", dataset.getKey(), e);
            }
          };
          visitOwnedDatasets(newOrg.getKey(), visitor);
        } else if (occurrenceMutator.requiresUpdate(oldOrg, newOrg)
            && newOrg.getNumPublishedDatasets() > 0) {
          LOG.info(
              "Starting m/r sync for all datasets of org [{}] because it has changed country from [{}] to [{}]",
              newOrg.getKey(),
              oldOrg.getCountry(),
              newOrg.getCountry());
          DatasetVisitor visitor =
              dataset -> {
                sendUpdateMessageToPipelines(
                    dataset,
                    Sets.newHashSet(METADATA_INTERPRETATION, LOCATION_INTERPRETATION),
                    occurrenceMutator
                        .generateUpdateMessage(oldOrg, newOrg)
                        .orElse("Organization change in registry"));
                runMrSync(dataset.getKey());
              };
          visitOwnedDatasets(newOrg.getKey(), visitor);
        }
        break;
      case DELETED:
      case CREATED:
        break;
    }
  }

  /**
   * Creates and submit a MapReduce job to the cluster using {@link OccurrenceScanMapper} as a mapper.
   */
  private static void runMrSync(@Nullable UUID datasetKey) {

    //create the HBase config here since hbase-site.xml is (at least should) be in our classpath.
    Configuration conf = HBaseConfiguration.create();
    conf.set("hbase.client.scanner.timeout.period", HBASE_TIMEOUT);
    conf.set("hbase.rpc.timeout", HBASE_TIMEOUT);

    Properties props = SyncCommon.loadProperties();
    // add all props to job context for use by the OccurrenceScanMapper when it no longer has access to our classpath
    for (Object key : props.keySet()) {
      String stringKey = (String) key;
      conf.set(stringKey, props.getProperty(stringKey));
    }

    Scan scan = new Scan();
    scan.addColumn(SyncCommon.OCC_CF, SyncCommon.DK_COL); //datasetKey
    scan.addColumn(SyncCommon.OCC_CF, SyncCommon.HC_COL); //publishingCountry
    scan.addColumn(SyncCommon.OCC_CF, SyncCommon.OOK_COL); //publishingOrgKey
    scan.addColumn(SyncCommon.OCC_CF, SyncCommon.CI_COL); //crawlId
    scan.addColumn(SyncCommon.OCC_CF, SyncCommon.LICENSE_COL);

    scan.setCaching(200);
    scan.setCacheBlocks(false); // don't set to true for MR jobs (from HBase MapReduce Examples)

    String targetTable = props.getProperty(SyncCommon.OCC_TABLE_PROPS_KEY);
    String mrUser = props.getProperty(SyncCommon.MR_USER_PROPS_KEY);
    String jobTitle = "Registry-Occurrence Sync on table " + targetTable;
    String rawDatasetKey = null;
    if (datasetKey != null) {
      rawDatasetKey = datasetKey.toString();
      scan.setFilter(new SingleColumnValueFilter(SyncCommon.OCC_CF,
                                                 SyncCommon.DK_COL,
                                                 CompareFilter.CompareOp.EQUAL,
                                                 Bytes.toBytes(rawDatasetKey)));
    }

    if (rawDatasetKey != null) {
      jobTitle = jobTitle + " for dataset " + rawDatasetKey;
    }

    try {
      Job job = Job.getInstance(conf, jobTitle);
      job.setUser(mrUser);
      job.setJarByClass(OccurrenceScanMapper.class);
      job.setOutputFormatClass(NullOutputFormat.class);
      job.setNumReduceTasks(0);
      job.getConfiguration().set("mapreduce.map.speculative", "false");
      job.getConfiguration().set("mapreduce.reduce.speculative", "false");
      job.getConfiguration().set("mapreduce.client.submit.file.replication", "3");
      job.getConfiguration().set("mapreduce.task.classpath.user.precedence", "true");
      job.getConfiguration().set("mapreduce.job.user.classpath.first", "true");
      job.getConfiguration().set("mapreduce.map.memory.mb", MR_MAP_MEMORY_MB);
      job.getConfiguration().set("mapreduce.map.java.opts", MR_MAP_JAVA_OPTS);
      job.getConfiguration().set("mapred.job.queue.name", MR_QUEUE_NAME);

      if (targetTable == null || mrUser == null) {
        LOG.error("Sync m/r not properly configured (occ table or mapreduce user not set) - aborting");
      } else {
        // NOTE: addDependencyJars must be false or you'll see it trying to load hdfs://c1n1/home/user/app/lib/occurrence-cli.jar
        TableMapReduceUtil.initTableMapperJob(targetTable,
                                              scan,
                                              OccurrenceScanMapper.class,
                                              ImmutableBytesWritable.class,
                                              NullWritable.class,
                                              job,
                                              false);
        job.waitForCompletion(true);
      }
    } catch (Exception e) {
      LOG.error("Could not start m/r job, aborting", e);
    }

    LOG.info("Finished running m/r sync for dataset [{}]", datasetKey);
  }

  private void visitOwnedDatasets(UUID orgKey, DatasetVisitor visitor) {
    int datasetCount = 0;
    boolean endOfRecords = false;
    int offset = 0;
    do {
      Pageable page = new PagingRequest(offset, PAGING_LIMIT);
      PagingResponse<Dataset> datasets = orgService.publishedDatasets(orgKey, page);

      for (Dataset dataset : datasets.getResults()) {
        visitor.visit(dataset);
      }
      datasetCount += datasets.getResults().size();
      offset += PAGING_LIMIT;

      if (datasets.isEndOfRecords()) {
        endOfRecords = datasets.isEndOfRecords();
      }
    } while (!endOfRecords);
    LOG.info("Visited [{}] datasets owned by org [{}]", datasetCount, orgKey);
  }

  private interface DatasetVisitor {

    void visit(Dataset dataset);
  }

  /**
   * Sends message to pipelines to update the metadata of the dataset.
   *
   * @param dataset dataset to update
   * @param interpretations interpretations that should be run
   * @param changedMessage message with the change occurred in the registry
   */
  private void sendUpdateMessageToPipelines(Dataset dataset, Set<String> interpretations, String changedMessage) {
    Optional<Endpoint> endpoint = getEndpoint(dataset);
    if (!endpoint.isPresent()) {
      LOG.error(
        "Could not find a valid endpoint for dataset {}. Message to pipelines to update metadata NOT SENT",
        dataset.getKey());
      return;
    }

    LOG.info(
        "Sending a message to pipelines to update the {} for dataset [{}], with reason {}",
        interpretations,
        dataset.getKey(),
        changedMessage);

    try {
      PipelinesVerbatimMessage message =
          new PipelinesVerbatimMessage(
              dataset.getKey(),
              null,
              interpretations,
              Sets.newHashSet("VERBATIM_TO_INTERPRETED", "INTERPRETED_TO_INDEX", "HDFS_VIEW"),
              endpoint.get().getType());

      messagePublisher.send(
        new PipelinesBalancerMessage(message.getClass().getSimpleName(), message.toString()));
    } catch (IOException e) {
      LOG.error("Could not send message to pipelines to update metadata for dataset [{}]", dataset.getKey(), e);
    }
  }

  private Optional<Endpoint> getEndpoint(Dataset dataset) {
    return dataset.getEndpoints()
      .stream()
      .filter(e -> EndpointPriorityComparator.PRIORITIES.contains(e.getType()))
      .max(new EndpointPriorityComparator());
  }
}
