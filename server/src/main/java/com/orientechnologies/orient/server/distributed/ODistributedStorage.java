/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.server.distributed;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import com.orientechnologies.orient.core.cache.OLevel2RecordCache;
import com.orientechnologies.orient.core.command.OCommandDistributedReplicateRequest;
import com.orientechnologies.orient.core.command.OCommandExecutor;
import com.orientechnologies.orient.core.command.OCommandManager;
import com.orientechnologies.orient.core.command.OCommandRequestText;
import com.orientechnologies.orient.core.config.OStorageConfiguration;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.sql.OCommandExecutorSQLDelegate;
import com.orientechnologies.orient.core.storage.OCluster;
import com.orientechnologies.orient.core.storage.ODataSegment;
import com.orientechnologies.orient.core.storage.OPhysicalPosition;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.core.storage.ORecordCallback;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.OStorageEmbedded;
import com.orientechnologies.orient.core.tx.OTransaction;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager.EXECUTION_MODE;
import com.orientechnologies.orient.server.task.OCreateRecordDistributedTask;
import com.orientechnologies.orient.server.task.ODeleteRecordDistributedTask;
import com.orientechnologies.orient.server.task.OReadRecordDistributedTask;
import com.orientechnologies.orient.server.task.OSQLCommandDistributedTask;
import com.orientechnologies.orient.server.task.OUpdateRecordDistributedTask;

