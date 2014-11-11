export HADOOP_CLIENT_OPTS="-Xmx2073741824 $HADOOP_CLIENT_OPTS"
hadoop --config /etc/hadoop/conf jar /opt/cloudera/parcels/CDH/lib/solr/contrib/mr/search-mr-*-job.jar org.apache.solr.hadoop.MapReduceIndexerTool -D mapred.child.java.opts=-Xmx2073741824 -D morphlineVariable.ENV_ZK_HOST=$2 -D morphlineVariable.ENV_SOLR_COLLECTION=$3 -libjars /opt/cloudera/auxjar/jts-1.13.jar --log4j /opt/cloudera/parcels/CDH/share/doc/search-1.0.0+cdh5.2.0+0/examples/solr-nrt/log4j.properties --morphline-file solr_occurrence_morphline.conf --shards 1 --output-dir $1 --solr-home-dir=solr/collection1/ --verbose $4
