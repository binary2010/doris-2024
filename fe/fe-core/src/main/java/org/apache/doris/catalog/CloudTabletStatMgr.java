// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.catalog;

import org.apache.doris.catalog.MaterializedIndex.IndexExtState;
import org.apache.doris.cloud.proto.Cloud.GetTabletStatsRequest;
import org.apache.doris.cloud.proto.Cloud.GetTabletStatsResponse;
import org.apache.doris.cloud.proto.Cloud.MetaServiceCode;
import org.apache.doris.cloud.proto.Cloud.TabletIndexPB;
import org.apache.doris.cloud.proto.Cloud.TabletStatsPB;
import org.apache.doris.cloud.rpc.MetaServiceProxy;
import org.apache.doris.common.Config;
import org.apache.doris.common.Pair;
import org.apache.doris.common.util.MasterDaemon;
import org.apache.doris.metric.MetricRepo;
import org.apache.doris.rpc.RpcException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/*
 * CloudTabletStatMgr is for collecting tablet(replica) statistics from backends.
 * Each FE will collect by itself.
 */
public class CloudTabletStatMgr extends MasterDaemon {
    private static final Logger LOG = LogManager.getLogger(CloudTabletStatMgr.class);

    // <(dbId, tableId) -> OlapTable.Statistics>
    private volatile Map<Pair<Long, Long>, OlapTable.Statistics> cloudTableStatsMap = new HashMap<>();

    private static final ExecutorService GET_TABLET_STATS_THREAD_POOL = Executors.newFixedThreadPool(
            Config.max_get_tablet_stat_task_threads_num);

    public CloudTabletStatMgr() {
        super("cloud tablet stat mgr", Config.tablet_stat_update_interval_second * 1000);
    }

