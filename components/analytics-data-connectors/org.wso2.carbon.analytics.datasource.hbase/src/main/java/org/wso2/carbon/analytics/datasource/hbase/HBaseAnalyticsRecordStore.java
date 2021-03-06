/*
*  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.analytics.datasource.hbase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.wso2.carbon.analytics.datasource.commons.AnalyticsIterator;
import org.wso2.carbon.analytics.datasource.commons.Record;
import org.wso2.carbon.analytics.datasource.commons.RecordGroup;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsException;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsTableNotAvailableException;
import org.wso2.carbon.analytics.datasource.core.rs.AnalyticsRecordStore;
import org.wso2.carbon.analytics.datasource.core.util.GenericUtils;
import org.wso2.carbon.analytics.datasource.hbase.rg.HBaseIDRecordGroup;
import org.wso2.carbon.analytics.datasource.hbase.rg.HBaseRegionSplitRecordGroup;
import org.wso2.carbon.analytics.datasource.hbase.rg.HBaseTimestampRecordGroup;
import org.wso2.carbon.analytics.datasource.hbase.util.HBaseAnalyticsDSConstants;
import org.wso2.carbon.analytics.datasource.hbase.util.HBaseUtils;
import org.wso2.carbon.ndatasource.common.DataSourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apache HBase implementation of {@link org.wso2.carbon.analytics.datasource.core.rs.AnalyticsRecordStore}
 */
public class HBaseAnalyticsRecordStore implements AnalyticsRecordStore {

    private Connection conn;

    private HBaseAnalyticsConfigurationEntry queryConfig;

    private static final Log log = LogFactory.getLog(HBaseAnalyticsRecordStore.class);

    public HBaseAnalyticsRecordStore(Connection conn, HBaseAnalyticsConfigurationEntry entry) throws IOException, AnalyticsException {
        this.conn = conn;
        this.queryConfig = entry;
    }

    public HBaseAnalyticsRecordStore() {
        this.conn = null;
        this.queryConfig = null;
    }

    @Override
    public void init(Map<String, String> properties) throws AnalyticsException {
        this.queryConfig = HBaseUtils.lookupConfiguration();
        String dsName = properties.get(HBaseAnalyticsDSConstants.DATASOURCE_NAME);
        if (dsName == null) {
            throw new AnalyticsException("The property '" + HBaseAnalyticsDSConstants.DATASOURCE_NAME +
                    "' is required");
        }
        try {
            Configuration config = (Configuration) GenericUtils.loadGlobalDataSource(dsName);
            if (config == null) {
                throw new AnalyticsException("Failed to initialize HBase configuration based on data source" +
                        " definition");
            }
            this.conn = ConnectionFactory.createConnection(config);
        } catch (DataSourceException | IOException e) {
            throw new AnalyticsException("Error establishing connection to HBase instance based on data source" +
                    " definition: " + e.getMessage(), e);
        }
        if (this.conn == null) {
            throw new AnalyticsException("Error establishing connection to HBase instance : HBase Client initialization " +
                    "failed");
        }
        log.debug("Initialized connection to HBase instance successfully.");
    }

    @Override
    public void createTable(int tenantId, String tableName) throws AnalyticsException {
        /* If the table we're proposing to create already exists, return in silence */
        if (this.tableExists(tenantId, tableName)) {
            log.debug("Creation of table " + tableName + " for tenant " + tenantId +
                    " could not be carried out since said table already exists.");
            return;
        }

        HTableDescriptor dataDescriptor = new HTableDescriptor(TableName.valueOf(
                HBaseUtils.generateTableName(tenantId, tableName, HBaseAnalyticsDSConstants.TableType.DATA)));
        /* creating table with standard column family "carbon-analytics-data" for storing the actual row data
         * in one column and the record timestamp in another */
        dataDescriptor.addFamily(new HColumnDescriptor(HBaseAnalyticsDSConstants.ANALYTICS_DATA_COLUMN_FAMILY_NAME)
                .setMaxVersions(1));

        HTableDescriptor indexDescriptor = new HTableDescriptor(TableName.valueOf(
                HBaseUtils.generateTableName(tenantId, tableName, HBaseAnalyticsDSConstants.TableType.INDEX)));
        /* creating table with standard column family "carbon-analytics-index" for storing timestamp -> ID index*/
        indexDescriptor.addFamily(new HColumnDescriptor(HBaseAnalyticsDSConstants.ANALYTICS_INDEX_COLUMN_FAMILY_NAME)
                .setMaxVersions(1));

        /* Table creation should fail if index cannot be created, so attempting to create index table first. */
        Admin admin = null;
        try {
            admin = this.conn.getAdmin();
            admin.createTable(indexDescriptor);
            admin.createTable(dataDescriptor);
            log.debug("Table " + tableName + " for tenant " + tenantId + " created");
        } catch (IOException e) {
            throw new AnalyticsException("Error creating table " + tableName + " for tenant " + tenantId + " : " + e.getMessage(), e);
        } finally {
            GenericUtils.closeQuietly(admin);
        }
    }

