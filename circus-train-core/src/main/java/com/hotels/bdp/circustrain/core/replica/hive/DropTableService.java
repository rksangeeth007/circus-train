/**
 * Copyright (C) 2016-2020 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hotels.bdp.circustrain.core.replica.hive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.collections.map.CaseInsensitiveMap;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hotels.bdp.circustrain.api.data.DataManipulationClient;
import com.hotels.bdp.circustrain.core.data.DefaultDataManipulationClientFactoryManager;
import com.hotels.hcommon.hive.metastore.client.api.CloseableMetaStoreClient;

public class DropTableService {

  private static final Logger LOG = LoggerFactory.getLogger(DropTableService.class);
  private static final String EXTERNAL_KEY = "EXTERNAL";
  private static final String IS_EXTERNAL = "TRUE";

  /**
   * Removes all parameters from a table before dropping the table.
   */
  public void removeTableParamsAndDrop(CloseableMetaStoreClient client, String databaseName, String tableName)
    throws TException {
    Table table = getTable(client, databaseName, tableName);
    if (table != null) {
      dropTable(client, table, databaseName, tableName);
    }
  }

  /**
   * Drops the table and its associated data. If the table is unpartitioned the table location is used. If the table is
   * partitioned then the data will be dropped from each partition location.
   */
  public void dropTableAndData(
      CloseableMetaStoreClient client,
      String databaseName,
      String tableName,
      DefaultDataManipulationClientFactoryManager dataManipulationClientFactoryManager)
    throws TException {
    LOG.debug("Dropping table {}.{} and its data.", databaseName, tableName);
    Table table = getTable(client, databaseName, tableName);
    if (table != null) {
      String replicaLocation = table.getSd().getLocation();
      if (table.getPartitionKeysSize() == 0) {
        deleteData(dataManipulationClientFactoryManager, replicaLocation);
      } else {
        List<String> partitionLocations = getPartitionLocations(client, databaseName, tableName);
        if (partitionLocations.size() > 0) {
          deletePartitionData(dataManipulationClientFactoryManager, replicaLocation, partitionLocations);
        } else {
          LOG.info("No partitions to delete.");
        }
      }
      dropTable(client, table, databaseName, tableName);
    }
  }

  private Table getTable(CloseableMetaStoreClient client, String databaseName, String tableName) throws TException {
    Table table = null;
    try {
      table = client.getTable(databaseName, tableName);
    } catch (NoSuchObjectException e) {
      LOG.info("No replica table '" + databaseName + "." + tableName + "' found. Nothing to delete.");
    }
    return table;
  }

  private void dropTable(CloseableMetaStoreClient client, Table table, String databaseName, String tableName)
    throws TException {
    Map<String, String> tableParameters = table.getParameters();
    if (tableParameters != null && !tableParameters.isEmpty()) {
      if (isExternal(tableParameters)) {
        table.setParameters(Collections.singletonMap(EXTERNAL_KEY, IS_EXTERNAL));
      } else {
        table.setParameters(Collections.emptyMap());
      }
      client.alter_table(databaseName, tableName, table);
    }
    LOG.info("Dropping table '{}.{}'.", databaseName, tableName);
    client.dropTable(databaseName, tableName, false, true);
  }

  private void deleteData(
      DefaultDataManipulationClientFactoryManager dataManipulationClientFactoryManager,
      String replicaDataLocation) {
    try {
      LOG.info("Dropping table data from location: {}.", replicaDataLocation);
      DataManipulationClient client = dataManipulationClientFactoryManager.getClientForPath(replicaDataLocation);
      boolean dataDeleted = client.delete(replicaDataLocation);
      LOG.info("Data deleted: {}.", dataDeleted);
    } catch (IOException e) {
      LOG.info("Could not drop replica table data at location:{}.", replicaDataLocation);
    }
  }

  private void deletePartitionData(
      DefaultDataManipulationClientFactoryManager dataManipulationClientFactoryManager,
      String replicaTableLocation,
      List<String> replicaPartitionLocations) {
    try {
      LOG.info("Dropping partition data from base location: {}.", replicaTableLocation);
      DataManipulationClient client = dataManipulationClientFactoryManager.getClientForPath(replicaTableLocation);
      for (String location : replicaPartitionLocations) {
        boolean deleted = client.delete(location);
        LOG.debug("Attempted to delete data from location: {}. Successful deletion = {}.", location, deleted);
      }
    } catch (IOException e) {
      LOG.info("Could not drop replica partition data at location:{}.", replicaTableLocation);
    } catch (UnsupportedOperationException e) {
      LOG.info(e.getMessage());
    }
  }

  private boolean isExternal(Map<String, String> tableParameters) {
    CaseInsensitiveMap caseInsensitiveParams = new CaseInsensitiveMap(tableParameters);
    return IS_EXTERNAL.equalsIgnoreCase((String) caseInsensitiveParams.get(EXTERNAL_KEY));
  }

  private List<String> getPartitionLocations(CloseableMetaStoreClient client, String databaseName, String tableName) {
    List<String> locations = new ArrayList<>();
    try {
      locations = client
          .listPartitions(databaseName, tableName, (short) -1)
          .stream()
          .map(partition -> partition.getSd().getLocation())
          .collect(Collectors.toList());
    } catch (TException e) {
      LOG.info("Could not list partitions for {}.{}.", databaseName, tableName);
    }
    return locations;
  }
}