    @Override
    protected void runAfterCatalogReady() {
        LOG.info("cloud tablet stat begin");
        long start = System.currentTimeMillis();

        List<GetTabletStatsRequest> reqList = new ArrayList<GetTabletStatsRequest>();
        GetTabletStatsRequest.Builder builder = GetTabletStatsRequest.newBuilder();
        List<Long> dbIds = Env.getCurrentInternalCatalog().getDbIds();
        for (Long dbId : dbIds) {
            Database db = Env.getCurrentInternalCatalog().getDbNullable(dbId);
            if (db == null) {
                continue;
            }

            List<Table> tableList = db.getTables();
            for (Table table : tableList) {
                if (!table.isManagedTable()) {
                    continue;
                }

                table.readLock();
                try {
                    OlapTable tbl = (OlapTable) table;
                    for (Partition partition : tbl.getAllPartitions()) {
                        for (MaterializedIndex index : partition.getMaterializedIndices(IndexExtState.VISIBLE)) {
                            for (Long tabletId : index.getTabletIdsInOrder()) {
                                Tablet tablet = index.getTablet(tabletId);
                                TabletIndexPB.Builder tabletBuilder = TabletIndexPB.newBuilder();
                                tabletBuilder.setDbId(dbId);
                                tabletBuilder.setTableId(table.getId());
                                tabletBuilder.setIndexId(index.getId());
                                tabletBuilder.setPartitionId(partition.getId());
                                tabletBuilder.setTabletId(tablet.getId());
                                builder.addTabletIdx(tabletBuilder);

                                if (builder.getTabletIdxCount() >= Config.get_tablet_stat_batch_size) {
                                    reqList.add(builder.build());
                                    builder = GetTabletStatsRequest.newBuilder();
                                }
                            }
                        }
                    } // partitions
                } finally {
                    table.readUnlock();
                }
            } // tables
        } // end for dbs

        if (builder.getTabletIdxCount() > 0) {
            reqList.add(builder.build());
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (GetTabletStatsRequest req : reqList) {
            futures.add(GET_TABLET_STATS_THREAD_POOL.submit(() -> {
                GetTabletStatsResponse resp = GetTabletStatsResponse.newBuilder().build();
                try {
                    resp = getTabletStats(req);
                } catch (RpcException e) {
                    LOG.warn("get tablet stats exception:", e);
                }
                if (resp.getStatus().getCode() != MetaServiceCode.OK) {
                    LOG.warn("get tablet stats return failed.");
                }
                if (LOG.isDebugEnabled()) {
                    int i = 0;
                    for (TabletIndexPB idx : req.getTabletIdxList()) {
                        LOG.debug("db_id: {} table_id: {} index_id: {} tablet_id: {} size: {}",
                                idx.getDbId(), idx.getTableId(), idx.getIndexId(),
                                idx.getTabletId(), resp.getTabletStats(i++).getDataSize());
                    }
                }
                updateTabletStat(resp);
                return null;
            }));
        }

        try {
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            LOG.error("Error waiting for get tablet stats tasks to complete", e);
        }

        LOG.info("finished to get tablet stat of all backends. cost: {} ms",
                (System.currentTimeMillis() - start));

        // after update replica in all backends, update index row num
        start = System.currentTimeMillis();
        Pair<String, Long> maxTabletSize = Pair.of(/* tablet id= */null, /* byte size= */0L);
        Pair<String, Long> maxPartitionSize = Pair.of(/* partition id= */null, /* byte size= */0L);
        Pair<String, Long> maxTableSize = Pair.of(/* table id= */null, /* byte size= */0L);
        Pair<String, Long> minTabletSize = Pair.of(/* tablet id= */null, /* byte size= */Long.MAX_VALUE);
        Pair<String, Long> minPartitionSize = Pair.of(/* partition id= */null, /* byte size= */Long.MAX_VALUE);
        Pair<String, Long> minTableSize = Pair.of(/* tablet id= */null, /* byte size= */Long.MAX_VALUE);
        Long totalTableSize = 0L;
        Long tabletCount = 0L;
        Long partitionCount = 0L;
        Long tableCount = 0L;
        Map<Pair<Long, Long>, OlapTable.Statistics> newCloudTableStatsMap = new HashMap<>();
        for (Long dbId : dbIds) {
            Database db = Env.getCurrentInternalCatalog().getDbNullable(dbId);
            if (db == null) {
                continue;
            }
            List<Table> tableList = db.getTables();
            for (Table table : tableList) {
                if (!table.isManagedTable()) {
                    continue;
                }
                tableCount++;
                OlapTable olapTable = (OlapTable) table;

                Long tableDataSize = 0L;
                Long tableTotalReplicaDataSize = 0L;
                Long tableTotalLocalIndexSize = 0L;
                Long tableTotalLocalSegmentSize = 0L;

                Long tableReplicaCount = 0L;

                Long tableRowCount = 0L;
                Long tableRowsetCount = 0L;
                Long tableSegmentCount = 0L;

                if (!table.readLockIfExist()) {
                    continue;
                }
                try {
                    List<Partition> allPartitions = olapTable.getAllPartitions();
                    partitionCount += allPartitions.size();
                    for (Partition partition : allPartitions) {
                        Long partitionDataSize = 0L;
                        for (MaterializedIndex index : partition.getMaterializedIndices(IndexExtState.VISIBLE)) {
                            long indexRowCount = 0L;
                            List<Tablet> tablets = index.getTablets();
                            tabletCount += tablets.size();
                            for (Tablet tablet : tablets) {
                                long tabletDataSize = 0L;

                                long tabletRowsetCount = 0L;
                                long tabletSegmentCount = 0L;
                                long tabletRowCount = 0L;
                                long tabletIndexSize = 0L;
                                long tabletSegmentSize = 0L;

                                for (Replica replica : tablet.getReplicas()) {
                                    if (replica.getDataSize() > tabletDataSize) {
                                        tabletDataSize = replica.getDataSize();
                                        tableTotalReplicaDataSize += replica.getDataSize();
                                    }

                                    if (replica.getRowCount() > tabletRowCount) {
                                        tabletRowCount = replica.getRowCount();
                                    }

                                    if (replica.getRowsetCount() > tabletRowsetCount) {
                                        tabletRowsetCount = replica.getRowsetCount();
                                    }

                                    if (replica.getSegmentCount() > tabletSegmentCount) {
                                        tabletSegmentCount = replica.getSegmentCount();
                                    }

                                    if (replica.getLocalInvertedIndexSize() > tabletIndexSize) {
                                        tabletIndexSize = replica.getLocalInvertedIndexSize();
                                    }
                                    if (replica.getLocalSegmentSize() > tabletSegmentSize) {
                                        tabletSegmentSize = replica.getLocalSegmentSize();
                                    }

                                    tableReplicaCount++;
                                }

                                tableDataSize += tabletDataSize;
                                partitionDataSize += tabletDataSize;
                                if (maxTabletSize.second <= tabletDataSize) {
                                    maxTabletSize = Pair.of("" + tablet.getId(), tabletDataSize);
                                }
                                if (minTabletSize.second >= tabletDataSize) {
                                    minTabletSize = Pair.of("" + tablet.getId(), tabletDataSize);
                                }

                                tableRowCount += tabletRowCount;
                                indexRowCount += tabletRowCount;

                                tableRowsetCount += tabletRowsetCount;
                                tableSegmentCount += tabletSegmentCount;
                                tableTotalLocalIndexSize += tabletIndexSize;
                                tableTotalLocalSegmentSize += tabletSegmentSize;
                            } // end for tablets
                            index.setRowCountReported(true);
                            index.setRowCount(indexRowCount);
                        } // end for indices
                        if (maxPartitionSize.second <= partitionDataSize) {
                            maxPartitionSize = Pair.of("" + partition.getId(), partitionDataSize);
                        }
                        if (minPartitionSize.second >= partitionDataSize) {
                            minPartitionSize = Pair.of("" + partition.getId(), partitionDataSize);
                        }
                    } // end for partitions
                    if (maxTableSize.second <= tableDataSize) {
                        maxTableSize = Pair.of("" + table.getId(), tableDataSize);
                    }
                    if (minTableSize.second >= tableDataSize) {
                        minTableSize = Pair.of("" + table.getId(), tableDataSize);
                    }

                    //  this is only one thread to update table statistics, readLock is enough
                    olapTable.setStatistics(new OlapTable.Statistics(db.getName(),
                            table.getName(), tableDataSize, tableTotalReplicaDataSize, 0L,
                            tableReplicaCount, tableRowCount, tableRowsetCount, tableSegmentCount,
                            tableTotalLocalIndexSize, tableTotalLocalSegmentSize, 0L, 0L));
                    LOG.debug("finished to set row num for table: {} in database: {}",
                             table.getName(), db.getFullName());
                } finally {
                    table.readUnlock();
                }
                totalTableSize += tableDataSize;
                newCloudTableStatsMap.put(Pair.of(dbId, table.getId()), new OlapTable.Statistics(db.getName(),
                        table.getName(), tableDataSize, tableTotalReplicaDataSize, 0L,
                        tableReplicaCount, tableRowCount, tableRowsetCount, tableSegmentCount, 0L, 0L, 0L, 0L));
            }
        }
        this.cloudTableStatsMap = newCloudTableStatsMap;

        MetricRepo.GAUGE_MAX_TABLE_SIZE_BYTES.setValue(maxTableSize.second);
        MetricRepo.GAUGE_MAX_PARTITION_SIZE_BYTES.setValue(maxPartitionSize.second);
        MetricRepo.GAUGE_MAX_TABLET_SIZE_BYTES.setValue(maxTabletSize.second);
        long minTableSizeTmp = minTableSize.second == Long.MAX_VALUE ? 0 : minTableSize.second;
        MetricRepo.GAUGE_MIN_TABLE_SIZE_BYTES.setValue(minTableSizeTmp);
        long minPartitionSizeTmp = minPartitionSize.second == Long.MAX_VALUE ? 0 : minPartitionSize.second;
        MetricRepo.GAUGE_MIN_PARTITION_SIZE_BYTES.setValue(minPartitionSizeTmp);
        long minTabletSizeTmp = minTabletSize.second == Long.MAX_VALUE ? 0 : minTabletSize.second;
        MetricRepo.GAUGE_MIN_TABLET_SIZE_BYTES.setValue(minTabletSizeTmp);
        long avgTableSize = totalTableSize / Math.max(1, tableCount); // avoid ArithmeticException: / by zero
        MetricRepo.GAUGE_AVG_TABLE_SIZE_BYTES.setValue(avgTableSize);
        long avgPartitionSize = totalTableSize / Math.max(1, partitionCount); // avoid ArithmeticException: / by zero
        MetricRepo.GAUGE_AVG_PARTITION_SIZE_BYTES.setValue(avgPartitionSize);
        long avgTabletSize = totalTableSize / Math.max(1, tabletCount); // avoid ArithmeticException: / by zero
        MetricRepo.GAUGE_AVG_TABLET_SIZE_BYTES.setValue(avgTabletSize);
        LOG.info("finished to update index row num of all databases. cost: {} ms",
                (System.currentTimeMillis() - start));
        LOG.info("Olap table num=" + tableCount + ", partition num=" + partitionCount + ", tablet num=" + tabletCount
                + ", max tablet byte size=" + maxTabletSize.second + "(tablet_id=" + maxTableSize.first + ")"
                + ", min tablet byte size=" + minTabletSizeTmp + "(tablet_id=" + minTabletSize.first + ")"
                + ", avg tablet byte size=" + avgTabletSize
                + ", max partition byte size=" + maxPartitionSize.second + "(partition_id=" + maxPartitionSize.first
                + ")"
                + ", min partition byte size=" + minPartitionSizeTmp + "(partition_id=" + minPartitionSize.first + ")"
                + ", avg partition byte size=" + avgPartitionSize
                + ", max table byte size=" + maxTableSize.second + "(table_id=" + maxTableSize.first + ")"
                + ", min table byte size=" + minTableSizeTmp + "(table_id=" + minTableSize.first + ")"
                + ", avg table byte size=" + avgTableSize);
    }

    private void updateTabletStat(GetTabletStatsResponse response) {
        TabletInvertedIndex invertedIndex = Env.getCurrentInvertedIndex();
        for (TabletStatsPB stat : response.getTabletStatsList()) {
            if (invertedIndex.getTabletMeta(stat.getIdx().getTabletId()) != null) {
                List<Replica> replicas = invertedIndex.getReplicasByTabletId(stat.getIdx().getTabletId());
                if (replicas == null || replicas.isEmpty() || replicas.get(0) == null) {
                    continue;
                }
                Replica replica = replicas.get(0);
                replica.setDataSize(stat.getDataSize());
                replica.setRowsetCount(stat.getNumRowsets());
                replica.setSegmentCount(stat.getNumSegments());
                replica.setRowCount(stat.getNumRows());
                replica.setLocalInvertedIndexSize(stat.getIndexSize());
                replica.setLocalSegmentSize(stat.getSegmentSize());
            }
        }
    }

    private GetTabletStatsResponse getTabletStats(GetTabletStatsRequest request)
            throws RpcException {
        GetTabletStatsResponse response;
        try {
            response = MetaServiceProxy.getInstance().getTabletStats(request);
        } catch (RpcException e) {
            LOG.info("get tablet stat get exception:", e);
            throw e;
        }
        return response;
    }

    public Map<Pair<Long, Long>, OlapTable.Statistics> getCloudTableStatsMap() {
        return this.cloudTableStatsMap;
    }
}