    public boolean tableExists(int tenantId, String tableName) throws AnalyticsException {
        boolean isExist;
        Admin admin = null;
        try {
            admin = this.conn.getAdmin();
            isExist = admin.tableExists(TableName.valueOf(
                    HBaseUtils.generateTableName(tenantId, tableName, HBaseAnalyticsDSConstants.TableType.DATA)));
        } catch (IOException e) {
            throw new AnalyticsException("Error checking existence of table " + tableName + " for tenant " + tenantId + " : " + e.getMessage(), e);
        } finally {
            GenericUtils.closeQuietly(admin);
        }
        return isExist;
    }

    @Override
    public void deleteTable(int tenantId, String tableName) throws AnalyticsException {
        /* If the table we're proposing to create does not exist, return in silence */
        if (!(this.tableExists(tenantId, tableName))) {
            if (log.isDebugEnabled()) {
                log.debug("Deletion of table " + tableName + " for tenant " + tenantId +
                        " could not be carried out since said table did not exist.");
            }
            return;
        }
        Admin admin = null;
        TableName dataTable = TableName.valueOf(HBaseUtils.generateTableName(tenantId, tableName,
                HBaseAnalyticsDSConstants.TableType.DATA));
        TableName indexTable = TableName.valueOf(HBaseUtils.generateTableName(tenantId, tableName,
                HBaseAnalyticsDSConstants.TableType.INDEX));
        try {
            admin = this.conn.getAdmin();
            /* delete the data table first */
            admin.disableTable(dataTable);
            admin.deleteTable(dataTable);
            /* finally, delete the index table */
            admin.disableTable(indexTable);
            admin.deleteTable(indexTable);
            log.debug("Table " + tableName + " for tenant " + tenantId + " deleted");
        } catch (IOException e) {
            throw new AnalyticsException("Error deleting table " + tableName, e);
        } finally {
            GenericUtils.closeQuietly(admin);
        }
    }

    @Override
    public boolean isPaginationSupported() {
        /* Pagination is not supported for this implementation. */
        return false;
    }

    @Override
    public boolean isRecordCountSupported() {
        /* Determination of record counts is not supported for this implementation. */
        return false;
    }

    @Override
    public long getRecordCount(int tenantId, String tableName, long timeFrom, long timeTo) throws AnalyticsException {
        return -1;
    }

    @Override
    public void put(List<Record> records) throws AnalyticsException, AnalyticsTableNotAvailableException {
        int tenantId = 0;
        String tableName = null;
        Table table, indexTable;
        if (records.isEmpty()) {
            return;
        }
        Map<String, List<Record>> recordBatches = this.generateRecordBatches(records);
        try {
            /* Check incoming records to eliminate any residual entries on the index table */
            this.policeIncomingRecords(recordBatches);
            /* iterating over record batches */
            for (Map.Entry<String, List<Record>> entry : recordBatches.entrySet()) {
                tenantId = HBaseUtils.inferTenantId(entry.getKey());
                tableName = HBaseUtils.inferTableName(entry.getKey());
                table = this.conn.getTable(TableName.valueOf(HBaseUtils.generateTableName(tenantId, tableName,
                        HBaseAnalyticsDSConstants.TableType.DATA)));
                indexTable = this.conn.getTable(TableName.valueOf(HBaseUtils.generateTableName(tenantId, tableName,
                        HBaseAnalyticsDSConstants.TableType.INDEX)));
                /* Populating batched Put instances from records in a single batch */
                List<List<Put>> allPuts = this.populatePuts(recordBatches.get(entry.getKey()));
                /* Using Table.put(List<Put>) method to minimise network calls per table */
                try {
                    indexTable.put(allPuts.get(0));
                    table.put(allPuts.get(1));
                    log.debug("Processed " + records.size() + " PUT operations for " + tableName + " for tenant " + tenantId);
                } finally {
                    table.close();
                    indexTable.close();
                }
            }
        } catch (AnalyticsTableNotAvailableException e) {
            throw e;
        } catch (IOException e) {
            if ((e instanceof TableNotFoundException) || ((e instanceof RetriesExhaustedException) && e.getMessage().contains("was not found"))) {
                throw new AnalyticsTableNotAvailableException(tenantId, tableName);
            }
            throw new AnalyticsException("Error adding new records: " + e.getMessage(), e);
        }
    }

