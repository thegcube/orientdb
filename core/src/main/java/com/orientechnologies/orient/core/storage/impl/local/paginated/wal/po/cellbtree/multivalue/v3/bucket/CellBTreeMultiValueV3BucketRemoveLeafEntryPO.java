package com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.cellbtree.multivalue.v3.bucket;

import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.common.serialization.types.OShortSerializer;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.PageOperationRecord;
import com.orientechnologies.orient.core.storage.index.sbtree.multivalue.v3.CellBTreeMultiValueV3Bucket;

import java.nio.ByteBuffer;

public final class CellBTreeMultiValueV3BucketRemoveLeafEntryPO extends PageOperationRecord {
  private int  index;
  private ORID value;

  public CellBTreeMultiValueV3BucketRemoveLeafEntryPO() {
  }

  public CellBTreeMultiValueV3BucketRemoveLeafEntryPO(int index, ORID value) {
    this.index = index;
    this.value = value;
  }

  public int getIndex() {
    return index;
  }

  public ORID getValue() {
    return value;
  }

  @Override
  public void redo(OCacheEntry cacheEntry) {
    final CellBTreeMultiValueV3Bucket bucket = new CellBTreeMultiValueV3Bucket(cacheEntry);
    final int result = bucket.removeLeafEntry(index, value);
    if (result < 0) {
      throw new IllegalStateException("Can not redo remove leaf entry operation");
    }
  }

  @Override
  public void undo(OCacheEntry cacheEntry) {
    final CellBTreeMultiValueV3Bucket bucket = new CellBTreeMultiValueV3Bucket(cacheEntry);
    final long result = bucket.appendNewLeafEntry(index, value);
    if (result != -1) {
      throw new IllegalStateException("Can not undo remove leaf entry operation");
    }
  }

  @Override
  public int getId() {
    return WALRecordTypes.CELL_BTREE_BUCKET_MULTI_VALUE_V3_REMOVE_LEAF_ENTRY_PO;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE + OShortSerializer.SHORT_SIZE;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);

    buffer.putInt(index);

    buffer.putShort((short) value.getClusterId());
    buffer.putLong(value.getClusterPosition());
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);

    index = buffer.getInt();

    final int clusterId = buffer.getShort();
    final long clusterPosition = buffer.getLong();

    value = new ORecordId(clusterId, clusterPosition);
  }
}