/**
 * Distributed storage implementation that routes to the owner node the request.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
public class ODistributedStorage implements OStorage {
  protected ODistributedServerManager dManager;
  protected OStorageEmbedded          wrapped;
  protected boolean                   eventuallyConsistent = true;
  protected EXECUTION_MODE            createRecordMode     = EXECUTION_MODE.SYNCHRONOUS;
  protected EXECUTION_MODE            updateRecordMode     = EXECUTION_MODE.SYNCHRONOUS;
  protected EXECUTION_MODE            deleteRecordMode     = EXECUTION_MODE.SYNCHRONOUS;

  public ODistributedStorage(final ODistributedServerManager iCluster, final OStorageEmbedded wrapped) {
    this.dManager = iCluster;
    this.wrapped = wrapped;
  }

  public Object command(final OCommandRequestText iCommand) {

    final OCommandExecutor executor = OCommandManager.instance().getExecutor(iCommand);

    executor.setProgressListener(iCommand.getProgressListener());
    executor.parse(iCommand);

    final boolean distribute = (executor instanceof OCommandExecutorSQLDelegate ? ((OCommandExecutorSQLDelegate) executor)
        .getDelegate() : executor) instanceof OCommandDistributedReplicateRequest;

    if (distribute)
      ODistributedThreadLocal.INSTANCE.distributedExecution = true;

    try {
      // EXECUTE IT LOCALLY
      final Object localResult = wrapped.executeCommand(iCommand, executor);

      if (distribute) {

        final Collection<Object> distributedResult = dManager.sendOperation2Nodes(dManager.getRemoteNodeIds(),
            new OSQLCommandDistributedTask(dManager.getLocalNodeId(), wrapped.getName(), createRecordMode, iCommand.getText()));
      }

      return localResult;

    } finally {

      if (distribute)
        ODistributedThreadLocal.INSTANCE.distributedExecution = false;
    }
  }

  public OPhysicalPosition createRecord(final int iDataSegmentId, final ORecordId iRecordId, final byte[] iContent,
      final int iRecordVersion, final byte iRecordType, final int iMode, final ORecordCallback<Long> iCallback) {
    if (ODistributedThreadLocal.INSTANCE.distributedExecution)
      // ALREADY DISTRIBUTED
      return wrapped.createRecord(iDataSegmentId, iRecordId, iContent, iRecordVersion, iRecordType, iMode, iCallback);

    Object result = null;

    try {
      result = dManager.routeOperation2Node(iRecordId,
          new OCreateRecordDistributedTask(dManager.getLocalNodeId(), wrapped.getName(), createRecordMode, iRecordId, iContent,
              iRecordVersion, iRecordType));
    } catch (ExecutionException e) {
      throw new OStorageException("Cannot route CREATE_RECORD operation to the distributed node", e);
    }

    return (OPhysicalPosition) result;
  }

  public ORawBuffer readRecord(final ORecordId iRid, final String iFetchPlan, final boolean iIgnoreCache,
      final ORecordCallback<ORawBuffer> iCallback) {
    if (ODistributedThreadLocal.INSTANCE.distributedExecution)
      // ALREADY DISTRIBUTED
      return wrapped.readRecord(iRid, iFetchPlan, iIgnoreCache, iCallback);

    if (eventuallyConsistent || dManager.isLocalNodeOwner(iRid))
      return wrapped.readRecord(iRid, iFetchPlan, iIgnoreCache, iCallback);

    try {
      return (ORawBuffer) dManager.routeOperation2Node(iRid,
          new OReadRecordDistributedTask(dManager.getLocalNodeId(), wrapped.getName(), iRid));
    } catch (ExecutionException e) {
      throw new OStorageException("Cannot route READ_RECORD operation to the distributed node", e);
    }
  }

  public int updateRecord(final ORecordId iRecordId, final byte[] iContent, final int iVersion, final byte iRecordType,
      final int iMode, final ORecordCallback<Integer> iCallback) {
    if (ODistributedThreadLocal.INSTANCE.distributedExecution)
      // ALREADY DISTRIBUTED
      return wrapped.updateRecord(iRecordId, iContent, iVersion, iRecordType, iMode, iCallback);

    Object result = null;

    try {
      result = dManager.routeOperation2Node(iRecordId,
          new OUpdateRecordDistributedTask(dManager.getLocalNodeId(), wrapped.getName(), updateRecordMode, iRecordId, iContent,
              iVersion, iRecordType));
    } catch (ExecutionException e) {
      throw new OStorageException("Cannot route UPDATE_RECORD operation to the distributed node", e);
    }

    // UPDATE LOCALLY
    return (Integer) result;
  }

  public boolean deleteRecord(final ORecordId iRecordId, final int iVersion, final int iMode,
      final ORecordCallback<Boolean> iCallback) {
    if (ODistributedThreadLocal.INSTANCE.distributedExecution)
      // ALREADY DISTRIBUTED
      return wrapped.deleteRecord(iRecordId, iVersion, iMode, iCallback);

    Object result = null;

    try {
      result = dManager.routeOperation2Node(iRecordId,
          new ODeleteRecordDistributedTask(dManager.getLocalNodeId(), wrapped.getName(), updateRecordMode, iRecordId, iVersion));
    } catch (ExecutionException e) {
      throw new OStorageException("Cannot route UPDATE_RECORD operation to the distributed node", e);
    }

    // DELETE LOCALLY
    return (Boolean) result;
  }

  public boolean existsResource(final String iName) {
    return wrapped.existsResource(iName);
  }

  public <T> T removeResource(final String iName) {
    return wrapped.removeResource(iName);
  }

  public <T> T getResource(final String iName, final Callable<T> iCallback) {
    return wrapped.getResource(iName, iCallback);
  }

  public void open(final String iUserName, final String iUserPassword, final Map<String, Object> iProperties) {
    wrapped.open(iUserName, iUserPassword, iProperties);
  }

  public void create(final Map<String, Object> iProperties) {
    wrapped.create(iProperties);
  }

  public boolean exists() {
    return wrapped.exists();
  }

  public void reload() {
    wrapped.reload();
  }

  public void delete() {
    wrapped.delete();
  }

  public void close() {
    wrapped.close();
  }

  public void close(final boolean iForce) {
    wrapped.close(iForce);
  }

  public boolean isClosed() {
    return wrapped.isClosed();
  }

  public OLevel2RecordCache getLevel2Cache() {
    return wrapped.getLevel2Cache();
  }

  public void commit(final OTransaction iTx) {
    wrapped.commit(iTx);
  }

  public void rollback(final OTransaction iTx) {
    wrapped.rollback(iTx);
  }

  public OStorageConfiguration getConfiguration() {
    return wrapped.getConfiguration();
  }

  public int getClusters() {
    return wrapped.getClusters();
  }

  public Set<String> getClusterNames() {
    return wrapped.getClusterNames();
  }

  public OCluster getClusterById(int iId) {
    return wrapped.getClusterById(iId);
  }

  public Collection<? extends OCluster> getClusterInstances() {
    return wrapped.getClusterInstances();
  }

  public int addCluster(final String iClusterType, final String iClusterName, final String iLocation,
      final String iDataSegmentName, final Object... iParameters) {
    return wrapped.addCluster(iClusterType, iClusterName, iLocation, iDataSegmentName, iParameters);
  }

  public boolean dropCluster(final String iClusterName) {
    return wrapped.dropCluster(iClusterName);
  }

  public boolean dropCluster(final int iId) {
    return wrapped.dropCluster(iId);
  }

  public int addDataSegment(final String iDataSegmentName) {
    return wrapped.addDataSegment(iDataSegmentName);
  }

  public int addDataSegment(final String iSegmentName, final String iDirectory) {
    return wrapped.addDataSegment(iSegmentName, iDirectory);
  }

  public long count(final int iClusterId) {
    return wrapped.count(iClusterId);
  }

  public long count(final int[] iClusterIds) {
    return wrapped.count(iClusterIds);
  }

  public long getSize() {
    return wrapped.getSize();
  }

  public long countRecords() {
    return wrapped.countRecords();
  }

  public int getDefaultClusterId() {
    return wrapped.getDefaultClusterId();
  }

  public void setDefaultClusterId(final int defaultClusterId) {
    wrapped.setDefaultClusterId(defaultClusterId);
  }

  public int getClusterIdByName(String iClusterName) {
    return wrapped.getClusterIdByName(iClusterName);
  }

  public String getClusterTypeByName(final String iClusterName) {
    return wrapped.getClusterTypeByName(iClusterName);
  }

  public String getPhysicalClusterNameById(final int iClusterId) {
    return wrapped.getPhysicalClusterNameById(iClusterId);
  }

  public boolean checkForRecordValidity(final OPhysicalPosition ppos) {
    return wrapped.checkForRecordValidity(ppos);
  }

  public String getName() {
    return wrapped.getName();
  }

  public String getURL() {
    return wrapped.getURL();
  }

  public long getVersion() {
    return wrapped.getVersion();
  }

  public void synch() {
    wrapped.synch();
  }

  public int getUsers() {
    return wrapped.getUsers();
  }

  public int addUser() {
    return wrapped.addUser();
  }

  public int removeUser() {
    return wrapped.removeUser();
  }

  public long[] getClusterDataRange(final int currentClusterId) {
    return wrapped.getClusterDataRange(currentClusterId);
  }

  public <V> V callInLock(final Callable<V> iCallable, final boolean iExclusiveLock) {
    return wrapped.callInLock(iCallable, iExclusiveLock);
  }

  public ODataSegment getDataSegmentById(final int iDataSegmentId) {
    return wrapped.getDataSegmentById(iDataSegmentId);
  }

  public int getDataSegmentIdByName(final String iDataSegmentName) {
    return wrapped.getDataSegmentIdByName(iDataSegmentName);
  }

  public boolean dropDataSegment(final String iName) {
    return wrapped.dropDataSegment(iName);
  }

  public STATUS getStatus() {
    return wrapped.getStatus();
  }
}