    private List<List<Put>> populatePuts(List<Record> records) throws AnalyticsException {
        byte[] data;
        List<Put> puts = new ArrayList<>();
        List<Put> indexPuts = new ArrayList<>();
        for (Record record : records) {
            String recordId = record.getId();
            long timestamp = record.getTimestamp();
            if (timestamp < 0L) {
                throw new AnalyticsException("HBase Analytics Record store does not support negative UNIX timestamps");
            }
            Map<String, Object> columns = record.getValues();
            if ((columns == null) || columns.isEmpty()) {
                data = new byte[]{};
            } else {
                data = GenericUtils.encodeRecordValues(columns);
            }
            Put put = new Put(recordId.getBytes(StandardCharsets.UTF_8));
            put.addColumn(HBaseAnalyticsDSConstants.ANALYTICS_DATA_COLUMN_FAMILY_NAME,
                    HBaseAnalyticsDSConstants.ANALYTICS_ROWDATA_QUALIFIER_NAME, data);
            put.addColumn(HBaseAnalyticsDSConstants.ANALYTICS_DATA_COLUMN_FAMILY_NAME,
                    HBaseAnalyticsDSConstants.ANALYTICS_TS_QUALIFIER_NAME, Bytes.toBytes(timestamp));
            indexPuts.add(this.putIndexData(record));
            puts.add(put);
        }
        List<List<Put>> output = new ArrayList<>();
        output.add(indexPuts);
        output.add(puts);
        return output;
    }

    private Put putIndexData(Record record) {
        Put indexPut = new Put(HBaseUtils.encodeLong(record.getTimestamp()));
        /* Setting the column qualifier the same as the column value to enable multiple columns per row with
        * unique qualifiers, since we will anyway not use the qualifier during index read */
        indexPut.addColumn(HBaseAnalyticsDSConstants.ANALYTICS_INDEX_COLUMN_FAMILY_NAME, record.getId().getBytes(StandardCharsets.UTF_8),
                record.getId().getBytes(StandardCharsets.UTF_8));
        return indexPut;
    }

    private Map<String, List<Record>> generateRecordBatches(List<Record> records) {
        Map<String, List<Record>> recordBatches = new HashMap<>();
        List<Record> recordBatch;
        for (Record record : records) {
            recordBatch = recordBatches.get(this.inferRecordIdentity(record));
            if (recordBatch == null) {
                recordBatch = new ArrayList<>();
                recordBatches.put(this.inferRecordIdentity(record), recordBatch);
            }
            recordBatch.add(record);
        }
        return recordBatches;
    }

    private String inferRecordIdentity(Record record) {
        return HBaseUtils.generateGenericTableName(record.getTenantId(), record.getTableName());
    }

