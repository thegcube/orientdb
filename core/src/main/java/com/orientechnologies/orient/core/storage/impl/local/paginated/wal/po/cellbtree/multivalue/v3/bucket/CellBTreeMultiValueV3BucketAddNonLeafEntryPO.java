package com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.cellbtree.multivalue.v3.bucket;

import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.PageOperationRecord;
import com.orientechnologies.orient.core.storage.index.sbtree.multivalue.v3.CellBTreeMultiValueV3Bucket;

import java.nio.ByteBuffer;

public final class CellBTreeMultiValueV3BucketAddNonLeafEntryPO extends PageOperationRecord {
  private int     index;
  private byte[]  key;
  private int     left;
  private int     right;
  private boolean updateNeighbours;
  private int     prevChild;

  public CellBTreeMultiValueV3BucketAddNonLeafEntryPO() {
  }

  public CellBTreeMultiValueV3BucketAddNonLeafEntryPO(final int index, final byte[] key, final int left, final int right,
      boolean updateNeighbours, final int prevChild) {
    this.index = index;
    this.key = key;
    this.left = left;
    this.right = right;
    this.updateNeighbours = updateNeighbours;
    this.prevChild = prevChild;
  }

  public int getIndex() {
    return index;
  }

  public byte[] getKey() {
    return key;
  }

  public int getLeft() {
    return left;
  }

  public int getRight() {
    return right;
  }

  public boolean isUpdateNeighbours() {
    return updateNeighbours;
  }

  public int getPrevChild() {
    return prevChild;
  }

  @Override
  public void redo(OCacheEntry cacheEntry) {
    final CellBTreeMultiValueV3Bucket bucket = new CellBTreeMultiValueV3Bucket(cacheEntry);
    final boolean result = bucket.addNonLeafEntry(index, key, left, right, updateNeighbours);
    if (!result) {
      throw new IllegalStateException("Can not redo add non leaf entry operation");
    }
  }

  @Override
  public void undo(OCacheEntry cacheEntry) {
    final CellBTreeMultiValueV3Bucket bucket = new CellBTreeMultiValueV3Bucket(cacheEntry);
    bucket.removeNonLeafEntry(index, key, prevChild);
  }

  @Override
  public int getId() {
    return WALRecordTypes.CELL_BTREE_BUCKET_MULTI_VALUE_V3_ADD_NON_LEAF_ENTRY_PO;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + 5 * OIntegerSerializer.INT_SIZE + OByteSerializer.BYTE_SIZE + key.length;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);

    buffer.putInt(index);
    buffer.putInt(left);
    buffer.putInt(right);
    buffer.putInt(prevChild);

    buffer.put(updateNeighbours ? (byte) 1 : 0);

    buffer.putInt(key.length);
    buffer.put(key);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);

    index = buffer.getInt();
    left = buffer.getInt();
    right = buffer.getInt();
    prevChild = buffer.getInt();

    updateNeighbours = buffer.get() > 0;

    final int keyLen = buffer.getInt();
    key = new byte[keyLen];
    buffer.get(key);
  }
}
