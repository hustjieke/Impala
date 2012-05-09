// Copyright (c) 2011 Cloudera, Inc. All rights reserved.

package com.cloudera.impala.planner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Map.Entry;

import org.apache.hadoop.fs.BlockLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.Analyzer;
import com.cloudera.impala.analysis.TupleDescriptor;
import com.cloudera.impala.catalog.HdfsPartition;
import com.cloudera.impala.catalog.HdfsTable;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.thrift.Constants;
import com.cloudera.impala.thrift.THdfsFileSplit;
import com.cloudera.impala.thrift.THdfsScanNode;
import com.cloudera.impala.thrift.TPlanNode;
import com.cloudera.impala.thrift.TPlanNodeType;
import com.cloudera.impala.thrift.TScanRange;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Scan of a single single table. Currently limited to full-table scans.
 * TODO: pass in range restrictions.
 */
public class HdfsScanNode extends ScanNode {
  private final static Logger LOG = LoggerFactory.getLogger(HdfsScanNode.class);

  private final HdfsTable tbl;

  // Partitions that are filtered in for scanning by the key ranges
  private final ArrayList<HdfsPartition> partitions = Lists.newArrayList();

  /**
   * Constructs node to scan given data files of table 'tbl'.
   */
  public HdfsScanNode(int id, TupleDescriptor desc, HdfsTable tbl) {
    super(id, desc);
    this.tbl = tbl;
  }

  @Override
  protected String debugString() {
    ToStringHelper helper = Objects.toStringHelper(this);
    for (HdfsPartition partition: partitions) {
      helper.add("Partition " + partition.getId() + ":", partition.toString());
    }
    return helper.addValue(super.debugString()).toString();
  }

  /**
   * Compute file paths and key values based on key ranges.
   */
  @Override
  public void finalize(Analyzer analyzer) throws InternalException {
    for (HdfsPartition p: tbl.getPartitions()) {
      if (p.getFileDescriptors().size() == 0) {
        // No point scanning partitions that have no data
        continue;
      }

      Preconditions.checkState(p.getPartitionValues().size() ==
        tbl.getNumClusteringCols());
      if (keyRanges != null) {
        // check partition key values against key ranges, if set
        Preconditions.checkState(keyRanges.size() <= p.getPartitionValues().size());
        boolean matchingPartition = true;
        for (int i = 0; i < keyRanges.size(); ++i) {
          ValueRange keyRange = keyRanges.get(i);
          if (keyRange != null &&
              !keyRange.isInRange(analyzer, p.getPartitionValues().get(i))) {
            matchingPartition = false;
            break;
          }
        }
        if (!matchingPartition) {
          // skip this partition, it's outside the key ranges
          continue;
        }
      }
      // HdfsPartition is immutable, so it's ok to copy by reference
      partitions.add(p);
    }
  }

  @Override
  protected void toThrift(TPlanNode msg) {
    msg.hdfs_scan_node = new THdfsScanNode(desc.getId().asInt());
    msg.node_type = TPlanNodeType.HDFS_SCAN_NODE;
  }

  /**
   * Block assignment data, including the total number of assigned bytes, for a single
   * host.
   */
  static private class HostBlockAssignment {
    private final String hostname;
    private long assignedBytes;

    public String getHostname() { return hostname; }
    public long getAssignedBytes() { return assignedBytes; }

    // list of (file path, block location)
    private final List<HdfsTable.BlockMetadata> blockMetadata;

    HostBlockAssignment(String hostname) {
      this.hostname = hostname;
      this.assignedBytes = 0;
      this.blockMetadata = Lists.newArrayList();
    }

    public void addBlock(HdfsTable.BlockMetadata block) {
      blockMetadata.add(block);
      assignedBytes += block.getLocation().getLength();
    }

    public void add(HostBlockAssignment info) {
      blockMetadata.addAll(info.blockMetadata);
      assignedBytes += info.assignedBytes;
    }
  }

  /**
   * Given a target number of nodes, assigns all blocks in all active partitions to nodes
   * and returns a block assignment for each host.
   */
  private List<HostBlockAssignment> computeHostBlockAssignments(int numNodes) {
    // map from host to list of blocks assigned to that host
    Map<String, HostBlockAssignment> assignmentMap = Maps.newHashMap();

    for (HdfsPartition partition: partitions) {
      List<HdfsTable.BlockMetadata> blockMetadata =
          HdfsTable.getBlockMetadata(partition);

      for (HdfsTable.BlockMetadata block: blockMetadata) {
        String[] blockHosts = null;
        try {
          blockHosts = block.getLocation().getHosts();
          LOG.info(Arrays.toString(blockHosts));
        } catch (IOException e) {
          // this shouldn't happen, getHosts() doesn't throw anything
          String errorMsg = "BlockLocation.getHosts() failed:\n" + e.getMessage();
          LOG.error(errorMsg);
          throw new IllegalStateException(errorMsg);
        }

        // greedy block assignment: find host with fewest assigned bytes
        Preconditions.checkState(blockHosts.length > 0);
        HostBlockAssignment minHost = assignmentMap.get(blockHosts[0]);
        for (String host: blockHosts) {
          if (assignmentMap.containsKey(host)) {
            HostBlockAssignment info = assignmentMap.get(host);
            if (minHost.getAssignedBytes() > info.getAssignedBytes()) {
              minHost = info;
            }
          } else {
            // new host with 0 bytes so far
            minHost = new HostBlockAssignment(host);
            assignmentMap.put(host, minHost);
            break;
          }
        }
        minHost.addBlock(block);
      }

      if (numNodes != Constants.NUM_NODES_ALL) {
        reassignBlocks(numNodes, assignmentMap);
      }
    }

    return Lists.newArrayList(assignmentMap.values());
  }