    private void policeIncomingRecords(Map<String, List<Record>> recordBatches) throws AnalyticsException {
        int tenantId;
        String tableName;
        Table recordTable = null;
        for (Map.Entry<String, List<Record>> entry : recordBatches.entrySet()) {
            tenantId = HBaseUtils.inferTenantId(entry.getKey());
            tableName = HBaseUtils.inferTableName(entry.getKey());
            if (!this.tableExists(tenantId, tableName)) {
                throw new AnalyticsTableNotAvailableException(tenantId, tableName);
            }
            try {
                /* Surveil */
                recordTable = this.conn.getTable(TableName.valueOf(HBaseUtils.generateTableName(tenantId, tableName,
                        HBaseAnalyticsDSConstants.TableType.DATA)));
                List<Get> suspects = new ArrayList<>();
                for (Record record : recordBatches.get(entry.getKey())) {
                    Get get = new Get(Bytes.toBytes(record.getId()));
                    get.addColumn(HBaseAnalyticsDSConstants.ANALYTICS_DATA_COLUMN_FAMILY_NAME,
                            HBaseAnalyticsDSConstants.ANALYTICS_TS_QUALIFIER_NAME);
                    suspects.add(get);
                }
                /* Arrest */
                List<byte[]> criminals = new ArrayList<>();
                Result[] results = recordTable.get(suspects);
                for (Result currentResult : results) {
                    if (currentResult.containsColumn(HBaseAnalyticsDSConstants.ANALYTICS_DATA_COLUMN_FAMILY_NAME,
                            HBaseAnalyticsDSConstants.ANALYTICS_TS_QUALIFIER_NAME)) {
                        Cell dataCell = currentResult.getColumnLatestCell(HBaseAnalyticsDSConstants.ANALYTICS_DATA_COLUMN_FAMILY_NAME,
                                HBaseAnalyticsDSConstants.ANALYTICS_TS_QUALIFIER_NAME);
                        byte[] data = CellUtil.cloneValue(dataCell);
                        if (data.length > 0) {
                            criminals.add(data);
                        }
                    }
                }
                if (criminals.size() > 0) {
                    /* Produce before courts */
                    if (log.isDebugEnabled()) {
                        log.debug("Found " + criminals.size() + " obsolete entries on the index table for " + tableName
                                + " for tenant" + tenantId);
                    }
                    /* Sentence */
                    this.deleteIndexEntries(tenantId, tableName, criminals);
                }
            } catch (IOException e) {
                throw new AnalyticsException("Error while pruning obsolete entries from table " + tableName
                        + " for tenant: " + tenantId + " : " + e.getMessage(), e);
            } finally {
                GenericUtils.closeQuietly(recordTable);
            }
        }
    }

    @Override
    public RecordGroup[] get(int tenantId, String tableName, int numPartitionsHint, List<String> columns, long timeFrom,
                             long timeTo, int recordsFrom, int recordsCount) throws AnalyticsException,
            AnalyticsTableNotAvailableException {
        if (recordsFrom > 0) {
            throw new HBaseUnsupportedOperationException("Pagination is not supported for HBase Analytics Record Store Implementation");
        }
        if (!this.tableExists(tenantId, tableName)) {
            throw new AnalyticsTableNotAvailableException(tenantId, tableName);
        }
        if ((timeFrom < 0) && (timeTo >= Long.MAX_VALUE - 1)) {
            log.debug("Performing GET on region split contours for table " + tableName + " and tenantID " + tenantId);
            return this.computeRegionSplits(tenantId, tableName, columns, recordsCount);
        } else {
            log.debug("Performing GET through timestamp slices for table " + tableName + " and tenantID " + tenantId);
            return new HBaseTimestampRecordGroup[]{
                    new HBaseTimestampRecordGroup(tenantId, tableName, columns, timeFrom, timeTo, recordsCount)
            };
        }
    }

    @Override
    public RecordGroup[] get(int tenantId, String tableName, int numPartitionsHint, List<String> columns,
                             List<String> ids) throws AnalyticsException, AnalyticsTableNotAvailableException {
        if (!this.tableExists(tenantId, tableName)) {
            throw new AnalyticsTableNotAvailableException(tenantId, tableName);
        }
        log.debug("Performing GET by direct Record ID lookup for table " + tableName + " and tenantID " + tenantId);
        return new HBaseIDRecordGroup[]{
                new HBaseIDRecordGroup(tenantId, tableName, columns, ids)
        };
    }

    @Override
    public AnalyticsIterator<Record> readRecords(RecordGroup recordGroup) throws AnalyticsException, AnalyticsTableNotAvailableException {

        if (recordGroup instanceof HBaseIDRecordGroup) {
            HBaseIDRecordGroup idRecordGroup = (HBaseIDRecordGroup) recordGroup;
            return this.getRecords(idRecordGroup.getTenantId(), idRecordGroup.getTableName(),
                    idRecordGroup.getColumns(), idRecordGroup.getIds());

        } else if (recordGroup instanceof HBaseTimestampRecordGroup) {
            HBaseTimestampRecordGroup tsRecordGroup = (HBaseTimestampRecordGroup) recordGroup;
            return this.getRecords(tsRecordGroup.getTenantId(), tsRecordGroup.getTableName(),
                    tsRecordGroup.getColumns(), tsRecordGroup.getStartTime(), tsRecordGroup.getEndTime(),
                    tsRecordGroup.getRecordsCount());

        } else if (recordGroup instanceof HBaseRegionSplitRecordGroup) {
            HBaseRegionSplitRecordGroup rsRecordGroup = (HBaseRegionSplitRecordGroup) recordGroup;
            return this.getRecords(rsRecordGroup.getTenantId(), rsRecordGroup.getTableName(),
                    rsRecordGroup.getColumns(), rsRecordGroup.getRecordsCount(), rsRecordGroup.getStartRow(), rsRecordGroup.getEndRow());
        } else {
            throw new AnalyticsException("Invalid HBase RecordGroup implementation: " + recordGroup.getClass());
        }
    }