  @Override
  public void getScanParams(
      int numNodes, List<TScanRange> scanRanges, List<String> hostPorts) {
    Preconditions.checkState(numNodes != Constants.NUM_NODES_ALL_RACKS);

    List<HostBlockAssignment> hostBlockAssignments =
      computeHostBlockAssignments(numNodes);

    if (partitions.size() > 0 && hostPorts != null) {
      hostPorts.clear();
    }

    LOG.info(hostBlockAssignments.toString());

    // Build a TScanRange for each host, with one file split per block range.
    for (HostBlockAssignment blockAssignment: hostBlockAssignments) {
      TScanRange scanRange = new TScanRange(id);
      for (HdfsTable.BlockMetadata metadata: blockAssignment.blockMetadata) {
        BlockLocation blockLocation = metadata.getLocation();
        THdfsFileSplit fileSplit =
            new THdfsFileSplit(metadata.fileName,
                blockLocation.getOffset(),
                blockLocation.getLength(),
                metadata.getPartition().getId());

        scanRange.addToHdfsFileSplits(fileSplit);
      }
      scanRanges.add(scanRange);
      if (hostPorts != null) {
        hostPorts.add(blockAssignment.getHostname());
      }
    }

    if (hostPorts != null) {
      LOG.info(hostPorts.toString());
    }
  }

  private static final Comparator<Entry<String, HostBlockAssignment>>
      MAX_BYTES_COMPARATOR =
      new Comparator<Entry<String, HostBlockAssignment>>() {
    public int compare(
        Entry<String, HostBlockAssignment> entry1,
        Entry<String, HostBlockAssignment> entry2) {
      long assignedBytes1 = entry1.getValue().getAssignedBytes();
      long assignedBytes2 = entry2.getValue().getAssignedBytes();
      if (assignedBytes1 < assignedBytes2) {
        return -1;
      } else if (assignedBytes1 > assignedBytes2) {
        return 1;
      } else {
        return 0;
      }
    }
  };

  private static final Comparator<Entry<String, HostBlockAssignment>>
      MIN_BYTES_COMPARATOR =
      new Comparator<Entry<String, HostBlockAssignment>>() {
    public int compare(
        Entry<String, HostBlockAssignment> entry1,
        Entry<String, HostBlockAssignment> entry2) {
      long assignedBytes1 = entry1.getValue().getAssignedBytes();
      long assignedBytes2 = entry2.getValue().getAssignedBytes();

      if (assignedBytes2 < assignedBytes1) {
        return -1;
      } else if (assignedBytes2 > assignedBytes1) {
        return 1;
      } else {
        return 0;
      }
    }
  };

  /**
   * Pick numPartitions hosts with the most assigned bytes, then assign
   * the other blocks to those, trying to even out total # of bytes assigned to nodes
   * locationMap: map from host to list of (file path, block location)
   * TODO: reassign to optimize block locality (this will conflict with assignment
   * based on data size, so we need to figure out what a good compromise is;
   * this is probably a fruitful area for further experimentation)
   */
  private void reassignBlocks(
      int numPartitions, Map<String, HostBlockAssignment> locationMap) {
    if (locationMap.isEmpty()) {
      return;
    }
    // create a priority queue of map entries, ordered by decreasing number of
    // assigned bytes
    PriorityQueue<Map.Entry<String, HostBlockAssignment>> maxBytesQueue =
        new PriorityQueue<Map.Entry<String, HostBlockAssignment>>(
          locationMap.size(), MAX_BYTES_COMPARATOR);
    for (Map.Entry<String, HostBlockAssignment> entry: locationMap.entrySet()) {
      maxBytesQueue.add(entry);
    }

    // pull out the top 'numPartitions' elements and insert them into a queue
    // that orders by increasing number of assigned bytes
    PriorityQueue<Map.Entry<String, HostBlockAssignment>> minBytesQueue =
        new PriorityQueue<Map.Entry<String, HostBlockAssignment>>(
          locationMap.size(), MIN_BYTES_COMPARATOR);

    for (int i = 0; i < numPartitions; ++i) {
      Map.Entry<String, HostBlockAssignment> entry = maxBytesQueue.poll();
      if (entry == null) {
        break;
      }
      minBytesQueue.add(entry);
    }

    // assign the remaining hosts' blocks
    // TODO: spread these round-robin
    while (!maxBytesQueue.isEmpty()) {
      Map.Entry<String, HostBlockAssignment> source = maxBytesQueue.poll();
      Map.Entry<String, HostBlockAssignment> dest = minBytesQueue.poll();
      dest.getValue().add(source.getValue());
      minBytesQueue.add(dest);
    }

    // re-create locationMap from minBytesQueue
    locationMap.clear();
    while (true) {
      Map.Entry<String, HostBlockAssignment> entry = minBytesQueue.poll();
      if (entry == null) {
        break;
      }
      locationMap.put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  protected String getExplainString(String prefix, ExplainPlanLevel detailLevel) {
    StringBuilder output = new StringBuilder();
    output.append(prefix + "SCAN HDFS table=" + desc.getTable().getFullName());
    output.append(" (" + id + ")");
    if (compactData) {
      output.append(" compact\n");
    } else {
      output.append("\n");
    }
    if (!conjuncts.isEmpty()) {
      output.append(prefix + "  PREDICATES: " + getExplainString(conjuncts) + "\n");
    }
    output.append(super.getExplainString(prefix + "  ", detailLevel));
    return output.toString();
  }
}