    public AnalyticsIterator<Record> getRecords(int tenantId, String tableName, List<String> columns, List<String> ids)
            throws AnalyticsException, AnalyticsTableNotAvailableException {
        int batchSize = this.queryConfig.getBatchSize();
        return new HBaseRecordIterator(tenantId, tableName, columns, ids, this.conn, batchSize);
    }

    public AnalyticsIterator<Record> getRecords(int tenantId, String tableName, List<String> columns, long startTime,
                                                long endTime, int recordsCount)
            throws AnalyticsException, AnalyticsTableNotAvailableException {
        int batchSize = this.queryConfig.getBatchSize();
        return new HBaseTimestampIterator(tenantId, tableName, columns, startTime, endTime, recordsCount, this.conn, batchSize);
    }

    public AnalyticsIterator<Record> getRecords(int tenantId, String tableName, List<String> columns, int recordsCount, byte[] startRow, byte[] endRow)
            throws AnalyticsException, AnalyticsTableNotAvailableException {
        return new HBaseRegionSplitIterator(tenantId, tableName, columns, recordsCount, this.conn, startRow, endRow);
    }

    private RecordGroup[] computeRegionSplits(int tenantId, String tableName, List<String> columns, int recordsCount) throws AnalyticsException {
        List<RecordGroup> regionalGroups = new ArrayList<>();
        String formattedTableName = HBaseUtils.generateTableName(tenantId, tableName, HBaseAnalyticsDSConstants.TableType.DATA);
        try {
            RegionLocator locator = this.conn.getRegionLocator(TableName.valueOf(formattedTableName));
            final Pair<byte[][], byte[][]> startEndKeys = locator.getStartEndKeys();
            byte[][] startKeys = startEndKeys.getFirst();
            byte[][] endKeys = startEndKeys.getSecond();
            for (int i = 0; i < startKeys.length && i < endKeys.length; i++) {
                RecordGroup regionalGroup = new HBaseRegionSplitRecordGroup(tenantId, tableName, columns, recordsCount,
                        startKeys[i], endKeys[i], locator.getRegionLocation(startKeys[i]).getHostname());
                regionalGroups.add(regionalGroup);
            }
        } catch (IOException e) {
            throw new AnalyticsException("Error computing region splits for table " + tableName + " for tenant " +
                    tenantId + " : " + e.getMessage(), e);
        }
        return regionalGroups.toArray(new RecordGroup[regionalGroups.size()]);
    }

    private List<String> lookupIndex(int tenantId, String tableName, long startTime, long endTime)
            throws AnalyticsException {
        List<String> recordIds = new ArrayList<>();
        String formattedTableName = HBaseUtils.generateTableName(tenantId, tableName, HBaseAnalyticsDSConstants.TableType.INDEX);
        Table indexTable = null;
        Cell[] cells;
        Scan indexScan = new Scan();
        if (startTime >= 0L) {
            indexScan.setStartRow(HBaseUtils.encodeLong(startTime));
        }
        if ((endTime != Long.MAX_VALUE)) {
            indexScan.setStopRow(HBaseUtils.encodeLong(endTime));
        }
        indexScan.addFamily(HBaseAnalyticsDSConstants.ANALYTICS_INDEX_COLUMN_FAMILY_NAME);
        ResultScanner resultScanner = null;
        try {
            indexTable = this.conn.getTable(TableName.valueOf(formattedTableName));
            resultScanner = indexTable.getScanner(indexScan);
            for (Result rowResult : resultScanner) {
                cells = rowResult.rawCells();
                for (Cell cell : cells) {
                    recordIds.add(new String(CellUtil.cloneValue(cell), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new AnalyticsException("Index for table " + tableName + " could not be read : " + e.getMessage(), e);
        } finally {
            GenericUtils.closeQuietly(resultScanner);
            GenericUtils.closeQuietly(indexTable);
        }
        return recordIds;
    }

    @Override
    public void delete(int tenantId, String tableName, long timeFrom, long timeTo) throws AnalyticsException {
        this.delete(tenantId, tableName, this.lookupIndex(tenantId, tableName, timeFrom, timeTo));
    }

    @Override
    public void delete(int tenantId, String tableName, List<String> ids) throws AnalyticsException {
        Table dataTable = null;
        List<Delete> dataDeletes = new ArrayList<>();
        String dataTableName = HBaseUtils.generateTableName(tenantId, tableName, HBaseAnalyticsDSConstants.TableType.DATA);
        List<byte[]> timestamps = this.lookupTimestamps(dataTableName, ids, tenantId, tableName);
        for (String recordId : ids) {
            dataDeletes.add(new Delete(recordId.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            dataTable = this.conn.getTable(TableName.valueOf(dataTableName));
            dataTable.delete(dataDeletes);
            log.debug("Processed deletion of " + dataDeletes.size() + " records from table " + tableName + "for tenant " + tenantId);
            /*//HBase delete propagation delays: WORKAROUND BELOW
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }*/
            this.deleteIndexEntries(tenantId, tableName, timestamps);
        } catch (IOException e) {
            throw new AnalyticsException("Error deleting records from " + tableName + " for tenant " + tenantId + " : "
                    + e.getMessage(), e);
        } finally {
            GenericUtils.closeQuietly(dataTable);
        }
    }

    @Override
    public void destroy() throws AnalyticsException {
        try {
            this.conn.close();
            log.debug("Closed HBase connection transients successfully.");
        } catch (IOException ignore) {
                /* do nothing, the connection is dead anyway */
        }
    }

    private void deleteIndexEntries(int tenantId, String tableName, List<byte[]> timestamps) throws IOException {
        Table indexTable = null;
        List<Delete> indexDeletes = new ArrayList<>();
        String indexTableName = HBaseUtils.generateTableName(tenantId, tableName, HBaseAnalyticsDSConstants.TableType.INDEX);
        for (byte[] timestamp : timestamps) {
            Delete delete = new Delete(timestamp);
            indexDeletes.add(delete);
        }
        try {
            indexTable = this.conn.getTable(TableName.valueOf(indexTableName));
            indexTable.delete(indexDeletes);
        } finally {
            GenericUtils.closeQuietly(indexTable);
        }
    }

    private List<byte[]> lookupTimestamps(String dataTableName, List<String> rowIds, int tenantId, String tableName)
            throws AnalyticsException {
        List<byte[]> timestamps = new ArrayList<>();
        List<Get> gets = new ArrayList<>();
        Table dataTable = null;
        for (String rowId : rowIds) {
            if (!rowId.isEmpty()) {
                gets.add(new Get(rowId.getBytes(StandardCharsets.UTF_8)).addColumn(HBaseAnalyticsDSConstants.ANALYTICS_DATA_COLUMN_FAMILY_NAME,
                        HBaseAnalyticsDSConstants.ANALYTICS_TS_QUALIFIER_NAME));
            }
        }
        try {
            dataTable = this.conn.getTable(TableName.valueOf(dataTableName));
            Result[] results = dataTable.get(gets);
            for (Result res : results) {
                if (res.containsColumn(HBaseAnalyticsDSConstants.ANALYTICS_DATA_COLUMN_FAMILY_NAME,
                        HBaseAnalyticsDSConstants.ANALYTICS_TS_QUALIFIER_NAME)) {
                    Cell dataCell = res.getColumnLatestCell(HBaseAnalyticsDSConstants.ANALYTICS_DATA_COLUMN_FAMILY_NAME,
                            HBaseAnalyticsDSConstants.ANALYTICS_TS_QUALIFIER_NAME);
                    byte[] data = CellUtil.cloneValue(dataCell);
                    if (data.length > 0) {
                        timestamps.add(data);
                    }
                }
            }

        } catch (IOException e) {
            throw new AnalyticsException("The table " + tableName + " for tenant " + tenantId +
                    " could not be initialized for deletion of rows: " + e.getMessage(), e);
        } finally {
            GenericUtils.closeQuietly(dataTable);
        }
        return timestamps;
    }

    public static class HBaseUnsupportedOperationException extends AnalyticsException {

        private static final long serialVersionUID = -380641886204128313L;

        public HBaseUnsupportedOperationException(String s) {
            super(s);
        }
    }

}